//! Keeps a hosted server's data folder in an object store, for hosts whose disk does not
//! outlive the process (a Cloudflare Container). The folder is restored before the server
//! starts and every changed file is copied back a few seconds after it changes.
use anyhow::{bail, Context, Result};
use futures_util::{stream, StreamExt, TryStreamExt};
use serde::Deserialize;
use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};
use std::time::UNIX_EPOCH;

/// @cc [owner:OctavianTocan,label:architecture;security] hosted-data-restored-before-serving
/// A hosted server MUST NOT start until its data folder is restored. A restore that fails
/// MUST stop the container instead of starting the server empty, because an empty server
/// would save fresh files over the ones it could not read (Tavi, 24 Oct 2026, "Move the
/// server, AI last"; `plans/12-hosted-server.md`).
pub struct Mirror {
    client: reqwest::Client,
    base: url::Url,
    root: PathBuf,
    saved: BTreeMap<String, Seen>,
}

/// What one sync pass did. Failed files are tried again on the next pass.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Pass {
    pub saved: usize,
    pub removed: usize,
    pub failed: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Seen {
    len: u64,
    modified: u128,
}

#[derive(Deserialize)]
struct Listing {
    files: Vec<Listed>,
    cursor: Option<String>,
}

#[derive(Deserialize)]
struct Listed {
    path: String,
}

const PARALLEL: usize = 16;

impl Mirror {
    /// A mirror of `root` kept at `base`, which answers `GET list`, and `GET`, `PUT` and
    /// `DELETE` on `file/<path>`.
    pub fn new(base: &str, root: PathBuf) -> Result<Self> {
        let mut base = url::Url::parse(base).context("Invalid data mirror address")?;
        if !base.path().ends_with('/') {
            base.set_path(&format!("{}/", base.path()));
        }
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .build()?;
        Ok(Self {
            client,
            base,
            root,
            saved: BTreeMap::new(),
        })
    }

    /// Downloads every stored file into the folder and returns how many there were.
    pub async fn restore(&mut self) -> Result<usize> {
        std::fs::create_dir_all(&self.root).context("Cannot create the data folder")?;
        let mut paths = Vec::new();
        let mut cursor: Option<String> = None;
        loop {
            let mut url = self.base.join("list")?;
            if let Some(cursor) = &cursor {
                url.query_pairs_mut().append_pair("cursor", cursor);
            }
            let listing: Listing = self
                .client
                .get(url)
                .send()
                .await?
                .error_for_status()
                .context("The data mirror did not list its files")?
                .json()
                .await?;
            for file in listing.files {
                relative(&file.path)?;
                paths.push(file.path);
            }
            match listing.cursor {
                Some(next) if !next.is_empty() => cursor = Some(next),
                _ => break,
            }
        }
        let count = paths.len();
        stream::iter(paths.into_iter().map(|path| self.download(path)))
            .buffer_unordered(PARALLEL)
            .try_collect::<Vec<()>>()
            .await?;
        self.saved = scan(&self.root)?;
        Ok(count)
    }

    /// @cc [owner:OctavianTocan,label:architecture] hosted-data-only-deletes-what-it-saw
    /// Each pass saves files that changed since the last one and deletes stored files that are
    /// gone. The mirror MUST delete a stored file only after it saw that file in the local folder and
    /// then saw it gone; it MUST NOT delete a stored file it has not restored or saved itself.
    pub async fn sync(&mut self) -> Result<Pass> {
        let now = scan(&self.root)?;
        let changed: Vec<(String, Seen)> = now
            .iter()
            .filter(|(path, seen)| self.saved.get(*path) != Some(seen))
            .map(|(path, seen)| (path.clone(), *seen))
            .collect();
        let gone: Vec<String> = self
            .saved
            .keys()
            .filter(|path| !now.contains_key(*path))
            .cloned()
            .collect();
        let mut pass = Pass::default();
        let this: &Self = self;
        let uploads = stream::iter(changed.into_iter().map(|(path, seen)| async move {
            let result = this.upload(&path).await;
            (path, seen, result)
        }))
        .buffer_unordered(PARALLEL)
        .collect::<Vec<_>>()
        .await;
        for (path, seen, result) in uploads {
            match result {
                Ok(()) => {
                    self.saved.insert(path, seen);
                    pass.saved += 1;
                }
                Err(error) => {
                    eprintln!("Data mirror could not save a file: {error:#}");
                    pass.failed += 1;
                }
            }
        }
        let this: &Self = self;
        let deletes = stream::iter(gone.into_iter().map(|path| async move {
            let result = this.delete(&path).await;
            (path, result)
        }))
        .buffer_unordered(PARALLEL)
        .collect::<Vec<_>>()
        .await;
        for (path, result) in deletes {
            match result {
                Ok(()) => {
                    self.saved.remove(&path);
                    pass.removed += 1;
                }
                Err(error) => {
                    eprintln!("Data mirror could not delete a file: {error:#}");
                    pass.failed += 1;
                }
            }
        }
        Ok(pass)
    }

    fn file_url(&self, path: &str) -> Result<url::Url> {
        let mut url = self.base.join("file")?;
        url.path_segments_mut()
            .map_err(|()| anyhow::anyhow!("Invalid data mirror address"))?
            .extend(path.split('/'));
        Ok(url)
    }

    async fn download(&self, path: String) -> Result<()> {
        let target = self.root.join(relative(&path)?);
        let bytes = self
            .client
            .get(self.file_url(&path)?)
            .send()
            .await?
            .error_for_status()
            .context("The data mirror did not return a file")?
            .bytes()
            .await?;
        let parent = target.parent().context("Invalid data path")?.to_owned();
        tokio::fs::create_dir_all(&parent).await?;
        let temporary = parent.join(format!(".tmp-{}", uuid::Uuid::new_v4()));
        tokio::fs::write(&temporary, &bytes).await?;
        tokio::fs::rename(&temporary, &target).await?;
        Ok(())
    }

    async fn upload(&self, path: &str) -> Result<()> {
        let bytes = match tokio::fs::read(self.root.join(relative(path)?)).await {
            Ok(bytes) => bytes,
            // Removed between the scan and the read; the next pass deletes it.
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error.into()),
        };
        self.client
            .put(self.file_url(path)?)
            .body(bytes)
            .send()
            .await?
            .error_for_status()?;
        Ok(())
    }

    async fn delete(&self, path: &str) -> Result<()> {
        let response = self.client.delete(self.file_url(path)?).send().await?;
        if response.status() != reqwest::StatusCode::NOT_FOUND {
            response.error_for_status()?;
        }
        Ok(())
    }
}

/// A stored path as a path under the data folder: plain names joined by `/`, nothing else.
fn relative(path: &str) -> Result<PathBuf> {
    let relative = Path::new(path);
    if path.is_empty()
        || path.contains('\\')
        || !relative
            .components()
            .all(|part| matches!(part, Component::Normal(_)))
    {
        bail!("Invalid data path");
    }
    Ok(relative.to_owned())
}

/// Files the server writes only for a moment, or that only mean something to this process.
fn ignored(name: &str) -> bool {
    name.starts_with(".tmp-") || name.ends_with(".tmp") || name == ".rust-server.lock"
}

/// Every regular file under `root` with its length and modification time. Symlinks are skipped.
fn scan(root: &Path) -> Result<BTreeMap<String, Seen>> {
    let mut files = BTreeMap::new();
    let mut folders = vec![root.to_owned()];
    while let Some(folder) = folders.pop() {
        let entries = match std::fs::read_dir(&folder) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        for entry in entries {
            let entry = entry?;
            let kind = entry.file_type()?;
            if kind.is_dir() {
                folders.push(entry.path());
                continue;
            }
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if !kind.is_file() || ignored(name) {
                continue;
            }
            let Ok(meta) = entry.metadata() else { continue };
            let Some(path) = entry
                .path()
                .strip_prefix(root)
                .ok()
                .and_then(Path::to_str)
                .map(str::to_owned)
            else {
                continue;
            };
            let modified = meta
                .modified()
                .ok()
                .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                .map_or(0, |time| time.as_nanos());
            files.insert(
                path,
                Seen {
                    len: meta.len(),
                    modified,
                },
            );
        }
    }
    Ok(files)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::Bytes,
        extract::{Path as Route, Query, State},
        http::StatusCode,
        routing::get,
        Json, Router,
    };
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    type Files = Arc<Mutex<BTreeMap<String, Vec<u8>>>>;

    async fn list(
        State(files): State<Files>,
        Query(query): Query<HashMap<String, String>>,
    ) -> Json<serde_json::Value> {
        // Pages of two, so restore has to follow the cursor.
        let files = files.lock().unwrap();
        let start = query
            .get("cursor")
            .and_then(|c| c.parse::<usize>().ok())
            .unwrap_or(0);
        let page: Vec<_> = files
            .keys()
            .skip(start)
            .take(2)
            .map(|path| serde_json::json!({"path": path}))
            .collect();
        let cursor = (start + 2 < files.len()).then(|| (start + 2).to_string());
        Json(serde_json::json!({"files": page, "cursor": cursor}))
    }

    async fn read(State(files): State<Files>, Route(path): Route<String>) -> Result<Vec<u8>, StatusCode> {
        files.lock().unwrap().get(&path).cloned().ok_or(StatusCode::NOT_FOUND)
    }

    async fn write(State(files): State<Files>, Route(path): Route<String>, body: Bytes) -> StatusCode {
        files.lock().unwrap().insert(path, body.to_vec());
        StatusCode::NO_CONTENT
    }

    async fn remove(State(files): State<Files>, Route(path): Route<String>) -> StatusCode {
        match files.lock().unwrap().remove(&path) {
            Some(_) => StatusCode::NO_CONTENT,
            None => StatusCode::NOT_FOUND,
        }
    }

    async fn serve(files: Files) -> String {
        let app = Router::new()
            .route("/data/list", get(list))
            .route("/data/file/{*path}", get(read).put(write).delete(remove))
            .with_state(files);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        format!("http://{address}/data")
    }

    fn folder() -> PathBuf {
        std::env::temp_dir().join(format!("pully-mirror-{}", uuid::Uuid::new_v4()))
    }

    #[tokio::test]
    async fn restores_every_page_then_saves_changes_and_deletes_what_it_saw_go() {
        let files: Files = Default::default();
        {
            let mut stored = files.lock().unwrap();
            stored.insert("store/diff/a b:1".into(), b"one".to_vec());
            stored.insert("store/settings".into(), b"{}".to_vec());
            stored.insert("analytics/2026-10-24.ndjson".into(), b"{\"e\":1}\n".to_vec());
            stored.insert("store/never-local".into(), b"kept".to_vec());
        }
        let root = folder();
        let mut mirror = Mirror::new(&serve(files.clone()).await, root.clone()).unwrap();
        assert_eq!(mirror.restore().await.unwrap(), 4);
        assert_eq!(std::fs::read(root.join("store/diff/a b:1")).unwrap(), b"one");
        assert_eq!(mirror.sync().await.unwrap(), Pass::default());

        std::fs::write(root.join("store/settings"), b"{\"theme\":\"dark\"}").unwrap();
        std::fs::create_dir_all(root.join("store/posted")).unwrap();
        std::fs::write(root.join("store/posted/x"), b"new").unwrap();
        std::fs::write(root.join("store/.tmp-123"), b"half").unwrap();
        std::fs::write(root.join("store/.rust-server.lock"), b"").unwrap();
        std::fs::remove_file(root.join("store/diff/a b:1")).unwrap();
        let pass = mirror.sync().await.unwrap();
        assert_eq!(pass, Pass { saved: 2, removed: 1, failed: 0 });
        {
            let stored = files.lock().unwrap();
            assert_eq!(stored["store/settings"], b"{\"theme\":\"dark\"}");
            assert_eq!(stored["store/posted/x"], b"new");
            assert!(!stored.contains_key("store/diff/a b:1"));
            assert!(!stored.keys().any(|k| k.contains(".tmp-") || k.ends_with(".lock")));
        }
        assert_eq!(mirror.sync().await.unwrap(), Pass::default());

        // A file the mirror never saw locally is never deleted, even after a fresh start.
        files.lock().unwrap().insert("store/added-elsewhere".into(), b"x".to_vec());
        assert_eq!(mirror.sync().await.unwrap(), Pass::default());
        assert!(files.lock().unwrap().contains_key("store/added-elsewhere"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn a_failed_restore_fails_and_bad_paths_are_refused() {
        let root = folder();
        let mut unreachable = Mirror::new("http://127.0.0.1:9/data", root.clone()).unwrap();
        assert!(unreachable.restore().await.is_err());

        let files: Files = Default::default();
        files.lock().unwrap().insert("../escape".into(), b"x".to_vec());
        let mut mirror = Mirror::new(&serve(files).await, root.clone()).unwrap();
        assert!(mirror.restore().await.is_err());
        assert!(!root.parent().unwrap().join("escape").exists());
        for bad in ["", "/etc/passwd", "a/../b", "a\\b", "./a"] {
            assert!(relative(bad).is_err(), "{bad}");
        }
        let _ = std::fs::remove_dir_all(root);
    }

    #[tokio::test]
    async fn failed_saves_are_retried_on_the_next_pass() {
        let files: Files = Default::default();
        let base = serve(files.clone()).await;
        let root = folder();
        let mut mirror = Mirror::new(&base, root.clone()).unwrap();
        mirror.restore().await.unwrap();
        std::fs::write(root.join("note"), b"1").unwrap();
        mirror.base = url::Url::parse("http://127.0.0.1:9/data/").unwrap();
        assert_eq!(mirror.sync().await.unwrap().failed, 1);
        mirror.base = url::Url::parse(&format!("{base}/")).unwrap();
        assert_eq!(mirror.sync().await.unwrap().saved, 1);
        assert_eq!(files.lock().unwrap()["note"], b"1");
        std::fs::remove_dir_all(root).unwrap();
    }
}
