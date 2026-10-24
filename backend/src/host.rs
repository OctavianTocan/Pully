//! Machine capabilities shared by HTTP, RPC and background work.
use crate::store::Store;
use anyhow::{bail, Context, Result};
use std::path::{Component, Path, PathBuf};

pub const NO_CHECKOUT: &str = "This server has no checkout, so it opens GitHub pull requests only.";

#[derive(Clone)]
pub struct ServerHost {
    pub storage: Store,
    pub cwd: Option<PathBuf>,
    /// Explicit command-line override; the environment is otherwise read per request.
    pub android_dir: Option<PathBuf>,
}
pub struct HostFile {
    pub file: crate::rt::fs::File,
    pub size: u64,
}
impl ServerHost {
    pub fn new(storage: Store, cwd: Option<PathBuf>, android_dir: Option<PathBuf>) -> Self {
        Self {
            storage,
            cwd: cwd.filter(|p| !p.as_os_str().is_empty()),
            android_dir,
        }
    }
    /// @cc [owner:OctavianTocan,label:architecture;security] rust-host-never-invents-checkout
    /// A host without a checkout MUST fail every local-source operation with NO_CHECKOUT and MUST NOT fall back to the process directory (Tavi, 16 Oct 08:39; plan 12).
    pub fn checkout(&self) -> Result<&Path> {
        self.cwd.as_deref().context(NO_CHECKOUT)
    }
    pub fn android_dir(&self) -> PathBuf {
        self.android_dir
            .clone()
            .or_else(|| {
                crate::rt::env::var_os("PULLS_REVIEW_ANDROID_DIR")
                    .filter(|s| !s.is_empty())
                    .map(PathBuf::from)
            })
            .unwrap_or_else(|| {
                PathBuf::from(crate::rt::env::var_os("HOME").unwrap_or_else(|| ".".into()))
                    .join(".local/share/pulls-review/android")
            })
    }
    pub async fn android_file(&self, name: &str) -> Result<Option<HostFile>> {
        let path = Path::new(name);
        if name.is_empty() || !path.components().all(|c| matches!(c, Component::Normal(_))) {
            bail!("Invalid Android file name");
        }
        let root = match crate::rt::fs::canonicalize(self.android_dir()).await {
            Ok(root) => root,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(e.into()),
        };
        let path = match crate::rt::fs::canonicalize(root.join(path)).await {
            Ok(path) => path,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(e.into()),
        };
        if !path.starts_with(&root) {
            bail!("Android file is outside its directory");
        }
        let file = crate::rt::fs::File::open(&path).await?;
        let info = file.metadata().await?;
        if !info.is_file() {
            return Ok(None);
        }
        #[cfg(target_os = "linux")]
        {
            use std::os::fd::AsRawFd;
            let opened =
                crate::rt::fs::canonicalize(format!("/proc/self/fd/{}", file.as_raw_fd())).await?;
            if !opened.starts_with(&root) {
                bail!("Android file is outside its directory");
            }
        }
        Ok(Some(HostFile {
            file,
            size: info.len(),
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn hosted_checkout_and_unsafe_files_fail_closed() {
        let root = std::env::temp_dir().join(format!("pulls-host-{}", uuid::Uuid::new_v4()));
        let host = ServerHost::new(Store::new(root.clone()).unwrap(), None, None);
        assert_eq!(host.checkout().unwrap_err().to_string(), NO_CHECKOUT);
        assert!(host.android_file("../secret").await.is_err());
        assert!(host.android_file("/secret").await.is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
