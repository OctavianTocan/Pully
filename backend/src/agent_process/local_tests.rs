//! The hosted agent container's plain-process runtime, run in a private child process so the
//! process-wide runtime switch never reaches other tests.
use super::*;

const FAKE_CODEX: &str = r#"#!/usr/bin/python3
import os,sys,json,pathlib
env=dict(os.environ)
assert 'PULLS_TEST_LOCAL_DIR' not in env, 'the server environment reached the CLI'
home=pathlib.Path(env.get('CODEX_HOME') or pathlib.Path(env['HOME'],'.codex'))
if sys.argv[1:2]==['login'] and sys.argv[2:3]==['status']:
    print('Logged in using ChatGPT'); sys.exit(0)
if sys.argv[1:3]==['login','--device-auth']:
    pathlib.Path(env['HOME'],'login.pid').write_text(str(os.getpid()))
    print('https://auth.openai.com/codex/device\n\nEnter this one-time code\n   ABCD-EFGH', flush=True)
    sys.stdin.read(); sys.exit(0)
if '@UID@':
    assert os.getuid()==int('@UID@'), 'the job ran as the runner'
    try: os.listdir('@HOMES@'); raise SystemExit('the job could read the login homes')
    except PermissionError: pass
auth=json.loads((home/'auth.json').read_text())
assert auth['tokens']['refresh_token']=='synthetic-refresh', auth
assert pathlib.Path(env['HOME'])==home.parent and pathlib.Path.cwd()==home.parent.parent
(home/'auth.json').write_text(json.dumps({**auth,'tokens':{'access_token':'a2','refresh_token':'rotated'}}))
prompt=sys.stdin.read()
print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'echo: '+prompt.strip()}}))
print(json.dumps({'type':'turn.completed'}))
"#;

/// The user jobs run as in these tests: `nobody` when the tests run as root, as in the agent
/// container, or none when they cannot switch users.
#[cfg(unix)]
fn test_cli_user() -> Option<(u32, u32)> {
    use std::os::unix::fs::MetadataExt;
    (std::fs::metadata("/proc/self").ok()?.uid() == 0).then_some((65534, 65534))
}

/// Writes the fake CLIs into `bin`, with `homes` the folder jobs must not read.
#[cfg(unix)]
fn write_fake_clis(bin: &Path, homes: &Path) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::create_dir_all(bin).unwrap();
    std::fs::create_dir_all(homes).unwrap();
    std::fs::set_permissions(homes, std::fs::Permissions::from_mode(0o700)).unwrap();
    let uid = test_cli_user()
        .map(|(uid, _)| uid.to_string())
        .unwrap_or_default();
    let codex = FAKE_CODEX
        .replace("@UID@", &uid)
        .replace("@HOMES@", homes.to_str().unwrap());
    for (name, body) in [
        ("codex", codex.as_str()),
        ("pulls-review-agent-entrypoint", "#!/bin/sh\nexec \"$@\"\n"),
        ("claude", "#!/bin/sh\necho '{\"loggedIn\":false}'\n"),
    ] {
        std::fs::write(bin.join(name), body).unwrap();
        std::fs::set_permissions(bin.join(name), std::fs::Permissions::from_mode(0o755)).unwrap();
    }
}

#[test]
#[cfg(unix)]
fn local_runtime_runs_signs_in_and_keeps_rotated_logins() {
    let root = std::env::temp_dir().join(format!("pulls-local-runtime-{}", Uuid::new_v4()));
    let bin = root.join("bin");
    write_fake_clis(&bin, &root.join("homes"));
    let volume = AgentLogin::Account("local-account".into()).volume();
    let codex = root.join("homes").join(&volume).join(".codex");
    std::fs::create_dir_all(&codex).unwrap();
    std::fs::write(
        codex.join("auth.json"),
        r#"{"auth_mode":"chatgpt","tokens":{"access_token":"a1","refresh_token":"synthetic-refresh"},"OPENAI_API_KEY":"never-copied"}"#,
    )
    .unwrap();
    let path = format!("{}:{}", bin.display(), std::env::var("PATH").unwrap());
    let status = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "agent_process::local_tests::local_runtime_child",
            "--nocapture",
        ])
        .env("PULLS_TEST_LOCAL_DIR", &root)
        .env("PULLS_TEST_LOCAL_PATH", &path)
        .env("TMPDIR", &root)
        .status()
        .unwrap();
    assert!(status.success());
    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(codex.join("auth.json")).unwrap()).unwrap();
    assert_eq!(saved["tokens"]["refresh_token"], "rotated");
    assert!(saved.get("OPENAI_API_KEY").is_none());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn local_runtime_child() {
    let Some(dir) = std::env::var_os("PULLS_TEST_LOCAL_DIR").map(PathBuf::from) else {
        return;
    };
    use_local_runtime(dir.join("homes"), test_cli_user()).unwrap();
    let login = AgentLogin::Account("local-account".into());
    let (_cancel, receiver) = watch::channel(false);
    let (answer, _) = run_agent_prepared(
        json!({"engine":"codex","prompt":"offline fixture"}),
        &login,
        receiver,
        Arc::new(|_| {}),
        crate::ai::StructuredAnswerPreparation::Strict,
    )
    .await
    .unwrap();
    assert_eq!(answer, "echo: offline fixture");
    let status = crate::agent_login::status(&login).await.unwrap();
    assert_eq!(status["codex"]["signedIn"], true);
    let logins = crate::agent_login::Logins::default();
    let started = logins.start(&login, "codex").await.unwrap();
    assert_eq!(started["code"], "ABCD-EFGH");
    let home = dir.join("homes").join(login.volume());
    let pid: u32 = std::fs::read_to_string(home.join("login.pid"))
        .unwrap()
        .parse()
        .unwrap();
    logins.cancel(&login, "codex").await;
    crate::rt::timeout(Duration::from_secs(5), async {
        while std::path::Path::new(&format!("/proc/{pid}")).exists()
            && !std::fs::read_to_string(format!("/proc/{pid}/stat"))
                .unwrap_or_default()
                .contains(") Z ")
        {
            crate::rt::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("a cancelled sign-in stops its process");
    let other = AgentLogin::Account("someone-else".into());
    assert_eq!(
        crate::agent_login::status(&other).await.unwrap()["codex"]["signedIn"],
        false
    );
}

/// The Worker's path: AI calls go through `agent_remote` to a runner in another process, which
/// runs them with the local runtime.
#[test]
#[cfg(unix)]
fn remote_calls_reach_the_runner_of_their_login_only() {
    let root = std::env::temp_dir().join(format!("pulls-agent-runner-{}", Uuid::new_v4()));
    let bin = root.join("bin");
    write_fake_clis(&bin, &root.join("homes"));
    let login = AgentLogin::Account("remote-account".into());
    let codex = root.join("homes").join(login.volume()).join(".codex");
    std::fs::create_dir_all(&codex).unwrap();
    std::fs::write(
        codex.join("auth.json"),
        r#"{"auth_mode":"chatgpt","tokens":{"access_token":"a1","refresh_token":"synthetic-refresh"}}"#,
    )
    .unwrap();
    let path = format!("{}:{}", bin.display(), std::env::var("PATH").unwrap());
    let child = |test: &str| {
        std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", test, "--nocapture"])
            .env("PULLS_TEST_RUNNER_DIR", &root)
            .env("PULLS_TEST_LOCAL_PATH", &path)
            .env("TMPDIR", &root)
            .spawn()
            .unwrap()
    };
    let mut runner = child("agent_process::local_tests::runner_child");
    let status = child("agent_process::local_tests::remote_child")
        .wait()
        .unwrap();
    let _ = runner.kill();
    let _ = runner.wait();
    assert!(status.success());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn runner_child() {
    let Some(dir) = std::env::var_os("PULLS_TEST_RUNNER_DIR").map(PathBuf::from) else {
        return;
    };
    use_local_runtime(dir.join("homes"), test_cli_user()).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    std::fs::write(
        dir.join("port"),
        listener.local_addr().unwrap().port().to_string(),
    )
    .unwrap();
    let volume = AgentLogin::Account("remote-account".into()).volume();
    axum::serve(listener, crate::agent_runner::router(volume))
        .await
        .unwrap();
}

struct HttpRemote {
    base: String,
}
impl crate::agent_remote::AgentRemote for HttpRemote {
    fn post(
        &self,
        _: &AgentLogin,
        path: &'static str,
        body: Vec<u8>,
    ) -> futures_util::future::BoxFuture<'static, Result<crate::agent_remote::Body>> {
        let url = format!("{}{path}", self.base);
        Box::pin(async move {
            use futures_util::StreamExt;
            let response = reqwest::Client::new()
                .post(url)
                .body(body)
                .header("content-type", "application/json")
                .send()
                .await?;
            let body = response
                .bytes_stream()
                .map(|chunk| chunk.map(|b| b.to_vec()).map_err(std::io::Error::other));
            Ok(Box::pin(body) as crate::agent_remote::Body)
        })
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn remote_child() {
    let Some(dir) = std::env::var_os("PULLS_TEST_RUNNER_DIR").map(PathBuf::from) else {
        return;
    };
    let port = crate::rt::timeout(Duration::from_secs(20), async {
        loop {
            if let Ok(port) = std::fs::read_to_string(dir.join("port")) {
                return port;
            }
            crate::rt::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    crate::agent_remote::set(Arc::new(HttpRemote {
        base: format!("http://127.0.0.1:{port}"),
    }))
    .unwrap();
    let login = AgentLogin::Account("remote-account".into());
    let events = Arc::new(std::sync::Mutex::new(Vec::new()));
    let seen = events.clone();
    let (_cancel, receiver) = watch::channel(false);
    let (answer, _) = run_agent_prepared(
        json!({"engine":"codex","prompt":"over the wire"}),
        &login,
        receiver,
        Arc::new(move |event| seen.lock().unwrap().push(event)),
        crate::ai::StructuredAnswerPreparation::Strict,
    )
    .await
    .unwrap();
    assert_eq!(answer, "echo: over the wire");
    assert!(!events.lock().unwrap().is_empty());
    let status = crate::agent_login::status(&login).await.unwrap();
    assert_eq!(status["codex"]["signedIn"], true);
    let logins = crate::agent_login::Logins::default();
    let started = logins.start(&login, "codex").await.unwrap();
    assert_eq!(started["url"], "https://auth.openai.com/codex/device");
    logins.cancel(&login, "codex").await;
    let base = format!("http://127.0.0.1:{port}");
    let client = reqwest::Client::new();
    let files: Value = client
        .get(format!("{base}/login-files"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let codex = &files[".codex/auth.json"];
    assert_eq!(codex["tokens"]["refresh_token"], "rotated");
    assert_eq!(
        files.as_object().unwrap().len(),
        1,
        "only sign-in files are kept: {files}"
    );
    let home = dir.join("homes").join(login.volume());
    std::fs::remove_dir_all(&home).unwrap();
    let put = |body: Value| {
        let request = client.put(format!("{base}/login-files")).json(&body);
        async move { request.send().await.unwrap().json::<Value>().await.unwrap() }
    };
    let mut extra = codex.clone();
    extra["OPENAI_API_KEY"] = json!("never-kept");
    assert_eq!(
        put(json!({".codex/auth.json": extra})).await,
        json!({"ok": true})
    );
    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(home.join(".codex/auth.json")).unwrap())
            .unwrap();
    assert_eq!(saved, *codex);
    assert!(put(json!({"../escape.json": codex})).await["error"].is_string());
    assert!(!dir.join("homes/escape.json").exists());
    let other = AgentLogin::Account("someone-else".into());
    let refused = crate::agent_login::status(&other).await.unwrap_err();
    assert!(refused.to_string().contains("another login"), "{refused}");
}
