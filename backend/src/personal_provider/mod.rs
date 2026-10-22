//! Personal provider connections. No listener or legacy RPC installs these capabilities.
mod policy;
mod types;

pub(crate) use policy::{PiEndpointPolicy, TrustedPiEndpoint};
pub(crate) use types::{
    ConnectionKind, ConnectionState, ConnectionStatus, ExpectedConnection, PiCredentialChange,
    PiSetupInput, ProviderCapabilities,
};
