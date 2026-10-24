//! Published Android artifacts, with a distinct HTML entry and binary update URL.
use crate::host::ServerHost;
use axum::{
    http::{header, StatusCode},
    response::{Html, IntoResponse, Response},
    Json,
};
use futures_util::StreamExt;
use serde_json::Value;
use std::sync::OnceLock;

pub(super) async fn response(host: &ServerHost, path: &str) -> Response {
    match path {
        "/download" | "/download/" => landing(host).await,
        "/download/latest.json" | "/android/latest.json" => match manifest(host).await {
            Some(value) => ([(header::CACHE_CONTROL, "no-store")], Json(value)).into_response(),
            None => super::error(
                StatusCode::NOT_FOUND,
                "No Android build has been published.",
            ),
        },
        "/download/pully.apk" => super::android_file_response(host, "pulls.apk").await,
        "/download/staging/latest.json" => match manifest_in(host, STAGING).await {
            Some(value) => ([(header::CACHE_CONTROL, "no-store")], Json(value)).into_response(),
            None => super::error(
                StatusCode::NOT_FOUND,
                "No staging build has been published.",
            ),
        },
        "/download/staging/pully.apk" => {
            super::android_file_response(host, "staging/pulls.apk").await
        }
        _ => {
            let relative = path
                .strip_prefix("/download/")
                .or_else(|| path.strip_prefix("/android/"));
            match relative.filter(|relative| history_asset(relative)) {
                Some(relative) => super::android_file_response(host, relative).await,
                None => super::error(StatusCode::NOT_FOUND, "Download not found."),
            }
        }
    }
}

fn history_asset(relative: &str) -> bool {
    static ALLOWED: OnceLock<regex::Regex> = OnceLock::new();
    relative == "past/index.json"
        || ALLOWED
            .get_or_init(|| {
                regex::Regex::new(
        r"^(past/\d+\.\d+\.\d+\.apk|changelog/\d+\.\d+\.\d+/[a-z0-9-]+\.(png|webp|gif|jpg))$"
    ).unwrap()
            })
            .is_match(relative)
}

async fn metadata(host: &ServerHost, name: &str) -> Option<Value> {
    let file = host.android_file(name).await.ok()??;
    const LIMIT: u64 = 2 * 1024 * 1024;
    if file.size > LIMIT {
        return None;
    }
    let mut bytes = Vec::new();
    let mut body = file.body;
    while let Some(chunk) = body.next().await {
        bytes.extend_from_slice(&chunk.ok()?);
        if bytes.len() as u64 > LIMIT {
            return None;
        }
    }
    serde_json::from_slice(&bytes).ok()
}

fn valid_release(value: &Value) -> bool {
    value["versionCode"]
        .as_u64()
        .is_some_and(|code| code > 0 && code <= 2_100_000_000)
        && value["versionName"]
            .as_str()
            .is_some_and(|name| !name.is_empty() && name.len() <= 100)
        && value["sha256"]
            .as_str()
            .is_some_and(|sha| sha.len() == 64 && sha.bytes().all(|b| b.is_ascii_hexdigit()))
}

/// The staging app's channel: its files sit under `staging/` beside the stable app's, built from
/// main by `.github/workflows/staging.yml` (android/CONTRACTS, `android-staging-channel`).
const STAGING: &str = "staging/";

async fn manifest(host: &ServerHost) -> Option<Value> {
    manifest_in(host, "").await
}

/// The update manifest of the channel whose files start with `channel` (`""` or [`STAGING`]),
/// with its `apkUrl` pointing at this channel's binary download.
async fn manifest_in(host: &ServerHost, channel: &str) -> Option<Value> {
    let mut value = metadata(host, &format!("{channel}latest.json")).await?;
    if !valid_release(&value)
        || host
            .android_file(&format!("{channel}pulls.apk"))
            .await
            .ok()??
            .size
            == 0
    {
        return None;
    }
    let mut url = url::Url::parse(value["apkUrl"].as_str()?).ok()?;
    if !matches!(url.scheme(), "https" | "http")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return None;
    }
    let binary = format!("/download/{channel}pully.apk");
    let prefix = if channel.is_empty() {
        url.path().strip_suffix("/android/pulls.apk")
    } else {
        None
    }
    .or_else(|| url.path().strip_suffix(binary.as_str()))?;
    let path = format!("{prefix}{binary}");
    url.set_path(&path);
    url.set_query(None);
    url.set_fragment(None);
    value["apkUrl"] = Value::String(url.into());
    Some(value)
}

/// The staging server's origin, from `PULLS_REVIEW_STAGING_URL`, when this server does not serve
/// the staging app itself.
fn staging_server() -> Option<String> {
    staging_origin(&crate::rt::env::var("PULLS_REVIEW_STAGING_URL").ok()?)
}

/// `value` as a bare HTTPS origin, or `None` for anything else.
fn staging_origin(value: &str) -> Option<String> {
    let url = url::Url::parse(value.trim()).ok()?;
    (url.scheme() == "https"
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.path() == "/"
        && url.query().is_none())
    .then(|| url.origin().ascii_serialization())
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

async fn landing(host: &ServerHost) -> Response {
    let latest = manifest(host).await;
    let mut html = String::from("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Download Pully</title></head><body><main><h1>Download Pully</h1><h2>Android</h2>");
    if let Some(value) = &latest {
        html.push_str(&format!("<p><a href=\"/download/pully.apk\" download>Download Pully {} for Android</a></p><p>Latest published build · version code {}</p><details><summary>Verify this download</summary><p>SHA-256: <code>{}</code></p></details>",
            escape(value["versionName"].as_str().unwrap()), value["versionCode"], escape(value["sha256"].as_str().unwrap())));
    } else {
        html.push_str("<p>No Android build is available yet.</p>");
    }
    let mut versions = metadata(host, "past/index.json")
        .await
        .and_then(|v| v["versions"].as_array().cloned())
        .unwrap_or_default();
    versions.sort_by_key(|v| std::cmp::Reverse(v["versionCode"].as_u64().unwrap_or(0)));
    let mut links = Vec::new();
    let mut names = std::collections::HashSet::new();
    for value in versions.into_iter().take(256) {
        if !valid_release(&value) {
            continue;
        }
        let name = value["versionName"].as_str().unwrap();
        let relative = format!("past/{name}.apk");
        if !history_asset(&relative)
            || latest.as_ref().is_some_and(|current| {
                value["versionCode"].as_u64() >= current["versionCode"].as_u64()
            })
            || value["packageName"].as_str()
                != Some(format!("com.octaviantocan.pulls.v{}", name.replace('.', "_")).as_str())
        {
            continue;
        }
        let Some(file) = host.android_file(&relative).await.ok().flatten() else {
            continue;
        };
        if file.size == 0
            || value["size"].as_u64() != Some(file.size)
            || !names.insert(name.to_owned())
        {
            continue;
        }
        links.push(format!(
            "<li><a href=\"/download/{relative}\" download>Pully {}</a></li>",
            escape(name)
        ));
    }
    if let Some(value) = manifest_in(host, STAGING).await {
        html.push_str(&format!("<h2>Pully Staging</h2><p><a href=\"/download/staging/pully.apk\" download>Download Pully Staging {}</a></p><p>Built from main after every merge. It installs beside Pully as its own app, starts on the staging server and updates itself from there.</p>",
            escape(value["versionName"].as_str().unwrap())));
    } else if let Some(url) = staging_server() {
        html.push_str(&format!("<h2>Pully Staging</h2><p><a href=\"{}/download\">Pully Staging is on the staging server</a>. It is built from main after every merge and installs beside Pully as its own app.</p>",
            escape(&url)));
    }
    if !links.is_empty() {
        html.push_str("<h2>Available past versions</h2><p>These versions install beside Pully. Open them from Pully’s Settings → Advanced → Past versions; they do not have their own launcher icon.</p><ul>");
        html.push_str(&links.concat());
        html.push_str("</ul>");
    }
    html.push_str("</main></body></html>");
    let mut response = ([(header::CACHE_CONTROL, "no-store")], Html(html)).into_response();
    response.headers_mut().insert(
        header::CONTENT_SECURITY_POLICY,
        header::HeaderValue::from_static(
            "default-src 'none'; base-uri 'none'; frame-ancestors 'none'",
        ),
    );
    response
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        host::ServerHost,
        store::Store,
        transport::{router, HttpState},
        AppState,
    };
    use serde_json::json;
    use sha2::{Digest, Sha256};
    use std::path::PathBuf;

    struct Server {
        root: PathBuf,
        artifacts: PathBuf,
        url: String,
        task: crate::rt::JoinHandle<()>,
        client: reqwest::Client,
    }
    impl Server {
        async fn new() -> Self {
            let root =
                std::env::temp_dir().join(format!("pully-download-{}", uuid::Uuid::new_v4()));
            let artifacts = root.join("artifacts");
            let assets = root.join("web");
            std::fs::create_dir_all(&artifacts).unwrap();
            std::fs::create_dir_all(&assets).unwrap();
            std::fs::write(
                assets.join("index.html"),
                "SPA fallback must not become a download",
            )
            .unwrap();
            let host = ServerHost::new(
                Store::new(root.join("data")).unwrap(),
                None,
                Some(artifacts.clone()),
            );
            let app = AppState::from_host(host).unwrap();
            app.account_seed.set(true).unwrap();
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let port = listener.local_addr().unwrap().port();
            let state = HttpState::new(app, "127.0.0.1", port, vec![], true, Some(assets)).unwrap();
            let task = crate::rt::spawn(async move {
                axum::serve(listener, router(state)).await.unwrap();
            });
            let client = reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .no_proxy()
                .timeout(std::time::Duration::from_secs(5))
                .build()
                .unwrap();
            Self {
                root,
                artifacts,
                url: format!("http://127.0.0.1:{port}"),
                task,
                client,
            }
        }
        async fn get(&self, path: &str) -> reqwest::Response {
            self.client
                .get(format!("{}{path}", self.url))
                .header("Accept", "text/html")
                .send()
                .await
                .unwrap()
        }
        fn publish(&self) -> String {
            let bytes = b"actual fixture APK bytes";
            let sha = hex::encode(Sha256::digest(bytes));
            std::fs::write(self.artifacts.join("pulls.apk"), bytes).unwrap();
            std::fs::write(self.artifacts.join("latest.json"), json!({"versionCode":76,"versionName":"0.59.0","sha256":sha,"apkUrl":format!("{}/android/pulls.apk",self.url),"sourceCommit":"fixture"}).to_string()).unwrap();
            sha
        }
    }
    impl Drop for Server {
        fn drop(&mut self) {
            self.task.abort();
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    #[tokio::test]
    async fn the_staging_app_has_its_own_channel_beside_the_stable_one() {
        let server = Server::new().await;
        server.publish();
        assert_eq!(
            server.get("/download/staging/latest.json").await.status(),
            StatusCode::NOT_FOUND
        );
        assert!(!server
            .get("/download")
            .await
            .text()
            .await
            .unwrap()
            .contains("Staging"));
        let staging = server.artifacts.join("staging");
        std::fs::create_dir_all(&staging).unwrap();
        let bytes = b"staging fixture APK bytes";
        let sha = hex::encode(Sha256::digest(bytes));
        std::fs::write(staging.join("pulls.apk"), bytes).unwrap();
        std::fs::write(staging.join("latest.json"), json!({"versionCode":812,"versionName":"0.62.0-staging.812","sha256":sha,"apkUrl":"https://staging.example/download/staging/pully.apk?x=1","sourceCommit":"fixture"}).to_string()).unwrap();
        let manifest: Value = server
            .get("/download/staging/latest.json")
            .await
            .json()
            .await
            .unwrap();
        assert_eq!(
            manifest["apkUrl"],
            "https://staging.example/download/staging/pully.apk"
        );
        assert_eq!(manifest["versionCode"], 812);
        let apk = server.get("/download/staging/pully.apk").await;
        assert_eq!(apk.status(), StatusCode::OK);
        assert_eq!(&apk.bytes().await.unwrap()[..], bytes);
        let stable: Value = server
            .get("/download/latest.json")
            .await
            .json()
            .await
            .unwrap();
        assert_eq!(stable["versionCode"], 76);
        let page = server.get("/download").await.text().await.unwrap();
        assert!(page.contains("href=\"/download/staging/pully.apk\""));
        assert!(page.contains("Pully Staging 0.62.0-staging.812"));
        // A stable apkUrl never stands in for the staging binary.
        std::fs::write(staging.join("latest.json"), json!({"versionCode":813,"versionName":"0.62.0-staging.813","sha256":sha,"apkUrl":"https://staging.example/download/pully.apk"}).to_string()).unwrap();
        assert_eq!(
            server.get("/download/staging/latest.json").await.status(),
            StatusCode::NOT_FOUND
        );
    }

    #[test]
    fn the_staging_link_takes_only_a_bare_https_origin() {
        assert_eq!(
            staging_origin(" https://pully-staging.example/ ").as_deref(),
            Some("https://pully-staging.example")
        );
        for refused in [
            "http://pully-staging.example",
            "https://user@pully-staging.example",
            "https://pully-staging.example/path",
            "https://pully-staging.example/?q=1",
            "javascript:alert(1)",
        ] {
            assert_eq!(staging_origin(refused), None, "{refused}");
        }
    }

    #[tokio::test]
    async fn download_page_is_html_and_does_not_invent_unpublished_versions_or_ios() {
        let server = Server::new().await;
        let page = server.get("/download").await;
        assert_eq!(page.status(), StatusCode::OK);
        assert_eq!(
            page.headers()[header::CONTENT_TYPE],
            "text/html; charset=utf-8"
        );
        assert_eq!(page.headers()[header::CACHE_CONTROL], "no-store");
        assert!(!page.headers().contains_key(header::LOCATION));
        let text = page.text().await.unwrap();
        assert!(text.contains("No Android build is available yet."));
        assert!(!text.contains("href="));
        assert!(!text.contains("iOS"));
        assert_eq!(
            server.get("/download/latest.json").await.status(),
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            server.get("/download/pully.apk").await.status(),
            StatusCode::NOT_FOUND
        );
    }

    #[tokio::test]
    async fn both_manifests_name_real_new_namespace_bytes_and_the_old_apk_never_redirects() {
        let server = Server::new().await;
        let sha = server.publish();
        for path in ["/download/latest.json", "/android/latest.json"] {
            let response = server.get(path).await;
            assert_eq!(response.status(), StatusCode::OK);
            assert_eq!(response.headers()[header::CACHE_CONTROL], "no-store");
            let manifest: Value = response.json().await.unwrap();
            assert_eq!(
                manifest["apkUrl"],
                format!("{}/download/pully.apk", server.url)
            );
            assert_eq!(manifest["sha256"], sha);
            let apk = server
                .client
                .get(manifest["apkUrl"].as_str().unwrap())
                .send()
                .await
                .unwrap();
            assert_eq!(
                apk.headers()[header::CONTENT_TYPE],
                "application/vnd.android.package-archive"
            );
            assert_eq!(hex::encode(Sha256::digest(apk.bytes().await.unwrap())), sha);
        }
        let old = server.get("/android/pulls.apk").await;
        assert_eq!(old.status(), StatusCode::NOT_FOUND);
        assert!(!old.headers().contains_key(header::LOCATION));
        assert!(!old.text().await.unwrap().contains("SPA fallback"));
        for path in [
            "/download/pulls.apk",
            "/download/private.txt",
            "/download/past/build.log",
        ] {
            assert_eq!(server.get(path).await.status(), StatusCode::NOT_FOUND);
        }
    }

    #[tokio::test]
    async fn landing_links_only_present_indexed_history_and_explains_its_real_launch_behavior() {
        let server = Server::new().await;
        server.publish();
        let past = server.artifacts.join("past");
        std::fs::create_dir_all(&past).unwrap();
        let item = |name: &str, code| json!({"versionName":name,"versionCode":code,"packageName":format!("com.octaviantocan.pulls.v{}",name.replace('.', "_")),"sha256":"a".repeat(64),"size":3});
        std::fs::write(past.join("0.58.0.apk"), b"apk").unwrap();
        std::fs::write(past.join("0.56.0.apk"), b"orphan").unwrap();
        std::fs::write(
            past.join("index.json"),
            json!({"versions":[item("0.58.0",75),item("0.57.0",74),item("../secret",73)]})
                .to_string(),
        )
        .unwrap();
        let html = server.get("/download").await.text().await.unwrap();
        assert!(html.contains("href=\"/download/pully.apk\""));
        assert!(html.contains("href=\"/download/past/0.58.0.apk\""));
        assert!(html.contains("install beside Pully"));
        assert!(html.contains("do not have their own launcher icon"));
        assert!(!html.contains("0.57.0"));
        assert!(!html.contains("0.56.0"));
        assert!(!html.contains("../secret"));
        assert!(!html.contains("/android/"));
        assert_eq!(
            server.get("/download/past/0.58.0.apk").await.status(),
            StatusCode::OK
        );
        std::fs::remove_file(past.join("0.58.0.apk")).unwrap();
        assert!(!server
            .get("/download")
            .await
            .text()
            .await
            .unwrap()
            .contains("0.58.0"));
    }

    #[tokio::test]
    async fn unsafe_metadata_is_escaped_and_a_manifest_cannot_name_the_html_installer() {
        let server = Server::new().await;
        server.publish();
        let path = server.artifacts.join("latest.json");
        let mut value: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        value["versionName"] = json!("0.59.0 <script>unsafe</script>");
        std::fs::write(&path, value.to_string()).unwrap();
        let html = server.get("/download").await.text().await.unwrap();
        assert!(html.contains("&lt;script&gt;unsafe&lt;/script&gt;"));
        assert!(!html.contains("<script>"));
        value["apkUrl"] = json!(format!("{}/download", server.url));
        std::fs::write(&path, value.to_string()).unwrap();
        assert_eq!(
            server.get("/download/latest.json").await.status(),
            StatusCode::NOT_FOUND
        );
        assert!(!server
            .get("/download")
            .await
            .text()
            .await
            .unwrap()
            .contains("href=\"/download/pully.apk\""));
        std::fs::write(&path, b"invalid json").unwrap();
        assert_eq!(
            server.get("/download/latest.json").await.status(),
            StatusCode::NOT_FOUND
        );
        server.publish();
        std::fs::remove_file(server.artifacts.join("pulls.apk")).unwrap();
        assert_eq!(
            server.get("/android/latest.json").await.status(),
            StatusCode::NOT_FOUND
        );
    }
}
