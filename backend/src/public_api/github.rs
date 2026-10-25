use super::{ErrorCode, PublicError, Result};
use futures_util::StreamExt;
use serde::Serialize;
use serde_json::Value;
use std::{collections::HashMap, sync::Mutex, time::Duration};
use web_time::{Instant, SystemTime, UNIX_EPOCH};

const PER_PAGE: u32 = 20;
const LAST_PAGE: u32 = 10;
const BODY_LIMIT: usize = 2 * 1024 * 1024;
const CACHE_LIMIT: usize = 64;
const HOURLY_REQUEST_LIMIT: u32 = 60;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PublicRepository {
    pub id: u64,
    pub owner: String,
    pub name: String,
    pub full_name: String,
    pub description: Option<String>,
    pub stars: u64,
    pub language: Option<String>,
    pub url: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct RepositoryPage {
    pub repositories: Vec<PublicRepository>,
    pub next_page: Option<u32>,
    pub incomplete: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PublicPull {
    pub number: u64,
    pub title: String,
    pub url: String,
    pub author: Option<String>,
    pub draft: bool,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PullPage {
    pub repository: PublicRepository,
    pub pulls: Vec<PublicPull>,
    pub next_page: Option<u32>,
}

#[derive(Clone)]
enum CacheValue {
    Repositories(RepositoryPage),
    Pulls(PullPage),
}
struct Cached {
    value: CacheValue,
    until: Instant,
    read: Instant,
}
struct Budget {
    start: Instant,
    requests: u32,
    blocked_until: Option<Instant>,
}

pub(crate) use super::fence::{IdentityFence, IdentityLease};
pub(crate) type RepositoryFence = IdentityFence<u64>;
pub(crate) type RepositoryLease = IdentityLease<u64>;
type RepositoryProbe = super::fence::IdentityProbe;

struct PublicCache {
    entries: HashMap<String, Cached>,
    fence: RepositoryFence,
}
impl Default for PublicCache {
    fn default() -> Self {
        Self {
            entries: Default::default(),
            fence: Default::default(),
        }
    }
}

pub(super) struct PublicGitHub {
    http: reqwest::Client,
    origin: url::Url,
    cache: Mutex<PublicCache>,
    budget: Mutex<Budget>,
    concurrent: tokio::sync::Semaphore,
}

impl PublicGitHub {
    /// @cc [owner:OctavianTocan,label:security] anonymous-github-is-fixed-origin-and-tokenless
    /// Public browsing MUST use this dedicated tokenless, fixed HTTPS GitHub REST client
    /// with redirects refused, bounded bodies/time/concurrency/cache and request budget.
    /// It MUST NOT receive caller headers or fall back to environment, gh CLI, legacy or
    /// personal authority after a rate limit or failure. Diagnostics MUST be fixed safe
    /// errors without upstream bodies, URLs or credentials. Source: Tavi, 21 Oct 2026,
    /// public browsing without GitHub login and isolation-before-public-exposure decision.
    pub(super) fn new() -> anyhow::Result<Self> {
        let http = crate::net::client()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .user_agent("Pully/public-explore")
            .build()?;
        Ok(Self {
            http,
            origin: url::Url::parse("https://api.github.com/")?,
            cache: Default::default(),
            budget: Mutex::new(Budget {
                start: Instant::now(),
                requests: 0,
                blocked_until: None,
            }),
            concurrent: tokio::sync::Semaphore::new(3),
        })
    }

    #[cfg(test)]
    pub(super) fn fixture(origin: url::Url) -> Self {
        assert_eq!(origin.host_str(), Some("127.0.0.1"));
        assert_eq!(origin.scheme(), "http");
        let mut client = Self::new().unwrap();
        client.origin = origin;
        client.http = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(3))
            .build()
            .unwrap();
        client
    }

    pub(super) async fn repositories(&self, query: &str, page: u32) -> Result<RepositoryPage> {
        valid_page(page)?;
        let query = query.trim();
        if query.chars().count() > 80
            || !query
                .chars()
                .all(|c| c.is_alphanumeric() || " -_./".contains(c))
        {
            return Err(PublicError::new(ErrorCode::InvalidRequest));
        }
        let key = format!("search:{page}:{query}");
        if let Some(CacheValue::Repositories(value)) = self.cached(&key) {
            return Ok(value);
        }
        let term = format!(
            "{} is:public archived:false",
            if query.is_empty() {
                "stars:>100"
            } else {
                query
            }
        );
        let body = self
            .get(
                &["search", "repositories"],
                &[
                    ("q", term),
                    ("sort", "stars".into()),
                    ("order", "desc".into()),
                    ("per_page", PER_PAGE.to_string()),
                    ("page", page.to_string()),
                ],
            )
            .await?;
        let items = body["items"].as_array().ok_or_else(invalid)?;
        if items.len() > PER_PAGE as usize {
            return Err(invalid());
        }
        let incomplete = body["incomplete_results"].as_bool().ok_or_else(invalid)?;
        let value = RepositoryPage {
            repositories: items
                .iter()
                .filter(|repo| repo["private"] != true)
                .map(repository)
                .collect::<Result<_>>()?,
            next_page: next_page(items.len(), page),
            incomplete,
        };
        self.remember(
            key,
            CacheValue::Repositories(value.clone()),
            Duration::from_secs(300),
            None,
        )?;
        Ok(value)
    }

    /// @cc [owner:OctavianTocan,label:security] cached-public-prs-recheck-visibility
    /// Every public PR page, including a cached page, MUST first verify repository
    /// visibility with this tokenless client. A missing/private/invalid response MUST
    /// prevent cached PR disclosure and discard that repository's cached pages. Only
    /// typed public fields MAY enter this cache; no legacy or authenticated cache MAY
    /// be adopted. Source: Tavi's 21 Oct 2026 anonymous public repositories request.
    /// A confirmed visibility denial or immutable repository-ID replacement MUST retire
    /// older in-flight reads and their cache writes. Name reuse MUST NOT relabel the old
    /// repository's PRs; bounded ownership slots MUST reject older identity confirmations.
    /// Source: 21 Oct 2026 independent public-boundary review, concurrent denial/name reuse.
    /// Uncached PR items MUST match the captured base repository ID and pass a final
    /// tokenless visibility/ID confirmation before publication or caching. Source:
    /// confirmed 22 Oct executable-listener full-review finding 3.
    pub(super) async fn pulls(&self, owner: &str, name: &str, page: u32) -> Result<PullPage> {
        valid_page(page)?;
        if !valid_segment(owner, 39) || !valid_segment(name, 100) {
            return Err(PublicError::new(ErrorCode::InvalidRequest));
        }
        let scope = format!(
            "pulls:{}/{}:",
            owner.to_ascii_lowercase(),
            name.to_ascii_lowercase()
        );
        let opening = self.open_repository(&scope)?;
        let visibility = self
            .get(&["repos", owner, name], &[])
            .await
            .and_then(|v| repository(&v));
        let repo = match visibility {
            Ok(repo)
                if repo.owner.eq_ignore_ascii_case(owner)
                    && repo.name.eq_ignore_ascii_case(name) =>
            {
                repo
            }
            denied => {
                self.deny_repository(&scope, opening);
                return Err(match denied {
                    Err(error) if error.code != ErrorCode::InvalidResponse => error,
                    _ => PublicError::new(ErrorCode::RepositoryUnavailable),
                });
            }
        };
        let lease = self.confirm_repository(&scope, opening, repo.id)?;
        let key = format!("{scope}{page}");
        if let Some(CacheValue::Pulls(mut value)) = self.cached(&key) {
            if value.repository.id == repo.id {
                value.repository = repo;
                let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
                check_lease(&cache, &scope, lease)?;
                return Ok(value);
            }
            self.cache
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .entries
                .retain(|key, _| !key.starts_with(&scope));
        }
        let body = self
            .get(
                &["repos", owner, name, "pulls"],
                &[
                    ("state", "open".into()),
                    ("sort", "updated".into()),
                    ("direction", "desc".into()),
                    ("per_page", PER_PAGE.to_string()),
                    ("page", page.to_string()),
                ],
            )
            .await?;
        let items = body.as_array().ok_or_else(invalid)?;
        if items.len() > PER_PAGE as usize {
            return Err(invalid());
        }
        let pulls = items
            .iter()
            .map(|value| {
                if value["base"]["repo"]["id"].as_u64() != Some(repo.id) {
                    self.retire_repository(&scope, lease);
                    return Err(PublicError::new(ErrorCode::RepositoryUnavailable));
                }
                pull(value, &repo)
            })
            .collect::<Result<_>>()?;
        {
            let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
            check_lease(&cache, &scope, lease)?;
        }
        let closing = self.open_repository(&scope)?;
        let confirmed = self
            .get(&["repos", owner, name], &[])
            .await
            .and_then(|body| repository(&body));
        let confirmed = match confirmed {
            Ok(repo)
                if repo.owner.eq_ignore_ascii_case(owner)
                    && repo.name.eq_ignore_ascii_case(name) =>
            {
                repo
            }
            denied => {
                self.deny_repository(&scope, closing);
                return Err(match denied {
                    Err(error) if error.code != ErrorCode::InvalidResponse => error,
                    _ => PublicError::new(ErrorCode::RepositoryUnavailable),
                });
            }
        };
        self.confirm_repository(&scope, closing, confirmed.id)?;
        if confirmed.id != repo.id {
            self.retire_repository(&scope, lease);
            return Err(PublicError::new(ErrorCode::RepositoryUnavailable));
        }
        let value = PullPage {
            pulls,
            repository: confirmed,
            next_page: next_page(items.len(), page),
        };
        self.remember(
            key,
            CacheValue::Pulls(value.clone()),
            Duration::from_secs(60),
            Some((&scope, lease)),
        )?;
        Ok(value)
    }

    fn cached(&self, key: &str) -> Option<CacheValue> {
        let now = Instant::now();
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache.entries.retain(|_, entry| entry.until > now);
        cache.entries.get_mut(key).map(|entry| {
            entry.read = now;
            entry.value.clone()
        })
    }

    fn remember(
        &self,
        key: String,
        value: CacheValue,
        ttl: Duration,
        lease: Option<(&str, RepositoryLease)>,
    ) -> Result<()> {
        let now = Instant::now();
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((scope, lease)) = lease {
            check_lease(&cache, scope, lease)?;
        }
        cache.entries.retain(|_, entry| entry.until > now);
        if !cache.entries.contains_key(&key) && cache.entries.len() >= CACHE_LIMIT {
            if let Some(oldest) = cache
                .entries
                .iter()
                .min_by_key(|(_, value)| value.read)
                .map(|(key, _)| key.clone())
            {
                cache.entries.remove(&oldest);
            }
        }
        cache.entries.insert(
            key,
            Cached {
                value,
                until: now + ttl,
                read: now,
            },
        );
        Ok(())
    }

    fn open_repository(&self, scope: &str) -> Result<RepositoryProbe> {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache
            .fence
            .open(scope)
            .ok_or_else(|| PublicError::new(ErrorCode::Unavailable))
    }

    fn deny_repository(&self, scope: &str, probe: RepositoryProbe) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache.fence.deny(scope, probe);
        cache.entries.retain(|key, _| !key.starts_with(scope));
    }
    fn retire_repository(&self, scope: &str, lease: RepositoryLease) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache.fence.retire(scope, lease);
        cache.entries.retain(|key, _| !key.starts_with(scope));
    }

    fn confirm_repository(
        &self,
        scope: &str,
        opening: RepositoryProbe,
        id: u64,
    ) -> Result<RepositoryLease> {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let (lease, changed) = cache
            .fence
            .confirm(scope, opening, id)
            .ok_or_else(|| PublicError::new(ErrorCode::RepositoryUnavailable))?;
        if changed {
            cache.entries.retain(|key, _| !key.starts_with(scope));
        }
        Ok(lease)
    }

    fn take_budget(&self) -> Result<()> {
        let now = Instant::now();
        let mut budget = self.budget.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(until) = budget.blocked_until.filter(|until| *until > now) {
            return Err(PublicError::rate_limited(
                until.duration_since(now).as_secs().saturating_add(1),
            ));
        }
        if now.duration_since(budget.start) >= Duration::from_secs(3600) {
            budget.start = now;
            budget.requests = 0;
        }
        if budget.requests >= HOURLY_REQUEST_LIMIT {
            return Err(PublicError::rate_limited(
                3600 - now.duration_since(budget.start).as_secs(),
            ));
        }
        budget.requests += 1;
        Ok(())
    }

    async fn get(&self, path: &[&str], query: &[(&str, String)]) -> Result<Value> {
        let _permit = self
            .concurrent
            .try_acquire()
            .map_err(|_| PublicError::rate_limited(1))?;
        self.take_budget()?;
        let mut url = self.origin.clone();
        url.path_segments_mut()
            .map_err(|_| invalid())?
            .clear()
            .extend(path);
        let response = crate::net::send(
            self.http
                .get(url)
                .query(query)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28"),
        )
        .await
        .map_err(|_| PublicError::new(ErrorCode::Unavailable))?;
        let status = response.status();
        if status == reqwest::StatusCode::TOO_MANY_REQUESTS
            || status == reqwest::StatusCode::FORBIDDEN
                && (response
                    .headers()
                    .get("x-ratelimit-remaining")
                    .is_some_and(|v| v == "0")
                    || response.headers().contains_key("retry-after"))
        {
            let retry = response
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .or_else(|| {
                    response
                        .headers()
                        .get("x-ratelimit-reset")
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.parse::<u64>().ok())
                        .map(|reset| {
                            reset.saturating_sub(
                                SystemTime::now()
                                    .duration_since(UNIX_EPOCH)
                                    .unwrap_or_default()
                                    .as_secs(),
                            )
                        })
                })
                .unwrap_or(60)
                .clamp(1, 3600);
            self.budget
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .blocked_until = Some(Instant::now() + Duration::from_secs(retry));
            return Err(PublicError::rate_limited(retry));
        }
        if status == reqwest::StatusCode::NOT_FOUND {
            return Err(PublicError::new(ErrorCode::RepositoryUnavailable));
        }
        if status != reqwest::StatusCode::OK {
            return Err(PublicError::new(ErrorCode::Unavailable));
        }
        let mut bytes = Vec::new();
        let mut chunks = response.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(|_| PublicError::new(ErrorCode::Unavailable))?;
            if bytes.len().saturating_add(chunk.len()) > BODY_LIMIT {
                return Err(invalid());
            }
            bytes.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&bytes).map_err(|_| invalid())
    }
}

fn check_lease(cache: &PublicCache, scope: &str, lease: RepositoryLease) -> Result<()> {
    if !cache.fence.current(scope, lease) {
        return Err(PublicError::new(ErrorCode::RepositoryUnavailable));
    }
    Ok(())
}

fn invalid() -> PublicError {
    PublicError::new(ErrorCode::InvalidResponse)
}
fn valid_page(page: u32) -> Result<()> {
    if (1..=LAST_PAGE).contains(&page) {
        Ok(())
    } else {
        Err(PublicError::new(ErrorCode::InvalidRequest))
    }
}
fn next_page(count: usize, page: u32) -> Option<u32> {
    (count == PER_PAGE as usize && page < LAST_PAGE).then_some(page + 1)
}
pub(crate) fn valid_segment(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value != "."
        && value != ".."
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"-_.".contains(&c))
}
fn bounded_text(value: &Value, max: usize) -> Result<String> {
    let text = value.as_str().ok_or_else(invalid)?;
    if text
        .chars()
        .any(|c| c.is_control() && !matches!(c, '\n' | '\r' | '\t'))
    {
        return Err(invalid());
    }
    Ok(text.chars().take(max).collect())
}
fn optional_text(value: &Value, max: usize) -> Result<Option<String>> {
    if value.is_null() {
        Ok(None)
    } else {
        bounded_text(value, max).map(Some)
    }
}
pub(crate) fn repository(value: &Value) -> Result<PublicRepository> {
    if value["private"].as_bool() != Some(false) {
        return Err(invalid());
    }
    repository_metadata(value)
}

/// @cc [owner:OctavianTocan,label:security] repository-metadata-does-not-grant-visibility
/// Callers MUST separately enforce public visibility or a current explicit personal
/// repository-consent capability before publishing this validated metadata. This
/// parser MUST NOT turn a repository ID or a response body into authorization.
/// Source: Tavi's 22 Oct 2026 own-account private repository access clarification.
pub(crate) fn repository_metadata(value: &Value) -> Result<PublicRepository> {
    let owner = bounded_text(&value["owner"]["login"], 128)?;
    let name = bounded_text(&value["name"], 128)?;
    let id = value["id"]
        .as_u64()
        .filter(|v| *v > 0)
        .ok_or_else(invalid)?;
    if !valid_segment(&owner, 39)
        || !valid_segment(&name, 100)
        || value["full_name"].as_str() != Some(&format!("{owner}/{name}"))
    {
        return Err(invalid());
    }
    Ok(PublicRepository {
        id,
        full_name: format!("{owner}/{name}"),
        url: format!("https://github.com/{owner}/{name}"),
        owner,
        name,
        description: optional_text(&value["description"], 512)?,
        stars: value["stargazers_count"].as_u64().ok_or_else(invalid)?,
        language: optional_text(&value["language"], 80)?,
    })
}
pub(crate) fn pull(value: &Value, repo: &PublicRepository) -> Result<PublicPull> {
    let number = value["number"]
        .as_u64()
        .filter(|v| *v > 0)
        .ok_or_else(invalid)?;
    if value["state"] != "open" {
        return Err(invalid());
    }
    Ok(PublicPull {
        number,
        title: bounded_text(&value["title"], 512)?,
        url: format!("{}/pull/{number}", repo.url),
        author: optional_text(&value["user"]["login"], 128)?,
        draft: value["draft"].as_bool().ok_or_else(invalid)?,
        created_at: bounded_text(&value["created_at"], 64)?,
        updated_at: bounded_text(&value["updated_at"], 64)?,
    })
}

#[cfg(test)]
mod fence_tests;
