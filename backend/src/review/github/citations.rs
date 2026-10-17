use super::Pull;
use std::sync::OnceLock;

pub(super) fn encode_segment(value: &str) -> String {
    let mut out = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.~".contains(&byte) {
            out.push(byte as char)
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}
pub(super) fn decode_path(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let high = (bytes[i + 1] as char).to_digit(16);
            let low = (bytes[i + 2] as char).to_digit(16);
            if let (Some(high), Some(low)) = (high, low) {
                out.push((high * 16 + low) as u8);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8(out).unwrap_or_else(|_| value.to_owned())
}
pub(super) fn cite_url(target: &str, pull: &Pull, head: &str) -> Option<String> {
    let target = target.trim();
    if target.is_empty() || target.starts_with('#') || target.starts_with("//") {
        return None;
    }
    static SCHEME: OnceLock<regex::Regex> = OnceLock::new();
    let scheme = SCHEME.get_or_init(|| regex::Regex::new(r"(?i)^[a-z][a-z0-9+.-]*:").unwrap());
    static NAME_LINE: OnceLock<regex::Regex> = OnceLock::new();
    let name_line = NAME_LINE.get_or_init(|| regex::Regex::new(r"^[^/]+:\d").unwrap());
    if scheme.is_match(target) && !name_line.is_match(target) {
        return None;
    }
    static PLACE: OnceLock<regex::Regex> = OnceLock::new();
    let place = PLACE.get_or_init(|| {
        regex::Regex::new(r"^([^#?\s:]+)(?::(\d+)|#L(\d+)(?:-L?(\d+))?)?$").unwrap()
    });
    let captures = place.captures(target)?;
    let decoded = decode_path(captures.get(1)?.as_str());
    let mut segments = Vec::new();
    if ["", ".", ".."].contains(&decoded.split('/').next_back()?) {
        return None;
    }
    for segment in decoded.split('/') {
        match segment {
            ".." => {
                segments.pop();
            }
            "" | "." => {}
            part => segments.push(part),
        }
    }
    if segments.is_empty() {
        return None;
    }
    let first = captures.get(2).or_else(|| captures.get(3));
    let anchor = first
        .map(|n| {
            format!(
                "#L{}{}",
                n.as_str(),
                captures
                    .get(4)
                    .map(|end| format!("-L{}", end.as_str()))
                    .unwrap_or_default()
            )
        })
        .unwrap_or_default();
    Some(format!(
        "https://github.com/{}/{}/blob/{}/{}{}",
        pull.owner,
        pull.repo,
        encode_segment(head),
        segments
            .into_iter()
            .map(encode_segment)
            .collect::<Vec<_>>()
            .join("/"),
        anchor
    ))
}
/// Rewrites root-relative repository mentions without changing fenced or inline code.
pub(super) fn github_cites(markdown: &str, pull: &Pull, head: &str) -> String {
    static LINK: OnceLock<regex::Regex> = OnceLock::new();
    let link = LINK
        .get_or_init(|| regex::Regex::new(r#"\]\(\s*([^()\s]+)((?:\s+"[^"]*")?\s*)\)"#).unwrap());
    static INLINE: OnceLock<regex::Regex> = OnceLock::new();
    let inline = INLINE.get_or_init(|| regex::Regex::new(r"`+[^`]*`+").unwrap());
    static FENCE_REGEX: OnceLock<regex::Regex> = OnceLock::new();
    let fence_regex =
        FENCE_REGEX.get_or_init(|| regex::Regex::new(r"^ {0,3}(`{3,}|~{3,})").unwrap());
    let mut fence: Option<(u8, usize)> = None;
    markdown
        .split('\n')
        .map(|line| {
            let marker = fence_regex.captures(line).and_then(|c| c.get(1));
            if let Some((kind, len)) = fence {
                if let Some(marker) = marker {
                    if marker.as_str().as_bytes()[0] == kind
                        && marker.len() >= len
                        && line[marker.end()..].trim().is_empty()
                    {
                        fence = None;
                    }
                }
                return line.to_owned();
            }
            if let Some(marker) = marker {
                fence = Some((marker.as_str().as_bytes()[0], marker.len()));
                return line.to_owned();
            }
            let replace = |s: &str| {
                link.replace_all(s, |captures: &regex::Captures| {
                    cite_url(&captures[1], pull, head)
                        .map(|url| format!("]({url}{})", &captures[2]))
                        .unwrap_or_else(|| captures[0].to_owned())
                })
                .into_owned()
            };
            let mut output = String::new();
            let mut offset = 0;
            for span in inline.find_iter(line) {
                output.push_str(&replace(&line[offset..span.start()]));
                output.push_str(span.as_str());
                offset = span.end();
            }
            output.push_str(&replace(&line[offset..]));
            output
        })
        .collect::<Vec<_>>()
        .join("\n")
}
