use anyhow::{ensure, Result};
use std::{future::Future, net::IpAddr, pin::Pin, sync::Arc, time::Duration};
use url::{Host, Url};

const MAX_ADDRESSES: usize = 16;
type DnsFuture<'a> = Pin<Box<dyn Future<Output = Result<Vec<IpAddr>>> + Send + 'a>>;
trait Dns: Send + Sync {
    fn resolve<'a>(&'a self, host: &'a str, port: u16) -> DnsFuture<'a>;
}
struct SystemDns;
impl Dns for SystemDns {
    fn resolve<'a>(&'a self, host: &'a str, port: u16) -> DnsFuture<'a> {
        Box::pin(async move {
            let resolved = crate::rt::timeout(
                Duration::from_secs(5),
                tokio::net::lookup_host((host, port)),
            )
            .await
            .map_err(|_| unavailable())?
            .map_err(|_| unavailable())?;
            let mut addresses = Vec::new();
            for address in resolved {
                if !addresses.contains(&address.ip()) {
                    addresses.push(address.ip());
                    ensure!(
                        addresses.len() <= MAX_ADDRESSES,
                        "Pi endpoint is not allowed"
                    );
                }
            }
            Ok(addresses)
        })
    }
}

#[derive(Clone)]
pub(super) struct Endpoint(Url);
impl Endpoint {
    pub(super) fn parse(raw: &str) -> Result<Self> {
        ensure!(
            !raw.is_empty() && raw.len() <= 2048 && !raw.chars().any(char::is_control),
            "Invalid Pi endpoint"
        );
        let mut url = Url::parse(raw.trim()).map_err(|_| anyhow::anyhow!("Invalid Pi endpoint"))?;
        ensure!(
            matches!(url.scheme(), "http" | "https")
                && url.host().is_some()
                && url.username().is_empty()
                && url.password().is_none()
                && url.query().is_none()
                && url.fragment().is_none(),
            "Invalid Pi endpoint"
        );
        let path = url.path().trim_end_matches('/').to_owned();
        url.set_path(if path.is_empty() { "/" } else { &path });
        Ok(Self(url))
    }
    pub(super) fn as_str(&self) -> &str {
        self.0.as_str()
    }
    pub(super) fn display_origin(&self) -> String {
        self.0.origin().ascii_serialization()
    }
    fn target(&self, route: Route) -> Result<Url> {
        let mut url = self.0.clone();
        url.path_segments_mut()
            .map_err(|_| anyhow::anyhow!("Invalid Pi endpoint"))?
            .pop_if_empty()
            .extend(route.parts());
        Ok(url)
    }
}

#[derive(Clone, Copy)]
pub(super) enum Route {
    Models,
    Completion,
}
impl Route {
    fn parts(self) -> &'static [&'static str] {
        match self {
            Self::Models => &["models"],
            Self::Completion => &["chat", "completions"],
        }
    }
}

/// Only host startup can construct this capability; it is never native setup input.
pub(crate) struct TrustedPiEndpoint {
    endpoint: Endpoint,
    addresses: Vec<IpAddr>,
}
impl TrustedPiEndpoint {
    pub(crate) fn pinned(base: &str, addresses: Vec<IpAddr>) -> Result<Self> {
        let endpoint = Endpoint::parse(base)?;
        ensure!(
            !addresses.is_empty() && addresses.len() <= MAX_ADDRESSES,
            "Invalid trusted Pi endpoint"
        );
        let literal = match endpoint.0.host() {
            Some(Host::Ipv4(ip)) => Some(IpAddr::V4(ip)),
            Some(Host::Ipv6(ip)) => Some(IpAddr::V6(ip)),
            _ => None,
        };
        ensure!(
            literal.is_none_or(|ip| addresses.iter().all(|address| *address == ip)),
            "Invalid trusted Pi endpoint"
        );
        Ok(Self {
            endpoint,
            addresses,
        })
    }
}

pub(crate) struct PiEndpointPolicy {
    trusted: Vec<TrustedPiEndpoint>,
    dns: Arc<dyn Dns>,
}
pub(super) struct PreparedEndpoint {
    pub client: reqwest::Client,
    pub url: Url,
}
impl PiEndpointPolicy {
    pub(crate) fn public_https() -> Self {
        Self {
            trusted: Vec::new(),
            dns: Arc::new(SystemDns),
        }
    }
    /// Exceptions are exact configured bases with fixed IPs, not user-selectable bypass flags.
    pub(crate) fn with_trusted(trusted: Vec<TrustedPiEndpoint>) -> Result<Self> {
        ensure!(trusted.len() <= 8, "Too many trusted Pi endpoints");
        Ok(Self {
            trusted,
            dns: Arc::new(SystemDns),
        })
    }
    pub(super) async fn prepare(
        &self,
        endpoint: &Endpoint,
        route: Route,
    ) -> Result<PreparedEndpoint> {
        let port = endpoint.0.port_or_known_default().ok_or_else(unavailable)?;
        let addresses = if let Some(trusted) = self
            .trusted
            .iter()
            .find(|trusted| trusted.endpoint.as_str() == endpoint.as_str())
        {
            trusted.addresses.clone()
        } else {
            ensure!(
                endpoint.0.scheme() == "https" && matches!(port, 443 | 8443),
                "Pi endpoint is not allowed"
            );
            let addresses = match endpoint.0.host().ok_or_else(unavailable)? {
                Host::Ipv4(ip) => vec![IpAddr::V4(ip)],
                Host::Ipv6(ip) => vec![IpAddr::V6(ip)],
                Host::Domain(host) => {
                    ensure!(
                        host.contains('.')
                            && !host.ends_with('.')
                            && !["localhost", ".localhost", ".local", ".internal"]
                                .iter()
                                .any(|suffix| host.ends_with(suffix)),
                        "Pi endpoint is not allowed"
                    );
                    self.dns.resolve(host, port).await?
                }
            };
            ensure!(
                !addresses.is_empty()
                    && addresses.len() <= MAX_ADDRESSES
                    && addresses.iter().all(public_address),
                "Pi endpoint is not allowed"
            );
            addresses
        };
        let mut client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30 * 60));
        if let Some(Host::Domain(host)) = endpoint.0.host() {
            let sockets: Vec<_> = addresses
                .iter()
                .map(|ip| std::net::SocketAddr::new(*ip, port))
                .collect();
            client = client.resolve_to_addrs(host, &sockets);
        }
        Ok(PreparedEndpoint {
            client: client.build().map_err(|_| unavailable())?,
            url: endpoint.target(route)?,
        })
    }
}

fn public_address(address: &IpAddr) -> bool {
    match address {
        IpAddr::V4(address) => {
            let [a, b, c, d] = address.octets();
            !(matches!(a, 0 | 10 | 127 | 224..=255)
                || (a == 100 && (64..=127).contains(&b))
                || (a == 169 && b == 254)
                || (a == 172 && (16..=31).contains(&b))
                || (a == 192
                    && ((b == 0 && matches!(c, 0 | 2)) || b == 168 || (b == 88 && c == 99)))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113)
                || (a == 168 && b == 63 && c == 129 && d == 16))
        }
        IpAddr::V6(address) => {
            let segments = address.segments();
            segments[0] & 0xe000 == 0x2000
                && !(segments[0] == 0x2001 && (segments[1] < 0x0200 || segments[1] == 0x0db8))
                && !matches!(segments[0], 0x2002 | 0x3fff)
        }
    }
}
fn unavailable() -> anyhow::Error {
    anyhow::anyhow!("Pi endpoint is unavailable")
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Answers(Vec<IpAddr>);
    impl Dns for Answers {
        fn resolve<'a>(&'a self, _: &'a str, _: u16) -> DnsFuture<'a> {
            Box::pin(async { Ok(self.0.clone()) })
        }
    }
    #[test]
    fn public_policy_rejects_private_reserved_metadata_and_embedded_addresses() {
        for address in [
            "0.0.0.0",
            "10.1.2.3",
            "127.0.0.1",
            "100.100.100.200",
            "169.254.169.254",
            "168.63.129.16",
            "172.31.2.3",
            "192.168.1.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "::ffff:127.0.0.1",
            "64:ff9b::127.0.0.1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "2002:7f00:1::1",
        ] {
            assert!(
                !public_address(&address.parse().unwrap()),
                "address must be denied"
            );
        }
        for address in [
            "8.8.8.8",
            "1.1.1.1",
            "192.0.78.1",
            "2606:4700:4700::1111",
            "2001:4860:4860::8888",
        ] {
            assert!(public_address(&address.parse().unwrap()));
        }
    }
    #[tokio::test]
    async fn every_dns_answer_is_checked_and_only_public_https_bases_are_admitted() {
        let policy = PiEndpointPolicy {
            trusted: Vec::new(),
            dns: Arc::new(Answers(vec![
                "8.8.8.8".parse().unwrap(),
                "127.0.0.1".parse().unwrap(),
            ])),
        };
        assert!(policy
            .prepare(
                &Endpoint::parse("https://models.example/v1").unwrap(),
                Route::Models
            )
            .await
            .is_err());
        let public = PiEndpointPolicy {
            trusted: Vec::new(),
            dns: Arc::new(Answers(vec!["8.8.8.8".parse().unwrap()])),
        };
        let prepared = public
            .prepare(
                &Endpoint::parse("https://models.example/v1/").unwrap(),
                Route::Completion,
            )
            .await
            .unwrap();
        assert_eq!(
            prepared.url.as_str(),
            "https://models.example/v1/chat/completions"
        );
        for url in [
            "http://models.example/v1",
            "https://models.example:8317/v1",
            "https://localhost/v1",
            "https://api.internal/v1",
            "https://127.1/v1",
            "https://2130706433/v1",
        ] {
            assert!(public
                .prepare(&Endpoint::parse(url).unwrap(), Route::Models)
                .await
                .is_err());
        }
    }
    #[tokio::test]
    async fn trusted_operator_exception_is_pinned_to_the_exact_base() {
        let policy = PiEndpointPolicy::with_trusted(vec![TrustedPiEndpoint::pinned(
            "http://127.0.0.1:8317/v1",
            vec!["127.0.0.1".parse().unwrap()],
        )
        .unwrap()])
        .unwrap();
        assert!(policy
            .prepare(
                &Endpoint::parse("http://127.0.0.1:8317/v1").unwrap(),
                Route::Models
            )
            .await
            .is_ok());
        for changed in [
            "http://127.0.0.1:8318/v1",
            "http://127.0.0.1:8317/admin",
            "http://localhost:8317/v1",
        ] {
            assert!(policy
                .prepare(&Endpoint::parse(changed).unwrap(), Route::Models)
                .await
                .is_err());
        }
        assert!(TrustedPiEndpoint::pinned(
            "http://127.0.0.1:8317/v1",
            vec!["10.0.0.1".parse().unwrap()]
        )
        .is_err());
    }
    #[test]
    fn endpoint_input_cannot_carry_proxy_authority_or_secrets_in_url_components() {
        for raw in [
            "https://user:secret@models.example/v1",
            "https://models.example/v1?key=secret",
            "https://models.example/v1#secret",
            "file:///etc/passwd",
            "ftp://models.example/v1",
            "https://models.example/v1\n",
        ] {
            assert!(Endpoint::parse(raw).is_err());
        }
    }
}
