//! AI work in containers, for a host built as a Cloudflare Worker. Each login gets one `AgentHome`
//! Durable Object, named after the login's volume, that owns that login's agent container (the
//! host's image, which runs [`crate::runner`]). [`ObjectRemote`] is how the host reaches it. The
//! container's disk goes when it sleeps, so the object keeps the login's sign-in files as text in
//! its own storage: written back into the container when it starts, and saved after each run,
//! sign-in, status check and sign-out. The runner sends only the sign-in part of each file.
//!
//! The host exports [`AgentHome`] from its Worker (`pub use ai_kit::home::AgentHome;`), binds it
//! with a container in its Wrangler file, configures the kit in its `#[event(start)]`, and calls
//! [`install`] before any AI work.
//!
//! ThyMind's feedback sandboxes borrow a login with `POST /feedback/checkout` and give it back
//! with `POST /feedback/checkin` (ThyMind `docs/feedback.md`). While it is checked out the
//! container stays stopped and every other call answers 403 `{"error":"busy"}`, until the checkin
//! or the end of the [`Lease`].
use crate::config::config;
use crate::lease::Lease;
use crate::login::AgentLogin;
use crate::remote::{AgentRemote, Body};
use futures_util::{future::BoxFuture, lock::Mutex, StreamExt};
use send_wrapper::SendWrapper;
use std::{rc::Rc, sync::Arc, time::Duration};
use wasm_bindgen::{JsCast, JsValue};
use worker::{
    durable_object, Env, Headers, Method, ObjectNamespace, Request, RequestInit, Response, Result,
    State, Storage,
};

/// The port the AI kit's runner listens on in the container.
const PORT: u16 = 8080;
/// How long a starting container has to answer `/health`.
const START_TIMEOUT_MS: f64 = 60_000.0;
/// How long a container with nothing to do stays up. A sign-in in progress keeps it up through
/// the phone's calls to finish it.
const IDLE_MS: f64 = 5.0 * 60_000.0;
/// The storage key of the login's sign-in files, the JSON text the AI kit's runner gives.
const LOGIN_FILES: &str = "login-files";
/// The storage key of the end of a checkout, in Unix milliseconds, while one is out.
const BUSY_UNTIL: &str = "feedback_busy_until";
/// Names the login a call is for; only the host's server sets it, as nothing else reaches the object.
const LOGIN_HEADER: &str = "x-agent-login";

/// The server's way to its logins' containers: each call goes to the `AgentHome` named after the
/// login's volume.
pub struct ObjectRemote {
    pub homes: SendWrapper<ObjectNamespace>,
}

/// Sends every Claude Code and Codex call of this isolate to its login's `AgentHome` in the
/// `binding` namespace. A second call in the same isolate keeps the first remote.
pub fn install(env: &Env, binding: &str) -> Result<()> {
    let homes = SendWrapper::new(env.durable_object(binding)?);
    let _ = crate::remote::set(Arc::new(ObjectRemote { homes }));
    Ok(())
}

impl AgentRemote for ObjectRemote {
    fn post(
        &self,
        login: &AgentLogin,
        path: &'static str,
        body: Vec<u8>,
    ) -> BoxFuture<'static, anyhow::Result<Body>> {
        let volume = login.volume();
        let stub = self.homes.get_by_name(&volume);
        Box::pin(SendWrapper::new(async move {
            let failed = |e: worker::Error| anyhow::anyhow!("The AI container is unavailable: {e}");
            let headers = Headers::new();
            headers
                .set("content-type", "application/json")
                .map_err(failed)?;
            headers.set(LOGIN_HEADER, &volume).map_err(failed)?;
            let mut init = RequestInit::new();
            init.with_method(Method::Post)
                .with_headers(headers)
                .with_body(Some(js_sys::Uint8Array::from(body.as_slice()).into()));
            let request =
                Request::new_with_init(&format!("https://agent{path}"), &init).map_err(failed)?;
            let mut response = stub
                .map_err(failed)?
                .fetch_with_request(request)
                .await
                .map_err(failed)?;
            if response.status_code() != 200 {
                let text = response.text().await.unwrap_or_default();
                anyhow::bail!(
                    "The AI container answered {}: {text}",
                    response.status_code()
                );
            }
            let stream = response
                .stream()
                .map_err(failed)?
                .map(|chunk| chunk.map_err(|e| std::io::Error::other(e.to_string())));
            Ok(Box::pin(SendWrapper::new(stream)) as Body)
        }))
    }
}

#[durable_object]
pub struct AgentHome {
    state: State,
    /// Held while the container starts and while the login is saved, so a second start never
    /// puts an older login over a newer one, and saves land in order.
    lock: Rc<Mutex<bool>>,
}

impl worker::DurableObject for AgentHome {
    fn new(state: State, _env: Env) -> Self {
        Self {
            state,
            lock: Rc::new(Mutex::new(false)),
        }
    }

    async fn fetch(&self, mut request: Request) -> Result<Response> {
        let volume = request
            .headers()
            .get(LOGIN_HEADER)?
            .filter(|v| v.starts_with(config().volume) && v.len() <= 64)
            .ok_or_else(|| worker::Error::RustError("Missing agent login".into()))?;
        // The first call fixes whose container this is; any other login is refused.
        let storage = self.state.storage();
        match storage.get::<String>("login").await? {
            Some(saved) if saved != volume => {
                return Response::error("This agent home belongs to another login", 403)
            }
            Some(_) => {}
            None => storage.put("login", &volume).await?,
        }
        let path = request.path();
        match (path.as_str(), request.method()) {
            ("/feedback/checkout", Method::Post) => return self.checkout().await,
            ("/feedback/checkin", Method::Post) => {
                let body = request.text().await?;
                return self.checkin(&body).await;
            }
            _ => {}
        }
        let Some(container) = self.ready(&volume).await? else {
            return busy();
        };
        let response = forward(&container, &to_container(request.inner(), &path)?).await?;
        // A Codex sign-in can end after its finish call gave up waiting, so status saves too.
        if !matches!(
            path.as_str(),
            "/run" | "/login/finish" | "/status" | "/sign-out"
        ) {
            return Ok(response);
        }
        // Save the login once the answer has been read to its end, so a rotated one is kept.
        let (status, headers) = (response.status_code(), response.headers().clone());
        let mut response = response;
        let body = response.stream()?;
        let save = Saver {
            container,
            storage: self.state.storage(),
            lock: self.lock.clone(),
        };
        let finished = futures_util::stream::once(async move {
            save.save().await;
            None
        })
        .filter_map(|done: Option<Result<Vec<u8>>>| async move { done });
        Ok(Response::from_stream(body.chain(finished))?
            .with_status(status)
            .with_headers(headers))
    }
}

impl AgentHome {
    /// The running container, started and given back its login's home when it was asleep, or
    /// `None` while the login is checked out. A lease that ran out is cleared here, so the
    /// container restarts with the files the object had.
    async fn ready(&self, volume: &str) -> Result<Option<JsValue>> {
        let container = self
            .state
            .container()
            .ok_or_else(|| worker::Error::RustError("This object has no container".into()))?;
        let mut started = self.lock.lock().await;
        let storage = self.state.storage();
        if let Some(until) = storage.get::<u64>(BUSY_UNTIL).await? {
            if let Lease::Busy(_) = Lease::check(crate::now_ms(), Some(until)) {
                return Ok(None);
            }
            storage.delete(BUSY_UNTIL).await?;
        }
        let raw: JsValue = container.as_ref().clone();
        if !container.running() {
            *started = false;
            // workers-rs 0.8.7's `ContainerStartupOptions` drops the options it is given, so the
            // object is built here as workerd expects it.
            let env = js_sys::Object::new();
            set(&env, &format!("{}_LOGIN", config().env), &volume.into())?;
            let options = js_sys::Object::new();
            set(&options, "enableInternet", &true.into())?;
            set(&options, "env", &env)?;
            call(&raw, "start", &[options.into()])?;
        }
        if !*started {
            wait_until_answering(&raw).await?;
            if let Some(files) = self.state.storage().get::<String>(LOGIN_FILES).await? {
                let mut restored =
                    send(&raw, "PUT", "/login-files", Some(files.into_bytes())).await?;
                if restored.status_code() != 200 || restored.text().await? != r#"{"ok":true}"# {
                    return Err(worker::Error::RustError(
                        "The AI login could not be restored".into(),
                    ));
                }
            }
            let _ = call(&raw, "setInactivityTimeout", &[IDLE_MS.into()]);
            *started = true;
        }
        Ok(Some(raw))
    }

    /// Hands the login's sign-in files to a feedback sandbox: saves them from the container if it
    /// is awake, stops it, and marks the login busy for a [`Lease`]. Answers `{"files": {...}}`,
    /// 403 busy while another checkout holds it, and 409 when the login has no sign-in.
    async fn checkout(&self) -> Result<Response> {
        let container = self
            .state
            .container()
            .ok_or_else(|| worker::Error::RustError("This object has no container".into()))?;
        let mut started = self.lock.lock().await;
        let storage = self.state.storage();
        let now = crate::now_ms();
        let Ok(until) = Lease::take(now, storage.get::<u64>(BUSY_UNTIL).await?) else {
            return busy();
        };
        let raw: JsValue = container.as_ref().clone();
        if container.running() {
            if *started {
                save_files(&raw, &storage).await?;
            }
            let promise = call(&raw, "destroy", &[])?;
            wasm_bindgen_futures::JsFuture::from(js_sys::Promise::from(promise))
                .await
                .map_err(|e| {
                    worker::Error::RustError(format!("Stopping the AI container failed: {e:?}"))
                })?;
        }
        *started = false;
        let Some(files) = storage.get::<String>(LOGIN_FILES).await? else {
            return Response::error(r#"{"error":"signed-out"}"#, 409);
        };
        storage.put(BUSY_UNTIL, until).await?;
        Response::ok(format!(r#"{{"files":{files},"busyUntil":{until}}}"#))
    }

    /// Takes a login back from a feedback sandbox: lays the files it returns (`{"files"?: {...}}`,
    /// the CLIs may have refreshed them) over the saved ones and ends the checkout. The container
    /// stays stopped, so its next start restores them. Answers 409 when nothing is checked out,
    /// because the container may since have started with the saved files.
    async fn checkin(&self, body: &str) -> Result<Response> {
        let _held = self.lock.lock().await;
        let storage = self.state.storage();
        if storage.get::<u64>(BUSY_UNTIL).await?.is_none() {
            return Response::error(r#"{"error":"not-checked-out"}"#, 409);
        }
        let body: serde_json::Value =
            serde_json::from_str(if body.is_empty() { "{}" } else { body })
                .map_err(|_| worker::Error::RustError("Invalid checkin".into()))?;
        if let Some(files) = body.get("files") {
            let saved = storage.get::<String>(LOGIN_FILES).await?;
            if let Some(merged) = crate::login_files::merge(saved.as_deref(), files) {
                storage.put(LOGIN_FILES, merged).await?;
            }
        }
        storage.delete(BUSY_UNTIL).await?;
        Response::ok(r#"{"ok":true}"#)
    }
}

/// The answer to any call while the login is checked out.
fn busy() -> Result<Response> {
    Response::error(r#"{"error":"busy"}"#, 403)
}

/// Saves a login's sign-in files from its container into the object's storage.
struct Saver {
    container: JsValue,
    storage: Storage,
    lock: Rc<Mutex<bool>>,
}

impl Saver {
    async fn save(&self) {
        let _held = self.lock.lock().await;
        let result = async {
            // A checkout stopped the container and owns the files until it checks in.
            if self.storage.get::<u64>(BUSY_UNTIL).await?.is_some() {
                return Ok(());
            }
            save_files(&self.container, &self.storage).await
        }
        .await;
        if let Err(error) = result {
            worker::console_error!("Saving an AI login failed: {error}");
        }
    }
}

/// Copies the login's sign-in files from a running container into storage. The caller holds the
/// object's lock.
async fn save_files(container: &JsValue, storage: &Storage) -> Result<()> {
    let mut files = send(container, "GET", "/login-files", None).await?;
    if files.status_code() != 200 {
        return Ok(());
    }
    let files = files.text().await?;
    if files == "{}" {
        storage.delete(LOGIN_FILES).await?;
    } else {
        storage.put(LOGIN_FILES, files).await?;
    }
    Ok(())
}

/// Polls the runner's `/health` until it answers or the start timeout passes.
async fn wait_until_answering(container: &JsValue) -> Result<()> {
    let deadline = js_sys::Date::now() + START_TIMEOUT_MS;
    loop {
        if let Ok(response) = send(container, "GET", "/health", None).await {
            if response.status_code() == 200 {
                return Ok(());
            }
        }
        if js_sys::Date::now() > deadline {
            return Err(worker::Error::RustError(
                "The AI container did not start".into(),
            ));
        }
        worker::Delay::from(Duration::from_millis(250)).await;
    }
}

/// Sends a request of the object's own to the runner.
async fn send(
    container: &JsValue,
    method: &str,
    path: &str,
    body: Option<Vec<u8>>,
) -> Result<Response> {
    let init = web_sys::RequestInit::new();
    init.set_method(method);
    if let Some(body) = body {
        init.set_body(&js_sys::Uint8Array::from(body.as_slice()).into());
    }
    let request =
        web_sys::Request::new_with_str_and_init(&format!("http://container{path}"), &init)
            .map_err(|e| worker::Error::RustError(format!("{e:?}")))?;
    forward(container, &request).await
}

/// `request` addressed to the container: containers take plain HTTP only.
fn to_container(request: &web_sys::Request, path: &str) -> Result<web_sys::Request> {
    web_sys::Request::new_with_str_and_init(
        &format!("http://container{path}"),
        request.unchecked_ref::<web_sys::RequestInit>(),
    )
    .map_err(|e| worker::Error::RustError(format!("{e:?}")))
}

/// Passes `request` to the runner's port in `container`.
async fn forward(container: &JsValue, request: &web_sys::Request) -> Result<Response> {
    let port = call(container, "getTcpPort", &[f64::from(PORT).into()])?;
    let request: &JsValue = request.as_ref();
    let promise = call(&port, "fetch", std::slice::from_ref(request))?;
    let response = wasm_bindgen_futures::JsFuture::from(js_sys::Promise::from(promise))
        .await
        .map_err(|e| worker::Error::RustError(format!("The AI container failed: {e:?}")))?;
    Ok(response.unchecked_into::<web_sys::Response>().into())
}

fn set(target: &js_sys::Object, key: &str, value: &JsValue) -> Result<()> {
    js_sys::Reflect::set(target, &key.into(), value)
        .map(|_| ())
        .map_err(|e| worker::Error::RustError(format!("{e:?}")))
}

/// Calls the method `name` of a JavaScript object.
fn call(target: &JsValue, name: &str, args: &[JsValue]) -> Result<JsValue> {
    let function: js_sys::Function = js_sys::Reflect::get(target, &name.into())
        .map_err(|e| worker::Error::RustError(format!("{e:?}")))?
        .dyn_into()
        .map_err(|_| worker::Error::RustError(format!("{name} is not a function")))?;
    function
        .apply(target, &args.iter().collect::<js_sys::Array>())
        .map_err(|e| worker::Error::RustError(format!("{e:?}")))
}
