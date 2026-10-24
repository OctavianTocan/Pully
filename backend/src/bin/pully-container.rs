//! The hosted server's container entry (`docker/server/Dockerfile`): restores the data
//! folder, runs `pulls-review-server` with the arguments it was given, and keeps saving
//! the folder until the server stops.
use anyhow::{Context, Result};
use pulls_review_server::data_mirror::Mirror;
use std::path::PathBuf;
use std::time::Duration;
use tokio::signal::unix::{signal, SignalKind};

#[tokio::main]
async fn main() {
    match run().await {
        Ok(code) => std::process::exit(code),
        Err(error) => {
            eprintln!("{error:#}");
            std::process::exit(1)
        }
    }
}

fn setting(name: &str, fallback: &str) -> String {
    std::env::var(name)
        .ok()
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| fallback.to_owned())
}

async fn run() -> Result<i32> {
    let root = PathBuf::from(setting("PULLY_DATA_ROOT", "/var/lib/pully"));
    let every = Duration::from_secs(
        setting("PULLY_DATA_MIRROR_SECONDS", "2")
            .parse()
            .context("PULLY_DATA_MIRROR_SECONDS must be a number of seconds")?,
    );
    let mut mirror = Mirror::new(&setting("PULLY_DATA_MIRROR", "http://data.pully/"), root)?;
    let restored = mirror
        .restore()
        .await
        .context("Could not restore the server's data, so the server was not started")?;
    eprintln!("Restored {restored} data files");

    let mut server = tokio::process::Command::new(setting("PULLY_SERVER_BINARY", "pulls-review-server"))
        .args(std::env::args_os().skip(1))
        .spawn()
        .context("Could not start pulls-review-server")?;
    let mut terminate = signal(SignalKind::terminate())?;
    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut tick = tokio::time::interval(every);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let code = loop {
        tokio::select! {
            status = server.wait() => break status?.code().unwrap_or(1),
            _ = terminate.recv() => break stop(&mut server).await,
            _ = interrupt.recv() => break stop(&mut server).await,
            _ = tick.tick() => report(mirror.sync().await),
        }
    };
    report(mirror.sync().await);
    Ok(code)
}

/// Asks the server to stop as a signal from the platform would, and kills it if it takes
/// longer than the platform waits.
async fn stop(server: &mut tokio::process::Child) -> i32 {
    if let Some(pid) = server.id() {
        // SAFETY: kill(2) on our own child's pid, which `wait` has not reaped yet.
        unsafe { libc::kill(pid as libc::pid_t, libc::SIGTERM) };
    }
    match tokio::time::timeout(Duration::from_secs(10), server.wait()).await {
        Ok(Ok(_)) => 0,
        _ => {
            let _ = server.kill().await;
            0
        }
    }
}

fn report(pass: Result<pulls_review_server::data_mirror::Pass>) {
    match pass {
        Ok(pass) if pass.failed > 0 => eprintln!(
            "Data mirror saved {} and removed {} files; {} failed and will be tried again",
            pass.saved, pass.removed, pass.failed
        ),
        Ok(_) => {}
        Err(error) => eprintln!("Data mirror pass failed: {error:#}"),
    }
}
