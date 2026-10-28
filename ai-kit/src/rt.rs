//! Tasks and timers for both hosts. The native server re-exports tokio; the Worker build
//! (`packages/worker`) runs on one JavaScript thread, so tasks are `spawn_local` promises and
//! timers are `setTimeout`. Code outside the native-only modules uses these names, never
//! `tokio::spawn`, `tokio::time`, `tokio::fs` or `tokio::process`, so it builds for both.
//! The Worker has no disk and cannot start programs: its `fs` and `process` fail every call,
//! so features that need them fail closed there.

#[cfg(not(target_arch = "wasm32"))]
pub use tokio::{
    runtime::Handle,
    spawn,
    task::{spawn_blocking, yield_now, AbortHandle, JoinError, JoinHandle},
    time::{
        error::Elapsed, interval, sleep, sleep_until, timeout, timeout_at, Instant, Interval,
        MissedTickBehavior,
    },
};

#[cfg(not(target_arch = "wasm32"))]
pub use tokio::{fs, process};

/// The server's environment. Natively it is the process environment; the Worker has none, so
/// it [`env::set`]s its variables and secrets here before handling a request.
#[cfg(not(target_arch = "wasm32"))]
pub mod env {
    pub use std::env::{var, var_os, vars};
}

#[cfg(target_arch = "wasm32")]
pub use wasm::*;

#[cfg(target_arch = "wasm32")]
mod wasm {
    use futures_util::future::{self, AbortHandle as Abort, Abortable, Either};
    use send_wrapper::SendWrapper;
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::task::{Context, Poll};
    use std::time::Duration;
    use wasm_bindgen::JsCast;

    pub use web_time::Instant;

    /// A task that ended without its value: it was aborted.
    #[derive(Debug)]
    pub struct JoinError;
    impl JoinError {
        pub fn is_cancelled(&self) -> bool {
            true
        }
        pub fn is_panic(&self) -> bool {
            false
        }
    }
    impl std::fmt::Display for JoinError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("task was cancelled")
        }
    }
    impl std::error::Error for JoinError {}

    /// Stops a spawned task at its next await.
    #[derive(Clone, Debug)]
    pub struct AbortHandle {
        abort: Abort,
        done: Arc<AtomicBool>,
    }
    impl AbortHandle {
        pub fn abort(&self) {
            self.abort.abort();
        }
        pub fn is_finished(&self) -> bool {
            self.done.load(Ordering::Acquire)
        }
    }

    /// The result of a spawned task, as tokio's `JoinHandle`.
    pub struct JoinHandle<T> {
        result: tokio::sync::oneshot::Receiver<T>,
        handle: AbortHandle,
    }
    impl<T> JoinHandle<T> {
        pub fn abort(&self) {
            self.handle.abort();
        }
        pub fn abort_handle(&self) -> AbortHandle {
            self.handle.clone()
        }
        pub fn is_finished(&self) -> bool {
            self.handle.is_finished()
        }
    }
    impl<T> Future for JoinHandle<T> {
        type Output = Result<T, JoinError>;
        fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
            Pin::new(&mut self.result)
                .poll(cx)
                .map(|result| result.map_err(|_| JoinError))
        }
    }

    /// The runtime of this thread, as tokio's `Handle`. The Worker always has one.
    #[derive(Clone, Debug)]
    pub struct Handle;
    impl Handle {
        pub fn current() -> Self {
            Handle
        }
        pub fn try_current() -> Result<Self, std::convert::Infallible> {
            Ok(Handle)
        }
        pub fn spawn<F>(&self, task: F) -> JoinHandle<F::Output>
        where
            F: Future + 'static,
            F::Output: 'static,
        {
            spawn(task)
        }
    }

    /// Runs `task` in the background on this thread.
    pub fn spawn<F>(task: F) -> JoinHandle<F::Output>
    where
        F: Future + 'static,
        F::Output: 'static,
    {
        let (abort, registration) = Abort::new_pair();
        let done = Arc::new(AtomicBool::new(false));
        let (send, result) = tokio::sync::oneshot::channel();
        let finished = done.clone();
        wasm_bindgen_futures::spawn_local(async move {
            if let Ok(value) = Abortable::new(task, registration).await {
                let _ = send.send(value);
            }
            finished.store(true, Ordering::Release);
        });
        JoinHandle {
            result,
            handle: AbortHandle { abort, done },
        }
    }

    /// @cc [owner:OctavianTocan,label:architecture] wasm-blocking-work-runs-after-its-caller
    /// On the Worker, blocking work runs as a task of its own once the caller yields, as there is
    /// no thread to move it to, and it MUST NOT run inside the call that queues it. Callers hold a
    /// lock while they queue work that takes the same lock, as threads make that safe natively;
    /// here it panics ("cannot recursively acquire mutex") and the whole Worker restarts. Source:
    /// Tavi, 26 Oct 2026, every Ask on staging failed with a 500 and "the server restarted".
    pub fn spawn_blocking<F, R>(work: F) -> JoinHandle<R>
    where
        F: FnOnce() -> R + 'static,
        R: 'static,
    {
        spawn(async move { work() })
    }

    /// Lets other tasks run before continuing.
    pub async fn yield_now() {
        sleep(Duration::ZERO).await
    }

    /// Completes after `duration`, through the runtime's `setTimeout`.
    pub fn sleep(duration: Duration) -> impl Future<Output = ()> + Send {
        let ms = duration.as_millis().min(i32::MAX as u128) as i32;
        SendWrapper::new(async move {
            let promise = js_sys::Promise::new(&mut |resolve, _| {
                let global = js_sys::global();
                if let Ok(set_timeout) = js_sys::Reflect::get(&global, &"setTimeout".into()) {
                    let set_timeout: js_sys::Function = set_timeout.unchecked_into();
                    let _ = set_timeout.call2(&global, &resolve, &ms.into());
                }
            });
            let _ = wasm_bindgen_futures::JsFuture::from(promise).await;
        })
    }

    /// Completes at `deadline`.
    pub fn sleep_until(deadline: Instant) -> impl Future<Output = ()> + Send {
        sleep(deadline.saturating_duration_since(Instant::now()))
    }

    /// The error of a future that did not finish in time.
    #[derive(Debug, PartialEq, Eq)]
    pub struct Elapsed(());
    impl std::fmt::Display for Elapsed {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("deadline has elapsed")
        }
    }
    impl std::error::Error for Elapsed {}

    /// Runs `task` for at most `duration`.
    pub async fn timeout<F: Future>(duration: Duration, task: F) -> Result<F::Output, Elapsed> {
        let task = std::pin::pin!(task);
        let limit = std::pin::pin!(sleep(duration));
        match future::select(task, limit).await {
            Either::Left((value, _)) => Ok(value),
            Either::Right(_) => Err(Elapsed(())),
        }
    }

    /// Runs `task` until `deadline` at the latest.
    pub async fn timeout_at<F: Future>(deadline: Instant, task: F) -> Result<F::Output, Elapsed> {
        timeout(deadline.saturating_duration_since(Instant::now()), task).await
    }

    /// What an interval does after a late tick; the Worker build always delays.
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum MissedTickBehavior {
        Burst,
        Delay,
        Skip,
    }

    /// Ticks every `period`, the first tick at once.
    pub struct Interval {
        period: Duration,
        next: Instant,
    }
    impl Interval {
        pub async fn tick(&mut self) -> Instant {
            sleep_until(self.next).await;
            let now = Instant::now();
            self.next = now + self.period;
            now
        }
        pub fn set_missed_tick_behavior(&mut self, _: MissedTickBehavior) {}
        pub fn reset(&mut self) {
            self.next = Instant::now() + self.period;
        }
    }

    pub fn interval(period: Duration) -> Interval {
        Interval {
            period,
            next: Instant::now(),
        }
    }

    pub mod env {
        use std::collections::BTreeMap;
        use std::env::VarError;
        use std::ffi::OsString;
        use std::sync::RwLock;

        static VARS: RwLock<BTreeMap<String, String>> = RwLock::new(BTreeMap::new());

        /// Replaces the environment with `vars`.
        pub fn set(vars: impl IntoIterator<Item = (String, String)>) {
            *VARS.write().unwrap_or_else(|e| e.into_inner()) = vars.into_iter().collect();
        }
        pub fn var(name: impl AsRef<str>) -> Result<String, VarError> {
            VARS.read()
                .unwrap_or_else(|e| e.into_inner())
                .get(name.as_ref())
                .cloned()
                .ok_or(VarError::NotPresent)
        }
        pub fn var_os(name: impl AsRef<str>) -> Option<OsString> {
            var(name).ok().map(OsString::from)
        }
        pub fn vars() -> std::vec::IntoIter<(String, String)> {
            let vars = VARS.read().unwrap_or_else(|e| e.into_inner());
            vars.iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect::<Vec<_>>()
                .into_iter()
        }
    }

    /// `tokio::fs` over `std::fs`, whose calls all fail on `wasm32-unknown-unknown`.
    pub mod fs {
        use std::io;
        use std::path::{Path, PathBuf};
        use std::pin::Pin;
        use std::task::{Context, Poll};
        use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

        pub async fn canonicalize(path: impl AsRef<Path>) -> io::Result<PathBuf> {
            std::fs::canonicalize(path)
        }
        pub async fn create_dir(path: impl AsRef<Path>) -> io::Result<()> {
            std::fs::create_dir(path)
        }
        pub async fn create_dir_all(path: impl AsRef<Path>) -> io::Result<()> {
            std::fs::create_dir_all(path)
        }
        pub async fn read(path: impl AsRef<Path>) -> io::Result<Vec<u8>> {
            std::fs::read(path)
        }
        pub async fn write(path: impl AsRef<Path>, contents: impl AsRef<[u8]>) -> io::Result<()> {
            std::fs::write(path, contents)
        }
        pub async fn remove_file(path: impl AsRef<Path>) -> io::Result<()> {
            std::fs::remove_file(path)
        }
        pub async fn remove_dir_all(path: impl AsRef<Path>) -> io::Result<()> {
            std::fs::remove_dir_all(path)
        }
        pub async fn metadata(path: impl AsRef<Path>) -> io::Result<std::fs::Metadata> {
            std::fs::metadata(path)
        }
        pub async fn read_dir(path: impl AsRef<Path>) -> io::Result<ReadDir> {
            std::fs::read_dir(path).map(ReadDir)
        }

        pub struct ReadDir(std::fs::ReadDir);
        impl ReadDir {
            pub async fn next_entry(&mut self) -> io::Result<Option<DirEntry>> {
                self.0.next().transpose().map(|entry| entry.map(DirEntry))
            }
        }
        pub struct DirEntry(std::fs::DirEntry);
        impl DirEntry {
            pub fn path(&self) -> PathBuf {
                self.0.path()
            }
            pub fn file_name(&self) -> std::ffi::OsString {
                self.0.file_name()
            }
            pub async fn metadata(&self) -> io::Result<std::fs::Metadata> {
                self.0.metadata()
            }
            pub async fn file_type(&self) -> io::Result<std::fs::FileType> {
                self.0.file_type()
            }
        }

        pub struct File(std::fs::File);
        impl File {
            pub async fn open(path: impl AsRef<Path>) -> io::Result<Self> {
                std::fs::File::open(path).map(File)
            }
            pub fn from_std(file: std::fs::File) -> Self {
                File(file)
            }
            pub async fn metadata(&self) -> io::Result<std::fs::Metadata> {
                self.0.metadata()
            }
            pub async fn set_len(&self, size: u64) -> io::Result<()> {
                self.0.set_len(size)
            }
        }
        impl AsyncRead for File {
            fn poll_read(
                mut self: Pin<&mut Self>,
                _: &mut Context<'_>,
                buf: &mut ReadBuf<'_>,
            ) -> Poll<io::Result<()>> {
                let read = io::Read::read(&mut self.0, buf.initialize_unfilled());
                Poll::Ready(read.map(|n| buf.advance(n)))
            }
        }
        impl AsyncWrite for File {
            fn poll_write(
                mut self: Pin<&mut Self>,
                _: &mut Context<'_>,
                buf: &[u8],
            ) -> Poll<io::Result<usize>> {
                Poll::Ready(io::Write::write(&mut self.0, buf))
            }
            fn poll_flush(mut self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
                Poll::Ready(io::Write::flush(&mut self.0))
            }
            fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
                self.poll_flush(cx)
            }
        }

        #[derive(Clone, Debug)]
        pub struct OpenOptions(std::fs::OpenOptions);
        impl OpenOptions {
            pub fn new() -> Self {
                OpenOptions(std::fs::OpenOptions::new())
            }
            pub fn read(&mut self, on: bool) -> &mut Self {
                self.0.read(on);
                self
            }
            pub fn write(&mut self, on: bool) -> &mut Self {
                self.0.write(on);
                self
            }
            pub fn append(&mut self, on: bool) -> &mut Self {
                self.0.append(on);
                self
            }
            pub fn create(&mut self, on: bool) -> &mut Self {
                self.0.create(on);
                self
            }
            pub fn create_new(&mut self, on: bool) -> &mut Self {
                self.0.create_new(on);
                self
            }
            pub fn truncate(&mut self, on: bool) -> &mut Self {
                self.0.truncate(on);
                self
            }
            pub async fn open(&self, path: impl AsRef<Path>) -> io::Result<File> {
                self.0.open(path).map(File)
            }
        }
    }

    /// `tokio::process` whose programs never start: the Worker cannot run them.
    pub mod process {
        use std::ffi::OsStr;
        use std::io;
        use std::path::Path;
        use std::process::{ExitStatus, Output, Stdio};

        pub type ChildStdin = tokio::io::Sink;
        pub type ChildStdout = tokio::io::Empty;
        pub type ChildStderr = tokio::io::Empty;

        fn unsupported() -> io::Error {
            io::Error::new(
                io::ErrorKind::Unsupported,
                "Programs cannot run in the Worker",
            )
        }

        #[derive(Debug)]
        pub struct Command(());
        impl Command {
            pub fn new(_: impl AsRef<OsStr>) -> Self {
                Command(())
            }
            pub fn arg(&mut self, _: impl AsRef<OsStr>) -> &mut Self {
                self
            }
            pub fn args<I, S>(&mut self, _: I) -> &mut Self
            where
                I: IntoIterator<Item = S>,
                S: AsRef<OsStr>,
            {
                self
            }
            pub fn env(&mut self, _: impl AsRef<OsStr>, _: impl AsRef<OsStr>) -> &mut Self {
                self
            }
            pub fn envs<I, K, V>(&mut self, _: I) -> &mut Self
            where
                I: IntoIterator<Item = (K, V)>,
                K: AsRef<OsStr>,
                V: AsRef<OsStr>,
            {
                self
            }
            pub fn env_clear(&mut self) -> &mut Self {
                self
            }
            pub fn env_remove(&mut self, _: impl AsRef<OsStr>) -> &mut Self {
                self
            }
            pub fn current_dir(&mut self, _: impl AsRef<Path>) -> &mut Self {
                self
            }
            pub fn stdin(&mut self, _: impl Into<Stdio>) -> &mut Self {
                self
            }
            pub fn stdout(&mut self, _: impl Into<Stdio>) -> &mut Self {
                self
            }
            pub fn stderr(&mut self, _: impl Into<Stdio>) -> &mut Self {
                self
            }
            pub fn kill_on_drop(&mut self, _: bool) -> &mut Self {
                self
            }
            pub fn spawn(&mut self) -> io::Result<Child> {
                Err(unsupported())
            }
            pub async fn output(&mut self) -> io::Result<Output> {
                Err(unsupported())
            }
            pub async fn status(&mut self) -> io::Result<ExitStatus> {
                Err(unsupported())
            }
        }

        /// A running program. [`Command::spawn`] never returns one in the Worker.
        #[derive(Debug)]
        pub struct Child {
            pub stdin: Option<ChildStdin>,
            pub stdout: Option<ChildStdout>,
            pub stderr: Option<ChildStderr>,
            never: std::convert::Infallible,
        }
        impl Child {
            pub fn id(&self) -> Option<u32> {
                match self.never {}
            }
            pub async fn wait(&mut self) -> io::Result<ExitStatus> {
                match self.never {}
            }
            pub fn try_wait(&mut self) -> io::Result<Option<ExitStatus>> {
                match self.never {}
            }
            pub async fn kill(&mut self) -> io::Result<()> {
                match self.never {}
            }
            pub fn start_kill(&mut self) -> io::Result<()> {
                match self.never {}
            }
            pub async fn wait_with_output(self) -> io::Result<Output> {
                match self.never {}
            }
        }
    }
}
