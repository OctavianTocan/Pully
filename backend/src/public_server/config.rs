use crate::{
    personal_auth::{OAuthConfig, PersonalAuthHostConfig, VaultKeys},
    store::blocking,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Deserialize;
use std::{
    collections::BTreeMap,
    io::Read,
    path::{Component, Path, PathBuf},
};

/// Trusted startup file paths; native input never supplies these or their file contents.
pub struct PersonalAuthFiles {
    pub data_root: PathBuf,
    pub oauth_app_file: PathBuf,
    pub vault_keys_file: PathBuf,
}
#[derive(Default)]
pub struct PublicServerConfig {
    pub personal: Option<PersonalAuthFiles>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PublicServerError;
impl std::fmt::Display for PublicServerError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("Public Pully service configuration is unavailable")
    }
}
impl std::error::Error for PublicServerError {}

pub(super) struct Loaded {
    pub auth: PersonalAuthHostConfig,
    pub tenant_root: PathBuf,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct OAuthFile {
    client_id: String,
    client_secret: String,
    callback: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct KeysFile {
    active_version: u32,
    keys: BTreeMap<u32, String>,
}

pub(super) async fn load(config: PublicServerConfig) -> Result<Option<Loaded>, PublicServerError> {
    let Some(files) = config.personal else {
        return Ok(None);
    };
    blocking(move || {
        absolute_normal(&files.data_root)?;
        let oauth: OAuthFile = serde_json::from_slice(&protected_file(&files.oauth_app_file)?)?;
        let supplied: KeysFile = serde_json::from_slice(&protected_file(&files.vault_keys_file)?)?;
        anyhow::ensure!((1..=8).contains(&supplied.keys.len()), "Invalid vault keys");
        let mut keys = BTreeMap::new();
        for (version, encoded) in supplied.keys {
            anyhow::ensure!(encoded.len() == 43, "Invalid vault key");
            let key: [u8; 32] = URL_SAFE_NO_PAD
                .decode(encoded)?
                .try_into()
                .map_err(|_| anyhow::anyhow!("Invalid vault key"))?;
            keys.insert(version, key);
        }
        let oauth = OAuthConfig::new(oauth.client_id, oauth.client_secret, &oauth.callback)?;
        let keys = VaultKeys::new(supplied.active_version, keys)?;
        Ok(Some(Loaded {
            auth: PersonalAuthHostConfig::new(files.data_root.join("personal-auth"), oauth, keys),
            tenant_root: files.data_root.join("personal-tenants"),
        }))
    })
    .await
    .map_err(|_| PublicServerError)
}

fn absolute_normal(path: &Path) -> anyhow::Result<()> {
    anyhow::ensure!(
        path.is_absolute()
            && path.parent().is_some()
            && path.components().all(|component| matches!(
                component,
                Component::Prefix(_) | Component::RootDir | Component::Normal(_)
            )),
        "Invalid service path"
    );
    for ancestor in path.ancestors() {
        if let Ok(metadata) = std::fs::symlink_metadata(ancestor) {
            anyhow::ensure!(
                !metadata.file_type().is_symlink(),
                "Service path symlinks are not allowed"
            );
        }
    }
    Ok(())
}

fn protected_file(path: &Path) -> anyhow::Result<Vec<u8>> {
    absolute_normal(path)?;
    let before = std::fs::symlink_metadata(path)?;
    anyhow::ensure!(
        before.is_file() && !before.file_type().is_symlink() && before.len() <= 16 * 1024,
        "Invalid service secret file"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        anyhow::ensure!(
            before.permissions().mode() & 0o077 == 0,
            "Service secret file must be private"
        );
        let file = std::fs::File::open(path)?;
        let opened = file.metadata()?;
        anyhow::ensure!(
            opened.is_file()
                && opened.dev() == before.dev()
                && opened.ino() == before.ino()
                && opened.uid() == before.uid()
                && opened.permissions().mode() & 0o077 == 0,
            "Service secret file changed"
        );
        let mut bytes = Vec::new();
        file.take(16 * 1024 + 1).read_to_end(&mut bytes)?;
        anyhow::ensure!(bytes.len() <= 16 * 1024, "Service secret file is too large");
        Ok(bytes)
    }
    #[cfg(not(unix))]
    {
        // This Linux/macOS host requires a verifiable owner-private secret-file permission boundary.
        anyhow::bail!("This host cannot verify private service secret files")
    }
}
