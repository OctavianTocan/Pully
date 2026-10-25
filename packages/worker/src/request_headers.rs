pub(crate) fn forwarded_cookie(path: &str, value: &str) -> Option<String> {
    if !path.starts_with("/workspace/") {
        return Some(value.to_owned());
    }
    if value.trim_matches([' ', '\t', ';']).is_empty() {
        return value.contains(';').then(|| value.to_owned());
    }
    let cookies: Vec<_> = value
        .split(';')
        .map(|cookie| cookie.trim_matches([' ', '\t']))
        .filter(|cookie| {
            cookie
                .split_once('=')
                .is_none_or(|(name, _)| name != "CF_Authorization")
        })
        .collect();
    (!cookies.is_empty()).then(|| cookies.join("; "))
}

#[cfg(test)]
mod tests {
    use super::forwarded_cookie;

    #[test]
    fn workspace_discards_only_access_transport_cookies() {
        for path in ["/workspace/api/rpc", "/workspace/api/events"] {
            for cookie in [
                "",
                " ",
                "CF_Authorization=fixture-access-jwt",
                "CF_Authorization=first; CF_Authorization=second",
            ] {
                assert_eq!(forwarded_cookie(path, cookie), None);
            }
            assert_eq!(
                forwarded_cookie(path, "CF_Authorization=fixture; pully_session=legacy"),
                Some("pully_session=legacy".into())
            );
        }
    }

    #[test]
    fn workspace_preserves_other_cookies_for_backend_rejection() {
        for cookie in [
            "pully_session=legacy",
            "cf_authorization=wrong-case",
            "CF_Authorization_extra=lookalike",
            "CF_Authorization",
            "CF_Authorization =malformed",
            "arbitrary=fixture; another=fixture",
        ] {
            assert_eq!(
                forwarded_cookie("/workspace/api/rpc", cookie),
                Some(cookie.into())
            );
        }
    }

    #[test]
    fn workspace_keeps_unicode_whitespace_and_malformed_delimiters_for_rejection() {
        for cookie in [
            "\u{00a0}CF_Authorization=fixture",
            "CF_Authorization\u{2003}=fixture",
            "\u{00a0}",
            ";;",
            "; ;",
        ] {
            assert_eq!(
                forwarded_cookie("/workspace/api/rpc", cookie),
                Some(cookie.into())
            );
        }
    }

    #[test]
    fn non_workspace_cookie_forwarding_is_unchanged() {
        for path in [
            "/api/rpc",
            "/api/personal/v1/session",
            "/api/public/v1/capabilities",
            "/download/staging/latest.json",
            "/workspace-other/api/rpc",
        ] {
            for cookie in ["", "CF_Authorization=fixture; pully_session=legacy"] {
                assert_eq!(forwarded_cookie(path, cookie), Some(cookie.into()));
            }
        }
    }
}
