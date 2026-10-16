//! Offline lifecycle fixtures run in private child processes, never the parent's PATH.
use super::*;

#[test]
#[cfg(unix)]
fn fake_docker_cancellation_removes_late_containers() {
    use std::os::unix::fs::PermissionsExt;
    let root = std::env::temp_dir().join(format!("pulls-fake-docker-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&root).unwrap();
    let mut children = Vec::new();
    for phase in ["create", "start"] {
        let dir = root.join(phase);
        std::fs::create_dir_all(&dir).unwrap();
        let executable = dir.join("docker");
        std::fs::write(
            &executable,
            r#"#!/usr/bin/python3
import os,sys,json,time,pathlib
root=pathlib.Path(os.environ['PULLS_TEST_DOCKER_DIR']); args=sys.argv[1:]
with (root/'calls').open('a') as f: f.write(json.dumps(args)+'\n')
if args[0]=='rm':
 name=args[-1]; countfile=root/(name+'.count')
 count=int(countfile.read_text())+1 if countfile.exists() else 1
 countfile.write_text(str(count)); marker=root/(name+'.container')
 if marker.exists(): marker.unlink()
 # Simulate a daemon creating the container after the killed client and first removals.
 if count==2: marker.write_text('late daemon creation')
 sys.exit(0)
phase=os.environ['PULLS_TEST_DOCKER_PHASE']
if args[0]==phase:
 (root/'ready').write_text('ready')
 while True: time.sleep(.05)
if args[0]=='create': print(args[args.index('--name')+1])
"#,
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o755)).unwrap();
        let child = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "agent_process::lifecycle_tests::fake_docker_child",
                "--nocapture",
            ])
            .env_clear()
            .env("PATH", format!("{}:/usr/bin:/bin", dir.display()))
            .env("HOME", &dir)
            .env("TMPDIR", &dir)
            .env("PULLS_TEST_DOCKER_DIR", &dir)
            .env("PULLS_TEST_DOCKER_PHASE", phase)
            .env("PULLS_REVIEW_AGENT_RUNTIME", "docker")
            .env("PULLS_REVIEW_AGENT_IMAGE", "offline-fixture")
            .env("OPENAI_API_KEY", "synthetic-test-credential")
            .spawn()
            .unwrap();
        children.push(child);
    }
    let deadline = std::time::Instant::now() + Duration::from_secs(25);
    for child in &mut children {
        loop {
            if let Some(status) = child.try_wait().unwrap() {
                assert!(status.success());
                break;
            }
            if std::time::Instant::now() >= deadline {
                for child in &mut children {
                    let _ = child.kill();
                    let _ = child.wait();
                }
                panic!("bounded fake-Docker child timed out");
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn fake_docker_child() {
    let Some(dir) = std::env::var_os("PULLS_TEST_DOCKER_DIR").map(PathBuf::from) else {
        return;
    };
    let (cancel, receiver) = watch::channel(false);
    let task = tokio::spawn(run_agent_prepared(
        json!({"engine":"codex","prompt":"offline fixture"}),
        receiver,
        Arc::new(|_| {}),
        crate::ai::StructuredAnswerPreparation::Strict,
    ));
    tokio::time::timeout(Duration::from_secs(5), async {
        while !dir.join("ready").exists() {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    let calls: Vec<Value> = std::fs::read_to_string(dir.join("calls"))
        .unwrap()
        .lines()
        .map(|s| serde_json::from_str(s).unwrap())
        .collect();
    let create = calls
        .iter()
        .find(|v| v[0] == "create")
        .unwrap()
        .as_array()
        .unwrap();
    let index = create.iter().position(|v| v == "--name").unwrap();
    let name = create[index + 1].as_str().unwrap().to_owned();
    let job = dir.join(&name);
    assert!(job.join("work").is_dir());
    cancel.send(true).unwrap();
    let error = tokio::time::timeout(Duration::from_secs(3), task)
        .await
        .unwrap()
        .unwrap()
        .unwrap_err();
    assert!(error.to_string().contains("cancelled"));
    tokio::time::timeout(Duration::from_secs(15), shutdown_cleanup())
        .await
        .unwrap();
    for name in [&name, &format!("{name}-login")] {
        let count: usize = std::fs::read_to_string(dir.join(format!("{name}.count")))
            .unwrap()
            .parse()
            .unwrap();
        assert!(
            count >= 4,
            "named removal must retry after late daemon creation"
        );
        assert!(!dir.join(format!("{name}.container")).exists());
    }
    assert!(!job.exists(), "shutdown must drain job-directory cleanup");
}
