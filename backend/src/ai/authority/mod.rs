//! Captured AI authority is independent of a GitHub login and of provider protocol parsing.
use anyhow::{ensure, Result};
use std::{
    future::Future,
    pin::Pin,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};
use tokio::sync::watch;

pub(crate) mod siwc;

pub(crate) type AiFuture<'a, T> = Pin<Box<dyn Future<Output = Result<T>> + Send + 'a>>;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum AiProviderKind {
    ClaudeSubscription,
    ChatGPTSubscription,
    PiEndpoint,
}

/// Public metadata only. Possession of this value cannot construct a credential lease.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct AiConnectionRef {
    pub connection_id: String,
    pub revision: u64,
    pub provider_kind: AiProviderKind,
}

pub(crate) trait AiPrincipal: Send + Sync {
    fn owner_id(&self) -> &str;
    fn ensure_current(&self) -> Result<()>;
    fn subscribe_retirement(&self) -> Result<watch::Receiver<bool>>;
}

/// The custody implementation owns refresh and validates the exact configured target and method.
/// It must never accept an arbitrary request destination or return a decrypted credential.
pub(crate) trait AiRequestAuthorizer: Send + Sync {
    fn authorize_request(
        &self,
        request: reqwest::RequestBuilder,
    ) -> AiFuture<'_, reqwest::RequestBuilder>;
}

pub(crate) trait AiCredentialCapability: Send + Sync {
    fn principal(&self) -> &dyn AiPrincipal;
    fn connection(&self) -> &AiConnectionRef;
    fn ensure_current(&self) -> AiFuture<'_, ()>;
    fn retired(&self) -> AiFuture<'_, ()>;
    fn authorize_request(
        &self,
        request: reqwest::RequestBuilder,
    ) -> AiFuture<'_, reqwest::RequestBuilder>;
}

struct Retirement {
    active: AtomicBool,
    notice: watch::Sender<bool>,
}
impl Retirement {
    fn new() -> Self {
        Self {
            active: AtomicBool::new(true),
            notice: watch::channel(false).0,
        }
    }
    fn ensure_current(&self) -> Result<()> {
        ensure!(self.active.load(Ordering::SeqCst), "AI authority changed");
        Ok(())
    }
    fn retire(&self) {
        self.active.store(false, Ordering::SeqCst);
        self.notice.send_replace(true);
    }
    fn subscribe(&self) -> Result<watch::Receiver<bool>> {
        let subscription = self.notice.subscribe();
        self.ensure_current()?;
        Ok(subscription)
    }
}

/// Created by a trusted host after verifying its own stable Pully/AI owner. Native request bodies
/// cannot mint this source. A source by itself grants no provider credential or paid allowance.
pub(crate) struct AiPrincipalSource {
    owner_id: String,
    retirement: Retirement,
}
impl AiPrincipalSource {
    pub(crate) fn verified(owner_id: String) -> Result<Arc<Self>> {
        ensure!(valid_id(&owner_id), "Invalid AI owner");
        Ok(Arc::new(Self {
            owner_id,
            retirement: Retirement::new(),
        }))
    }
    pub(crate) fn local() -> Arc<Self> {
        Arc::new(Self {
            owner_id: format!("local-{}", uuid::Uuid::new_v4()),
            retirement: Retirement::new(),
        })
    }
    pub(crate) fn retire(&self) {
        self.retirement.retire();
    }
}
impl AiPrincipal for AiPrincipalSource {
    fn owner_id(&self) -> &str {
        &self.owner_id
    }
    fn ensure_current(&self) -> Result<()> {
        self.retirement.ensure_current()
    }
    fn subscribe_retirement(&self) -> Result<watch::Receiver<bool>> {
        self.retirement.subscribe()
    }
}

struct Connection {
    principal: Arc<dyn AiPrincipal>,
    reference: AiConnectionRef,
    authorizer: Arc<dyn AiRequestAuthorizer>,
    retirement: Retirement,
}

/// A custody owner installs a verified connection, retires this authority synchronously before
/// replacing/removing its durable revision, and never reuses it for the replacement connection.
pub(crate) struct AiConnectionAuthority(Arc<Connection>);
impl AiConnectionAuthority {
    pub(crate) fn new(
        principal: Arc<dyn AiPrincipal>,
        reference: AiConnectionRef,
        authorizer: Arc<dyn AiRequestAuthorizer>,
    ) -> Result<Self> {
        principal.ensure_current()?;
        ensure!(
            valid_id(principal.owner_id())
                && valid_id(&reference.connection_id)
                && reference.revision > 0,
            "Invalid AI connection"
        );
        Ok(Self(Arc::new(Connection {
            principal,
            reference,
            authorizer,
            retirement: Retirement::new(),
        })))
    }
    pub(crate) fn capture(&self) -> Result<AiLease> {
        self.0.principal.ensure_current()?;
        self.0.retirement.ensure_current()?;
        Ok(AiLease(self.0.clone()))
    }
    pub(crate) fn retire(&self) {
        self.0.retirement.retire();
    }
}
impl Drop for AiConnectionAuthority {
    fn drop(&mut self) {
        self.retire();
    }
}

/// This handle retains the original source, connection and credential authorizer for its run.
/// A replacement can never turn an old handle into the new connection's authority.
#[derive(Clone)]
pub(crate) struct AiLease(Arc<Connection>);
impl AiLease {
    fn current(&self) -> Result<()> {
        self.0.principal.ensure_current()?;
        self.0.retirement.ensure_current()
    }
}
impl AiCredentialCapability for AiLease {
    fn principal(&self) -> &dyn AiPrincipal {
        self.0.principal.as_ref()
    }
    fn connection(&self) -> &AiConnectionRef {
        &self.0.reference
    }
    fn ensure_current(&self) -> AiFuture<'_, ()> {
        Box::pin(async { self.current() })
    }
    fn retired(&self) -> AiFuture<'_, ()> {
        Box::pin(async {
            // Install both subscriptions before admitting current authority. A hint is never
            // the authority check; it just wakes an in-flight network/tool/event await.
            let mut connection = match self.0.retirement.subscribe() {
                Ok(receiver) => receiver,
                Err(_) => return Ok(()),
            };
            let mut principal = match self.0.principal.subscribe_retirement() {
                Ok(receiver) => receiver,
                Err(_) => return Ok(()),
            };
            loop {
                if self.current().is_err() {
                    return Ok(());
                }
                tokio::select! {
                    changed = connection.changed() => {
                        ensure!(changed.is_ok(), "AI connection observation ended");
                    }
                    changed = principal.changed() => {
                        ensure!(changed.is_ok(), "AI owner observation ended");
                    }
                }
            }
        })
    }
    fn authorize_request(
        &self,
        request: reqwest::RequestBuilder,
    ) -> AiFuture<'_, reqwest::RequestBuilder> {
        Box::pin(async {
            self.current()?;
            let authorized = tokio::select! {
                result = self.0.authorizer.authorize_request(request) => result?,
                _ = self.retired() => anyhow::bail!("AI authority changed"),
            };
            self.current()?;
            Ok(authorized)
        })
    }
}

fn valid_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte))
}

#[cfg(test)]
mod tests;
