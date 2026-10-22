//! Opt-in public/personal host. No legacy listener, checkout or operator account is initialized.
mod config;
mod personal;

use crate::personal_auth::PersonalAuthHost;
use axum::Router;
pub use config::{PersonalAuthFiles, PublicServerConfig, PublicServerError};

/// Construction does not bind or publish a service; the executable explicitly opts into serving it.
pub async fn router(config: PublicServerConfig) -> Result<Router, PublicServerError> {
    let configured = config::load(config).await?;
    let (auth_config, tenant_root) = match configured {
        Some(configured) => (Some(configured.auth), Some(configured.tenant_root)),
        None => (None, None),
    };
    let (auth_router, auth) = PersonalAuthHost::open(auth_config)
        .await
        .map_err(|_| PublicServerError)?
        .into_parts();
    let public = crate::public_api::router_with_personal_github(auth.is_some())
        .map_err(|_| PublicServerError)?;
    let personal = if let (Some(auth), Some(root)) = (auth, tenant_root) {
        Some(
            personal::router(auth, root)
                .await
                .map_err(|_| PublicServerError)?,
        )
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
