//! Native presentation adapters. Neither endpoint starts Node or a browser.
mod highlight;
mod mermaid;
use anyhow::Result;
use serde_json::Value;

pub fn handle(name: &str, args: &Value) -> Option<Result<Value>> {
    match name {
        "mermaid-svg" => Some(mermaid::render(args)),
        "highlight" => Some(highlight::render(args)),
        _ => None,
    }
}
