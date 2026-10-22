use super::{
    wire::{InboxPull, ReadRepository, Relation},
    Code, Context, ReadError, ReadScope, Result,
};
use crate::{
    public_api::github::{
        pull, repository, repository_metadata, valid_segment, PublicPull, RepositoryFence,
        RepositoryLease,
    },
    AppState,
};
use futures_util::{stream, StreamExt, TryStreamExt};
use serde_json::Value;
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

const MAX_BODY: usize = 2 * 1024 * 1024;
struct Budget {
    start: Instant,
    requests: u32,
}
pub(super) struct PersonalGitHub {
    state: Arc<AppState>,
    http: reqwest::Client,
    origin: url::Url,
    permits: Arc<tokio::sync::Semaphore>,
    budget: Mutex<Budget>,
    repositories: Mutex<RepositoryFence>,
}
struct RepositoryRead {
    repository: ReadRepository,
    scope: String,
    lease: RepositoryLease,
}
impl PersonalGitHub {
    pub(super) fn new(state: Arc<AppState>, http: reqwest::Client, origin: url::Url) -> Self {
        Self {
            state,
            http,
            origin,
            permits: Arc::new(tokio::sync::Semaphore::new(3)),
            budget: Mutex::new(Budget {
                start: Instant::now(),
                requests: 0,
            }),
            repositories: Mutex::new(RepositoryFence::default()),
        }
    }
    pub(super) fn current(&self) -> bool {
        self.state.identity.ensure_current().is_ok()
    }
    fn check_repository(&self, scope: &str, lease: RepositoryLease) -> Result<()> {
        if self
            .repositories
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .current(scope, lease)
        {
            Ok(())
        } else {
            Err(ReadError::new(Code::Forbidden))
        }
    }
    fn retire_repository(&self, read: &RepositoryRead) {
        self.repositories
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .retire(&read.scope, read.lease);
    }
    async fn current_login(&self, context: &Context) -> Result<String> {
        let expected = context
            .session
            .status()
            .map_err(|_| ReadError::new(Code::Unauthorized))?
            .github_id;
        let user = self.get(context, &["user"], &[]).await?;
        if user["id"].as_u64() != Some(expected) {
            return Err(ReadError::new(Code::Reconnect));
        }
        user["login"]
            .as_str()
            .filter(|login| valid_segment(login, 39))
            .map(str::to_owned)
            .ok_or_else(|| ReadError::new(Code::InvalidResponse))
    }
    async fn get(
        &self,
        context: &Context,
        segments: &[&str],
        query: &[(&str, String)],
    ) -> Result<Value> {
        context.current()?;
        let _permit = self
            .permits
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| ReadError::new(Code::Unavailable))?;
        context.current()?;
        {
            let mut budget = self
                .budget
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if budget.start.elapsed() >= Duration::from_secs(3600) {
                budget.start = Instant::now();
                budget.requests = 0;
            }
            if budget.requests >= 3600 {
                return Err(ReadError::rate(
                    3600u64
                        .saturating_sub(budget.start.elapsed().as_secs())
                        .max(1),
                ));
            }
            budget.requests += 1;
        }
        let mut url = self.origin.clone();
        url.path_segments_mut()
            .map_err(|_| ReadError::new(Code::InvalidRequest))?
            .clear()
            .extend(segments);
        url.query_pairs_mut()
            .extend_pairs(query.iter().map(|(name, value)| (*name, value.as_str())));
        let token = self
            .state
            .identity
            .github_token(|| async { None })
            .await
            .map_err(|_| ReadError::new(Code::Unauthorized))?
            .ok_or_else(|| ReadError::new(Code::Unauthorized))?;
        context.current()?;
        let response = self
            .http
            .get(url)
            .header("accept", "application/vnd.github+json")
            .header("x-github-api-version", "2022-11-28")
            .header("user-agent", "Pully/personal-read")
            .bearer_auth(&token)
            .send()
            .await
            .map_err(|_| ReadError::new(Code::Unavailable))?;
        context.current()?;
        let status = response.status().as_u16();
        if status == 429
            || (status == 403
                && response
                    .headers()
                    .get("x-ratelimit-remaining")
                    .is_some_and(|value| value.as_bytes() == b"0"))
        {
            let seconds = response
                .headers()
                .get("retry-after")
                .and_then(|value| value.to_str().ok())
                .and_then(|value| value.parse().ok())
                .unwrap_or(60);
            return Err(ReadError::rate(seconds));
        }
        if status == 401 {
            return Err(ReadError::new(Code::Reconnect));
        }
        if matches!(status, 403 | 404) {
            return Err(ReadError::new(Code::Forbidden));
        }
        if status != 200 {
            return Err(ReadError::new(Code::Unavailable));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_BODY as u64)
        {
            return Err(ReadError::new(Code::InvalidResponse));
        }
        let mut bytes = Vec::new();
        let mut body = response.bytes_stream();
        while let Some(chunk) = body.next().await {
            let chunk = chunk.map_err(|_| ReadError::new(Code::Unavailable))?;
            if chunk.len() > MAX_BODY.saturating_sub(bytes.len()) {
                return Err(ReadError::new(Code::InvalidResponse));
            }
            bytes.extend_from_slice(&chunk);
            context.current()?;
        }
        let body: Value =
            serde_json::from_slice(&bytes).map_err(|_| ReadError::new(Code::InvalidResponse))?;
        if contains_secret(&body, &token) {
            return Err(ReadError::new(Code::InvalidResponse));
        }
        context.current()?;
        Ok(body)
    }
    pub(super) async fn repositories(
        &self,
        context: &Context,
        page: u32,
    ) -> Result<Vec<ReadRepository>> {
        let body = self
            .get(
                context,
                &["user", "repos"],
                &[
                    (
                        "visibility",
                        if context.scope == ReadScope::Account {
                            "all"
                        } else {
                            "public"
                        }
                        .into(),
                    ),
                    (
                        "affiliation",
                        "owner,collaborator,organization_member".into(),
                    ),
                    ("sort", "updated".into()),
                    ("direction", "desc".into()),
                    ("per_page", "20".into()),
                    ("page", page.to_string()),
                ],
            )
            .await?;
        let values = page_items(&body)?;
        let values = values
            .iter()
            .map(|value| parse_repository(context, value))
            .collect::<Result<_>>()?;
        context.current()?;
        Ok(values)
    }
    async fn accessible_repository(
        &self,
        context: &Context,
        owner: &str,
        name: &str,
    ) -> Result<RepositoryRead> {
        if !valid_segment(owner, 39) || !valid_segment(name, 100) {
            return Err(ReadError::new(Code::InvalidRequest));
        }
        let scope = format!(
            "{}:{}/{}",
            if context.scope == ReadScope::Public {
                "public"
            } else {
                "account"
            },
            owner.to_ascii_lowercase(),
            name.to_ascii_lowercase()
        );
        let opening = self
            .repositories
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .open(&scope)
            .ok_or_else(|| ReadError::new(Code::Unavailable))?;
        let repository = self
            .get(context, &["repos", owner, name], &[])
            .await
            .and_then(|body| {
                parse_repository(context, &body).map_err(|_| ReadError::new(Code::Forbidden))
            });
        let repository = match repository {
            Ok(repository)
                if repository.owner.eq_ignore_ascii_case(owner)
                    && repository.name.eq_ignore_ascii_case(name) =>
            {
                repository
            }
            denied => {
                self.repositories
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .deny(&scope, opening);
                return Err(denied
                    .err()
                    .unwrap_or_else(|| ReadError::new(Code::Forbidden)));
            }
        };
        let (lease, _) = self
            .repositories
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .confirm(&scope, opening, repository.id)
            .ok_or_else(|| ReadError::new(Code::Forbidden))?;
        Ok(RepositoryRead {
            repository,
            scope,
            lease,
        })
    }
    pub(super) async fn pulls(
        &self,
        context: &Context,
        owner: &str,
        name: &str,
        page: u32,
    ) -> Result<(ReadRepository, Vec<PublicPull>)> {
        let initial = self.accessible_repository(context, owner, name).await?;
        let body = self
            .get(
                context,
                &["repos", owner, name, "pulls"],
                &[
                    ("state", "open".into()),
                    ("sort", "updated".into()),
                    ("direction", "desc".into()),
                    ("per_page", "20".into()),
                    ("page", page.to_string()),
                ],
            )
            .await?;
        let values = page_items(&body)?
            .iter()
            .map(|value| {
                // A PR explicitly reports its base repository; do not relabel an old response after name reuse.
                if value["base"]["repo"]["id"].as_u64() != Some(initial.repository.id) {
                    self.retire_repository(&initial);
                    return Err(ReadError::new(Code::Forbidden));
                }
                pull(value, &initial.repository).map_err(|_| ReadError::new(Code::InvalidResponse))
            })
            .collect::<Result<_>>()?;
        self.check_repository(&initial.scope, initial.lease)?;
        let confirmed = self.accessible_repository(context, owner, name).await?;
        if confirmed.repository.id != initial.repository.id {
            return Err(ReadError::new(Code::Forbidden));
        }
        self.check_repository(&initial.scope, initial.lease)?;
        self.check_repository(&confirmed.scope, confirmed.lease)?;
        context.current()?;
        Ok((confirmed.repository, values))
    }
    pub(super) async fn inbox(
        &self,
        context: &Context,
        relation: Relation,
        page: u32,
    ) -> Result<(Vec<InboxPull>, usize, bool)> {
        let login = self.current_login(context).await?;
        let selector = match relation {
            Relation::ReviewRequested => "review-requested",
            Relation::Authored => "author",
            Relation::Involved => "involves",
        };
        let body = self
            .get(
                context,
                &["search", "issues"],
                &[
                    (
                        "q",
                        format!(
                            "is:pr is:open {}{selector}:{login}",
                            if context.scope == ReadScope::Public {
                                "is:public "
                            } else {
                                ""
                            }
                        ),
                    ),
                    ("sort", "updated".into()),
                    ("order", "desc".into()),
                    ("per_page", "20".into()),
                    ("page", page.to_string()),
                ],
            )
            .await?;
        let incomplete = body["incomplete_results"]
            .as_bool()
            .ok_or_else(|| ReadError::new(Code::InvalidResponse))?;
        let items = page_items(&body["items"])?;
        let count = items.len();
        let reads = stream::iter(items.to_vec().into_iter().map(|item| async move {
            let url = item["repository_url"]
                .as_str()
                .and_then(|raw| url::Url::parse(raw).ok())
                .ok_or_else(|| ReadError::new(Code::InvalidResponse))?;
            if url.scheme() != "https"
                || url.host_str() != Some("api.github.com")
                || url.port().is_some()
                || !url.username().is_empty()
                || url.password().is_some()
                || url.query().is_some()
                || url.fragment().is_some()
            {
                return Err(ReadError::new(Code::InvalidResponse));
            }
            let parts = url
                .path_segments()
                .map(|parts| parts.collect::<Vec<_>>())
                .unwrap_or_default();
            let ["repos", owner, name] = parts.as_slice() else {
                return Err(ReadError::new(Code::InvalidResponse));
            };
            let repository = self.accessible_repository(context, owner, name).await?;
            let number = item["number"]
                .as_u64()
                .filter(|number| *number > 0)
                .ok_or_else(|| ReadError::new(Code::InvalidResponse))?;
            let issue_id = item["id"]
                .as_u64()
                .filter(|id| *id > 0)
                .ok_or_else(|| ReadError::new(Code::InvalidResponse))?;
            if !item["pull_request"].is_object() {
                return Err(ReadError::new(Code::InvalidResponse));
            }
            let mut fresh = self
                .get(
                    context,
                    &["repos", owner, name, "issues", &number.to_string()],
                    &[],
                )
                .await?;
            if fresh["id"].as_u64() != Some(issue_id)
                || fresh["number"].as_u64() != Some(number)
                || !fresh["pull_request"].is_object()
            {
                return Err(ReadError::new(Code::Forbidden));
            }
            if fresh["state"] == "closed" {
                return Ok(None);
            }
            // Search draft is optional and may lag a ready-for-review transition.
            if fresh["draft"].as_bool().is_none() {
                let details = self
                    .get(
                        context,
                        &["repos", owner, name, "pulls", &number.to_string()],
                        &[],
                    )
                    .await?;
                if details["number"].as_u64() != Some(number)
                    || details["base"]["repo"]["id"].as_u64() != Some(repository.repository.id)
                {
                    self.retire_repository(&repository);
                    return Err(ReadError::new(Code::Forbidden));
                }
                if details["state"] == "closed" {
                    return Ok(None);
                }
                fresh = details;
            }
            let parsed = pull(&fresh, &repository.repository)
                .map_err(|_| ReadError::new(Code::InvalidResponse))?;
            self.check_repository(&repository.scope, repository.lease)?;
            Ok(Some((
                InboxPull {
                    repository: repository.repository,
                    pull: parsed,
                },
                repository.scope,
                repository.lease,
            )))
        }))
        .buffered(3)
        .try_collect::<Vec<_>>()
        .await?;
        let changed = reads.iter().any(Option::is_none);
        let mut pulls = reads.into_iter().flatten().collect::<Vec<_>>();
        let mut repositories = HashMap::new();
        for (item, _, _) in &pulls {
            if repositories
                .get(&item.repository.full_name)
                .is_some_and(|previous: &ReadRepository| previous.id != item.repository.id)
            {
                return Err(ReadError::new(Code::Forbidden));
            }
            repositories.insert(item.repository.full_name.clone(), item.repository.clone());
        }
        let confirmed = stream::iter(repositories.into_values().map(|initial| async move {
            let confirmed = self
                .accessible_repository(context, &initial.owner, &initial.name)
                .await?;
            if confirmed.repository.id != initial.id {
                return Err(ReadError::new(Code::Forbidden));
            }
            Ok((initial.full_name.clone(), confirmed))
        }))
        .buffer_unordered(3)
        .try_collect::<HashMap<_, _>>()
        .await?;
        if self.current_login(context).await? != login {
            return Err(ReadError::new(Code::Forbidden));
        }
        for (item, scope, lease) in &mut pulls {
            self.check_repository(scope, *lease)?;
            let current = confirmed
                .get(&item.repository.full_name)
                .ok_or_else(|| ReadError::new(Code::Forbidden))?;
            self.check_repository(&current.scope, current.lease)?;
            item.repository = current.repository.clone();
        }
        context.current()?;
        Ok((
            pulls.into_iter().map(|(pull, _, _)| pull).collect(),
            count,
            incomplete || changed,
        ))
    }
}
fn parse_repository(context: &Context, value: &Value) -> Result<ReadRepository> {
    context.current()?;
    let private = value["private"]
        .as_bool()
        .ok_or_else(|| ReadError::new(Code::InvalidResponse))?;
    let metadata = match context.scope {
        ReadScope::Public => repository(value),
        ReadScope::Account => repository_metadata(value),
    }
    .map_err(|_| ReadError::new(Code::InvalidResponse))?;
    Ok(ReadRepository {
        metadata,
        private: (context.scope == ReadScope::Account).then_some(private),
    })
}
fn page_items(body: &Value) -> Result<&Vec<Value>> {
    body.as_array()
        .filter(|items| items.len() <= 20)
        .ok_or_else(|| ReadError::new(Code::InvalidResponse))
}
fn contains_secret(value: &Value, secret: &str) -> bool {
    match value {
        Value::String(value) => value.contains(secret),
        Value::Array(values) => values.iter().any(|value| contains_secret(value, secret)),
        Value::Object(values) => values
            .iter()
            .any(|(key, value)| key.contains(secret) || contains_secret(value, secret)),
        _ => false,
    }
}
