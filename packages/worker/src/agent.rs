//! AI work in containers. Each Pully login gets one `AgentHome` Durable Object, named after the
//! login's volume, that owns that login's agent container (`agent/Dockerfile`, which runs the
//! AI kit's runner, `ai_kit::runner`). `AgentRemote` is how the server reaches it. The container's disk goes
//! when it sleeps, so the object keeps the login's sign-in files as text in its own storage: written
//! back into the container when it starts, and saved after each run, sign-in, status check and
//! sign-out. The runner sends only the sign-in part of each file (`ai_kit::runner`).
use futures_util::{future::BoxFuture, lock::Mutex, StreamExt};
use pulls_review_server::{
    agent_login::AgentLogin,
    agent_remote::{AgentRemote, Body},
};
use send_wrapper::SendWrapper;
use std::{rc::Rc, time::Duration};
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
/// Names the login a call is for; only `PullyServer` sets it, as nothing else reaches the object.
const LOGIN_HEADER: &str = "x-pully-agent-login";

/// The server's way to its logins' containers: each call goes to the `AgentHome` named after the
/// login's volume.
pub struct ObjectRemote {
    pub homes: SendWrapper<ObjectNamespace>,
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

    async fn fetch(&self, request: Request) -> Result<Response> {
        let volume = request
            .headers()
            .get(LOGIN_HEADER)?
            .filter(|v| v.starts_with("pulls-review-agents-home") && v.len() <= 64)
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
        let container = self.ready(&volume).await?;
        let path = request.path();
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
    /// The running container, started and given back its login's home when it was asleep.
    async fn ready(&self, volume: &str) -> Result<JsValue> {
        let container = self
            .state
            .container()
            .ok_or_else(|| worker::Error::RustError("This object has no container".into()))?;
        let mut started = self.lock.lock().await;
        let raw: JsValue = container.as_ref().clone();
        if !container.running() {
            *started = false;
            // workers-rs 0.8.7's `ContainerStartupOptions` drops the options it is given, so the
            // object is built here as workerd expects it.
            let env = js_sys::Object::new();
            set(&env, "PULLY_AGENT_LOGIN", &volume.into())?;
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
        Ok(raw)
    }
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
            let mut files = send(&self.container, "GET", "/login-files", None).await?;
            if files.status_code() != 200 {
                return Ok(());
            }
            let files = files.text().await?;
            if files == "{}" {
                self.storage.delete(LOGIN_FILES).await?;
            } else {
                self.storage.put(LOGIN_FILES, files).await?;
            }
            Ok::<_, worker::Error>(())
        }
        .await;
        if let Err(error) = result {
            worker::console_error!("Saving an AI login failed: {error}");
        }
    }
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
