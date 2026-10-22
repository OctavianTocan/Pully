use crate::public_api::github::{PublicPull, PublicRepository};
use serde::{Deserialize, Serialize};

pub(super) const REPOSITORIES_PATH: &str = "/api/personal/v1/github/repositories";
pub(super) const REPOSITORY_PULLS_PATH: &str =
    "/api/personal/v1/github/repositories/{owner}/{repo}/pulls";
pub(super) const PULLS_PATH: &str = "/api/personal/v1/github/pulls";
pub(super) const ACCOUNT_REPOSITORIES_PATH: &str = "/api/personal/v1/github/account/repositories";
pub(super) const ACCOUNT_REPOSITORY_PULLS_PATH: &str =
    "/api/personal/v1/github/account/repositories/{owner}/{repo}/pulls";
pub(super) const ACCOUNT_PULLS_PATH: &str = "/api/personal/v1/github/account/pulls";
pub(super) const ACCOUNT_CAPABILITIES_PATH: &str = "/api/personal/v1/github/account/capabilities";

#[derive(Clone, Serialize)]
pub(super) struct ReadRepository {
    #[serde(flatten)]
    pub metadata: PublicRepository,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub private: Option<bool>,
}
impl std::ops::Deref for ReadRepository {
    type Target = PublicRepository;
    fn deref(&self) -> &Self::Target {
        &self.metadata
    }
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) enum Relation {
    ReviewRequested,
    Authored,
    Involved,
}
impl Default for Relation {
    fn default() -> Self {
        Self::ReviewRequested
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ReadOwner {
    pub account_id: String,
    pub github_id: u64,
    pub authority: &'static str,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ReadCapabilities {
    #[serde(flatten)]
    pub owner: ReadOwner,
    pub private_repositories: PrivateRepositoryCapabilities,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PrivateRepositoryCapabilities {
    pub consent_available: bool,
    pub read_enabled: bool,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct RepositoryPage {
    #[serde(flatten)]
    pub owner: ReadOwner,
    pub repositories: Vec<ReadRepository>,
    pub next_page: Option<u32>,
    pub incomplete: bool,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PullPage {
    #[serde(flatten)]
    pub owner: ReadOwner,
    pub repository: ReadRepository,
    pub pulls: Vec<PublicPull>,
    pub next_page: Option<u32>,
    pub incomplete: bool,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct InboxPull {
    pub repository: ReadRepository,
    #[serde(flatten)]
    pub pull: PublicPull,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct InboxPage {
    #[serde(flatten)]
    pub owner: ReadOwner,
    pub relation: Relation,
    pub pulls: Vec<InboxPull>,
    pub next_page: Option<u32>,
    pub incomplete: bool,
}
