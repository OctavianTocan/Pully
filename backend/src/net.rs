//! Outbound HTTP for both hosts. Requests are built with reqwest everywhere; [`client`] makes the
//! client and [`send`] sends a request. The native server uses reqwest's own client. The Worker
//! build (`packages/worker`) sends through the runtime's `fetch` itself, because reqwest's
//! WebAssembly client always follows redirects and its futures cannot cross an `await` in an
//! axum handler.

#[cfg(not(target_arch = "wasm32"))]
pub use reqwest::Response;

/// A client builder that never follows redirects. Every outbound call starts here: the token and
/// sign-in contracts refuse redirects instead of re-sending credentials to another origin.
#[cfg(not(target_arch = "wasm32"))]
pub fn client() -> reqwest::ClientBuilder {
    reqwest::Client::builder().redirect(reqwest::redirect::Policy::none())
}

/// Sends `request` and returns its response headers; the body is read after.
#[cfg(not(target_arch = "wasm32"))]
pub fn send(
    request: reqwest::RequestBuilder,
) -> impl std::future::Future<Output = reqwest::Result<Response>> + Send {
    request.send()
}

#[cfg(target_arch = "wasm32")]
pub use wasm::*;

#[cfg(target_arch = "wasm32")]
mod wasm {
    use bytes::Bytes;
    use futures_util::Stream;
    use reqwest::{header::HeaderMap, StatusCode};
    use send_wrapper::SendWrapper;
    use std::{future::Future, time::Duration};
    use wasm_bindgen::{JsCast, JsValue};

    /// How long a request may take when it sets no timeout of its own. The Worker keeps no
    /// client-wide timeout, so per-client limits fall back to this.
    const DEFAULT_TIMEOUT: Duration = Duration::from_secs(60);

    /// Why an outbound request failed.
    #[derive(Debug)]
    pub struct Error(String);
    impl std::fmt::Display for Error {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str(&self.0)
        }
    }
    impl std::error::Error for Error {}
    fn js_error(context: &str, value: JsValue) -> Error {
        Error(format!(
            "{context}: {}",
            value.as_string().unwrap_or_else(|| format!("{value:?}"))
        ))
    }

    /// The native client's builder options. The Worker's `fetch` has no connection settings
    /// and never follows redirects, so all but `build` change nothing.
    pub struct ClientBuilder;
    impl ClientBuilder {
        pub fn timeout(self, _: Duration) -> Self {
            self
        }
        pub fn connect_timeout(self, _: Duration) -> Self {
            self
        }
        pub fn read_timeout(self, _: Duration) -> Self {
            self
        }
        pub fn https_only(self, _: bool) -> Self {
            self
        }
        pub fn no_proxy(self) -> Self {
            self
        }
        /// Every request without its own User-Agent is sent as `pully` (see [`send`]).
        pub fn user_agent(self, _: &str) -> Self {
            self
        }
        pub fn build(self) -> reqwest::Result<reqwest::Client> {
            reqwest::Client::builder().build()
        }
    }

    /// A client builder that never follows redirects (see [`send`]).
    pub fn client() -> ClientBuilder {
        ClientBuilder
    }

    /// A response whose body has not been read yet.
    pub struct Response {
        status: StatusCode,
        headers: HeaderMap,
        reader: Option<SendWrapper<web_sys::ReadableStreamDefaultReader>>,
    }
    impl Response {
        pub fn status(&self) -> StatusCode {
            self.status
        }
        pub fn headers(&self) -> &HeaderMap {
            &self.headers
        }
        pub fn content_length(&self) -> Option<u64> {
            self.headers
                .get(reqwest::header::CONTENT_LENGTH)?
                .to_str()
                .ok()?
                .parse()
                .ok()
        }
        pub fn error_for_status(self) -> Result<Self, Error> {
            if self.status.is_client_error() || self.status.is_server_error() {
                return Err(Error(format!("HTTP status {}", self.status)));
            }
            Ok(self)
        }
        /// The next piece of the body, or `None` at its end.
        pub async fn chunk(&mut self) -> Result<Option<Bytes>, Error> {
            let Some(reader) = &self.reader else {
                return Ok(None);
            };
            let read = SendWrapper::new(wasm_bindgen_futures::JsFuture::from(reader.read()));
            let result = read
                .await
                .map_err(|e| js_error("Reading the response failed", e))?;
            let done = js_sys::Reflect::get(&result, &"done".into())
                .ok()
                .and_then(|v| v.as_bool())
                .unwrap_or(true);
            if done {
                self.reader = None;
                return Ok(None);
            }
            let value = js_sys::Reflect::get(&result, &"value".into())
                .map_err(|e| js_error("Reading the response failed", e))?;
            Ok(Some(Bytes::from(js_sys::Uint8Array::new(&value).to_vec())))
        }
        pub fn bytes_stream(
            self,
        ) -> std::pin::Pin<Box<dyn Stream<Item = Result<Bytes, Error>> + Send>> {
            Box::pin(futures_util::stream::unfold(
                Some(self),
                |state| async move {
                    let mut response = state?;
                    match response.chunk().await {
                        Ok(Some(bytes)) => Some((Ok(bytes), Some(response))),
                        Ok(None) => None,
                        Err(error) => Some((Err(error), None)),
                    }
                },
            ))
        }
    }

    /// Sends `request` through the runtime's `fetch` with `redirect: "manual"`, so a redirect
    /// comes back as its own 3xx response and is never followed. Gives up after the request's
    /// timeout, or one minute.
    pub fn send(
        request: reqwest::RequestBuilder,
    ) -> impl Future<Output = Result<Response, Error>> + Send {
        SendWrapper::new(async move {
            let request = request
                .build()
                .map_err(|e| Error(format!("Building the request failed: {e}")))?;
            let limit = request.timeout().copied().unwrap_or(DEFAULT_TIMEOUT);
            crate::rt::timeout(limit, fetch(request))
                .await
                .map_err(|_| Error("The request timed out".into()))?
        })
    }

    async fn fetch(request: reqwest::Request) -> Result<Response, Error> {
        let headers = web_sys::Headers::new().map_err(|e| js_error("Headers", e))?;
        for (name, value) in request.headers() {
            let value = value
                .to_str()
                .map_err(|_| Error(format!("Header {name} is not text")))?;
            headers
                .append(name.as_str(), value)
                .map_err(|e| js_error("Headers", e))?;
        }
        // GitHub refuses API requests without a User-Agent, and the Worker's fetch sends none.
        if !request.headers().contains_key(reqwest::header::USER_AGENT) {
            headers
                .append("user-agent", "pully")
                .map_err(|e| js_error("Headers", e))?;
        }
        let init = web_sys::RequestInit::new();
        init.set_method(request.method().as_str());
        init.set_headers(&headers);
        init.set_redirect(web_sys::RequestRedirect::Manual);
        if let Some(body) = request.body() {
            let bytes = body
                .as_bytes()
                .ok_or_else(|| Error("Streaming request bodies are not supported".into()))?;
            init.set_body(&js_sys::Uint8Array::from(bytes).into());
        }
        let js_request = web_sys::Request::new_with_str_and_init(request.url().as_str(), &init)
            .map_err(|e| js_error("Building the request failed", e))?;
        let global = js_sys::global();
        let fetch: js_sys::Function = js_sys::Reflect::get(&global, &"fetch".into())
            .map_err(|e| js_error("fetch is unavailable", e))?
            .dyn_into()
            .map_err(|e| js_error("fetch is unavailable", e))?;
        let promise: js_sys::Promise = fetch
            .call1(&global, &js_request)
            .map_err(|e| js_error("The request failed", e))?
            .dyn_into()
            .map_err(|e| js_error("The request failed", e))?;
        let response: web_sys::Response = wasm_bindgen_futures::JsFuture::from(promise)
            .await
            .map_err(|e| js_error("The request failed", e))?
            .dyn_into()
            .map_err(|e| js_error("The request failed", e))?;
        let status = StatusCode::from_u16(response.status())
            .map_err(|_| Error(format!("Invalid status {}", response.status())))?;
        let mut map = HeaderMap::new();
        let entries = js_sys::try_iter(&response.headers())
            .map_err(|e| js_error("Reading headers failed", e))?
            .ok_or_else(|| Error("Reading headers failed".into()))?;
        for entry in entries {
            let entry: js_sys::Array = entry
                .map_err(|e| js_error("Reading headers failed", e))?
                .unchecked_into();
            let (Some(name), Some(value)) = (entry.get(0).as_string(), entry.get(1).as_string())
            else {
                continue;
            };
            if let (Ok(name), Ok(value)) = (
                reqwest::header::HeaderName::from_bytes(name.as_bytes()),
                reqwest::header::HeaderValue::from_str(&value),
            ) {
                map.append(name, value);
            }
        }
        let reader = response
            .body()
            .map(|body| SendWrapper::new(body.get_reader().unchecked_into()));
        Ok(Response {
            status,
            headers: map,
            reader,
        })
    }
}
