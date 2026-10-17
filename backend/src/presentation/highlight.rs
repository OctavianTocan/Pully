//! Native grammar-aware highlighting with the exact Pierre theme assets used by
//! the existing web/CLI clients. Syntect scope/token boundaries differ from Shiki;
//! the native wire format, text, palettes and extension selection stay stable.
use anyhow::{anyhow, ensure, Context, Result};
use serde_json::{json, Value};
use std::sync::OnceLock;
use syntect::easy::HighlightLines;
use syntect::highlighting::{Color, StyleModifier, Theme, ThemeItem};
use syntect::parsing::{SyntaxReference, SyntaxSet};

const MAX_BYTES: usize = 512 * 1024;
const MAX_LINE_BYTES: usize = 32 * 1024;

struct Assets {
    syntax: SyntaxSet,
    dark: (Theme, Value),
    light: (Theme, Value),
}
static ASSETS: OnceLock<std::result::Result<Assets, String>> = OnceLock::new();
fn assets() -> Result<&'static Assets> {
    ASSETS
        .get_or_init(|| {
            (|| {
                Ok(Assets {
                    syntax: two_face::syntax::extra_newlines(),
                    dark: theme(include_str!("themes/pierre-dark.json"))?,
                    light: theme(include_str!("themes/pierre-light.json"))?,
                })
            })()
            .map_err(|e: anyhow::Error| e.to_string())
        })
        .as_ref()
        .map_err(|e| anyhow!("Native highlighting assets could not be loaded: {e}"))
}
fn color(value: &str) -> Result<Color> {
    let value = value
        .strip_prefix('#')
        .context("Theme colour must be hexadecimal")?;
    ensure!(
        matches!(value.len(), 6 | 8) && value.bytes().all(|b| b.is_ascii_hexdigit()),
        "Invalid theme colour"
    );
    Ok(Color {
        r: u8::from_str_radix(&value[..2], 16)?,
        g: u8::from_str_radix(&value[2..4], 16)?,
        b: u8::from_str_radix(&value[4..6], 16)?,
        a: if value.len() == 8 {
            u8::from_str_radix(&value[6..8], 16)?
        } else {
            255
        },
    })
}
fn theme(source: &str) -> Result<(Theme, Value)> {
    let source: Value = serde_json::from_str(source)?;
    let colors = &source["colors"];
    let mut theme = Theme::default();
    theme.name = source["name"].as_str().map(str::to_owned);
    theme.settings.foreground = Some(color(
        colors["editor.foreground"]
            .as_str()
            .context("Missing theme foreground")?,
    )?);
    theme.settings.background = Some(color(
        colors["editor.background"]
            .as_str()
            .context("Missing theme background")?,
    )?);
    for rule in source["tokenColors"]
        .as_array()
        .context("Missing theme rules")?
    {
        let scopes = match &rule["scope"] {
            Value::String(s) => s.clone(),
            Value::Array(a) => a
                .iter()
                .filter_map(Value::as_str)
                .collect::<Vec<_>>()
                .join(", "),
            _ => continue,
        };
        let Some(fg) = rule["settings"]["foreground"].as_str() else {
            continue;
        };
        // Syntect selectors support ancestor stacks but not TextMate's direct-
        // child combinator. Keep the same colours while lowering those few JSON
        // selectors to ancestor matches, rather than silently never matching them.
        let scopes = scopes.replace(" > ", " ");
        theme.scopes.push(ThemeItem {
            scope: scopes
                .parse()
                .map_err(|e| anyhow!("Invalid theme scope: {e}"))?,
            style: StyleModifier {
                foreground: Some(color(fg)?),
                ..StyleModifier::default()
            },
        });
    }
    let envelope = json!({"background":colors["editor.background"],"foreground":colors["editor.foreground"],"added":colors["diffEditor.insertedTextBackground"],"removed":colors["diffEditor.deletedTextBackground"],"gutter":colors["editorLineNumber.foreground"]});
    Ok((theme, envelope))
}
fn syntax<'a>(set: &'a SyntaxSet, path: &str) -> Option<&'a SyntaxReference> {
    let ext = path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    // This is the native API's existing allowlist. Do not interpret arbitrary
    // filenames as filesystem paths or load user-supplied syntax definitions.
    let canonical = match ext.as_str() {
        "ts" | "mts" | "cts" => "ts",
        "tsx" => "tsx",
        "js" | "mjs" | "cjs" => "js",
        "jsx" => "jsx",
        "json" => "json",
        "css" => "css",
        "scss" => "scss",
        "html" => "html",
        "vue" => "vue",
        "md" => "md",
        "py" => "py",
        "kt" | "kts" => "kt",
        "go" => "go",
        "rs" => "rs",
        "sh" => "sh",
        "yml" | "yaml" => "yaml",
        "java" => "java",
        "xml" => "xml",
        _ => return None,
    };
    set.find_syntax_by_extension(canonical)
        // The fancy-regex asset set intentionally excludes Babel's grammar;
        // its native JavaScript/TSX grammars still support these file families.
        .or_else(|| match canonical {
            "jsx" => set
                .find_syntax_by_extension("tsx")
                .or_else(|| set.find_syntax_by_extension("js")),
            _ => None,
        })
}
fn plain(line: &str) -> Value {
    json!([{"text":line,"color":""}])
}
pub(super) fn render(args: &Value) -> Result<Value> {
    let path = args["path"]
        .as_str()
        .filter(|s| !s.is_empty())
        .context("Highlighting needs a path.")?;
    let lines: Vec<&str> = args["lines"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .take(400)
        .collect();
    ensure!(
        lines
            .iter()
            .try_fold(0usize, |sum, line| sum.checked_add(line.len()))
            .is_some_and(|n| n <= MAX_BYTES),
        "Highlight input is too large"
    );
    let assets = assets()?;
    let dark = args["dark"] != false;
    let (theme, envelope) = if dark { &assets.dark } else { &assets.light };
    let Some(syntax) = syntax(&assets.syntax, path) else {
        return Ok(
            json!({"lines":lines.into_iter().map(plain).collect::<Vec<_>>(),"theme":envelope}),
        );
    };
    let mut highlighter = HighlightLines::new(syntax, theme);
    let foreground = envelope["foreground"].as_str().unwrap_or("");
    let mut output = Vec::with_capacity(lines.len());
    let mut plain_remaining = false;
    for line in lines {
        // A minified megabyte line is not useful to tokenize. Plain text is the
        // same native fallback used for grammar errors, never altered source.
        if plain_remaining || line.len() > MAX_LINE_BYTES {
            output.push(plain(line));
            // Skipping a line loses its multiline parser transitions. Do not
            // resume coloring with fabricated fresh state on later lines.
            plain_remaining = true;
            continue;
        }
        let with_newline = if line.ends_with('\n') {
            line.to_owned()
        } else {
            format!("{line}\n")
        };
        let mut tokens = Vec::new();
        let mut remaining = line.len();
        let mut failed = false;
        for physical_line in with_newline.split_inclusive('\n') {
            match highlighter.highlight_line(physical_line, &assets.syntax) {
                Ok(ranges) => {
                    for (style, text) in ranges {
                        let take = text.len().min(remaining);
                        remaining -= take;
                        if take > 0 {
                            let fg = style.foreground;
                            tokens.push(json!({"text":&text[..take],"color":format!("#{:02x}{:02x}{:02x}",fg.r,fg.g,fg.b)}));
                        }
                    }
                }
                Err(_) => {
                    failed = true;
                    break;
                }
            }
        }
        if failed || remaining != 0 {
            output.push(plain(line));
            plain_remaining = true;
        } else {
            if tokens.is_empty() {
                tokens.push(json!({"text":"","color":foreground}));
            }
            output.push(tokens.into());
        }
    }
    Ok(json!({"lines":output,"theme":envelope}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn oversized_multiline_comment_keeps_remaining_lines_plain() {
        let lines = vec![
            "/* comment".to_owned(),
            "x".repeat(MAX_LINE_BYTES + 1),
            "let still_inside_comment = true;".to_owned(),
            "*/ fn outside() {}".to_owned(),
        ];
        let result = render(&json!({"path":"a.rs","lines":lines})).unwrap();
        for (index, line) in lines.iter().enumerate().skip(1) {
            assert_eq!(result["lines"][index], plain(line));
        }
    }
    #[test]
    fn exact_pierre_theme_envelopes() {
        for (dark, background, foreground, added, removed) in [
            (true, "#0a0a0a", "#fafafa", "#07c4801a", "#ff2e3f1a"),
            (false, "#ffffff", "#0a0a0a", "#18a46c33", "#d52c3633"),
        ] {
            let result = render(&json!({"path":"a.rs","lines":[],"dark":dark})).unwrap();
            assert_eq!(
                result["theme"],
                json!({"background":background,"foreground":foreground,"added":added,"removed":removed,"gutter":"#737373"})
            );
        }
    }
    #[test]
    fn grammar_selection_covers_native_extensions() {
        let assets = assets().unwrap();
        for ext in [
            "ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "json", "css", "scss", "html",
            "vue", "md", "py", "kt", "kts", "go", "rs", "sh", "yml", "yaml", "java", "xml",
        ] {
            assert!(
                syntax(&assets.syntax, &format!("src/example.{ext}")).is_some(),
                "Missing {ext} grammar"
            );
        }
    }
    #[test]
    fn text_is_lossless_and_comments_keep_multiline_state() {
        let lines = [
            "/* begin",
            "const remains_comment = 1;",
            "end */ let text = \"雪😀\";",
            "",
            "\t",
            "line one\nline two",
        ];
        let result = render(&json!({"path":"src/a.ts","lines":lines})).unwrap();
        for (i, line) in lines.iter().enumerate() {
            let text = result["lines"][i]
                .as_array()
                .unwrap()
                .iter()
                .map(|t| t["text"].as_str().unwrap())
                .collect::<String>();
            assert_eq!(text, *line);
        }
        assert!(result["lines"][1]
            .as_array()
            .unwrap()
            .iter()
            .all(|t| t["color"] == "#737373"));
        assert!(result["lines"][2]
            .as_array()
            .unwrap()
            .iter()
            .any(|t| t["color"] != "#737373"));
    }
    #[test]
    fn native_input_normalization_and_unknown_language_fallback() {
        assert!(render(&json!({"lines":[]})).is_err());
        let result =
            render(&json!({"path":"a.unknown","lines":["fn x()",false,null,""],"dark":false}))
                .unwrap();
        assert_eq!(
            result["lines"],
            json!([[{"text":"fn x()","color":""}],[{"text":"","color":""}]])
        );
        assert_eq!(
            render(&json!({"path":"a.rs","lines":vec!["x";401]})).unwrap()["lines"]
                .as_array()
                .unwrap()
                .len(),
            400
        );
        assert!(render(&json!({"path":"a.rs","lines":["x".repeat(MAX_BYTES+1)]})).is_err());
    }
}
