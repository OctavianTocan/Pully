//! Opt-in public/personal host. No legacy listener, checkout or operator account is initialized.
mod config;
mod personal;

use crate::{
    identity::TenantStates,
    personal_auth::PersonalAuthHost,
    store::{blocking, Store},
};
use axum::Router;
pub use config::{PersonalAuthFiles, PublicServerConfig, PublicServerError};
use std::sync::Arc;

/// How many personal accounts one server keeps state for at once.
const TENANT_LIMIT: usize = 256;

/// Construction does not bind or publish a service; the executable explicitly opts into serving it.
pub async fn router(config: PublicServerConfig) -> Result<Router, PublicServerError> {
    let configured = config::load(config).await?;
    let (auth_config, tenant_root) = match configured {
        Some(configured) => (Some(configured.auth), Some(configured.tenant_root)),
        None => (None, None),
    };
    let host = PersonalAuthHost::open(auth_config)
        .await
        .map_err(|_| PublicServerError)?;
    #[cfg(target_arch = "wasm32")]
    let tenants = tenant_root.map(|_| unreachable!("the Worker opens no configured files"));
    #[cfg(not(target_arch = "wasm32"))]
    let tenants = match tenant_root {
        Some(root) => Some(
            blocking(move || TenantStates::new(root, TENANT_LIMIT))
                .await
                .map_err(|_| PublicServerError)?,
        ),
        None => None,
    };
    serve(host, tenants)
}

/// Stores the Worker supplies for the personal service: the sign-in store, and one store for
/// each personal account, named by its namespace.
pub struct HostedPersonal {
    pub auth: Store,
    pub tenants: Arc<dyn Fn(&str) -> anyhow::Result<Store> + Send + Sync>,
    /// The OAuth file's text (`{"githubCli": true}`).
    pub oauth: String,
    /// The vault keys file's text.
    pub vault_keys: String,
}

/// The Worker's form of [`router`]: the same service over the stores and secrets the Worker
/// supplies. Without `personal` it serves public browsing and disabled GitHub discovery.
pub async fn hosted_router(personal: Option<HostedPersonal>) -> Result<Router, PublicServerError> {
    let Some(personal) = personal else {
        let host = PersonalAuthHost::open(None)
            .await
            .map_err(|_| PublicServerError)?;
        return serve(host, None);
    };
    let (oauth, keys) = config::parse(personal.oauth.as_bytes(), personal.vault_keys.as_bytes())
        .map_err(|_| PublicServerError)?;
    let tenants =
        TenantStates::with_stores(personal.tenants, TENANT_LIMIT).map_err(|_| PublicServerError)?;
    let host = PersonalAuthHost::open_store(personal.auth, oauth, keys)
        .await
        .map_err(|_| PublicServerError)?;
    serve(host, Some(tenants))
}

fn serve(
    host: PersonalAuthHost,
    tenants: Option<TenantStates>,
) -> Result<Router, PublicServerError> {
    let (auth_router, auth) = host.into_parts();
    let public = crate::public_api::router_with_personal_github(auth.is_some())
        .map_err(|_| PublicServerError)?;
    let personal = if let (Some(auth), Some(tenants)) = (auth, tenants) {
        Some(personal::router(auth, tenants).map_err(|_| PublicServerError)?)
    } else {
        None
    };
    Ok(compose(public, auth_router, personal))
}

fn compose(public: Router, auth: Router, personal: Option<Router>) -> Router {
    let router = public.merge(auth);
    match personal {
        Some(personal) => router.merge(personal),
        None => router,
    }
}

#[cfg(test)]
mod tests;
