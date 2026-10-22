use serde::{Deserialize, Serialize};

pub(crate) const MAX_PI_ENDPOINT_BYTES: usize = 2048;
pub(crate) const MAX_PI_MODEL_BYTES: usize = 256;
pub(crate) const MAX_PI_CREDENTIAL_BYTES: usize = 16 * 1024;

#[derive(Clone, Copy, Serialize)]
pub(crate) enum ConnectionKind {
    #[serde(rename = "claudeSubscription")]
    ClaudeSubscription,
    #[serde(rename = "chatGPTSubscription")]
    ChatGptSubscription,
    #[serde(rename = "piEndpoint")]
    PiEndpoint,
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ConnectionState {
    Disconnected,
    Pending,
    Unverified,
    Connected,
    RefreshNeeded,
    Unavailable,
    Unsupported,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProviderCapabilities {
    pub configure: bool,
    pub models: bool,
    pub execute: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ConnectionStatus {
    pub kind: ConnectionKind,
    pub state: ConnectionState,
    /// Owner of this authenticated personal connection, not the product's global AI eligibility.
    pub account_id: String,
    pub connection_id: Option<String>,
    pub revision: Option<u64>,
    pub endpoint_origin: Option<String>,
    pub preferred_model: Option<String>,
    pub has_credential: bool,
    pub approval_required: bool,
    pub capabilities: ProviderCapabilities,
}

/// Both absent means a first configuration; a persisted connection requires both exact values.
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct ExpectedConnection {
    pub connection_id: Option<String>,
    pub revision: Option<u64>,
}

/// Secret writes have no Serialize or Debug implementation and no corresponding readback DTO.
#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "camelCase", deny_unknown_fields)]
pub(crate) enum PiCredentialChange {
    Keep {},
    Replace { value: String },
    Clear {},
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct PiSetupInput {
    /// Omission keeps the existing base; an initial connection must provide one.
    pub endpoint: Option<String>,
    /// Omission keeps the current model; an empty string clears its preference.
    pub preferred_model: Option<String>,
    pub credential: PiCredentialChange,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PiCatalog {
    pub account_id: String,
    pub connection_id: String,
    pub revision: u64,
    /// The actual canonical Pi catalog rows, without provider credentials or a fabricated email.
    pub models: Vec<serde_json::Value>,
}
