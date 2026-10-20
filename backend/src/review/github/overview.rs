use super::*;

const OVERVIEW_QUERY: &str = r###"
query PullRequestOverview($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    viewerPermission
    mergeCommitAllowed
    squashMergeAllowed
    rebaseMergeAllowed
    viewerDefaultMergeMethod
    squashMergeCommitTitle
    squashMergeCommitMessage
    mergeCommitTitle
    mergeCommitMessage
    pullRequest(number: $number) {
      id
      number
      title
      url
      body
      bodyHTML
      createdAt
      updatedAt
      author { __typename login avatarUrl }
      state
      isDraft
      merged
      mergedAt
      closedAt
      mergeable
      mergeStateStatus
      reviewDecision
      viewerCanUpdate
      viewerDidAuthor
      viewerCanUpdateBranch
      viewerCanClose
      viewerCanReopen
      viewerCanMergeAsAdmin
      viewerCanEnableAutoMerge
      viewerCanDisableAutoMerge
      autoMergeRequest { mergeMethod }
      headRefOid
      headRefName
      baseRefName
      baseRefOid
      headRepositoryOwner { login }
      reviewRequests(first: 20) {
        nodes { requestedReviewer { __typename ... on User { login } ... on Team { name } ... on Bot { login } ... on Mannequin { login } } }
      }
      latestOpinionatedReviews(first: 20) {
        nodes { state author { __typename login avatarUrl } }
      }
      headCommit: commits(last: 1) {
        nodes {
          commit {
            oid
            statusCheckRollup {
              state
              contexts(first: 100) {
                totalCount
                nodes {
                  __typename
                  ... on CheckRun {
                    id
                    name
                    status
                    conclusion
                    detailsUrl
                    startedAt
                    completedAt
                    title
                    isRequired(pullRequestNumber: $number)
                    checkSuite { app { name } workflowRun { workflow { name } } }
                  }
                  ... on StatusContext {
                    id
                    context
                    state
                    description
                    targetUrl
                    createdAt
                    isRequired(pullRequestNumber: $number)
                  }
                }
              }
            }
          }
        }
      }
      commits(last: 100) {
        totalCount
        nodes {
          commit {
            oid
            abbreviatedOid
            messageHeadline
            messageBody
            committedDate
            url
            author { name avatarUrl user { login } }
            parents(first: 1) { nodes { oid } }
            statusCheckRollup { state }
          }
        }
      }
      timelineItems(last: 100, itemTypes: [ISSUE_COMMENT, PULL_REQUEST_REVIEW, PULL_REQUEST_COMMIT, LABELED_EVENT, UNLABELED_EVENT, ASSIGNED_EVENT, UNASSIGNED_EVENT, READY_FOR_REVIEW_EVENT, CONVERT_TO_DRAFT_EVENT, CLOSED_EVENT, REOPENED_EVENT, MERGED_EVENT, HEAD_REF_FORCE_PUSHED_EVENT, REVIEW_REQUESTED_EVENT, REVIEW_DISMISSED_EVENT, RENAMED_TITLE_EVENT]) {
        totalCount
        nodes {
          __typename
          ... on IssueComment { id url createdAt bodyHTML isMinimized minimizedReason author { __typename login avatarUrl } }
          ... on PullRequestReview {
            id url state submittedAt createdAt bodyHTML author { __typename login avatarUrl }
            comments(first: 20) { totalCount nodes { id url path line originalLine outdated bodyHTML author { __typename login avatarUrl } } }
          }
          ... on PullRequestCommit { id url commit { oid abbreviatedOid messageHeadline committedDate author { name avatarUrl user { login } } } }
          ... on LabeledEvent { id createdAt actor { __typename login avatarUrl } label { name color } }
          ... on UnlabeledEvent { id createdAt actor { __typename login avatarUrl } label { name color } }
          ... on AssignedEvent { id createdAt actor { __typename login avatarUrl } assignee { ... on Actor { login } } }
          ... on UnassignedEvent { id createdAt actor { __typename login avatarUrl } assignee { ... on Actor { login } } }
          ... on ReadyForReviewEvent { id createdAt actor { __typename login avatarUrl } }
          ... on ConvertToDraftEvent { id createdAt actor { __typename login avatarUrl } }
          ... on ClosedEvent { id createdAt actor { __typename login avatarUrl } }
          ... on ReopenedEvent { id createdAt actor { __typename login avatarUrl } }
          ... on MergedEvent { id createdAt actor { __typename login avatarUrl } commit { oid abbreviatedOid } mergeRefName }
          ... on HeadRefForcePushedEvent { id createdAt actor { __typename login avatarUrl } beforeCommit { abbreviatedOid } afterCommit { abbreviatedOid } }
          ... on ReviewRequestedEvent { id createdAt actor { __typename login avatarUrl } requestedReviewer { __typename ... on User { login } ... on Team { name } ... on Bot { login } ... on Mannequin { login } } }
          ... on ReviewDismissedEvent { id createdAt actor { __typename login avatarUrl } dismissalMessage review { author { login } } }
          ... on RenamedTitleEvent { id createdAt actor { __typename login avatarUrl } previousTitle currentTitle }
        }
      }
    }
  }
}"###;
fn parse_checks(rollup_value: &Value) -> (Vec<Value>, Value) {
    let mut checks = Vec::new();
    for c in list(&rollup_value["contexts"]["nodes"]) {
        let status = c["__typename"] == "StatusContext";
        if !status && c["__typename"] != "CheckRun" {
            continue;
        }
        let bucket = if status {
            match text(c, "state") {
                "SUCCESS" => "passing",
                "PENDING" | "EXPECTED" => "pending",
                _ => "failing",
            }
        } else if c["status"] != "COMPLETED" {
            "pending"
        } else {
            match text(c, "conclusion") {
                "SUCCESS" => "passing",
                "NEUTRAL" | "SKIPPED" | "STALE" => "skipped",
                _ => "failing",
            }
        };
        let conclusion = if status {
            text(c, "state")
        } else if c["status"] == "COMPLETED" {
            c["conclusion"].as_str().unwrap_or("completed")
        } else {
            text(c, "status")
        };
        let group = c["checkSuite"]["workflowRun"]["workflow"]["name"]
            .as_str()
            .or_else(|| c["checkSuite"]["app"]["name"].as_str());
        checks.push(json!({"id":c["id"],"name":if status{&c["context"]}else{&c["name"]},"group":group,"bucket":bucket,"conclusion":conclusion.to_lowercase(),"summary":if status{&c["description"]}else{&c["title"]},"url":if status{&c["targetUrl"]}else{&c["detailsUrl"]},"startedAt":if status{&c["createdAt"]}else{&c["startedAt"]},"completedAt":if status{Value::Null}else{c["completedAt"].clone()},"required":bool_at(c,"isRequired")}));
    }
    let order = |v: &Value| {
        ["failing", "pending", "passing", "skipped"]
            .iter()
            .position(|b| *b == text(v, "bucket"))
            .unwrap_or(4)
    };
    checks.sort_by(|a, b| {
        order(a)
            .cmp(&order(b))
            .then_with(|| bool_at(b, "required").cmp(&bool_at(a, "required")))
            .then_with(|| {
                text(a, "name")
                    .to_lowercase()
                    .cmp(&text(b, "name").to_lowercase())
            })
    });
    let count = |bucket: &str| {
        checks
            .iter()
            .filter(|c| text(c, "bucket") == bucket)
            .count()
    };
    let total = rollup_value["contexts"]["totalCount"].as_u64().unwrap_or(0);
    let summary = json!({"failing":count("failing"),"pending":count("pending"),"passing":count("passing"),"skipped":count("skipped"),"total":total,"hidden":total.saturating_sub(checks.len()as u64),"state":rollup(&rollup_value["state"])});
    (checks, summary)
}
fn parse_timeline(nodes: &[Value]) -> Vec<Value> {
    let mut items: Vec<Value> = Vec::new();
    for node in nodes {
        let kind = text(node, "__typename");
        if kind == "PullRequestCommit" {
            let c = &node["commit"];
            let commit = json!({"oid":c["oid"],"abbreviatedOid":c["abbreviatedOid"],"headline":c["messageHeadline"],"url":node["url"]});
            if let Some(last) = items.last_mut().filter(|v| v["kind"] == "commits") {
                last["commits"].as_array_mut().unwrap().push(commit);
                continue;
            }
            let login = c["author"]["user"]["login"]
                .as_str()
                .or_else(|| c["author"]["name"].as_str());
            items.push(json!({"kind":"commits","id":node["id"],"createdAt":c["committedDate"],"actor":login.map(|login|json!({"login":login,"avatarUrl":c["author"]["avatarUrl"]})),"commits":[commit]}));
            continue;
        }
        if kind == "IssueComment" {
            items.push(json!({"kind":"comment","id":node["id"],"url":node["url"],"createdAt":node["createdAt"],"actor":actor(&node["author"]),"bodyHTML":node["bodyHTML"],"minimizedReason":if bool_at(node,"isMinimized"){json!(node["minimizedReason"].as_str().unwrap_or("hidden").to_lowercase())}else{Value::Null}}));
            continue;
        }
        if kind == "PullRequestReview" {
            let state = text(node, "state").to_lowercase();
            if state == "pending"
                || (state == "commented"
                    && text(node, "bodyHTML").trim().is_empty()
                    && node["comments"]["totalCount"] == 0)
            {
                continue;
            }
            let comments:Vec<Value>=list(&node["comments"]["nodes"]).iter().map(|c|json!({"id":c["id"],"url":c["url"],"path":c["path"],"line":if c["line"].is_null(){&c["originalLine"]}else{&c["line"]},"outdated":bool_at(c,"outdated"),"bodyHTML":c["bodyHTML"],"author":actor(&c["author"])})).collect();
            items.push(json!({"kind":"review","id":node["id"],"url":node["url"],"createdAt":node["submittedAt"].as_str().unwrap_or(text(node,"createdAt")),"actor":actor(&node["author"]),"state":state,"bodyHTML":node["bodyHTML"],"commentCount":node["comments"]["totalCount"],"comments":comments}));
            continue;
        }
        let event = match kind {
            "LabeledEvent" => "labeled",
            "UnlabeledEvent" => "unlabeled",
            "AssignedEvent" => "assigned",
            "UnassignedEvent" => "unassigned",
            "ReadyForReviewEvent" => "ready_for_review",
            "ConvertToDraftEvent" => "convert_to_draft",
            "ClosedEvent" => "closed",
            "ReopenedEvent" => "reopened",
            "MergedEvent" => "merged",
            "HeadRefForcePushedEvent" => "force_pushed",
            "ReviewRequestedEvent" => "review_requested",
            "ReviewDismissedEvent" => "review_dismissed",
            "RenamedTitleEvent" => "renamed",
            _ => continue,
        };
        let mut item = json!({"kind":"event","id":node["id"],"createdAt":node["createdAt"],"actor":actor(&node["actor"]),"event":event});
        match kind {
            "LabeledEvent" | "UnlabeledEvent" => item["label"] = node["label"].clone(),
            "AssignedEvent" | "UnassignedEvent" => {
                item["subject"] = node["assignee"]["login"].clone()
            }
            "MergedEvent" => {
                item["subject"] = node["commit"]["abbreviatedOid"].clone();
                item["to"] = node["mergeRefName"].clone();
            }
            "HeadRefForcePushedEvent" => {
                item["from"] = node["beforeCommit"]["abbreviatedOid"].clone();
                item["to"] = node["afterCommit"]["abbreviatedOid"].clone();
            }
            "ReviewRequestedEvent" => {
                item["subject"] = node["requestedReviewer"]["login"]
                    .as_str()
                    .or_else(|| node["requestedReviewer"]["name"].as_str())
                    .map(|s| json!(s))
                    .unwrap_or(Value::Null)
            }
            "ReviewDismissedEvent" => {
                item["subject"] = node["review"]["author"]["login"].clone();
                item["message"] = node["dismissalMessage"].clone();
            }
            "RenamedTitleEvent" => {
                item["from"] = node["previousTitle"].clone();
                item["to"] = node["currentTitle"].clone();
            }
            _ => {}
        }
        items.push(item);
    }
    items
}
fn normalized_mergeable(value: &str) -> String {
    match value.to_lowercase().as_str() {
        "mergeable" => "mergeable",
        "conflicting" => "conflicting",
        _ => "unknown",
    }
    .into()
}
fn normalized_merge_state(value: &str) -> String {
    match value.to_lowercase().as_str() {
        "clean" => "clean",
        "dirty" => "dirty",
        "draft" => "draft",
        "blocked" => "blocked",
        "behind" => "behind",
        "unstable" => "unstable",
        "has_hooks" => "has_hooks",
        _ => "unknown",
    }
    .into()
}

/// @cc [owner:OctavianTocan,label:product] github-mergeability-requires-known-state
/// GitHub conflicts MUST prevent readiness regardless of passing checks or administrator
/// permissions. Otherwise, unless the pull request is a draft, missing, unknown or
/// unrecognized mergeability and merge-state fields MUST remain checking, including for
/// administrators (Tavi, 20 Oct 2026, "a PR would have merge conflicts ... yet Pully
/// would show it as being all good").
pub(super) fn mergeability(o: &Value) -> Value {
    let state = text(o, "state");
    let mergeable = normalized_mergeable(text(o, "mergeable"));
    let status = normalized_merge_state(text(o, "mergeStateStatus"));
    if !["open", "draft"].contains(&state)
        || !bool_at(&o["viewer"], "canPush")
        || list(&o["merge"]["methods"]).is_empty()
    {
        return json!({"allowed":false,"ready":false});
    }
    if mergeable == "conflicting" || status == "dirty" {
        return json!({"allowed":true,"ready":false,"blocker":"conflicts"});
    }
    if state == "draft" || status == "draft" {
        return json!({"allowed":true,"ready":false,"blocker":"draft"});
    }
    if mergeable == "unknown" || status == "unknown" {
        return json!({"allowed":true,"ready":false,"blocker":"checking"});
    }
    if ["blocked", "behind"].contains(&status.as_str()) {
        let admin = bool_at(&o["viewer"], "canMergeAsAdmin");
        return json!({"allowed":true,"ready":admin,"blocker":status,"warning":if admin{json!("bypass")}else{Value::Null}});
    }
    if status == "unstable" {
        return json!({"allowed":true,"ready":true,"warning":"unstable"});
    }
    json!({"allowed":true,"ready":true})
}
fn default_commits(o: &Value) -> Value {
    let merge = &o["merge"];
    let title = text(o, "title");
    let body = text(o, "body");
    let number = o["number"].as_u64().unwrap_or(0);
    let head = &o["head"];
    let merge_title = if merge["mergeTitle"] == "PR_TITLE" {
        format!("{title} (#{number})")
    } else {
        format!(
            "Merge pull request #{number} from {}{}",
            if text(head, "owner").is_empty() {
                String::new()
            } else {
                format!("{}/", text(head, "owner"))
            },
            text(head, "ref")
        )
    };
    let merge_body = match text(merge, "mergeMessage") {
        "PR_BODY" => body,
        "PR_TITLE" => title,
        _ => "",
    };
    let commits = list(&o["commits"]);
    let single = (commits.len() == 1 && o["commitsHidden"] == 0).then(|| &commits[0]);
    let squash_title = format!(
        "{} (#{number})",
        if merge["squashTitle"] == "COMMIT_OR_PR_TITLE" {
            single.map(|c| text(c, "headline")).unwrap_or(title)
        } else {
            title
        }
    );
    let squash_body = match text(merge, "squashMessage") {
        "PR_BODY" => body.to_owned(),
        "BLANK" => String::new(),
        _ => single
            .map(|c| text(c, "body").to_owned())
            .unwrap_or_else(|| {
                commits
                    .iter()
                    .map(|c| {
                        if text(c, "body").is_empty() {
                            format!("* {}", text(c, "headline"))
                        } else {
                            format!("* {}\n\n{}", text(c, "headline"), text(c, "body"))
                        }
                    })
                    .collect::<Vec<_>>()
                    .join("\n\n")
            }),
    };
    json!({"merge":{"title":merge_title,"body":merge_body},"squash":{"title":squash_title,"body":squash_body},"rebase":{"title":"","body":""}})
}
fn normalize_overview(data: &Value) -> Result<Value> {
    let repo = &data["repository"];
    let pr = &repo["pullRequest"];
    if repo.is_null() || pr.is_null() {
        bail!("Pull request not found.")
    }
    let (checks, checks_summary) =
        parse_checks(&pr["headCommit"]["nodes"][0]["commit"]["statusCheckRollup"]);
    let commits:Vec<Value>=list(&pr["commits"]["nodes"]).iter().map(|n|{let c=&n["commit"];json!({"oid":c["oid"],"abbreviatedOid":c["abbreviatedOid"],"headline":c["messageHeadline"],"body":c["messageBody"],"committedDate":c["committedDate"],"url":c["url"],"author":{"name":c["author"]["name"],"login":c["author"]["user"]["login"],"avatarUrl":c["author"]["avatarUrl"]},"parentOid":c["parents"]["nodes"][0]["oid"],"checks":rollup(&c["statusCheckRollup"]["state"])})}).collect();
    let methods: Vec<&str> = [
        ("mergeCommitAllowed", "merge"),
        ("squashMergeAllowed", "squash"),
        ("rebaseMergeAllowed", "rebase"),
    ]
    .into_iter()
    .filter(|(key, _)| bool_at(repo, key))
    .map(|(_, method)| method)
    .collect();
    let preferred = text(repo, "viewerDefaultMergeMethod").to_lowercase();
    let default_method = if methods.contains(&preferred.as_str()) {
        preferred.as_str()
    } else {
        methods.first().copied().unwrap_or("merge")
    };
    let requested: Vec<Value> = list(&pr["reviewRequests"]["nodes"])
        .iter()
        .filter_map(|n| {
            n["requestedReviewer"]["login"]
                .as_str()
                .or_else(|| n["requestedReviewer"]["name"].as_str())
                .map(|s| json!(s))
        })
        .collect();
    let latest: Vec<Value> = list(&pr["latestOpinionatedReviews"]["nodes"])
        .iter()
        .filter(|n| !n["author"].is_null())
        .map(|n| json!({"author":actor(&n["author"]),"state":text(n,"state").to_lowercase()}))
        .collect();
    let timeline_nodes = list(&pr["timelineItems"]["nodes"]);
    let mut o = json!({
        "id":pr["id"],"number":pr["number"],"title":pr["title"],"url":pr["url"],"body":pr["body"],"bodyHTML":pr["bodyHTML"],"createdAt":pr["createdAt"],"updatedAt":pr["updatedAt"],"author":actor(&pr["author"]),"state":pr_state(pr),"mergedAt":pr["mergedAt"],"closedAt":pr["closedAt"],"mergeable":normalized_mergeable(text(pr,"mergeable")),"mergeStateStatus":normalized_merge_state(text(pr,"mergeStateStatus")),"reviewDecision":pr["reviewDecision"].as_str().map(str::to_lowercase),
        "head":{"oid":pr["headRefOid"],"ref":pr["headRefName"],"owner":pr["headRepositoryOwner"]["login"]},"base":{"oid":pr["baseRefOid"],"ref":pr["baseRefName"]},"requestedReviewers":requested,"latestReviews":latest,"checks":checks,"checksSummary":checks_summary,"commitsHidden":pr["commits"]["totalCount"].as_u64().unwrap_or(0).saturating_sub(commits.len()as u64),"commits":commits,"timeline":parse_timeline(timeline_nodes),"timelineHidden":pr["timelineItems"]["totalCount"].as_u64().unwrap_or(0).saturating_sub(timeline_nodes.len()as u64),
        "viewer":{"permission":repo["viewerPermission"],"canPush":(["ADMIN","MAINTAIN","WRITE"].contains(&text(repo,"viewerPermission"))),"canMergeAsAdmin":bool_at(pr,"viewerCanMergeAsAdmin"),"canClose":bool_at(pr,"viewerCanClose"),"canReopen":bool_at(pr,"viewerCanReopen"),"canUpdate":bool_at(pr,"viewerCanUpdate"),"isAuthor":bool_at(pr,"viewerDidAuthor"),"canUpdateBranch":bool_at(pr,"viewerCanUpdateBranch"),"canEnableAutoMerge":bool_at(pr,"viewerCanEnableAutoMerge"),"canDisableAutoMerge":bool_at(pr,"viewerCanDisableAutoMerge")},
        "merge":{"methods":methods,"defaultMethod":default_method,"squashTitle":repo["squashMergeCommitTitle"],"squashMessage":repo["squashMergeCommitMessage"],"mergeTitle":repo["mergeCommitTitle"],"mergeMessage":repo["mergeCommitMessage"]},"autoMerge":pr["autoMergeRequest"]["mergeMethod"].as_str().map(|method|json!({"method":method.to_lowercase()}))
    });
    omit_nulls(&mut o);
    let mut value =
        json!({"mergeability":mergeability(&o),"defaultCommits":default_commits(&o),"overview":o});
    omit_nulls(&mut value);
    Ok(value)
}
pub(super) async fn overview(state: &AppState, args: &Value) -> Result<Value> {
    let pull = Pull::from(args)?;
    let key = format!("pr-overview:{}/{}#{}", pull.owner, pull.repo, pull.number);
    if bool_at(args, "cached") {
        return Ok(match state.host.storage.get_async(&key).await? {
            Some(stored) if stored["value"]["overview"].is_object() => {
                let mut value = stored["value"].clone();
                value["storedAt"] = stored["at"].clone();
                value
            }
            _ => Value::Null,
        });
    }
    let at = crate::now_ms();
    let value = normalize_overview(&graphql(state, OVERVIEW_QUERY, pull.variables()).await?)?;
    let stored = json!({"at":at,"updatedAt":value["overview"]["updatedAt"].as_str().unwrap_or(""),"value":value});
    // A slower earlier fetch may answer its caller but cannot replace a newer cached overview.
    let _ = state
        .host
        .storage
        .transaction(move |store| {
            store.update(&key, |current| {
                Ok(match current {
                    Some(current) if current["at"].as_u64().unwrap_or(0) > at => current,
                    _ => stored,
                })
            })
        })
        .await;
    Ok(value)
}

/// @cc [owner:OctavianTocan,label:product] rust-overview-bots-from-github
/// Actors MUST be bots only when GitHub gives type Bot or a login ending in
/// [bot]; MUST NOT classify accounts by a name list (16 Oct 12:10, N31).
fn actor(raw: &Value) -> Value {
    let login = text(raw, "login");
    if login.is_empty() {
        return Value::Null;
    }
    let mut actor = json!({"login":login,"avatarUrl":raw["avatarUrl"]});
    if raw["__typename"] == "Bot" || login.to_ascii_lowercase().ends_with("[bot]") {
        actor["bot"] = json!(true);
    }
    omit_nulls(&mut actor);
    actor
}
pub(super) async fn pull_text(state: &AppState, args: &Value) -> Result<Value> {
    let pull = Pull::from(args)?;
    let data = graphql(state, r#"query PullRequestText($owner: String!, $repo: String!, $number: Int!) {
      repository(owner: $owner, name: $repo) { pullRequest(number: $number) { title body bodyHTML updatedAt } }
    }"#, pull.variables()).await?;
    let pr = &data["repository"]["pullRequest"];
    if !pr.is_object() {
        bail!(
            "Pull request {}/{}#{} not found",
            pull.owner,
            pull.repo,
            pull.number
        );
    }
    Ok(
        json!({"title":pr["title"],"body":pr["body"],"bodyHTML":pr["bodyHTML"],"updatedAt":pr["updatedAt"]}),
    )
}
#[cfg(test)]
mod latest_tests {
    use super::*;
    #[test]
    fn github_alone_classifies_bots() {
        assert_eq!(
            actor(&json!({"login":"new-app","__typename":"Bot"}))["bot"],
            true
        );
        assert_eq!(actor(&json!({"login":"robot[BOT]"}))["bot"], true);
        assert!(
            actor(&json!({"login":"github-actions","__typename":"User"}))
                .get("bot")
                .is_none()
        );
        assert!(actor(&Value::Null).is_null());
    }
}

#[cfg(test)]
mod mergeability_tests {
    use super::*;

    fn raw_pr(mergeable: Value, status: Value) -> Value {
        json!({"repository":{"viewerPermission":"ADMIN","mergeCommitAllowed":true,"pullRequest":{
            "id":"PR_1","state":"OPEN","mergeable":mergeable,"mergeStateStatus":status,
            "viewerCanMergeAsAdmin":true,"headCommit":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS"}}}]}
        }}})
    }

    #[test]
    fn mergeability_normalization_preserves_conflicts_with_passing_checks() {
        for (mergeable, status) in [("CONFLICTING", "CLEAN"), ("MERGEABLE", "DIRTY")] {
            let value = normalize_overview(&raw_pr(json!(mergeable), json!(status))).unwrap();
            assert_eq!(value["overview"]["checksSummary"]["state"], "success");
            assert_eq!(value["mergeability"]["ready"], false);
            assert_eq!(value["mergeability"]["blocker"], "conflicts");
        }
    }

    #[test]
    fn mergeability_normalization_never_bypasses_unknown_fields() {
        for (mergeable, status) in [
            (Value::Null, json!("CLEAN")), (json!("MERGEABLE"), Value::Null),
            (json!("UNKNOWN"), json!("BLOCKED")), (json!(""), json!("CLEAN")),
            (json!("MERGEABLE"), json!("NEW_STATUS")), (json!("NEW_STATE"), json!("BEHIND")),
        ] {
            let value = normalize_overview(&raw_pr(mergeable, status)).unwrap();
            assert_eq!(value["mergeability"]["ready"], false, "{value}");
            assert_eq!(value["mergeability"]["blocker"], "checking");
            assert!(value["overview"]["mergeable"] == "unknown" || value["overview"]["mergeStateStatus"] == "unknown");
        }
        let mut missing = raw_pr(Value::Null, Value::Null);
        missing["repository"]["pullRequest"].as_object_mut().unwrap().remove("mergeable");
        missing["repository"]["pullRequest"].as_object_mut().unwrap().remove("mergeStateStatus");
        assert_eq!(normalize_overview(&missing).unwrap()["mergeability"]["blocker"], "checking");
    }

    #[test]
    fn mergeability_normalization_allows_known_clean_state() {
        let value = normalize_overview(&raw_pr(json!("MERGEABLE"), json!("CLEAN"))).unwrap();
        assert_eq!(value["overview"]["mergeable"], "mergeable");
        assert_eq!(value["overview"]["mergeStateStatus"], "clean");
        assert_eq!(value["mergeability"]["ready"], true);
    }
}
