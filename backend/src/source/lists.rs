use super::*;
use futures_util::{stream, StreamExt};
use std::collections::{HashMap, HashSet};
async fn get(state: &AppState, path: &str) -> Result<Value> {
    github_request(state, reqwest::Method::GET, path, None).await
}
fn check_status(state: &str) -> Option<&'static str> {
    match state {
        "SUCCESS" => Some("success"),
        "FAILURE" | "ERROR" => Some("failure"),
        "PENDING" | "EXPECTED" => Some("pending"),
        _ => None,
    }
}
fn repo_key(args: &Value) -> String {
    format!(
        "repo-pulls:{}/{}",
        s(args, "owner").to_lowercase(),
        s(args, "repo").to_lowercase()
    )
}
type RepoRefreshKey = (std::path::PathBuf, String);
type RepoRefresh =
    tokio::sync::watch::Receiver<Option<std::result::Result<Value, RepoRefreshError>>>;

#[derive(Clone, Debug)]
pub(super) struct RepoRefreshError(Arc<anyhow::Error>);
impl std::fmt::Display for RepoRefreshError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Display::fmt(self.0.as_ref(), f)
    }
}
impl std::error::Error for RepoRefreshError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.0.source()
    }
}
fn repo_refreshes() -> &'static std::sync::Mutex<HashMap<RepoRefreshKey, RepoRefresh>> {
    use std::sync::{Mutex, OnceLock};
    static INFLIGHT: OnceLock<Mutex<HashMap<RepoRefreshKey, RepoRefresh>>> = OnceLock::new();
    INFLIGHT.get_or_init(Default::default)
}
struct RepoRefreshGuard(Option<RepoRefreshKey>);
impl RepoRefreshGuard {
    async fn publish(
        mut self,
        sender: tokio::sync::watch::Sender<Option<std::result::Result<Value, RepoRefreshError>>>,
        result: Result<Value>,
    ) {
        let identity = self.0.take().expect("refresh identity");
        let _ = crate::store::blocking(move || {
            let mut inflight = repo_refreshes().lock().unwrap_or_else(|e| e.into_inner());
            inflight.remove(&identity);
            sender.send_replace(Some(result.map_err(|e| RepoRefreshError(Arc::new(e)))));
            Ok(())
        })
        .await;
    }
}
impl Drop for RepoRefreshGuard {
    fn drop(&mut self) {
        let Some(identity) = self.0.take() else {
            return;
        };
        crate::rt::spawn_blocking(move || {
            repo_refreshes()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&identity);
        });
    }
}
async fn fetch_repo_page(state: &AppState, args: &Value) -> Result<Value> {
    fetch_repo_page_with(
        args,
        github::token(state).await?.is_some(),
        |method, path, body| async move { github_request(state, method, &path, body).await },
    )
    .await
}
/// @cc [owner:OctavianTocan,label:architecture] rust-repository-continuations-survive-authentication
/// Nonempty all-digit repository continuations MUST retain REST page semantics,
/// including page limits and errors, even after authentication becomes available.
/// Other authenticated continuations MUST reach GraphQL unchanged (16 Oct 2026
/// parity request; CE review round 8; persisted repo-pulls page compatibility).
async fn fetch_repo_page_with<F, Fut>(
    args: &Value,
    authenticated: bool,
    request: F,
) -> Result<Value>
where
    F: FnOnce(reqwest::Method, String, Option<Value>) -> Fut,
    Fut: std::future::Future<Output = Result<Value>>,
{
    let next = s(args, "next");
    let legacy_rest = !next.is_empty() && next.bytes().all(|c| c.is_ascii_digit());
    if authenticated && !legacy_rest {
        repo_pulls_graphql(args, request).await
    } else {
        repo_pulls_rest(args, request).await
    }
}
/// @cc [owner:OctavianTocan,label:reliability] rust-repository-refreshes-share-results
/// Concurrent first-page refreshes for one store/repository MUST share one fetch and
/// its result, including errors. Stale reads MUST NOT queue retries, and failed fetches
/// MUST preserve the cached page (16 Oct 2026 parity request; CE review finding 15;
/// former TypeScript swr.ts refresh contract).
fn start_repo_refresh_sync<F, Fut>(
    state: &Arc<AppState>,
    args: &Value,
    fresh: bool,
    fetch: F,
) -> Result<RepoRefresh>
where
    F: FnOnce(Arc<AppState>, Value) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = Result<Value>> + Send + 'static,
{
    let key = repo_key(args);
    let identity = (state.host.storage.root().to_path_buf(), key.clone());
    let mut inflight = repo_refreshes().lock().unwrap_or_else(|e| e.into_inner());
    if let Some(running) = inflight.get(&identity) {
        return Ok(running.clone());
    }
    if !fresh {
        if let Some(v) = state.host.storage.get(&key)? {
            if now().saturating_sub(v["cachedAt"].as_u64().unwrap_or(0)) < 60_000 {
                return Ok(tokio::sync::watch::channel(Some(Ok(v))).1);
            }
        }
    }
    // Register before spawning: stale reads can drop their receiver without creating
    // detached waiters, and foreground callers keep the same result after completion.
    let (sender, receiver) = tokio::sync::watch::channel(None);
    inflight.insert(identity.clone(), receiver.clone());
    drop(inflight);
    let flight = RepoRefreshGuard(Some(identity));
    let state = state.clone();
    let args = args.clone();
    crate::rt::spawn(async move {
        let result = async {
            let mut result = fetch(state.clone(), args).await?;
            result["cachedAt"] = json!(now());
            state.host.storage.set_async(&key, &result).await?;
            Ok(result)
        }
        .await;
        flight.publish(sender, result).await;
    });
    Ok(receiver)
}
async fn start_repo_refresh<F, Fut>(
    state: &Arc<AppState>,
    args: &Value,
    fresh: bool,
    fetch: F,
) -> Result<RepoRefresh>
where
    F: FnOnce(Arc<AppState>, Value) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = Result<Value>> + Send + 'static,
{
    let (state, args) = (state.clone(), args.clone());
    crate::store::blocking(move || start_repo_refresh_sync(&state, &args, fresh, fetch)).await
}
/// @cc [owner:OctavianTocan,label:architecture] inbox-refresh-shares-failure
/// Concurrent inbox refreshes MUST share one completion, including failure, without
/// queuing retries behind a failed fetch (16 Oct 2026 review fixes).
fn start_inbox_refresh_with<F, Fut>(
    state: &Arc<AppState>,
    args: &Value,
    fetch: F,
) -> Result<RepoRefresh>
where
    F: FnOnce(Arc<AppState>, Value) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = Result<Value>> + Send + 'static,
{
    let st = args["state"].as_str().unwrap_or("open");
    ensure!(["open", "closed"].contains(&st), "Invalid pull state");
    let key = format!("my-pulls:{st}");
    let identity = (state.host.storage.root().to_path_buf(), key.clone());
    let mut inflight = repo_refreshes().lock().unwrap_or_else(|e| e.into_inner());
    if let Some(running) = inflight.get(&identity) {
        return Ok(running.clone());
    }
    if let Some(v) = state.host.storage.get(&key)? {
        if now().saturating_sub(v["fetchedAt"].as_u64().unwrap_or(0)) < 5_000 {
            return Ok(tokio::sync::watch::channel(Some(Ok(v))).1);
        }
    }
    // Register before spawning: stale reads can drop their receiver without creating
    // detached waiters, and foreground callers keep the same result after completion.
    let (sender, receiver) = tokio::sync::watch::channel(None);
    inflight.insert(identity.clone(), receiver.clone());
    drop(inflight);
    let flight = RepoRefreshGuard(Some(identity));
    let state = state.clone();
    let args = args.clone();
    crate::rt::spawn(async move {
        let result = fetch(state, args).await;
        flight.publish(sender, result).await;
    });
    Ok(receiver)
}
pub(super) async fn start_inbox_refresh(
    state: &Arc<AppState>,
    args: &Value,
) -> Result<RepoRefresh> {
    let (state, args) = (state.clone(), args.clone());
    crate::store::blocking(move || {
        start_inbox_refresh_with(&state, &args, |state, args| async move {
            fetch_my_pulls(&state, &args).await
        })
    })
    .await
}
pub(super) async fn inbox_refreshing(state: &AppState, args: &Value) -> Result<bool> {
    let key = format!("my-pulls:{}", args["state"].as_str().unwrap_or("open"));
    let root = state.host.storage.root().to_path_buf();
    crate::store::blocking(move || {
        Ok(repo_refreshes()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .contains_key(&(root, key)))
    })
    .await
}
async fn repo_refresh_result(mut refresh: RepoRefresh) -> Result<Value> {
    loop {
        if let Some(result) = refresh.borrow().clone() {
            return result.map_err(anyhow::Error::new);
        }
        refresh
            .changed()
            .await
            .context("Repository refresh stopped before completing")?;
    }
}
async fn fetch_repo_page_owned(state: Arc<AppState>, args: Value) -> Result<Value> {
    fetch_repo_page(&state, &args).await
}
async fn refresh_repo(state: &Arc<AppState>, args: &Value, fresh: bool) -> Result<Value> {
    repo_refresh_result(start_repo_refresh(state, args, fresh, fetch_repo_page_owned).await?).await
}
pub async fn repo_pulls(state: &Arc<AppState>, args: &Value) -> Result<Value> {
    repo_pulls_with(state, args, fetch_repo_page_owned).await
}
async fn repo_pulls_with<F, Fut>(state: &Arc<AppState>, args: &Value, fetch: F) -> Result<Value>
where
    F: FnOnce(Arc<AppState>, Value) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = Result<Value>> + Send + 'static,
{
    github::prefix(args)?;
    let first = args["next"].is_null() || s(args, "next").is_empty();
    if !first {
        return fetch(state.clone(), args.clone()).await;
    }
    if args["fresh"] != true {
        if let Some(v) = state.host.storage.get_async(&repo_key(args)).await? {
            if now().saturating_sub(v["cachedAt"].as_u64().unwrap_or(0)) >= 60_000 {
                let _ = start_repo_refresh(state, args, false, fetch).await;
            }
            return Ok(v);
        }
    }
    repo_refresh_result(start_repo_refresh(state, args, args["fresh"] == true, fetch).await?).await
}
pub async fn warm_repositories(state: &Arc<AppState>, pulls: &[Value]) -> Result<()> {
    let (state, pulls) = (state.clone(), pulls.to_vec());
    crate::store::blocking(move || {
        warm_repositories_sync(&state, &pulls);
        Ok(())
    })
    .await
}
fn warm_repositories_sync(state: &Arc<AppState>, pulls: &[Value]) {
    use std::sync::{Mutex, OnceLock};
    static WARMING: OnceLock<Mutex<HashSet<std::path::PathBuf>>> = OnceLock::new();
    let root = state.host.storage.root().to_path_buf();
    let mut counts: HashMap<String, (Value, usize)> = HashMap::new();
    for p in pulls {
        let args = json!({"owner":p["owner"],"repo":p["repo"]});
        if github::prefix(&args).is_err() {
            continue;
        }
        let entry = counts.entry(repo_key(&args)).or_insert((args, 0));
        entry.1 += 1;
    }
    let mut counts: Vec<_> = counts.into_values().collect();
    counts.sort_by(|a, b| b.1.cmp(&a.1));
    counts.truncate(12);
    let due: Vec<_> = counts
        .into_iter()
        .map(|(args, _)| args)
        .filter(|args| {
            state
                .host
                .storage
                .get(&repo_key(args))
                .ok()
                .flatten()
                .is_none_or(|v| {
                    now().saturating_sub(v["cachedAt"].as_u64().unwrap_or(0)) >= 300_000
                })
        })
        .collect();
    if due.is_empty()
        || !WARMING
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(root.clone())
    {
        return;
    }
    let state = state.clone();
    crate::rt::spawn(async move {
        for args in due {
            let _ = refresh_repo(&state, &args, false).await;
        }
        WARMING
            .get()
            .unwrap()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&root);
    });
}
/// @cc [owner:OctavianTocan,label:architecture] rust-repository-label-descriptions-are-optional-strings
/// Repository labels MUST contain only name, color and an optional string
/// description. Null or non-string descriptions MUST be omitted (16 Oct 2026
/// parity request; canonical PullRequestLabelSchema and TypeScript normalization).
fn repo_labels(labels: &Value) -> Vec<Value> {
    labels
        .as_array()
        .into_iter()
        .flatten()
        .map(|label| {
            let mut result = json!({"name":label["name"],"color":label["color"]});
            if label["description"].is_string() {
                result["description"] = label["description"].clone();
            }
            result
        })
        .collect()
}
async fn repo_pulls_rest<F, Fut>(args: &Value, request: F) -> Result<Value>
where
    F: FnOnce(reqwest::Method, String, Option<Value>) -> Fut,
    Fut: std::future::Future<Output = Result<Value>>,
{
    let page = args["next"]
        .as_str()
        .filter(|v| !v.is_empty())
        .unwrap_or("1")
        .parse::<usize>()
        .context("Invalid continuation")?;
    ensure!(page > 0 && page <= 10, "Invalid continuation");
    let v = request(
        reqwest::Method::GET,
        format!(
            "/search/issues?q={}&sort=updated&order=desc&per_page=100&page={page}",
            enc(&format!(
                "repo:{}/{} is:pr is:open",
                s(args, "owner"),
                s(args, "repo")
            ))
        ),
        None,
    )
    .await?;
    ensure!(
        v["incomplete_results"] != true,
        "GitHub search is incomplete; retry later"
    );
    let items = v["items"]
        .as_array()
        .context("Invalid GitHub search response")?;
    let mut result = json!({"totalCount":v["total_count"],"items":items.iter().map(|p|{
        let labels = repo_labels(&p["labels"]);
        let mut n=json!({"number":p["number"],"title":p["title"],"url":p["html_url"],"state":if p["draft"]==true{"draft"}else{"open"},"labels":labels,"assignees":p["assignees"].as_array().into_iter().flatten().map(|u|json!({"login":u["login"],"avatarUrl":u["avatar_url"]})).collect::<Vec<_>>(),"createdAt":p["created_at"],"updatedAt":p["updated_at"],"comments":p["comments"]});
        if p["user"].is_object(){n["author"]=json!({"login":p["user"]["login"],"avatarUrl":p["user"]["avatar_url"]});} if p["milestone"]["title"].is_string(){n["milestone"]=p["milestone"]["title"].clone();} n
    }).collect::<Vec<_>>()});
    if items.len() == 100
        && page * 100 < (v["total_count"].as_u64().unwrap_or(0) as usize).min(1000)
    {
        result["next"] = json!((page + 1).to_string());
    }
    Ok(result)
}
async fn repo_pulls_graphql<F, Fut>(args: &Value, request: F) -> Result<Value>
where
    F: FnOnce(reqwest::Method, String, Option<Value>) -> Fut,
    Fut: std::future::Future<Output = Result<Value>>,
{
    let query = "query OpenPullRequests($query: String!, $first: Int!, $cursor: String) { search(query: $query, type: ISSUE, first: $first, after: $cursor) { issueCount pageInfo { hasNextPage endCursor } nodes { ... on PullRequest { number title url isDraft createdAt updatedAt author { login avatarUrl } labels(first:20) { nodes { name color description } } assignees(first:10) { nodes { login avatarUrl } } milestone { title } comments { totalCount } reviewDecision closingIssuesReferences { totalCount } commits(last:1) { nodes { commit { statusCheckRollup { state } } } } additions deletions changedFiles } } } }";
    let response = request(reqwest::Method::POST,"/graphql".into(),Some(json!({"query":query,"variables":{"query":format!("repo:{}/{} is:pr is:open sort:updated-desc",s(args,"owner"),s(args,"repo")),"first":100,"cursor":args["next"].as_str().filter(|s|!s.is_empty())}}))).await?;
    let search = &response["data"]["search"];
    let nodes = search["nodes"]
        .as_array()
        .context("GitHub repository list is unavailable")?;
    let items = nodes.iter().filter(|p|p["number"].is_number()).map(|p| {
        let mut n = json!({"number":p["number"],"title":p["title"],"url":p["url"],"state":if p["isDraft"]==true{"draft"}else{"open"},"labels":repo_labels(&p["labels"]["nodes"]),"assignees":p["assignees"]["nodes"].as_array().cloned().unwrap_or_default(),"createdAt":p["createdAt"],"updatedAt":p["updatedAt"],"comments":p["comments"]["totalCount"],"linkedIssues":p["closingIssuesReferences"]["totalCount"].as_u64().unwrap_or(0),"additions":p["additions"],"deletions":p["deletions"],"changedFiles":p["changedFiles"]});
        if p["author"].is_object() { n["author"] = p["author"].clone(); }
        if p["milestone"]["title"].is_string() { n["milestone"] = p["milestone"]["title"].clone(); }
        if let Some(decision) = p["reviewDecision"].as_str() { n["reviewDecision"] = json!(decision.to_lowercase()); }
        if let Some(checks) = check_status(s(&p["commits"]["nodes"][0]["commit"]["statusCheckRollup"],"state")) { n["checks"] = json!(checks); }
        n
    }).collect::<Vec<_>>();
    let mut result = json!({"totalCount":search["issueCount"],"items":items});
    if search["pageInfo"]["hasNextPage"] == true {
        let next = search["pageInfo"]["endCursor"]
            .as_str()
            .context("GitHub list continuation is missing")?;
        ensure!(!next.is_empty(), "GitHub list continuation is missing");
        result["next"] = json!(next);
    }
    Ok(result)
}
pub async fn my_pulls(state: &Arc<AppState>, args: &Value) -> Result<Value> {
    let value = repo_refresh_result(start_inbox_refresh(state, args).await?).await?;
    crate::jev::apply_pulls(state, value).await
}
async fn fetch_my_pulls(state: &AppState, args: &Value) -> Result<Value> {
    let st = args["state"].as_str().unwrap_or("open");
    ensure!(["open", "closed"].contains(&st), "Invalid pull state");
    ensure!(
        github::token(state).await?.is_some(),
        "GitHub authentication is required"
    );
    let cachekey = format!("my-pulls:{st}");
    if let Some(v) = state.host.storage.get_async(&cachekey).await? {
        if now().saturating_sub(v["fetchedAt"].as_u64().unwrap_or(0)) < 5000 {
            return Ok(v);
        }
    }
    let progress = InboxProgress((state.host.storage.root().to_path_buf(), cachekey.clone()));
    let (viewer, orgs) = futures_util::join!(
        get(state, "/user"),
        github::pages(state, "/user/orgs", None)
    );
    let viewer = viewer?;
    let login = s(&viewer, "login");
    let orgs = orgs.unwrap_or_default();
    let mut pulls = inbox_window(
        st,
        login,
        &orgs,
        |path| async move { get(state, &path).await },
        |pulls| progress.publish(st, pulls),
    )
    .await?;
    let _ = enrich(state, &mut pulls, |pulls| progress.publish(st, pulls)).await;
    if st == "open" {
        let _ = native_stacks(state, &mut pulls).await;
        apply_stacks(&mut pulls);
    }
    inbox_rows(st, &mut pulls);
    let v = json!({"pulls":pulls,"fetchedAt":now(),"refreshing":false});
    state.host.storage.set_async(&cachekey, &v).await?;
    Ok(v)
}

/// GitHub requests one Inbox refresh keeps in flight at once: the role searches, then the
/// detail and stack queries of twenty rows each.
const INBOX_REQUESTS: usize = 4;

/// Drops the fields only the refresh uses and sorts the rows newest first, as clients get them.
fn inbox_rows(st: &str, pulls: &mut [Value]) {
    for pull in pulls.iter_mut() {
        if let Some(object) = pull.as_object_mut() {
            object.remove("_crossRepository");
            object.remove("_defaultBranch");
            if st == "closed" {
                for field in [
                    "reviewDecision",
                    "checks",
                    "mergeable",
                    "reviewRequests",
                    "stack",
                ] {
                    object.remove(field);
                }
            }
        }
    }
    pulls.sort_by(|a, b| s(b, "updatedAt").cmp(s(a, "updatedAt")));
}

fn inbox_progresses() -> &'static std::sync::Mutex<HashMap<RepoRefreshKey, Value>> {
    use std::sync::{Mutex, OnceLock};
    static PROGRESS: OnceLock<Mutex<HashMap<RepoRefreshKey, Value>>> = OnceLock::new();
    PROGRESS.get_or_init(Default::default)
}

/// @cc [owner:OctavianTocan,label:product;architecture] inbox-refresh-shows-rows-as-they-arrive
/// While an Inbox refresh runs, the rows it has so far MUST be readable as a `partial`
/// snapshot, kept in memory under the same storage root and key as the refresh and dropped
/// when it ends, so a first load shows pull requests as they arrive rather than all at the
/// end. A partial snapshot MUST NOT be stored as the server's copy (Tavi, 26 Oct 2026, on
/// Staging 0.62.0: "showed some of them gradually as we load them").
struct InboxProgress(RepoRefreshKey);
impl InboxProgress {
    fn publish(&self, st: &str, pulls: &[Value]) {
        let mut rows = pulls.to_vec();
        inbox_rows(st, &mut rows);
        let snapshot = json!({"pulls":rows,"fetchedAt":now(),"refreshing":true,"partial":true});
        inbox_progresses()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(self.0.clone(), snapshot);
    }
}
impl Drop for InboxProgress {
    fn drop(&mut self) {
        inbox_progresses()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.0);
    }
}

/// The rows a running Inbox refresh has found so far, or None when none is running.
pub(super) fn inbox_partial(state: &AppState, args: &Value) -> Option<Value> {
    let key = format!("my-pulls:{}", args["state"].as_str().unwrap_or("open"));
    inbox_progresses()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&(state.host.storage.root().to_path_buf(), key))
        .cloned()
}

/// @cc [owner:OctavianTocan,label:product;architecture] rust-inbox-preserves-role-windows
/// Inbox searches MUST return at most the latest 100 matches per role and MUST NOT
/// reject that window because the provider reports more than 1000 total matches.
/// Genuinely incomplete responses MUST fail (16 Oct 2026 parity request; R13 finding 9;
/// former my-pulls SEARCH_LIMIT).
async fn inbox_window<F, Fut>(
    st: &str,
    login: &str,
    orgs: &[Value],
    mut request: F,
    mut progress: impl FnMut(&[Value]),
) -> Result<Vec<Value>>
where
    F: FnMut(String) -> Fut,
    Fut: std::future::Future<Output = Result<Value>>,
{
    let mut owners = vec![login.to_string()];
    owners.extend(
        orgs.iter()
            .filter_map(|o| o["login"].as_str().map(str::to_string)),
    );
    let roles = vec![
        ("review-requested", format!("review-requested:{login}")),
        ("authored", format!("author:{login}")),
        ("involved", format!("involves:{login}")),
        (
            "owned",
            owners
                .iter()
                .map(|o| format!("user:{o}"))
                .collect::<Vec<_>>()
                .join(" "),
        ),
    ];
    let mut seen = HashSet::new();
    let mut pulls = vec![];
    // The searches run together and are read in role order, so an earlier role keeps a shared row.
    let mut searches = vec![];
    for (role, query) in roles {
        let response = request(format!(
            "/search/issues?q={}&per_page=100&page=1",
            enc(&format!("is:pr is:{st} sort:updated-desc {query}"))
        ));
        searches.push(async move { (role, response.await) });
    }
    let mut searches = stream::iter(searches).buffered(INBOX_REQUESTS);
    while let Some((role, v)) = searches.next().await {
        let v = v?;
        ensure!(
            v["incomplete_results"] != true,
            "GitHub inbox search is incomplete; retry later"
        );
        let items = v["items"]
            .as_array()
            .context("Invalid inbox search response")?;
        for p in items.iter().take(100) {
            let repo_url = s(p, "repository_url");
            let names = repo_url.trim_start_matches("https://api.github.com/repos/");
            let Some((owner, repo)) = names.split_once('/') else {
                continue;
            };
            let id = format!("{names}#{}", p["number"]);
            if !seen.insert(id) {
                continue;
            }
            let mut item = json!({"owner":owner,"repo":repo,"number":p["number"],"title":p["title"],"author":p["user"]["login"].as_str().unwrap_or("ghost"),"isDraft":p["draft"].as_bool().unwrap_or(false),"updatedAt":p["updated_at"],"commentsCount":p["comments"],"labels":p["labels"].as_array().unwrap_or(&vec![]).iter().map(|l|l["name"].clone()).collect::<Vec<_>>(),"state":st,"role":role,"url":p["html_url"],"requestedFromMe":role=="review-requested"});
            if st == "closed" {
                item["closedAt"] = p["closed_at"].clone();
                item["merged"] = json!(p["pull_request"]["merged_at"].is_string());
            }
            pulls.push(item)
        }
        progress(&pulls);
    }
    Ok(pulls)
}

async fn enrich(
    state: &AppState,
    pulls: &mut [Value],
    mut progress: impl FnMut(&[Value]),
) -> Result<()> {
    let mut queries = vec![];
    for (index, chunk) in pulls.chunks(20).enumerate() {
        let mut query = String::from("query {");
        for (i, p) in chunk.iter().enumerate() {
            github::prefix(p)?;
            let n = p["number"].as_u64().context("Invalid pull number")?;
            query.push_str(&format!(" p{i}: repository(owner:{},name:{}) {{ defaultBranchRef {{ name }} pullRequest(number:{n}) {{ isCrossRepository headRefOid baseRefName headRefName mergedAt closedAt reviewDecision mergeable additions deletions changedFiles commits(last:1) {{ nodes {{ commit {{ statusCheckRollup {{ state }} }} }} }} reviewRequests(first:100) {{ nodes {{ requestedReviewer {{ ... on User {{ login }} ... on Team {{ combinedSlug }} }} }} }} }} }}",serde_json::to_string(s(p,"owner"))?,serde_json::to_string(s(p,"repo"))?));
        }
        query.push('}');
        queries.push((index * 20, query));
    }
    let mut responses = stream::iter(queries)
        .map(|(start, query)| async move {
            let response = github_request(
                state,
                reqwest::Method::POST,
                "/graphql",
                Some(json!({"query":query})),
            )
            .await;
            (start, response)
        })
        .buffer_unordered(INBOX_REQUESTS);
    while let Some((start, response)) = responses.next().await {
        let Ok(response) = response else {
            continue;
        };
        let end = (start + 20).min(pulls.len());
        let chunk = &mut pulls[start..end];
        // Enrichment is optional. Omit unavailable fields without inventing a successful check or review.
        for (i, p) in chunk.iter_mut().enumerate() {
            let d = &response["data"][format!("p{i}")]["pullRequest"];
            if !d.is_object() {
                continue;
            }
            p["_crossRepository"] = d["isCrossRepository"].clone();
            p["_defaultBranch"] =
                response["data"][format!("p{i}")]["defaultBranchRef"]["name"].clone();
            for (to, from) in [
                ("headSha", "headRefOid"),
                ("baseRef", "baseRefName"),
                ("headRef", "headRefName"),
                ("additions", "additions"),
                ("deletions", "deletions"),
                ("changedFiles", "changedFiles"),
            ] {
                if !d[from].is_null() {
                    p[to] = d[from].clone()
                }
            }
            if let Some(v) = d["reviewDecision"].as_str() {
                p["reviewDecision"] = json!(v.to_lowercase().replace('_', "-"))
            }
            if let Some(v) = d["mergeable"].as_str() {
                p["mergeable"] = json!(v.to_lowercase())
            }
            let checks = d["commits"]["nodes"][0]["commit"]["statusCheckRollup"]["state"].as_str();
            p["checks"] = json!(checks.and_then(check_status).unwrap_or("none"));
            if let Some(nodes) = d["reviewRequests"]["nodes"].as_array() {
                p["reviewRequests"] = json!(nodes
                    .iter()
                    .filter_map(|n| n["requestedReviewer"]["login"]
                        .as_str()
                        .or_else(|| n["requestedReviewer"]["combinedSlug"].as_str()))
                    .collect::<Vec<_>>())
            }
            if p["state"] == "closed" {
                p["merged"] = json!(d["mergedAt"].is_string());
                p["closedAt"] = if d["mergedAt"].is_string() {
                    d["mergedAt"].clone()
                } else {
                    d["closedAt"].clone()
                }
            }
        }
        progress(pulls);
    }
    Ok(())
}

async fn native_stacks(state: &AppState, pulls: &mut [Value]) -> Result<()> {
    use std::sync::atomic::{AtomicU64, Ordering};
    static UNSUPPORTED_UNTIL: AtomicU64 = AtomicU64::new(0);
    if now() < UNSUPPORTED_UNTIL.load(Ordering::Relaxed) {
        return Ok(());
    }
    let mut queries = vec![];
    for (index, chunk) in pulls.chunks(20).enumerate() {
        let mut query = String::from("query {");
        for (i, p) in chunk.iter().enumerate() {
            github::prefix(p)?;
            let n = p["number"].as_u64().context("Invalid pull number")?;
            query.push_str(&format!(" p{i}: repository(owner:{},name:{}) {{ pullRequest(number:{n}) {{ stack {{ number size }} stackEntry {{ position }} }} }}",serde_json::to_string(s(p,"owner"))?,serde_json::to_string(s(p,"repo"))?));
        }
        query.push('}');
        queries.push((index * 20, query));
    }
    let mut responses = stream::iter(queries)
        .map(|(start, query)| async move {
            let response = github_request(
                state,
                reqwest::Method::POST,
                "/graphql",
                Some(json!({"query":query})),
            )
            .await;
            (start, response)
        })
        .buffer_unordered(INBOX_REQUESTS);
    while let Some((start, response)) = responses.next().await {
        let response = response?;
        if response["errors"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|e| s(e, "message").contains("doesn't exist on type"))
        {
            UNSUPPORTED_UNTIL.store(now() + 3_600_000, Ordering::Relaxed);
            return Ok(());
        }
        let end = (start + 20).min(pulls.len());
        let chunk = &mut pulls[start..end];
        for (i, p) in chunk.iter_mut().enumerate() {
            let d = &response["data"][format!("p{i}")]["pullRequest"];
            if let (Some(number), Some(size), Some(position)) = (
                d["stack"]["number"].as_u64(),
                d["stack"]["size"].as_u64(),
                d["stackEntry"]["position"].as_u64(),
            ) {
                if size > 1 {
                    p["stack"] = json!({"id":format!("native:{}/{}:{number}",s(p,"owner"),s(p,"repo")),"size":size,"position":position,"native":true});
                }
            }
        }
    }
    Ok(())
}
fn apply_stacks(pulls: &mut [Value]) {
    let mut below = HashMap::new();
    for (i, pull) in pulls.iter().enumerate() {
        let base = s(pull, "baseRef");
        if base.is_empty() || base == s(pull, "_defaultBranch") {
            continue;
        }
        if let Some((j, _)) = pulls
            .iter()
            .enumerate()
            .filter(|(j, p)| {
                *j != i
                    && p["_crossRepository"] != true
                    && !s(p, "headRef").is_empty()
                    && s(p, "headRef") == base
                    && p["owner"] == pull["owner"]
                    && p["repo"] == pull["repo"]
            })
            .min_by_key(|(_, p)| p["number"].as_u64())
        {
            below.insert(i, j);
        }
    }
    let mut placements = vec![];
    let mut sizes = HashMap::new();
    for i in 0..pulls.len() {
        let mut seen = HashSet::from([i]);
        let mut bottom = i;
        let mut cycle = false;
        while let Some(parent) = below.get(&bottom).copied() {
            if !seen.insert(parent) {
                cycle = true;
                break;
            }
            bottom = parent;
        }
        if !cycle {
            placements.push((i, bottom, seen.len()));
            *sizes.entry(bottom).or_insert(0usize) += 1;
        }
    }
    for (i, bottom, position) in placements {
        let size = sizes[&bottom];
        if size <= 1 || pulls[i]["stack"].is_object() {
            continue;
        }
        let lower = &pulls[bottom];
        pulls[i]["stack"] = json!({"id":format!("inferred:{}/{}:{}",s(lower,"owner"),s(lower,"repo"),s(lower,"headRef")),"position":position,"size":size,"native":false});
        if let Some(below) = below.get(&i) {
            pulls[i]["stack"]["below"] = pulls[*below]["number"].clone();
        }
    }
    for i in 0..pulls.len() {
        if pulls[i]["stack"]["native"] != true {
            continue;
        }
        let lower = pulls
            .iter()
            .enumerate()
            .filter(|(j, p)| *j != i && p["stack"]["id"] == pulls[i]["stack"]["id"])
            .find(|(_, p)| p["_crossRepository"] != true && p["headRef"] == pulls[i]["baseRef"])
            .or_else(|| {
                pulls.iter().enumerate().find(|(j, p)| {
                    *j != i
                        && p["stack"]["id"] == pulls[i]["stack"]["id"]
                        && p["stack"]["position"]
                            .as_u64()
                            .zip(pulls[i]["stack"]["position"].as_u64())
                            .is_some_and(|(a, b)| a + 1 == b)
                })
            })
            .map(|(_, p)| p["number"].clone());
        if let Some(lower) = lower {
            pulls[i]["stack"]["below"] = lower;
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    struct RepoFixture(Arc<AppState>);
    impl RepoFixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!("pulls-repo-{}", uuid::Uuid::new_v4()));
            Self(AppState::new(root.clone(), root).unwrap())
        }
    }
    impl Drop for RepoFixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(self.0.host.storage.root());
        }
    }
    #[derive(Clone)]
    struct RepoFetch {
        calls: Arc<AtomicUsize>,
        release: Arc<tokio::sync::Semaphore>,
        fail: bool,
    }
    impl RepoFetch {
        fn new(fail: bool) -> Self {
            Self {
                calls: Arc::new(AtomicUsize::new(0)),
                release: Arc::new(tokio::sync::Semaphore::new(0)),
                fail,
            }
        }
        async fn fetch(self, _state: Arc<AppState>, args: Value) -> Result<Value> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.release.acquire().await.unwrap().forget();
            if self.fail {
                return Err(
                    anyhow::anyhow!("fake GitHub failure").context("Failed to refresh repository")
                );
            }
            Ok(
                json!({"totalCount":1,"items":[{"number":2}],"next":"cursor-2","requestedNext":args["next"]}),
            )
        }
    }
    #[tokio::test]
    async fn inbox_refresh_shares_failure_without_queued_retries() {
        let fixture = RepoFixture::new();
        let state = &fixture.0;
        let args = json!({"state":"open"});
        let cached = json!({"pulls":[],"fetchedAt":0});
        state.host.storage.set("my-pulls:open", &cached).unwrap();
        let fake = RepoFetch::new(true);
        let mut receivers = Vec::new();
        for _ in 0..16 {
            let fetch = fake.clone();
            receivers.push(
                start_inbox_refresh_with(state, &args, move |state, args| fetch.fetch(state, args))
                    .unwrap(),
            );
        }
        crate::rt::yield_now().await;
        assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
        fake.release.add_permits(1);
        for receiver in receivers {
            assert!(repo_refresh_result(receiver).await.is_err());
        }
        assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
        assert_eq!(
            state.host.storage.get("my-pulls:open").unwrap(),
            Some(cached)
        );
        assert!(!inbox_refreshing(state, &args).await.unwrap());
    }
    async fn shared_repo_refresh(fail: bool) {
        let fixture = RepoFixture::new();
        let state = &fixture.0;
        let args = json!({"owner":"O","repo":"R"});
        let fresh_args = json!({"owner":"o","repo":"r","fresh":true});
        let cached = json!({"items":[{"number":1}],"next":"old-cursor","cachedAt":0});
        state.host.storage.set(&repo_key(&args), &cached).unwrap();
        let fake = RepoFetch::new(fail);
        let fetch = fake.clone();
        assert_eq!(
            repo_pulls_with(state, &args, move |state, args| fetch.fetch(state, args))
                .await
                .unwrap(),
            cached
        );
        crate::rt::yield_now().await;
        assert_eq!(fake.calls.load(Ordering::SeqCst), 1);

        // All stale reads return immediately while GitHub is blocked; none enqueue work.
        let stale = futures_util::future::join_all((0..16).map(|_| {
            let fetch = fake.clone();
            repo_pulls_with(state, &args, move |state, args| fetch.fetch(state, args))
        }))
        .await;
        assert!(stale.into_iter().all(|value| value.unwrap() == cached));
        // Registration now uses the blocking pool. Await each registration before
        // releasing GitHub so this checks overlapping waiters, not OS scheduling.
        let mut fresh = Vec::new();
        for _ in 0..8 {
            let fetch = fake.clone();
            let refresh = start_repo_refresh(state, &fresh_args, true, move |state, args| {
                fetch.fetch(state, args)
            })
            .await
            .unwrap();
            fresh.push(Box::pin(repo_refresh_result(refresh)));
        }
        for waiting in &mut fresh {
            assert!(futures_util::poll!(waiting.as_mut()).is_pending());
        }
        assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
        // Allow even an accidental backlog to run so the request count catches it.
        fake.release.add_permits(64);
        let results = futures_util::future::join_all(fresh).await;
        let mut shared_error = None;
        for result in results {
            if fail {
                let error = result.unwrap_err();
                assert_eq!(
                    format!("{error:#}"),
                    "Failed to refresh repository: fake GitHub failure"
                );
                let shared = &error.downcast_ref::<RepoRefreshError>().unwrap().0;
                if let Some(previous) = &shared_error {
                    assert!(Arc::ptr_eq(previous, shared));
                }
                shared_error = Some(shared.clone());
            } else {
                let value = result.unwrap();
                assert_eq!(value["items"][0]["number"], 2);
                assert_eq!(value["next"], "cursor-2");
                assert!(value["cachedAt"].as_u64().unwrap() > 0);
                assert_eq!(
                    state.host.storage.get(&repo_key(&args)).unwrap(),
                    Some(value)
                );
            }
        }
        for _ in 0..16 {
            crate::rt::yield_now().await;
        }
        assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
        assert!(!repo_refreshes()
            .lock()
            .unwrap()
            .contains_key(&(state.host.storage.root().to_path_buf(), repo_key(&args))));
        if fail {
            assert_eq!(
                state.host.storage.get(&repo_key(&args)).unwrap(),
                Some(cached)
            );
        }
        // A subsequent explicit refresh can retry, rather than retaining a failed flight.
        let mut fetch = fake.clone();
        fetch.fail = false;
        let refreshed = repo_pulls_with(state, &fresh_args, move |state, args| {
            fetch.fetch(state, args)
        })
        .await
        .unwrap();
        assert_eq!(refreshed["items"][0]["number"], 2);
        assert_eq!(fake.calls.load(Ordering::SeqCst), 2);
    }
    #[tokio::test]
    async fn repository_refresh_coalesces_slow_success_and_failure() {
        crate::rt::timeout(Duration::from_secs(2), async {
            shared_repo_refresh(false).await;
            shared_repo_refresh(true).await;
        })
        .await
        .expect("repository refresh callers must not hang");
    }
    #[tokio::test]
    async fn repository_continuations_do_not_replace_first_page() {
        let fixture = RepoFixture::new();
        let state = &fixture.0;
        let first = json!({"owner":"o","repo":"r"});
        let cached = json!({"items":[{"number":1}],"next":"cursor-1","cachedAt":now()});
        state.host.storage.set(&repo_key(&first), &cached).unwrap();
        let fake = RepoFetch::new(false);
        fake.release.add_permits(1);
        let fetch = fake.clone();
        let page = repo_pulls_with(
            state,
            &json!({"owner":"O","repo":"R","next":"cursor-1","fresh":true}),
            move |state, args| fetch.fetch(state, args),
        )
        .await
        .unwrap();
        assert_eq!(page["requestedNext"], "cursor-1");
        assert!(page["cachedAt"].is_null());
        assert_eq!(
            state.host.storage.get(&repo_key(&first)).unwrap(),
            Some(cached)
        );
        assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
    }
    #[tokio::test]
    async fn offline_inbox_read_does_not_start_repository_refreshes() {
        let fixture = RepoFixture::new();
        let state = &fixture.0;
        let cached = json!({"pulls":[{"owner":"o","repo":"r","number":1}],"fetchedAt":0});
        state.host.storage.set("my-pulls:open", &cached).unwrap();
        let value = crate::source::handle(state, "my-pulls-cached", json!({"revalidate":false}))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(value["pulls"], cached["pulls"]);
        assert_eq!(value["refreshing"], false);
        crate::rt::yield_now().await;
        assert!(!repo_refreshes()
            .lock()
            .unwrap()
            .keys()
            .any(|(root, _)| root == state.host.storage.root()));
    }

    #[tokio::test]
    async fn first_inbox_load_reads_the_rows_found_so_far() {
        let fixture = RepoFixture::new();
        let state = &fixture.0;
        let args = json!({"state":"open","revalidate":false});
        let read = || async {
            crate::source::handle(state, "my-pulls-cached", args.clone())
                .await
                .unwrap()
                .unwrap()
        };
        assert_eq!(read().await, Value::Null);
        let progress = InboxProgress((
            state.host.storage.root().to_path_buf(),
            "my-pulls:open".into(),
        ));
        progress.publish(
            "open",
            &[
                json!({"owner":"o","repo":"r","number":1,"updatedAt":"2026-10-25T00:00:00Z","_defaultBranch":"main"}),
                json!({"owner":"o","repo":"r","number":2,"updatedAt":"2026-10-26T00:00:00Z"}),
            ],
        );
        let partial = read().await;
        assert_eq!(partial["partial"], true);
        assert_eq!(partial["refreshing"], true);
        assert_eq!(partial["pulls"][0]["number"], 2);
        assert!(partial["pulls"][1].get("_defaultBranch").is_none());
        // A partial list is never the server's copy, and it is gone once the refresh ends.
        assert!(state.host.storage.get("my-pulls:open").unwrap().is_none());
        drop(progress);
        assert_eq!(read().await, Value::Null);
    }

    fn pull(number: u64, base: &str, head: &str) -> Value {
        json!({"owner":"o","repo":"r","number":number,"baseRef":base,"headRef":head,"_defaultBranch":"main"})
    }
    #[test]
    fn inferred_stacks_reject_forks_cycles_and_default_branch_parents() {
        let mut pulls = vec![
            pull(1, "main", "a"),
            pull(2, "a", "b"),
            pull(3, "b", "c"),
            pull(4, "other", "main"),
        ];
        apply_stacks(&mut pulls);
        assert_eq!(pulls[0]["stack"]["size"], 3);
        assert_eq!(pulls[2]["stack"]["position"], 3);
        assert_eq!(pulls[2]["stack"]["below"], 2);
        assert!(pulls[3]["stack"].is_null());
        let mut cycle = vec![pull(1, "b", "a"), pull(2, "a", "b")];
        apply_stacks(&mut cycle);
        assert!(cycle[0]["stack"].is_null());
        let mut forks = vec![pull(1, "main", "a"), pull(2, "a", "b")];
        forks[0]["_crossRepository"] = json!(true);
        apply_stacks(&mut forks);
        assert!(forks[1]["stack"].is_null());
    }
    #[test]
    fn native_stack_wins_and_links_adjacent_layers() {
        let mut pulls = vec![pull(1, "main", "a"), pull(2, "a", "b")];
        for (i, p) in pulls.iter_mut().enumerate() {
            p["stack"] = json!({"id":"native:o/r:8","size":2,"position":i+1,"native":true});
        }
        apply_stacks(&mut pulls);
        assert_eq!(pulls[1]["stack"]["id"], "native:o/r:8");
        assert_eq!(pulls[1]["stack"]["below"], 1);
    }
}

#[cfg(test)]
mod page_tests;
