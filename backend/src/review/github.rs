//! GitHub review operations. Credentials and HTTP policy are owned by `source`.
mod citations;
mod overview;
mod stack;
use crate::source::github_request;
use crate::{omit_nulls, text, AppState};
use anyhow::{bail, Context, Result};
#[cfg(test)]
use citations::{cite_url, decode_path};
use citations::{encode_segment, github_cites};
#[cfg(test)]
use overview::mergeability;
use overview::{overview, pull_text};
use reqwest::Method;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
#[cfg(test)]
use stack::native_stack;
use stack::stack;
use std::{
    collections::{BTreeMap, HashSet},
    sync::Arc,
};

fn list(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or(&[])
}
fn bool_at(v: &Value, key: &str) -> bool {
    v[key].as_bool().unwrap_or(false)
}
fn number(v: &Value) -> Option<u64> {
    v.as_u64().or_else(|| {
        let value = v.as_str()?;
        if value.is_empty() || !value.bytes().all(|c| c.is_ascii_digit()) {
            return None;
        }
        value.parse().ok()
    })
}
fn required<'a>(v: &'a Value, key: &str) -> Result<&'a str> {
    let value = text(v, key);
    if value.is_empty() {
        bail!("{key} is required.")
    }
    Ok(value)
}
fn positive(v: &Value, key: &str) -> Result<u64> {
    number(&v[key])
        .filter(|n| *n > 0)
        .with_context(|| format!("{key} needs a positive integer."))
}
fn nonempty_body(args: &Value) -> Result<&str> {
    let body = text(args, "body").trim();
    if body.is_empty() {
        bail!("A comment needs some text.")
    }
    Ok(body)
}
#[derive(Clone)]
struct Pull {
    owner: String,
    repo: String,
    number: u64,
}
impl Pull {
    fn from(args: &Value) -> Result<Self> {
        let owner = text(args, "owner");
        let repo = text(args, "repo");
        if [owner, repo].iter().any(|s| {
            s.is_empty()
                || *s == "."
                || *s == ".."
                || !s
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"-_.".contains(&c))
        }) {
            bail!("That needs the pull request owner, repo and number.")
        }
        Ok(Self {
            owner: owner.into(),
            repo: repo.into(),
            number: positive(args, "number")?,
        })
    }
    fn base(&self) -> String {
        format!("/repos/{}/{}", self.owner, self.repo)
    }
    fn path(&self) -> String {
        format!("{}/pulls/{}", self.base(), self.number)
    }
    fn variables(&self) -> Value {
        json!({"owner":self.owner,"repo":self.repo,"number":self.number})
    }
}
async fn graphql(state: &AppState, query: &str, variables: Value) -> Result<Value> {
    let response = github_request(
        state,
        Method::POST,
        "/graphql",
        Some(json!({"query":query,"variables":variables})),
    )
    .await?;
    if !list(&response["errors"]).is_empty() {
        bail!("GitHub could not complete this operation.")
    }
    response
        .get("data")
        .cloned()
        .context("GitHub returned no GraphQL data.")
}
async fn pages(state: &AppState, path: &str) -> Result<Vec<Value>> {
    let mut all = Vec::new();
    for page in 1..=1000 {
        let separator = if path.contains('?') { '&' } else { '?' };
        let value = github_request(
            state,
            Method::GET,
            &format!("{path}{separator}per_page=100&page={page}"),
            None,
        )
        .await?;
        let batch = value
            .as_array()
            .context("GitHub returned an invalid list.")?;
        all.extend(batch.iter().cloned());
        if batch.len() < 100 {
            return Ok(all);
        }
    }
    bail!("GitHub returned too many pages to load this review safely.")
}
fn author(user: &Value) -> Value {
    if user.is_null() {
        Value::Null
    } else {
        json!({"login":user["login"],"avatarUrl":user["avatar_url"]})
    }
}
fn side(value: &Value) -> &'static str {
    if value == "LEFT" {
        "deletions"
    } else {
        "additions"
    }
}
fn github_side(value: &Value) -> Result<&'static str> {
    match value.as_str() {
        Some("additions") => Ok("RIGHT"),
        Some("deletions") => Ok("LEFT"),
        _ => bail!("A comment needs an additions or deletions side."),
    }
}
const THREADS_QUERY: &str = r#"query ReviewThreads($owner:String!,$repo:String!,$number:Int!,$cursor:String){repository(owner:$owner,name:$repo){pullRequest(number:$number){reviewThreads(first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{id isResolved comments(first:1){nodes{fullDatabaseId}}}}}}}"#;
async fn resolutions(state: &AppState, pull: &Pull) -> Result<BTreeMap<u64, Value>> {
    let mut out = BTreeMap::new();
    let mut cursor = Value::Null;
    loop {
        let mut vars = pull.variables();
        vars["cursor"] = cursor.clone();
        let data = graphql(state, THREADS_QUERY, vars).await?;
        let conn = &data["repository"]["pullRequest"]["reviewThreads"];
        for node in list(&conn["nodes"]) {
            if let Some(root) = number(&node["comments"]["nodes"][0]["fullDatabaseId"]) {
                out.insert(
                    root,
                    json!({"threadId":node["id"],"resolved":node["isResolved"]}),
                );
            }
        }
        if !bool_at(&conn["pageInfo"], "hasNextPage") {
            break;
        }
        let next = conn["pageInfo"]["endCursor"].clone();
        if next.is_null() || next == cursor {
            bail!("GitHub returned an invalid review cursor.")
        }
        cursor = next;
    }
    Ok(out)
}
fn normalize_reviews(
    comments: &[Value],
    reviews: &[Value],
    pending_comments: &[Value],
    resolutions: &BTreeMap<u64, Value>,
) -> Value {
    let mut threads: Vec<Value> = Vec::new();
    for (batch, pending) in [(comments, false), (pending_comments, true)] {
        for comment in batch {
            let root = number(&comment["in_reply_to_id"])
                .or_else(|| number(&comment["id"]))
                .unwrap_or(0);
            let index = if let Some(index) = threads
                .iter()
                .position(|t| number(&t["rootId"]) == Some(root))
            {
                index
            } else {
                let resolution = resolutions.get(&root).cloned().unwrap_or(Value::Null);
                threads.push(json!({"rootId":root,"path":comment["path"],"side":side(&comment["side"]),"line":comment["line"],"startLine":comment["start_line"],"startSide":if comment["start_side"].is_null(){Value::Null}else{json!(side(&comment["start_side"]))},"outdated":comment["line"].is_null(),"resolved":resolution["resolved"],"threadId":resolution["threadId"],"pending":pending,"comments":[]}));
                threads.len() - 1
            };
            threads[index]["comments"].as_array_mut().unwrap().push(json!({"id":comment["id"],"author":author(&comment["user"]),"body":comment["body"],"createdAt":comment["created_at"],"url":comment["html_url"],"pending":pending}));
        }
    }
    let mut summaries = Vec::new();
    let mut pending = Value::Null;
    for review in reviews {
        let state = text(review, "state");
        if state == "PENDING" {
            pending =
                json!({"id":review["id"],"nodeId":review["node_id"],"body":text(review,"body")});
            continue;
        }
        if !["APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED"].contains(&state)
            || (state == "COMMENTED" && text(review, "body").is_empty())
        {
            continue;
        }
        summaries.push(json!({"id":review["id"],"author":author(&review["user"]),"state":state.to_lowercase(),"body":text(review,"body"),"submittedAt":review["submitted_at"],"url":review["html_url"]}));
    }
    let mut result = json!({"threads":threads,"summaries":summaries,"pendingReview":pending});
    omit_nulls(&mut result);
    result
}
async fn review_data(state: &AppState, pull: &Pull) -> Result<Value> {
    let comments_path = format!("{}/comments", pull.path());
    let reviews_path = format!("{}/reviews", pull.path());
    let (comments, reviews) =
        tokio::try_join!(pages(state, &comments_path), pages(state, &reviews_path))?;
    let pending = reviews.iter().find(|r| r["state"] == "PENDING");
    let pending_comments = if let Some(review) = pending {
        pages(
            state,
            &format!(
                "{}/reviews/{}/comments",
                pull.path(),
                positive(review, "id")?
            ),
        )
        .await?
    } else {
        Vec::new()
    };
    let resolution = resolutions(state, pull).await.unwrap_or_default();
    Ok(normalize_reviews(
        &comments,
        &reviews,
        &pending_comments,
        &resolution,
    ))
}
async fn pending_review(state: &AppState, pull: &Pull) -> Result<Option<Value>> {
    Ok(pages(state, &format!("{}/reviews", pull.path()))
        .await?
        .into_iter()
        .find(|r| r["state"] == "PENDING")
        .map(|r| json!({"id":r["id"],"nodeId":r["node_id"],"body":text(&r,"body"),"commitId":r["commit_id"]})))
}
async fn current_head(state: &AppState, pull: &Pull) -> Result<String> {
    let pr = github_request(state, Method::GET, &pull.path(), None).await?;
    Ok(required(&pr["head"], "sha")?.to_owned())
}
fn comment_belongs_to_pull(comment: &Value, pull: &Pull) -> bool {
    let Ok(url) = url::Url::parse(text(comment, "pull_request_url")) else {
        return false;
    };
    url.scheme() == "https"
        && url.host_str() == Some("api.github.com")
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.path().eq_ignore_ascii_case(&pull.path())
}
async fn verify_comment_target(state: &AppState, pull: &Pull, id: u64) -> Result<()> {
    let comment = github_request(
        state,
        Method::GET,
        &format!("{}/pulls/comments/{id}", pull.base()),
        None,
    )
    .await?;
    if !comment_belongs_to_pull(&comment, pull) {
        bail!("That comment does not belong to this pull request.")
    }
    Ok(())
}
fn verify_pending_head(pending: &Value, head: &str) -> Result<()> {
    if pending["commitId"]
        .as_str()
        .is_some_and(|commit| !commit.is_empty() && commit != head)
    {
        bail!("The pending review belongs to an older head. Reopen or discard it before posting.")
    }
    Ok(())
}
async fn verify_head(state: &AppState, pull: &Pull, head: &str) -> Result<()> {
    if current_head(state, pull).await? != head {
        bail!("The pull request head changed. Reopen it before posting.")
    }
    Ok(())
}
fn draft_target(args: &Value) -> Result<Value> {
    let path = required(args, "path")?;
    if path.starts_with('/')
        || path.contains('\\')
        || path.chars().any(char::is_control)
        || path
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
    {
        bail!("A comment needs a repository-relative file path.")
    }
    let line = positive(args, "line")?;
    let side = github_side(&args["side"])?;
    let mut target = json!({"path":path,"line":line,"side":side});
    if let Some(start) = number(&args["startLine"]).filter(|n| *n > 0 && *n < line) {
        target["start_line"] = json!(start);
        target["start_side"] = json!(side);
    }
    Ok(target)
}
async fn add_pending_thread(
    state: &AppState,
    review: &Value,
    target: &Value,
    body: &str,
) -> Result<()> {
    let query = r#"mutation AddPendingThread($reviewId:ID!,$path:String!,$body:String!,$line:Int!,$side:DiffSide!,$startLine:Int,$startSide:DiffSide){addPullRequestReviewThread(input:{pullRequestReviewId:$reviewId,path:$path,body:$body,line:$line,side:$side,startLine:$startLine,startSide:$startSide}){thread{id}}}"#;
    graphql(state,query,json!({"reviewId":required(review,"nodeId")?,"path":target["path"],"body":body,"line":target["line"],"side":target["side"],"startLine":target["start_line"],"startSide":target["start_side"]})).await?;
    Ok(())
}
async fn review_operation(state: &AppState, name: &str, args: &Value) -> Result<Value> {
    let pull = Pull::from(args)?;
    match name {
        "review-data" | "review-threads" => {
            let mut data = review_data(state, &pull).await?;
            if name == "review-threads" {
                let threads:Vec<Value>=list(&data["threads"]).iter().filter(|t|!t["line"].is_null()&&!bool_at(t,"outdated")).map(|t|json!({"path":t["path"],"side":t["side"],"line":t["line"],"rootId":t["rootId"],"threadId":t["threadId"],"resolved":bool_at(t,"resolved"),"comments":list(&t["comments"]).iter().map(|c|json!({"author":c["author"]["login"],"body":c["body"]})).collect::<Vec<_>>()})).collect();
                data = json!({"threads":threads});
            } else {
                let viewer=graphql(state,"query Viewer($owner:String!,$repo:String!){viewer{login} repository(owner:$owner,name:$repo){viewerPermission}}",json!({"owner":pull.owner,"repo":pull.repo})).await.unwrap_or(Value::Null);
                data["viewer"] = viewer["viewer"]["login"].clone();
                data["canWrite"] = json!(["ADMIN", "MAINTAIN", "WRITE"]
                    .contains(&text(&viewer["repository"], "viewerPermission")));
            }
            omit_nulls(&mut data);
            return Ok(data);
        }
        "review-reply" => {
            let id = positive(args, "rootId")?;
            let body = nonempty_body(args)?;
            verify_comment_target(state, &pull, id).await?;
            let body = github_cites(body, &pull, &current_head(state, &pull).await?);
            github_request(
                state,
                Method::POST,
                &format!("{}/comments/{id}/replies", pull.path()),
                Some(json!({"body":body})),
            )
            .await?;
        }
        "review-resolve" => {
            let id = required(args, "threadId")?;
            if !resolutions(state, &pull)
                .await?
                .values()
                .any(|thread| text(thread, "threadId") == id)
            {
                bail!("That thread does not belong to this pull request.")
            }
            graphql(state,"mutation ResolveThread($threadId:ID!){resolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}",json!({"threadId":id})).await?;
        }
        "review-edit" => {
            let id = positive(args, "commentId")?;
            let body = nonempty_body(args)?;
            verify_comment_target(state, &pull, id).await?;
            let body = github_cites(body, &pull, &current_head(state, &pull).await?);
            github_request(
                state,
                Method::PATCH,
                &format!("{}/pulls/comments/{id}", pull.base()),
                Some(json!({"body":body})),
            )
            .await?;
        }
        "review-delete" => {
            let id = positive(args, "commentId")?;
            verify_comment_target(state, &pull, id).await?;
            github_request(
                state,
                Method::DELETE,
                &format!("{}/pulls/comments/{id}", pull.base()),
                None,
            )
            .await?;
        }
        "review-submit" => {
            let verdict = required(args, "verdict")?;
            if !["APPROVE", "REQUEST_CHANGES", "COMMENT"].contains(&verdict) {
                bail!("A review needs a verdict.")
            }
            let head = current_head(state, &pull).await?;
            if !text(args, "headSha").is_empty() && text(args, "headSha") != head {
                bail!("The pull request head changed. Reopen it before posting.")
            }
            let body = github_cites(text(args, "body"), &pull, &head);
            let pending = pending_review(state, &pull).await?;
            let has_pending = pending.is_some();
            let path = if let Some(pending) = pending {
                verify_pending_head(&pending, &head)?;
                format!(
                    "{}/reviews/{}/events",
                    pull.path(),
                    positive(&pending, "id")?
                )
            } else {
                format!("{}/reviews", pull.path())
            };
            let mut payload = json!({"event":verdict,"body":body});
            if !has_pending {
                payload["commit_id"] = json!(head);
            }
            github_request(state, Method::POST, &path, Some(payload)).await?;
        }
        "review-discard" => {
            if let Some(pending) = pending_review(state, &pull).await? {
                github_request(
                    state,
                    Method::DELETE,
                    &format!("{}/reviews/{}", pull.path(), positive(&pending, "id")?),
                    None,
                )
                .await?;
            }
        }
        "review-comment" => {
            let mut target = draft_target(args)?;
            let head = required(args, "headSha")?;
            let body = github_cites(nonempty_body(args)?, &pull, head);
            verify_head(state, &pull, head).await?;
            if text(args, "mode") == "review" {
                if let Some(pending) = pending_review(state, &pull).await? {
                    verify_pending_head(&pending, head)?;
                    add_pending_thread(state, &pending, &target, &body).await?;
                } else {
                    target["body"] = json!(body);
                    github_request(
                        state,
                        Method::POST,
                        &format!("{}/reviews", pull.path()),
                        Some(json!({"commit_id":head,"comments":[target]})),
                    )
                    .await?;
                }
            } else {
                target["body"] = json!(body);
                target["commit_id"] = json!(head);
                github_request(
                    state,
                    Method::POST,
                    &format!("{}/comments", pull.path()),
                    Some(target),
                )
                .await?;
            }
        }
        _ => bail!("Unknown review operation."),
    }
    Ok(json!(true))
}

async fn pr_action(state: &AppState, args: &Value) -> Result<Value> {
    let id = required(args, "id")?;
    let action = required(args, "action")?;
    let mut input = json!({"pullRequestId":id});
    let (mutation, input_type) = match action {
        "merge" | "auto-merge" => {
            let method = text(args, "method");
            if !["merge", "squash", "rebase"].contains(&method) {
                bail!("Merge needs a method: merge, squash or rebase.")
            }
            input["mergeMethod"] = json!(method.to_uppercase());
            if !text(args, "expectedHeadOid").is_empty() {
                input["expectedHeadOid"] = args["expectedHeadOid"].clone();
            }
            if action == "merge" {
                if !text(args, "title").is_empty() {
                    input["commitHeadline"] = args["title"].clone();
                }
                if args["body"].is_string() {
                    input["commitBody"] = args["body"].clone();
                }
                ("mergePullRequest", "MergePullRequestInput")
            } else {
                (
                    "enablePullRequestAutoMerge",
                    "EnablePullRequestAutoMergeInput",
                )
            }
        }
        "cancel-auto-merge" => (
            "disablePullRequestAutoMerge",
            "DisablePullRequestAutoMergeInput",
        ),
        "update-branch" => {
            input["updateMethod"] = json!(if text(args, "method") == "rebase" {
                "REBASE"
            } else {
                "MERGE"
            });
            if !text(args, "expectedHeadOid").is_empty() {
                input["expectedHeadOid"] = args["expectedHeadOid"].clone();
            }
            ("updatePullRequestBranch", "UpdatePullRequestBranchInput")
        }
        "edit" => {
            if let Some(title) = args["title"].as_str() {
                if title.trim().is_empty() {
                    bail!("A pull request needs a title.")
                }
                input["title"] = json!(title.trim());
            }
            if args["body"].is_string() {
                input["body"] = args["body"].clone();
            }
            if input.get("title").is_none() && input.get("body").is_none() {
                bail!("Edit needs a title or a description.")
            }
            ("updatePullRequest", "UpdatePullRequestInput")
        }
        "close" => ("closePullRequest", "ClosePullRequestInput"),
        "reopen" => ("reopenPullRequest", "ReopenPullRequestInput"),
        "ready" => (
            "markPullRequestReadyForReview",
            "MarkPullRequestReadyForReviewInput",
        ),
        "draft" => (
            "convertPullRequestToDraft",
            "ConvertPullRequestToDraftInput",
        ),
        _ => bail!("Unknown pull request action."),
    };
    let query=format!("mutation PullAction($input:{input_type}!){{{mutation}(input:$input){{pullRequest{{id}}}}}}");
    graphql(state, &query, json!({"input":input})).await?;
    Ok(json!(true))
}

fn finding_body(finding: &Value) -> String {
    let mut body = format!(
        "**{}**\n\n{}",
        text(finding, "title"),
        text(finding, "body")
    );
    if let Some(suggestion) = finding["suggestion"].as_str() {
        let mut longest = 2;
        let mut current = 0;
        for c in suggestion.chars() {
            if c == '`' {
                current += 1;
                longest = longest.max(current)
            } else {
                current = 0
            }
        }
        let fence = "`".repeat(longest + 1);
        body.push_str(&format!("\n\n{fence}suggestion\n{suggestion}\n{fence}"));
    }
    body
}
fn critique_body(result: &Value) -> String {
    let by = [text(result, "engine"), text(result, "model")]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("/");
    let lens = if text(result, "lens").is_empty() {
        String::new()
    } else {
        format!(", through the {} lens", text(result, "lens"))
    };
    format!(
        "{}\n\n<sub>Reviewed with {by}{lens} on pulls.review.</sub>",
        text(result, "summary")
    )
}
fn critique_selection(
    entry: &Value,
    stored: &Value,
    args: &Value,
) -> Result<(Pull, Value, Vec<Value>)> {
    let variant = required(args, "variant")?;
    let revision = required(args, "revision")?;
    let head = required(args, "headSha")?;
    let ids = args["ids"]
        .as_array()
        .context("Posting needs selected finding IDs.")?;
    if ids.is_empty() || ids.iter().any(|id| id.as_str().unwrap_or("").is_empty()) {
        bail!("Posting needs selected finding IDs.")
    }
    let ids: HashSet<&str> = ids.iter().filter_map(Value::as_str).collect();
    if entry["diff"]["ref"]["kind"] != "github-pr" {
        bail!("There is no AI review of this pull request to post.")
    }
    let pull = Pull::from(&entry["diff"]["ref"])?;
    let result = list(&stored["entries"])
        .iter()
        .find(|e| text(e, "variant") == variant)
        .map(|e| e["result"].clone())
        .context("There is no AI review of this pull request to post.")?;
    let digest = hex::encode(Sha256::digest(serde_json::to_vec(&result)?));
    if text(&entry["diff"]["head"], "sha") != head
        || text(&result, "headSha") != head
        || digest != revision
    {
        bail!("That review changed. Reopen the findings before posting.")
    }
    let chosen: Vec<Value> = list(&result["findings"])
        .iter()
        .filter(|f| ids.contains(text(f, "id")))
        .cloned()
        .collect();
    let resolved: HashSet<&str> = chosen.iter().map(|f| text(f, "id")).collect();
    if chosen.len() != ids.len() || resolved != ids {
        bail!("Some selected findings are no longer in that review.")
    }
    // Validate every selected target before any GitHub request, not halfway through a pending review.
    for finding in &chosen {
        draft_target(finding)?;
    }
    Ok((pull, result, chosen))
}
async fn critique_post(state: &AppState, args: &Value) -> Result<Value> {
    let owned_args = args.clone();
    let (pull, result, chosen) = state
        .host
        .storage
        .transaction(move |store| {
            let key = required(&owned_args, "key")?;
            let entry = store
                .get(&format!("diff:{key}"))?
                .context("There is no AI review of this pull request to post.")?;
            let scope = crate::source::serialize_ref(&entry["diff"]["ref"])?;
            let stored = store
                .get(&format!("critique:{scope}"))?
                .unwrap_or(Value::Null);
            critique_selection(&entry, &stored, &owned_args)
        })
        .await?;
    let head = required(args, "headSha")?;
    if current_head(state, &pull).await? != head {
        bail!("The pull request head changed. Reopen the findings before posting.")
    }
    let body = github_cites(
        args["body"]
            .as_str()
            .map(str::to_owned)
            .unwrap_or_else(|| critique_body(&result))
            .as_str(),
        &pull,
        head,
    );
    let mut comments = Vec::new();
    for finding in &chosen {
        let mut target = draft_target(finding)?;
        target["body"] = json!(github_cites(&finding_body(finding), &pull, head));
        comments.push(target);
    }
    if let Some(pending) = pending_review(state, &pull).await? {
        verify_pending_head(&pending, head)?;
        for comment in &comments {
            add_pending_thread(state, &pending, comment, text(comment, "body")).await?;
        }
        github_request(
            state,
            Method::POST,
            &format!(
                "{}/reviews/{}/events",
                pull.path(),
                positive(&pending, "id")?
            ),
            Some(json!({"event":"COMMENT","body":body})),
        )
        .await?;
    } else {
        github_request(
            state,
            Method::POST,
            &format!("{}/reviews", pull.path()),
            Some(json!({"commit_id":head,"event":"COMMENT","body":body,"comments":comments})),
        )
        .await?;
    }
    Ok(json!({"posted":chosen.iter().map(|f|f["id"].clone()).collect::<Vec<_>>()}))
}

const SHARED_MARKER: &str = "<!-- pulls.review data -->";
fn valid_group(group: &Value, children: bool) -> bool {
    group["key"].is_string()
        && group["label"].is_string()
        && group["filePaths"]
            .as_array()
            .is_some_and(|a| a.iter().all(Value::is_string))
        && (group.get("summary").is_none() || group["summary"].is_string())
        && (group.get("children").is_none()
            || (children
                && group["children"]
                    .as_array()
                    .is_some_and(|a| a.iter().all(|g| valid_group(g, false)))))
}
fn valid_shared(value: &Value) -> bool {
    let r = &value["result"];
    value["headSha"].is_string()
        && r["groups"]
            .as_array()
            .is_some_and(|a| a.iter().all(|g| valid_group(g, true)))
        && r["schemaVersion"].is_number()
        && ["none", "rule-based", "llm", "web-llm"].contains(&text(r, "source"))
        && r["generatedAt"].is_string()
        && ["overallSummary", "model", "sharedBy", "locale"]
            .iter()
            .all(|k| r.get(*k).is_none() || r[*k].is_string())
        && (r.get("files").is_none()
            || r["files"].as_array().is_some_and(|files| {
                files.iter().all(|f| {
                    f["path"].is_string()
                        && f["summary"].is_string()
                        && (f.get("hunks").is_none()
                            || f["hunks"].as_array().is_some_and(|a| {
                                a.iter()
                                    .all(|h| h["index"].is_number() && h["note"].is_string())
                            }))
                })
            }))
}
fn parse_shared(body: &str) -> Option<Value> {
    if !body.starts_with(SHARED_MARKER) {
        return None;
    }
    static FENCE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let fence =
        FENCE.get_or_init(|| regex::Regex::new(r"(?s)```(json|lz-string)\n(.*?)\n```").unwrap());
    let captures = fence.captures(body)?;
    let decoded;
    let payload = if &captures[1] == "lz-string" {
        decoded = crate::lz::decompress(&captures[2], 4 * 1024 * 1024)?;
        &decoded
    } else {
        &captures[2]
    };
    let value: Value = serde_json::from_str(payload).ok()?;
    valid_shared(&value).then_some(value)
}
async fn shared_comments(state: &AppState, pull: &Pull) -> Result<Vec<Value>> {
    let response = github_request(
        state,
        Method::GET,
        &format!(
            "{}/issues/{}/comments?per_page=100",
            pull.base(),
            pull.number
        ),
        None,
    )
    .await?;
    let mut comments = Vec::new();
    for comment in list(&response) {
        if let Some(analysis) = parse_shared(text(comment, "body")) {
            if comment["user"]["login"].is_string() {
                comments.push(json!({"id":comment["id"],"login":comment["user"]["login"],"url":comment["html_url"],"updatedAt":comment["updated_at"],"analysis":analysis}));
            }
        }
    }
    comments.sort_by(|a, b| text(b, "updatedAt").cmp(text(a, "updatedAt")));
    Ok(comments)
}
fn rewrite_result(result: &mut Value, pull: &Pull, head: &str) {
    for key in ["overallSummary", "summary", "note"] {
        if let Some(s) = result[key].as_str() {
            result[key] = json!(github_cites(s, pull, head));
        }
    }
    for key in ["groups", "children", "files", "hunks"] {
        if let Some(items) = result.get_mut(key).and_then(Value::as_array_mut) {
            for item in items {
                rewrite_result(item, pull, head);
            }
        }
    }
}
fn shared_body(pull: &Pull, login: &str, analysis: &Value) -> Result<String> {
    let mut analysis = analysis.clone();
    let head = text(&analysis, "headSha").to_owned();
    rewrite_result(&mut analysis["result"], pull, &head);
    let result = &analysis["result"];
    let date = text(result, "generatedAt")
        .replace('T', " ")
        .chars()
        .take(16)
        .collect::<String>();
    let model = result["model"].as_str().unwrap_or(text(result, "source"));
    let render = |payload: &str| {
        format!("{SHARED_MARKER}\n👁️‍🗨️ Review this pull request with grouped, summarized diffs at:\n👉 https://pulls.review/gh/{}/{}/{}?from={}\n\n<details><summary>raw result</summary>\n\n<br>\n\n💭 analyzed by `{model}`\n🕰️ {date} UTC\n🔗 head {}\n🤖 automated by [pulls.review](https://pulls.review)\n\n{}\n\n</details>\n",pull.owner,pull.repo,pull.number,encode_segment(login),head.chars().take(7).collect::<String>(),payload)
    };
    let body = render(&format!(
        "```json\n{}\n```",
        serde_json::to_string_pretty(&analysis)?
    ));
    let body = if body.encode_utf16().count() > 60_000 {
        render(&format!(
            "```lz-string\n{}\n```",
            crate::lz::compress(&serde_json::to_string(&analysis)?)
        ))
    } else {
        body
    };
    if body.encode_utf16().count() > 60_000 {
        bail!("This analysis is too large for a GitHub comment even after compression.")
    }
    Ok(body)
}
async fn sharing(state: &AppState, name: &str, args: &Value) -> Result<Value> {
    let key = text(args, "key");
    let storage_key = format!("diff:{key}");
    let entry = state.host.storage.get_async(&storage_key).await?;
    if name == "shared-list"
        && entry
            .as_ref()
            .is_none_or(|e| e["diff"]["ref"]["kind"] != "github-pr")
    {
        return Ok(json!({"candidates":[]}));
    }
    let entry = entry.context("Open the pull request first.")?;
    if entry["diff"]["ref"]["kind"] != "github-pr" {
        bail!("Sharing is only available for pull requests.")
    }
    let pull = Pull::from(&entry["diff"]["ref"])?;
    let head = text(&entry["diff"]["head"], "sha");
    if name == "share-analysis" {
        let result = &entry["analyzedBy"]["llm"];
        if result.is_null() {
            bail!("Analyze this pull request before sharing it.")
        }
        let head = super::llm_analyzed_head(&entry)
            .context("The saved analysis has no head commit. Analyze it again before sharing.")?;
        let viewer = github_request(state, Method::GET, "/user", None).await?;
        let login = required(&viewer, "login")?;
        let body = shared_body(&pull, login, &json!({"headSha":head,"result":result}))?;
        // Discover the author's comment to avoid editing an unrelated remembered comment.
        let comments = shared_comments(state, &pull).await?;
        let old = comments.iter().find(|c| text(c, "login") == login);
        let (method, path) = if let Some(old) = old {
            (
                Method::PATCH,
                format!("{}/issues/comments/{}", pull.base(), positive(old, "id")?),
            )
        } else {
            (
                Method::POST,
                format!("{}/issues/{}/comments", pull.base(), pull.number),
            )
        };
        let response = github_request(state, method, &path, Some(json!({"body":body}))).await?;
        let comment = json!({"id":response["id"],"url":response["html_url"]});
        let saved_comment = comment.clone();
        state
            .host
            .storage
            .transaction(move |store| {
                store.update(&storage_key, |current| {
                    let mut current = current.context("The saved diff is no longer available.")?;
                    current["sharedComment"] = saved_comment;
                    Ok(current)
                })
            })
            .await?;
        return Ok(comment);
    }
    let comments = shared_comments(state, &pull).await?;
    let current = &entry["analyzedBy"]["llm"];
    let candidate = if name == "shared-load" {
        let login = required(args, "login")?;
        Some(
            comments
                .iter()
                .find(|c| text(c, "login") == login)
                .context("That person has not shared an analysis on this pull request.")?,
        )
    } else if current.is_null() {
        comments
            .iter()
            .find(|c| text(c, "login") == "github-actions[bot]")
    } else {
        None
    };
    if let Some(comment) = candidate {
        let mut result = comment["analysis"]["result"].clone();
        result["sharedBy"] = comment["login"].clone();
        let (head, shared_list, analysis_head) = (
            head.to_owned(),
            name == "shared-list",
            comment["analysis"]["headSha"].clone(),
        );
        state
            .host
            .storage
            .transaction(move |store| {
                store.update(&storage_key, |now| {
                    let mut now = now.context("The saved diff is no longer available.")?;
                    if text(&now["diff"]["head"], "sha") != head {
                        bail!("The pull request changed. Reopen it before loading this analysis.")
                    }
                    if shared_list && !now["analyzedBy"]["llm"].is_null() {
                        return Ok(now);
                    }
                    super::set_llm_analysis(&mut now, result, analysis_head);
                    Ok(now)
                })
            })
            .await?;
        return Ok(if name == "shared-list" {
            json!({"candidates":[],"loaded":comment["login"]})
        } else {
            json!({"loaded":comment["login"]})
        });
    }
    if !current.is_null() {
        return Ok(json!({"candidates":[]}));
    }
    let viewer = github_request(state, Method::GET, "/user", None)
        .await
        .unwrap_or(Value::Null);
    let candidates:Vec<Value>=comments.iter().map(|c|json!({"login":c["login"],"url":c["url"],"own":c["login"]==viewer["login"],"stale":text(&c["analysis"],"headSha")!=head,"locale":c["analysis"]["result"]["locale"]})).collect();
    let mut value = json!({"candidates":candidates});
    omit_nulls(&mut value);
    Ok(value)
}

fn rollup(state: &Value) -> Value {
    match state.as_str() {
        Some("SUCCESS") => json!("success"),
        Some("FAILURE" | "ERROR") => json!("failure"),
        Some("PENDING" | "EXPECTED") => json!("pending"),
        _ => Value::Null,
    }
}
/// @cc [owner:OctavianTocan,label:product] github-lifecycle-preserves-merged-state
/// A merged flag, MERGED state or merge timestamp MUST produce merged before closed or
/// draft. Missing or unrecognized state without terminal evidence MUST NOT become open
/// (Tavi, 20 Oct 2026, "On PRs that have already been merged, we have no indication that
/// they've been merged").
fn pr_state(pr: &Value) -> &'static str {
    if bool_at(pr, "merged") || pr["state"] == "MERGED" || !text(pr, "mergedAt").is_empty() {
        "merged"
    } else if pr["state"] == "CLOSED" || !text(pr, "closedAt").is_empty() {
        "closed"
    } else if pr["state"] == "OPEN" {
        if bool_at(pr, "isDraft") { "draft" } else { "open" }
    } else {
        "unknown"
    }
}
pub async fn handle(state: &Arc<AppState>, name: &str, args: &Value) -> Option<Result<Value>> {
    Some(match name {
        "pr-overview" => overview(state, args).await,
        "pr-text" => pull_text(state, args).await,
        "pr-stack" => stack(state, args).await,
        "pr-action" => pr_action(state, args).await,
        "review-data" | "review-threads" | "review-reply" | "review-resolve" | "review-comment"
        | "review-submit" | "review-discard" | "review-edit" | "review-delete" => {
            review_operation(state, name, args).await
        }
        "critique-post" => critique_post(state, args).await,
        "share-analysis" | "shared-list" | "shared-load" => sharing(state, name, args).await,
        _ => return None,
    })
}

#[cfg(test)]
mod tests;
