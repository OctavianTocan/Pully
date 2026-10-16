//! Pure Rust Mermaid layout, with a deliberately inert AndroidSVG output boundary.
use anyhow::{anyhow, bail, ensure, Context, Result};
use mermaid_rs_renderer::{
    compute_layout, ir::NodeStyle, parse_mermaid, parse_mermaid_strict, render_svg, DiagramKind,
    Graph, LayoutConfig, Theme,
};
use roxmltree::{Document, Node};
use serde_json::{json, Value};
use std::fmt::Write;

#[path = "xychart.rs"]
mod xychart;

const SVG_NS: &str = "http://www.w3.org/2000/svg";
const MAX_SOURCE: usize = 8_000;
const MAX_SVG: usize = 4 * 1024 * 1024;

#[derive(Clone, Copy)]
struct Palette {
    bg: &'static str,
    fg: &'static str,
    line: &'static str,
    accent: &'static str,
    muted: &'static str,
    surface: &'static str,
    border: &'static str,
}

impl Palette {
    fn new(dark: bool) -> Self {
        if dark {
            Self {
                bg: "#111111",
                fg: "#E5E5E5",
                line: "#737373",
                accent: "#5B9544",
                muted: "#A3A3A3",
                surface: "#1A1A1A",
                border: "#3A3A3A",
            }
        } else {
            Self {
                bg: "#FFFFFF",
                fg: "#262626",
                line: "#A3A3A3",
                accent: "#49833E",
                muted: "#525252",
                surface: "#F6F6F6",
                border: "#D4D4D4",
            }
        }
    }

    fn theme(self) -> Theme {
        let mut theme = Theme::modern();
        theme.font_family = "sans-serif".into();
        theme.background = self.bg.into();
        theme.primary_color = self.surface.into();
        theme.primary_text_color = self.fg.into();
        theme.primary_border_color = self.border.into();
        theme.line_color = self.line.into();
        theme.secondary_color = self.surface.into();
        theme.tertiary_color = self.bg.into();
        theme.edge_label_background = self.bg.into();
        theme.cluster_background = self.bg.into();
        theme.cluster_border = self.border.into();
        theme.sequence_actor_fill = self.surface.into();
        theme.sequence_actor_border = self.border.into();
        theme.sequence_actor_line = self.line.into();
        theme.sequence_note_fill = self.surface.into();
        theme.sequence_note_border = self.border.into();
        theme.sequence_activation_fill = self.surface.into();
        theme.sequence_activation_border = self.border.into();
        theme.text_color = self.fg.into();
        theme
    }
}

use crate::js_whitespace;

pub(super) fn render(args: &Value) -> Result<Value> {
    let source = args["source"]
        .as_str()
        .filter(|source| {
            !source.trim_matches(js_whitespace).is_empty()
                && source.encode_utf16().take(MAX_SOURCE + 1).count() <= MAX_SOURCE
        })
        .context("That needs Mermaid source.")?;
    let palette = Palette::new(args["dark"] == true);
    // The renderer is a native library. Malformed input must not unwind the RPC
    // handler, and parser diagnostics must remain errors rather than fake diagrams.
    let svg = std::panic::catch_unwind(|| draw(source, palette))
        .map_err(|_| anyhow!("The diagram could not be drawn: invalid Mermaid diagram."))?
        .map_err(|error| anyhow!("The diagram could not be drawn: {error}"))?;
    Ok(json!({ "svg": svg }))
}

fn draw(source: &str, palette: Palette) -> Result<String> {
    let decoded = decode_entities(source);
    let source = decoded.trim_start_matches(js_whitespace);
    let mut parsed = match parse_mermaid_strict(source) {
        // Mermaid permits implicit participants alongside explicit declarations.
        // This library's optional strict preflight is unnecessarily restrictive.
        Err(mermaid_rs_renderer::ParseError::UnknownParticipant { .. }) => parse_mermaid(source)?,
        other => other?,
    };
    if parsed.graph.kind == DiagramKind::XYChart {
        return xychart::draw(source, palette);
    }
    ensure!(
        matches!(
            parsed.graph.kind,
            DiagramKind::Flowchart
                | DiagramKind::State
                | DiagramKind::Sequence
                | DiagramKind::Class
                | DiagramKind::Er
        ),
        "Unsupported Mermaid diagram type."
    );
    ensure!(!parsed.graph.nodes.is_empty(), "The diagram has no nodes.");
    // Mermaid's shorthand can expand a small source into a Cartesian product of
    // edges. Bound the expanded graph before the layout algorithm runs.
    ensure!(
        parsed.graph.nodes.len() <= 512
            && parsed.graph.edges.len() <= 2_048
            && parsed.graph.subgraphs.len() <= 64,
        "The diagram is too complex."
    );
    validate_graph(&mut parsed.graph)?;
    // Do not apply source-supplied init config: the native API owns the palette,
    // fonts, resources and layout configuration, matching the former TypeScript native API boundary.
    let mut theme = palette.theme();
    if parsed.graph.kind == DiagramKind::Er {
        theme.cluster_background = mix(palette.fg, palette.bg, 0.05);
    }
    let config = LayoutConfig::default();
    let mut layout = compute_layout(&parsed.graph, &theme, &config);
    if parsed.graph.kind == DiagramKind::Er {
        for node in layout.nodes.values_mut() {
            node.style
                .fill
                .get_or_insert_with(|| palette.surface.into());
        }
    }
    ensure!(
        layout.width.is_finite()
            && layout.height.is_finite()
            && layout.width > 0.0
            && layout.height > 0.0,
        "The diagram has invalid dimensions."
    );
    let svg = render_svg(&layout, &theme, &config);
    ensure!(svg.len() <= MAX_SVG, "The rendered diagram is too large.");
    native_svg(&svg, palette, parsed.graph.kind)
}

fn validate_graph(graph: &mut Graph) -> Result<()> {
    // Click links are not part of a native diagram image. Removing them also
    // prevents javascript/data/file URLs and unsolicited external navigation.
    graph.node_links.clear();
    for node in graph.nodes.values_mut() {
        node.icon = None;
    }
    for group in &mut graph.subgraphs {
        group.icon = None;
    }
    for style in graph
        .class_defs
        .values()
        .chain(graph.node_styles.values())
        .chain(graph.subgraph_styles.values())
    {
        validate_style(style)?;
    }
    for style in graph
        .edge_styles
        .values()
        .chain(graph.edge_style_default.iter())
    {
        for color in [&style.stroke, &style.label_color].into_iter().flatten() {
            plain_color(color)?;
        }
        validate_dash(style.dasharray.as_deref())?;
        validate_width(style.stroke_width)?;
    }
    for group in &graph.sequence_boxes {
        if let Some(color) = &group.color {
            plain_color(color)?;
        }
    }
    Ok(())
}

fn validate_style(style: &NodeStyle) -> Result<()> {
    for color in [
        &style.fill,
        &style.stroke,
        &style.text_color,
        &style.line_color,
    ]
    .into_iter()
    .flatten()
    {
        plain_color(color)?;
    }
    validate_dash(style.stroke_dasharray.as_deref())?;
    validate_width(style.stroke_width)
}

fn validate_width(width: Option<f32>) -> Result<()> {
    ensure!(
        width.is_none_or(|n| n.is_finite() && (0.0..=100.0).contains(&n)),
        "Invalid Mermaid stroke width."
    );
    Ok(())
}

fn validate_dash(dash: Option<&str>) -> Result<()> {
    if let Some(dash) = dash {
        ensure!(
            dash == "none"
                || (!dash.is_empty()
                    && dash
                        .split(|ch: char| ch == ',' || ch.is_whitespace())
                        .filter(|n| !n.is_empty())
                        .all(|n| n
                            .parse::<f64>()
                            .is_ok_and(|n| n.is_finite() && (0.0..=10_000.0).contains(&n)))),
            "Invalid Mermaid stroke pattern."
        );
    }
    Ok(())
}

/// Convert renderer-generated HSL/RGB to colours AndroidSVG understands. Input
/// styles may contain simple colours, never CSS functions that fetch resources.
fn plain_color(value: &str) -> Result<String> {
    let value = value.trim();
    if let Some(hex) = value.strip_prefix('#') {
        ensure!(
            (hex.len() == 3 || hex.len() == 6) && hex.bytes().all(|b| b.is_ascii_hexdigit()),
            "Invalid Mermaid colour."
        );
        return Ok(value.to_owned());
    }
    if !value.is_empty() && value.len() <= 32 && value.bytes().all(|b| b.is_ascii_alphabetic()) {
        return Ok(value.to_owned());
    }
    let (kind, values) = value
        .split_once('(')
        .context("Unsupported Mermaid colour.")?;
    let values = values
        .strip_suffix(')')
        .context("Invalid Mermaid colour.")?;
    let values: Vec<_> = values.split(',').map(str::trim).collect();
    ensure!(values.len() == 3, "Unsupported Mermaid colour.");
    let number = |value: &str| -> Result<f64> {
        let n: f64 = value.parse().context("Invalid Mermaid colour.")?;
        ensure!(n.is_finite(), "Invalid Mermaid colour.");
        Ok(n)
    };
    let rgb = if kind.eq_ignore_ascii_case("rgb") {
        let mut rgb = [0.0; 3];
        for (i, value) in values.iter().enumerate() {
            rgb[i] = if let Some(pct) = value.strip_suffix('%') {
                number(pct)?.clamp(0.0, 100.0) * 2.55
            } else {
                number(value)?.clamp(0.0, 255.0)
            };
        }
        rgb
    } else if kind.eq_ignore_ascii_case("hsl") {
        let h = number(values[0])?.rem_euclid(360.0) / 60.0;
        let s = number(
            values[1]
                .strip_suffix('%')
                .context("Invalid HSL saturation.")?,
        )?
        .clamp(0.0, 100.0)
            / 100.0;
        let l = number(
            values[2]
                .strip_suffix('%')
                .context("Invalid HSL lightness.")?,
        )?
        .clamp(0.0, 100.0)
            / 100.0;
        let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
        let x = c * (1.0 - (h.rem_euclid(2.0) - 1.0).abs());
        let rgb = match h as usize {
            0 => [c, x, 0.0],
            1 => [x, c, 0.0],
            2 => [0.0, c, x],
            3 => [0.0, x, c],
            4 => [x, 0.0, c],
            _ => [c, 0.0, x],
        };
        rgb.map(|n| (n + l - c / 2.0) * 255.0)
    } else {
        bail!("Unsupported Mermaid colour.")
    };
    Ok(format!(
        "#{:02X}{:02X}{:02X}",
        rgb[0].round() as u8,
        rgb[1].round() as u8,
        rgb[2].round() as u8
    ))
}

fn decode_entities(source: &str) -> String {
    let mut out = String::with_capacity(source.len());
    let mut rest = source;
    while let Some(start) = rest.find('&') {
        out.push_str(&rest[..start]);
        rest = &rest[start..];
        let entity = rest
            .find(';')
            .filter(|end| *end <= 16)
            .map(|end| (&rest[1..end], end));
        let decoded = entity.and_then(|(entity, end)| {
            let ch = match entity {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                _ => entity
                    .strip_prefix("#x")
                    .and_then(|n| u32::from_str_radix(n, 16).ok())
                    .or_else(|| entity.strip_prefix('#').and_then(|n| n.parse().ok()))
                    .and_then(char::from_u32),
            };
            ch.map(|ch| (ch, end))
        });
        if let Some((ch, end)) = decoded {
            out.push(ch);
            rest = &rest[end + 1..];
        } else {
            out.push('&');
            rest = &rest[1..];
        }
    }
    out.push_str(rest);
    out
}

fn native_svg(svg: &str, palette: Palette, kind: DiagramKind) -> Result<String> {
    // roxmltree rejects DTDs by default; no entity expansion or external loads.
    let doc = Document::parse(svg).context("The renderer produced invalid SVG.")?;
    let root = doc.root_element();
    ensure!(
        root.tag_name().name() == "svg" && root.tag_name().namespace() == Some(SVG_NS),
        "The renderer did not produce SVG."
    );
    let background = root.children().find(Node::is_element).filter(|node| {
        node.tag_name().name() == "rect" && node.attribute("fill") == Some(palette.bg)
    });
    ensure!(
        background.is_some(),
        "The renderer produced an unexpected canvas."
    );
    let mut out = String::with_capacity(svg.len());
    write_node(root, background, palette, kind, false, 0, &mut out)?;
    Ok(out)
}

fn write_node(
    node: Node<'_, '_>,
    background: Option<Node<'_, '_>>,
    palette: Palette,
    kind: DiagramKind,
    marker: bool,
    depth: usize,
    out: &mut String,
) -> Result<()> {
    ensure!(depth <= 128, "The SVG is too deeply nested.");
    if Some(node) == background {
        return Ok(());
    }
    if node.is_text() {
        escape(node.text().unwrap_or_default(), out);
        return Ok(());
    }
    if !node.is_element() {
        return Ok(());
    }
    let name = node.tag_name().name();
    ensure!(
        node.tag_name().namespace() == Some(SVG_NS)
            && matches!(
                name,
                "svg"
                    | "g"
                    | "defs"
                    | "marker"
                    | "path"
                    | "rect"
                    | "circle"
                    | "ellipse"
                    | "polygon"
                    | "polyline"
                    | "line"
                    | "text"
                    | "tspan"
                    | "title"
                    | "desc"
            ),
        "The renderer produced unsupported SVG content."
    );
    let marker = marker || name == "marker";
    write!(out, "<{name}")?;
    if name == "svg" {
        write!(out, " xmlns=\"{SVG_NS}\"")?;
    }
    for attr in node.attributes() {
        let key = attr.name();
        // Root max-width/aspect-ratio CSS is web-only; all actual drawing
        // properties of the supported families are presentation attributes.
        if key == "style" && name == "svg" {
            continue;
        }
        if key == "class" || key.starts_with("data-") {
            continue;
        }
        ensure!(
            attr.namespace().is_none()
                && matches!(
                    key,
                    "id" | "width"
                        | "height"
                        | "viewBox"
                        | "preserveAspectRatio"
                        | "x"
                        | "y"
                        | "x1"
                        | "x2"
                        | "y1"
                        | "y2"
                        | "cx"
                        | "cy"
                        | "r"
                        | "rx"
                        | "ry"
                        | "dx"
                        | "dy"
                        | "d"
                        | "points"
                        | "transform"
                        | "fill"
                        | "stroke"
                        | "fill-opacity"
                        | "stroke-opacity"
                        | "opacity"
                        | "stroke-width"
                        | "stroke-dasharray"
                        | "stroke-linecap"
                        | "stroke-linejoin"
                        | "stroke-miterlimit"
                        | "fill-rule"
                        | "clip-rule"
                        | "font-family"
                        | "font-size"
                        | "font-weight"
                        | "font-style"
                        | "text-anchor"
                        | "dominant-baseline"
                        | "alignment-baseline"
                        | "text-decoration"
                        | "textLength"
                        | "lengthAdjust"
                        | "marker-start"
                        | "marker-end"
                        | "marker-mid"
                        | "markerWidth"
                        | "markerHeight"
                        | "markerUnits"
                        | "refX"
                        | "refY"
                        | "orient"
                ),
            "The renderer produced an unsupported SVG attribute."
        );
        let mut value = attr.value().to_owned();
        if matches!(key, "fill" | "stroke") {
            value = plain_color(&value)?;
            if key == "fill"
                && matches!(name, "text" | "tspan")
                && node.ancestors().any(|parent| {
                    parent.attribute("class").is_some_and(|class| {
                        class.split_whitespace().any(|part| part == "edgeLabel")
                    })
                })
                && value.eq_ignore_ascii_case(palette.fg)
            {
                value = palette.muted.into();
            }
            if kind == DiagramKind::State
                && key == "fill"
                && name == "rect"
                && attr.value().starts_with("hsl(")
            {
                // This renderer derives default state-group header/body colours
                // from surface; match beautiful-mermaid's group tokens instead.
                let dark = palette.bg == "#111111";
                if value == if dark { "#101010" } else { "#ECECEC" } {
                    value = mix(palette.fg, palette.bg, 0.05);
                } else if value == if dark { "#343434" } else { "#FFFFFF" } {
                    value = palette.bg.into();
                }
            }
            if marker && value.eq_ignore_ascii_case(palette.line) {
                value = palette.accent.into();
            }
            // The native renderer's ER key badges otherwise use its own palette.
            if kind == DiagramKind::Er {
                if name == "rect"
                    && matches!(
                        value.as_str(),
                        "#1D4ED8" | "#0F766E" | "#7C3AED" | "#475569"
                    )
                {
                    value = mix(palette.fg, palette.bg, 0.10);
                } else if name == "text"
                    && (matches!(node.text(), Some("PK" | "FK" | "UK"))
                        || value.eq_ignore_ascii_case(palette.line)
                        || (value.eq_ignore_ascii_case(palette.fg)
                            && node.attribute("text-anchor") == Some("start")))
                {
                    value = palette.muted.into();
                }
            }
        } else if key.starts_with("marker-") {
            ensure!(
                value
                    .strip_prefix("url(#")
                    .and_then(|s| s.strip_suffix(')'))
                    .is_some_and(|id| !id.is_empty()
                        && id
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')),
                "The SVG references an external resource."
            );
        } else {
            ensure!(
                !value.contains("url(")
                    && !value.contains("var(")
                    && !value.contains("color-mix(")
                    && !value.contains('@'),
                "The SVG contains unsupported CSS."
            );
        }
        // AndroidSVG supports auto, and start markers already have reversed paths.
        if key == "orient" && value == "auto-start-reverse" {
            value = "auto".into();
        }
        write!(out, " {key}=\"")?;
        escape(&value, out);
        out.push('"');
    }
    out.push('>');
    for child in node.children() {
        write_node(child, background, palette, kind, marker, depth + 1, out)?;
    }
    write!(out, "</{name}>")?;
    Ok(())
}

fn mix(a: &str, b: &str, weight: f64) -> String {
    let channel = |color: &str, i| u8::from_str_radix(&color[i..i + 2], 16).unwrap_or(0) as f64;
    let mut out = String::from("#");
    for i in [1, 3, 5] {
        let _ = write!(
            out,
            "{:02x}",
            (channel(a, i) * weight + channel(b, i) * (1.0 - weight)).round() as u8
        );
    }
    out
}

fn escape(value: &str, out: &mut String) {
    for ch in value.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(ch),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn svg(source: &str, dark: Value) -> String {
        render(&json!({"source": source, "dark": dark})).unwrap()["svg"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    #[test]
    fn native_geometry_for_all_five_diagram_families() {
        for (source, labels) in [
            ("flowchart TD\n A[Save] --> B{Cached?}\n B -->|no| C[Parse]", vec!["Save", "Cached?", "Parse"]),
            ("stateDiagram-v2\n [*] --> Idle\n Idle --> Running: start\n Running --> [*]", vec!["Idle", "Running", "start"]),
            ("sequenceDiagram\n participant Alice\n participant Bob\n Alice->>Bob: Hello\n Bob-->>Alice: Done", vec!["Alice", "Bob", "Hello", "Done"]),
            ("classDiagram\n class Animal {\n +String name\n +speak()\n }\n Animal <|-- Dog", vec!["Animal", "Dog", "name", "speak"]),
            ("erDiagram\n CUSTOMER ||--o{ ORDER : places\n CUSTOMER {\n int id PK\n string name\n }", vec!["CUSTOMER", "ORDER", "places", "id", "name"]),
        ] {
            for dark in [false, true] {
                let output = svg(source, json!(dark));
                let doc = Document::parse(&output).unwrap();
                assert!(doc.descendants().any(|n| n.has_tag_name("path")), "{source}");
                assert!(doc.descendants().any(|n| n.has_tag_name("rect") || n.has_tag_name("circle")), "{source}");
                for label in &labels { assert!(output.contains(label), "Missing {label}: {output}"); }
                for forbidden in ["var(", "color-mix(", "@import", "foreignObject", "display=", "<style", "<image", "href="] {
                    assert!(!output.contains(forbidden), "{forbidden}: {output}");
                }
                let first = doc.root_element().children().find(Node::is_element).unwrap();
                assert_eq!(first.tag_name().name(), "defs", "Canvas must be transparent");
            }
        }
    }

    #[test]
    fn palettes_match_native_api_and_only_true_selects_dark() {
        let source = "flowchart LR\n A[One] --> B[Two]";
        for dark in [
            Value::Null,
            json!(false),
            json!("true"),
            json!(1),
            json!({}),
        ] {
            let output = svg(source, dark);
            for color in ["#262626", "#A3A3A3", "#49833E", "#F6F6F6", "#D4D4D4"] {
                assert!(output.contains(color), "Missing light colour {color}");
            }
        }
        let output = svg(source, json!(true));
        for color in ["#E5E5E5", "#737373", "#5B9544", "#1A1A1A", "#3A3A3A"] {
            assert!(output.contains(color), "Missing dark colour {color}");
        }
    }

    #[test]
    fn source_validation_matches_javascript_utf16_length() {
        for args in [
            json!({}),
            json!({"source": 12}),
            json!({"source": " \n\t\u{feff}"}),
            json!({"source": "x".repeat(8_001)}),
        ] {
            assert_eq!(
                render(&args).unwrap_err().to_string(),
                "That needs Mermaid source."
            );
        }
        let prefix = "flowchart TD\n A[OK]\n%%";
        let mut exact = format!("{prefix}{}", "😀".repeat((MAX_SOURCE - prefix.len()) / 2));
        if exact.encode_utf16().count() < MAX_SOURCE {
            exact.push('x');
        }
        assert_eq!(exact.encode_utf16().count(), MAX_SOURCE);
        assert!(render(&json!({"source": exact})).is_ok());
        let over = format!("{prefix}{}", "😀".repeat(MAX_SOURCE / 2));
        assert_eq!(
            render(&json!({"source": over})).unwrap_err().to_string(),
            "That needs Mermaid source."
        );
    }

    #[test]
    fn malformed_source_is_an_error_never_source_text_svg() {
        for source in [
            "not a diagram",
            "flowchart TD\n subgraph group\n A --> B",
            "flowchart TD\n --> B",
            "pie\n \"no\": 2",
        ] {
            assert!(render(&json!({"source": source})).is_err(), "{source}");
        }
    }

    #[test]
    fn supports_subgraphs_notes_members_styles_and_cycles() {
        for source in [
            "flowchart LR\n subgraph Group\n A[Start] --> B{Choice}\n B --> A\n end\n classDef good fill:#123456,stroke:#654321\n class A good",
            "stateDiagram-v2\n state Active {\n [*] --> Waiting\n Waiting --> Ready\n }\n note right of Active: Running",
            "sequenceDiagram\n participant A\n participant B\n autonumber\n A->>+B: request\n alt success\n B-->>-A: response\n else failure\n B-->>A: error\n end\n Note over A,B: A note",
            "classDiagram\n class Bird {\n +String name\n +fly() void\n }\n Bird <|-- Duck",
        ] {
            let output = svg(source, json!(true));
            assert!(Document::parse(&output).is_ok());
            assert!(!output.contains("hsl("));
        }
    }

    #[test]
    fn output_is_inert_even_with_clicks_and_markup_labels() {
        let output = svg("flowchart LR\n A[\"<script>alert(1)</script> & text\"] --> B\n click A \"javascript:alert(1)\"", json!(false));
        assert!(!output.contains("<script"));
        assert!(!output.contains("javascript:"));
        assert!(!output.contains("href="));
        for color in [
            "url(https://example.com/a)",
            "var(--secret)",
            "red\" onload=\"alert(1)",
            "#aabbcc\"><script>",
        ] {
            assert!(plain_color(color).is_err());
        }
        for input in [
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect fill=\"#FFFFFF\"/><script>alert(1)</script></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect fill=\"#FFFFFF\"/><path onload=\"alert(1)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect fill=\"#FFFFFF\"/><path marker-end=\"url(https://example.com/a)\"/></svg>",
        ] {
            assert!(native_svg(input, Palette::new(false), DiagramKind::Flowchart).is_err());
        }
    }

    #[test]
    fn xy_dispatch_entities_and_implicit_sequence_participants_are_supported() {
        let output = svg(
            "xychart-beta horizontal\n x-axis [Jan, Feb]\n bar [1, 2]",
            json!(true),
        );
        assert!(output.contains("Jan") && output.contains("#5B9544"));
        let output = svg(
            "sequenceDiagram\n participant Alice\n Alice-&gt;&gt;Bob: A &amp; B",
            json!(false),
        );
        assert!(output.contains("Bob") && output.contains("A &amp; B"));
        assert_eq!(
            decode_entities("&lt;b&gt; &#65; &#x1F600; &unknown;"),
            "<b> A 😀 &unknown;"
        );
    }

    #[test]
    fn colours_flatten_without_css() {
        assert_eq!(plain_color("hsl(120, 100%, 25%)").unwrap(), "#008000");
        assert_eq!(plain_color("rgb(255, 0, 128)").unwrap(), "#FF0080");
        assert_eq!(mix("#E5E5E5", "#111111", 0.10), "#262626");
    }
}
