//! XY-chart semantics in beautiful-mermaid 1.1.3 extend the crate's renderer:
//! horizontal charts, numeric x axes, negative values, titles and mixed series.
use super::{escape, mix, native_svg, plain_color, Palette, SVG_NS};
use anyhow::{bail, ensure, Context, Result};
use mermaid_rs_renderer::DiagramKind;
use std::fmt::Write;

#[derive(Default)]
struct Axis {
    title: Option<String>,
    categories: Vec<String>,
    range: Option<(f64, f64)>,
}
struct Series {
    bar: bool,
    values: Vec<f64>,
}
#[derive(Default)]
struct Chart {
    horizontal: bool,
    title: Option<String>,
    x: Axis,
    y: Axis,
    series: Vec<Series>,
}

pub(super) fn draw(source: &str, palette: Palette) -> Result<String> {
    let chart = parse(source)?;
    ensure!(!chart.series.is_empty(), "The chart has no data series.");
    let count = chart
        .series
        .iter()
        .map(|s| s.values.len())
        .max()
        .unwrap_or(0)
        .max(chart.x.categories.len());
    ensure!(
        count > 0 && count <= 1_024 && chart.series.len() <= 64,
        "The chart is too complex."
    );
    let min = chart
        .series
        .iter()
        .flat_map(|s| &s.values)
        .copied()
        .reduce(f64::min)
        .unwrap_or(0.0);
    let max = chart
        .series
        .iter()
        .flat_map(|s| &s.values)
        .copied()
        .reduce(f64::max)
        .unwrap_or(1.0);
    let span = if max == min { 1.0 } else { max - min };
    let (min, max) = chart.y.range.unwrap_or_else(|| {
        let mut min = min - span * 0.1;
        if min > 0.0 && min < span * 0.5 {
            min = 0.0;
        }
        (min, max + span * 0.1)
    });
    ensure!(
        min.is_finite() && max.is_finite() && max > min && (max - min).is_finite(),
        "The chart needs an increasing finite value range."
    );
    let left = 110.0;
    let top = if chart.title.is_some() { 65.0 } else { 30.0 };
    let width = 560.0;
    let height = 320.0;
    let canvas_width = left + width + 30.0;
    let legend_columns = 5;
    let canvas_height = if chart.series.len() > 1 {
        top + height + 110.0 + chart.series.len().div_ceil(legend_columns) as f64 * 22.0
    } else {
        top + height + 100.0
    };
    let mut svg = format!("<svg xmlns=\"{SVG_NS}\" width=\"{canvas_width}\" height=\"{canvas_height}\" viewBox=\"0 0 {canvas_width} {canvas_height}\"><rect width=\"{canvas_width}\" height=\"{canvas_height}\" fill=\"{}\"/><defs></defs>", palette.bg);
    let point = |category: f64, value: f64| {
        let ratio = ((value - min) / (max - min)).clamp(0.0, 1.0);
        if chart.horizontal {
            (left + ratio * width, top + category * height)
        } else {
            (left + category * width, top + (1.0 - ratio) * height)
        }
    };
    let grid = mix(palette.fg, palette.bg, 0.12);
    for i in 0..=5 {
        let value = min + (max - min) * i as f64 / 5.0;
        let (x1, y1) = point(0.0, value);
        let (x2, y2) = point(1.0, value);
        write!(svg, "<line x1=\"{x1:.2}\" y1=\"{y1:.2}\" x2=\"{x2:.2}\" y2=\"{y2:.2}\" stroke=\"{grid}\" stroke-width=\"1\"/>")?;
        let label = number_label(value);
        if chart.horizontal {
            text(
                &mut svg,
                x1,
                top + height + 23.0,
                &label,
                palette.muted,
                "middle",
                12.0,
            )?;
        } else {
            text(
                &mut svg,
                left - 12.0,
                y1 + 4.0,
                &label,
                palette.muted,
                "end",
                12.0,
            )?;
        }
    }
    let bar_count = chart
        .series
        .iter()
        .filter(|series| series.bar)
        .count()
        .max(1);
    let category_position = |index: usize| {
        if chart.x.range.is_some()
            && bar_count == 1
            && !chart.series.iter().any(|s| s.bar)
            && count > 1
        {
            index as f64 / (count - 1) as f64
        } else {
            (index as f64 + 0.5) / count as f64
        }
    };
    let bar_step = if chart.horizontal { height } else { width } / count as f64;
    let bar_width = bar_step * 0.75 / bar_count as f64;
    let baseline = 0.0_f64.clamp(min, max);
    let mut bar_index = 0;
    for (index, series) in chart.series.iter().enumerate() {
        let color = series_color(index, palette)?;
        if series.bar {
            for (i, value) in series.values.iter().enumerate() {
                let (cx, cy) = point(category_position(i), *value);
                let (bx, by) = point(category_position(i), baseline);
                let offset = -bar_step * 0.375 + bar_index as f64 * bar_width;
                let (x, y, w, h) = if chart.horizontal {
                    (cx.min(bx), cy + offset, (cx - bx).abs(), bar_width)
                } else {
                    (cx + offset, cy.min(by), bar_width, (cy - by).abs())
                };
                write!(svg, "<rect x=\"{x:.2}\" y=\"{y:.2}\" width=\"{w:.2}\" height=\"{h:.2}\" rx=\"2\" fill=\"{}\" stroke=\"{color}\" stroke-width=\"1.5\"/>", mix(&color, palette.bg, 0.25))?;
            }
            bar_index += 1;
        } else {
            let mut d = String::new();
            for (i, value) in series.values.iter().enumerate() {
                let (x, y) = point(category_position(i), *value);
                write!(d, "{} {x:.2} {y:.2} ", if i == 0 { "M" } else { "L" })?;
            }
            write!(svg, "<path d=\"{d}\" fill=\"none\" stroke=\"{color}\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>")?;
            if series.values.len() <= 12 {
                for (i, value) in series.values.iter().enumerate() {
                    let (x, y) = point(category_position(i), *value);
                    write!(
                        svg,
                        "<circle cx=\"{x:.2}\" cy=\"{y:.2}\" r=\"3\" fill=\"{color}\"/>"
                    )?;
                }
            }
        }
    }
    let tick_step = count
        .div_ceil(if chart.horizontal { 12 } else { 10 })
        .max(1);
    for i in (0..count).step_by(tick_step) {
        let label = if let Some(label) = chart.x.categories.get(i) {
            label.clone()
        } else if let Some((min, max)) = chart.x.range {
            number_label(
                min + (max - min)
                    * if count == 1 {
                        0.0
                    } else {
                        i as f64 / (count - 1) as f64
                    },
            )
        } else {
            (i + 1).to_string()
        };
        let category = category_position(i);
        if chart.horizontal {
            text(
                &mut svg,
                left - 12.0,
                top + category * height + 4.0,
                &label,
                palette.muted,
                "end",
                12.0,
            )?;
        } else {
            text(
                &mut svg,
                left + category * width,
                top + height + 23.0,
                &label,
                palette.muted,
                "middle",
                12.0,
            )?;
        }
    }
    if let Some(title) = &chart.title {
        text(
            &mut svg,
            left + width / 2.0,
            28.0,
            title,
            palette.fg,
            "middle",
            18.0,
        )?;
    }
    let (bottom_title, side_title) = if chart.horizontal {
        (&chart.y.title, &chart.x.title)
    } else {
        (&chart.x.title, &chart.y.title)
    };
    if let Some(title) = bottom_title {
        text(
            &mut svg,
            left + width / 2.0,
            top + height + 58.0,
            title,
            palette.muted,
            "middle",
            14.0,
        )?;
    }
    if let Some(title) = side_title {
        write!(
            svg,
            "<g transform=\"translate(25 {}) rotate(-90)\">",
            top + height / 2.0
        )?;
        text(&mut svg, 0.0, 0.0, title, palette.muted, "middle", 14.0)?;
        svg.push_str("</g>");
    }
    if chart.series.len() > 1 {
        // Wrap legends so large-but-bounded series counts remain visible.
        let mut bars = 0;
        let mut lines = 0;
        let cols = legend_columns;
        for (index, series) in chart.series.iter().enumerate() {
            let (name, count) = if series.bar {
                bars += 1;
                ("Bar", bars)
            } else {
                lines += 1;
                ("Line", lines)
            };
            let x = left + (index % cols) as f64 * 110.0;
            let y = top + height + 88.0 + (index / cols) as f64 * 22.0;
            let color = series_color(index, palette)?;
            write!(svg, "<line x1=\"{x:.2}\" y1=\"{y:.2}\" x2=\"{:.2}\" y2=\"{y:.2}\" stroke=\"{color}\" stroke-width=\"3\"/>", x + 16.0)?;
            text(
                &mut svg,
                x + 23.0,
                y + 4.0,
                &format!("{name} {count}"),
                palette.muted,
                "start",
                12.0,
            )?;
        }
    }
    svg.push_str("</svg>");
    native_svg(&svg, palette, DiagramKind::XYChart)
}

fn parse(source: &str) -> Result<Chart> {
    let mut chart = Chart::default();
    for line in source
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty() && !s.starts_with("%%"))
    {
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("xychart") {
            chart.horizontal = lower.split_whitespace().any(|part| part == "horizontal");
        } else if let Some(value) = line.strip_prefix("title ") {
            chart.title = Some(unquote(value.trim()).to_owned());
        } else if let Some(value) = line.strip_prefix("x-axis ") {
            chart.x = axis(value)?;
        } else if let Some(value) = line.strip_prefix("y-axis ") {
            chart.y = axis(value)?;
        } else if let Some((bar, rest)) = line
            .strip_prefix("bar ")
            .map(|s| (true, s))
            .or_else(|| line.strip_prefix("line ").map(|s| (false, s)))
        {
            let values = rest
                .trim()
                .strip_prefix('[')
                .and_then(|s| s.strip_suffix(']'))
                .context("Invalid chart series.")?
                .split(',')
                .map(|s| finite(s.trim()))
                .collect::<Result<Vec<_>>>()?;
            ensure!(!values.is_empty(), "Empty chart series.");
            chart.series.push(Series { bar, values });
        }
    }
    Ok(chart)
}

fn axis(value: &str) -> Result<Axis> {
    let mut axis = Axis::default();
    let mut value = value.trim();
    if let Some(rest) = value.strip_prefix('"') {
        let end = rest.find('"').context("Unclosed chart axis title.")?;
        axis.title = Some(rest[..end].to_owned());
        value = rest[end + 1..].trim();
    }
    if let Some(rest) = value.strip_prefix('[') {
        let values = rest
            .strip_suffix(']')
            .context("Unclosed chart category array.")?;
        axis.categories = values
            .split(',')
            .map(|s| unquote(s.trim()).to_owned())
            .collect();
    } else if let Some((min, max)) = value.split_once("-->") {
        let (min, max) = (finite(min.trim())?, finite(max.trim())?);
        ensure!(
            max > min && (max - min).is_finite(),
            "The chart needs an increasing finite axis range."
        );
        axis.range = Some((min, max));
    } else if !value.is_empty() {
        bail!("Invalid chart axis.");
    }
    Ok(axis)
}
fn finite(value: &str) -> Result<f64> {
    let value: f64 = value.parse().context("Invalid chart number.")?;
    ensure!(
        value.is_finite() && value.abs() <= 1.0e100,
        "The chart needs finite numbers."
    );
    Ok(value)
}
fn unquote(value: &str) -> &str {
    value
        .strip_prefix('"')
        .and_then(|s| s.strip_suffix('"'))
        .unwrap_or(value)
}
fn number_label(n: f64) -> String {
    if n.abs() >= 1.0e7 || (n != 0.0 && n.abs() < 0.001) {
        return format!("{n:.2e}");
    }
    let s = format!("{n:.3}");
    s.trim_end_matches('0').trim_end_matches('.').to_owned()
}
fn text(
    svg: &mut String,
    x: f64,
    y: f64,
    label: &str,
    color: &str,
    anchor: &str,
    size: f64,
) -> Result<()> {
    write!(svg, "<text x=\"{x:.2}\" y=\"{y:.2}\" fill=\"{color}\" font-family=\"sans-serif\" font-size=\"{size}\" text-anchor=\"{anchor}\">")?;
    escape(label, svg);
    svg.push_str("</text>");
    Ok(())
}

fn series_color(index: usize, palette: Palette) -> Result<String> {
    if index == 0 {
        return Ok(palette.accent.to_owned());
    }
    let channel = |i| u8::from_str_radix(&palette.accent[i..i + 2], 16).unwrap_or(0) as f64 / 255.0;
    let (r, g, b) = (channel(1), channel(3), channel(5));
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    let l = (max + min) / 2.0;
    let s = if delta == 0.0 {
        0.0
    } else {
        delta / (1.0 - (2.0 * l - 1.0).abs()) * 100.0
    };
    let h = if delta == 0.0 {
        0.0
    } else if max == r {
        ((g - b) / delta).rem_euclid(6.0) * 60.0
    } else if max == g {
        ((b - r) / delta + 2.0) * 60.0
    } else {
        ((r - g) / delta + 4.0) * 60.0
    };
    let tier = index.div_ceil(2) as f64;
    let darker = if palette.bg == "#111111" {
        index % 2 == 0
    } else {
        index % 2 == 1
    };
    let l = if darker {
        (48.0 - tier * 13.0).max(25.0)
    } else {
        (55.0 + tier * 11.0).min(78.0)
    };
    let h = (h + if darker { -8.0 } else { 12.0 } * tier).rem_euclid(360.0);
    plain_color(&format!("hsl({h}, {}%, {l}%)", s.clamp(55.0, 85.0)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use roxmltree::Document;

    #[test]
    fn canvas_height_accounts_for_all_legend_rows() {
        for title in [false, true] {
            for count in [1usize, 2, 5, 6] {
                let mut source = String::from("xychart\n");
                if title {
                    source.push_str("title \"Chart\"\n");
                }
                for _ in 0..count {
                    source.push_str("bar [1, 2]\n");
                }
                let output = draw(&source, Palette::new(false)).unwrap();
                let doc = Document::parse(&output).unwrap();
                let top = if title { 65 } else { 30 };
                let expected = top
                    + 320
                    + if count > 1 {
                        110 + count.div_ceil(5) * 22
                    } else {
                        100
                    };
                assert_eq!(
                    doc.root_element().attribute("height").unwrap(),
                    expected.to_string()
                );
                assert_eq!(
                    doc.root_element().attribute("viewBox").unwrap(),
                    format!("0 0 700 {expected}")
                );
            }
        }
    }

    #[test]
    fn mixed_chart_preserves_titles_categories_series_and_transparency() {
        let source = "xychart-beta\n title \"Sales\"\n x-axis \"Month\" [Jan, Feb, Mar]\n y-axis \"Revenue\" -10 --> 20\n bar [-5, 10, 15]\n line [0, 7, 12]";
        for dark in [false, true] {
            let output = draw(source, Palette::new(dark)).unwrap();
            for label in ["Sales", "Month", "Revenue", "Jan", "Mar", "Bar 1", "Line 1"] {
                assert!(output.contains(label));
            }
            let doc = Document::parse(&output).unwrap();
            assert_eq!(
                doc.descendants().filter(|n| n.has_tag_name("rect")).count(),
                3
            );
            assert!(doc.descendants().any(|n| n.has_tag_name("path")));
            assert!(output.contains(Palette::new(dark).accent));
            assert!(!output.contains("var("));
        }
    }

    #[test]
    fn horizontal_negative_bars_straddle_zero_and_numeric_x_labels_survive() {
        let output = draw(
            "xychart horizontal\n x-axis \"Range\" 10 --> 20\n y-axis -10 --> 10\n bar [-5, 5]",
            Palette::new(false),
        )
        .unwrap();
        let doc = Document::parse(&output).unwrap();
        let bars: Vec<_> = doc
            .descendants()
            .filter(|n| n.has_tag_name("rect"))
            .collect();
        let x = |i: usize| bars[i].attribute("x").unwrap().parse::<f64>().unwrap();
        let y = |i: usize| bars[i].attribute("y").unwrap().parse::<f64>().unwrap();
        assert!(x(0) < x(1));
        assert!(y(0) < y(1));
        assert!(output.contains(">10</text>") && output.contains(">20</text>"));
    }

    #[test]
    fn malformed_nonfinite_and_degenerate_ranges_are_rejected() {
        for source in [
            "xychart\n bar [NaN]",
            "xychart\n bar [inf]",
            "xychart\n y-axis 2 --> 2\n bar [1]",
            "xychart\n bar []",
        ] {
            assert!(draw(source, Palette::new(false)).is_err());
        }
    }
}
