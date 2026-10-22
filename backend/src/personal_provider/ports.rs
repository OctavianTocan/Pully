//! Backend capability ports do not make GitHub sign-in the prerequisite for all AI.
use anyhow::Result;
use std::{future::Future, pin::Pin};
use tokio::sync::watch;

pub(crate) type AiFuture<'a, T> = Pin<Box<dyn Future<Output = Result<T>> + Send + 'a>>;

/// A trusted host captures a verified owner or an explicitly granted anonymous/default capability.
/// Native request bodies cannot construct this authority; a shared Guest namespace is not an owner.
pub(crate) trait AiPrincipal: Send + Sync {
    fn owner_id(&self) -> &str;
    fn ensure_current(&self) -> Result<()>;
    fn subscribe_retirement(&self) -> Result<watch::Receiver<bool>>;
}

/// Source and credential lifetime required by an AI adapter before/after reads, cache returns or jobs.
/// The personal Pi lease implements this port; an anonymous/local or labelled default adapter must
/// supply its own real principal, credential isolation and allowance, never a forged GitHub binding.
pub(crate) trait AiCredentialCapability: Send + Sync {
    fn principal(&self) -> &dyn AiPrincipal;
    fn ensure_current(&self) -> AiFuture<'_, ()>;
    fn retired(&self) -> AiFuture<'_, ()>;
}
