//! Personal provider connections. No listener or legacy RPC installs these capabilities.
mod policy;
mod ports;
mod types;
mod vault;

pub(crate) use policy::{PiEndpointPolicy, TrustedPiEndpoint};
pub(crate) use ports::{AiCredentialCapability, AiPrincipal};
pub(crate) use types::{
    ConnectionKind, ConnectionState, ConnectionStatus, ExpectedConnection, PiCredentialChange,
    PiCatalog, PiSetupInput, ProviderCapabilities,
};
pub(crate) use vault::{PersonalProviders, PiLease};
