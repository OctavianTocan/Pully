//! Startup composition only. The caller owns flags, secret loading and listener binding.
use super::{configured_router, unconfigured_router, OAuthConfig, PersonalAuth, VaultKeys};
use crate::store::{blocking, Store};
use axum::Router;
use std::path::{Component, Path, PathBuf};

/// Constructed by trusted host startup, never deserialized from a native request.
pub(crate) struct PersonalAuthHostConfig {
    storage_root: PathBuf,
    oauth: OAuthConfig,
    keys: VaultKeys,
}
impl PersonalAuthHostConfig {
    pub(crate) fn new(storage_root: PathBuf, oauth: OAuthConfig, keys: VaultKeys) -> Self {
        Self {
            storage_root,
            oauth,
            keys,
        }
    }
}

/// No underlying path, provider error or secret is retained in this public startup failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PersonalAuthHostError;
impl std::fmt::Display for PersonalAuthHostError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("Personal GitHub authorization could not be initialized")
    }
}
impl std::error::Error for PersonalAuthHostError {}

pub(crate) struct PersonalAuthHost {
    router: Router,
    service: Option<PersonalAuth>,
}
impl PersonalAuthHost {
    #[cfg(test)]
    pub(crate) async fn fixture(
        config: PersonalAuthHostConfig,
        origin: url::Url,
    ) -> Result<Self, PersonalAuthHostError> {
        let (store, oauth, keys) = blocking(move || {
            validate_storage_root(&config.storage_root)?;
            Ok((Store::new(config.storage_root)?, config.oauth, config.keys))
        })
        .await
        .map_err(|_| PersonalAuthHostError)?;
        let service = PersonalAuth::fixture(oauth, store, keys, origin)
            .await
            .map_err(|_| PersonalAuthHostError)?;
        Ok(Self {
            router: configured_router(service.clone()),
            service: Some(service),
        })
    }
    /// @cc [owner:OctavianTocan,label:security;architecture] personal-host-configuration-is-explicit
    /// Missing startup configuration MUST return only disabled discovery without opening storage
    /// or loading any account/provider. Configured startup MUST use the explicitly supplied
    /// isolated path (in the Worker, `open_store` with the object's own scoped store; Tavi,
    /// 25 Oct 2026, Connect GitHub on staging), OAuth configuration (GitHub CLI device sign-in or a Pully OAuth App) and vault keys; a failure MUST NOT select legacy or default
    /// authority. This factory MUST NOT bind a listener. Source: Tavi's 21 Oct 2026 personal
    /// authorization decision and 22 Oct concrete host-composition requirement.
    pub(crate) async fn open(
        config: Option<PersonalAuthHostConfig>,
    ) -> Result<Self, PersonalAuthHostError> {
        let Some(config) = config else {
            return Ok(Self {
                router: unconfigured_router(),
                service: None,
            });
        };
        // The Worker has no files: it opens its store itself and calls `open_store`.
        #[cfg(target_arch = "wasm32")]
        {
            drop(config);
            Err(PersonalAuthHostError)
        }
        #[cfg(not(target_arch = "wasm32"))]
        {
            let (store, oauth, keys) = blocking(move || {
                validate_storage_root(&config.storage_root)?;
                let store = Store::new(config.storage_root)?;
                Ok((store, config.oauth, config.keys))
            })
            .await
            .map_err(|_| PersonalAuthHostError)?;
            Self::open_store(store, oauth, keys).await
        }
    }

    /// Configured startup over a store the host opened itself: the Worker keeps it in its own
    /// scope of Durable Object storage, apart from every other store.
    pub(crate) async fn open_store(
        store: Store,
        oauth: OAuthConfig,
        keys: VaultKeys,
    ) -> Result<Self, PersonalAuthHostError> {
        let service = PersonalAuth::new(oauth, store, keys)
            .await
            .map_err(|_| PersonalAuthHostError)?;
        Ok(Self {
            router: configured_router(service.clone()),
            service: Some(service),
        })
    }

    /// The listener owner installs these parts explicitly; the service is not a request principal.
    pub(crate) fn into_parts(self) -> (Router, Option<PersonalAuth>) {
        (self.router, self.service)
    }
}

fn validate_storage_root(path: &Path) -> anyhow::Result<()> {
    anyhow::ensure!(
        path.is_absolute()
            && path.parent().is_some()
            && path.components().all(|part| {
                matches!(
                    part,
                    Component::RootDir | Component::Prefix(_) | Component::Normal(_)
                )
            }),
        "Invalid personal storage configuration"
    );
    for ancestor in path.ancestors() {
        match std::fs::symlink_metadata(ancestor) {
            Ok(metadata) => anyhow::ensure!(
                metadata.is_dir() && !metadata.file_type().is_symlink(),
                "Invalid personal storage configuration"
            ),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => anyhow::bail!("Invalid personal storage configuration"),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests;
