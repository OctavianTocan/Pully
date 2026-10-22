use serde::{Deserialize, Serialize};

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
    Keep,
    Replace { value: String },
    Clear,
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
