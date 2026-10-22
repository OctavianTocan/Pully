//! The actual executable must select public startup before any legacy initialization.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader},
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::mpsc,
    time::Duration,
};

struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("pully-public-cli-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn command(&self) -> Command {
        let mut command = Command::new(env!("CARGO_BIN_EXE_pulls-review-server"));
        for name in [
            "PULLY_PUBLIC_LISTEN",
            "PULLY_PUBLIC_DATA_DIR",
            "PULLY_PERSONAL_OAUTH_CONFIG_FILE",
            "PULLY_PERSONAL_VAULT_KEYS_FILE",
        ] {
            command.env_remove(name);
        }
        command
            .arg("--cwd")
            .arg(self.0.join("this-checkout-does-not-exist"));
        command
            .env(
                "PULLS_REVIEW_DATA_DIR",
                self.0.join("legacy-must-not-be-opened"),
            )
            .env(
                "PULLS_REVIEW_GITHUB_TOKEN",
                "fixture-poisoned-operator-token",
            )
            .env("GITHUB_TOKEN", "fixture-poisoned-operator-token")
            .env("GH_TOKEN", "fixture-poisoned-operator-token")
            .env("PULLY_ACCOUNT_EMAIL", "fixture-legacy@example.invalid")
            .env("PULLY_ACCOUNT_PASSWORD", "fixture-legacy-password")
            .env(
                "PULLS_REVIEW_PI_BASE_URL",
                "http://127.0.0.1:1/operator-only",
            )
            .env("PULLS_REVIEW_PI_API_KEY", "fixture-poisoned-provider-key")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped());
        command
    }
    fn unchanged(&self) {
        assert!(!self.0.join("legacy-must-not-be-opened").exists());
        assert!(!self.0.join("this-checkout-does-not-exist").exists());
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
struct Process {
    child: Child,
    origin: String,
}
impl Process {
    fn start(command: &mut Command) -> Self {
        let mut child = command.spawn().unwrap();
        let stderr = child.stderr.take().unwrap();
        let (send, receive) = mpsc::sync_channel(1);
        std::thread::spawn(move || {
            for line in BufReader::new(stderr).lines().take(20) {
                let line = line.unwrap_or_default();
                if let Some(origin) = line.strip_prefix("Pully public API: ") {
                    let _ = send.send(origin.to_owned());
                    return;
                }
            }
        });
        let origin = match receive.recv_timeout(Duration::from_secs(10)) {
            Ok(origin) => origin,
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                panic!("public executable did not start independently of poisoned legacy inputs");
            }
        };
        assert!(origin.starts_with("http://127.0.0.1:"));
        Self { child, origin }
    }
}
impl Drop for Process {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(5))
        .build()
        .unwrap()
}
#[cfg(unix)]
fn private(path: &std::path::Path, value: Value) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::write(path, serde_json::to_vec(&value).unwrap()).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600)).unwrap();
}

#[tokio::test]
async fn actual_unconfigured_cli_ignores_poisoned_legacy_cwd_data_and_credentials() {
    let root = Root::new();
    let process = Process::start(root.command().arg("--public-listen").arg("127.0.0.1:0"));
    let client = client();
    let response = client
        .get(format!("{}/api/personal/v1/capabilities", process.origin))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(
        response.json::<Value>().await.unwrap(),
        json!({"github":{"enabled":false}})
    );
    let public = client
        .get(format!("{}/api/public/v1/capabilities", process.origin))
        .send()
        .await
        .unwrap()
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(public["publicBrowsing"], true);
    assert_eq!(public["githubSignIn"], "notConfigured");
    for path in [
        "/api/rpc",
        "/android/latest.json",
        "/api/v1/feedback",
        "/api/personal/v1/github/repositories",
    ] {
        assert_eq!(
            client
                .get(format!("{}{path}", process.origin))
                .send()
                .await
                .unwrap()
                .status(),
            404
        );
    }
    root.unchanged();
}

#[cfg(unix)]
#[tokio::test]
async fn actual_configured_cli_uses_only_explicit_private_files_and_typed_routes() {
    let root = Root::new();
    let oauth = root.0.join("oauth.json");
    let keys = root.0.join("keys.json");
    let data = root.0.join("personal-isolated");
    private(
        &oauth,
        json!({"clientId":"fixture-pully-cli","clientSecret":"fixture-cli-secret","callback":"https://pully.example/api/personal/v1/auth/github/callback"}),
    );
    private(
        &keys,
        json!({"activeVersion":1,"keys":{"1":URL_SAFE_NO_PAD.encode([79;32])}}),
    );
    let mut command = root.command();
    command
        .env("PULLY_PUBLIC_LISTEN", "127.0.0.1:0")
        .arg("--public-data-dir")
        .arg(&data)
        .arg("--personal-oauth-config")
        .arg(&oauth)
        .arg("--personal-vault-keys")
        .arg(&keys);
    let process = Process::start(&mut command);
    let client = client();
    let capabilities = client
        .get(format!("{}/api/personal/v1/capabilities", process.origin))
        .send()
        .await
        .unwrap()
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(capabilities, json!({"github":{"enabled":true}}));
    assert_eq!(
        client
            .get(format!(
                "{}/api/personal/v1/github/repositories",
                process.origin
            ))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    assert!(data.join("personal-auth").is_dir());
    assert!(data.join("personal-tenants").is_dir());
    root.unchanged();
}

#[tokio::test]
async fn partial_personal_configuration_and_private_work_flags_are_rejected_before_binding() {
    let root = Root::new();
    for extra in [
        vec!["--personal-oauth-config", "/fixture/not-to-be-read"],
        vec!["--preanalyze"],
        vec!["--no-auth"],
        vec!["--mcp-context", "/fixture/not-to-be-read"],
    ] {
        let mut command = root.command();
        command
            .arg("--public-listen")
            .arg("127.0.0.1:0")
            .args(extra);
        let mut child = command.spawn().unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        loop {
            if let Some(status) = child.try_wait().unwrap() {
                assert!(!status.success());
                break;
            }
            if std::time::Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                panic!("conflicting public configuration was not rejected");
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        root.unchanged();
    }
}
