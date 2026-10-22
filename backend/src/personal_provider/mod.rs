//! Personal provider connections. No listener or legacy RPC installs these capabilities.
mod policy;

pub(crate) use policy::{PiEndpointPolicy, TrustedPiEndpoint};
