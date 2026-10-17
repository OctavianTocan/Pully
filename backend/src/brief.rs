//! OpenUI Lang's backend parser and plain brief-tree lowering, compatible with
//! @openuidev/lang-core 1.0.0. Reactive expressions remain inert AST data exactly
//! as in the TypeScript briefTree boundary; this module never evaluates code,
//! calls tools, opens URLs or executes actions. Parsing and expansion are bounded.
use anyhow::{anyhow, ensure, Result};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};

mod syntax;

const MAX_SOURCE: usize = 512 * 1024;
const MAX_DEPTH: usize = 96;
const MAX_NODES: usize = 20_000;
const MAX_OUTPUT_BYTES: usize = 8 * 1024 * 1024;

fn ast_kind(value: &Value) -> &str {
    value["k"].as_str().unwrap_or("")
}
fn is_ast(value: &Value) -> bool {
    matches!(
        ast_kind(value),
        "Comp"
            | "Str"
            | "Num"
            | "Bool"
            | "Null"
            | "Arr"
            | "Obj"
            | "Ref"
            | "Ph"
            | "StateRef"
            | "RuntimeRef"
            | "BinOp"
            | "UnaryOp"
            | "Ternary"
            | "Member"
            | "Index"
            | "Assign"
    )
}
fn builtin(name: &str) -> Option<(&'static [&'static str], bool)> {
    Some(match name {
        "Count" | "First" | "Last" => (&["array"], false),
        "Sum" | "Avg" | "Min" | "Max" => (&["numbers"], false),
        "Sort" => (&["array", "field", "direction"], false),
        "Filter" => (&["array", "field", "operator", "value"], false),
        "Round" => (&["number", "decimals"], false),
        "Abs" | "Floor" | "Ceil" => (&["number"], false),
        "Each" => (&["array", "varName", "template"], true),
        "Run" => (&["ref"], true),
        "ToAssistant" => (&["message", "context"], false),
        "OpenUrl" => (&["url"], false),
        "Set" => (&["variable", "value"], true),
        "Reset" => (&["variable"], true),
        "Action" => (&["steps"], true),
        _ => return None,
    })
}
fn reserved(name: &str) -> bool {
    matches!(name, "Query" | "Mutation")
}

#[derive(Clone, Copy)]
enum Kind {
    String,
    Number,
    Bool,
    Enum(&'static [&'static str]),
    Strings,
    Rows,
    Nodes(&'static [&'static str]),
}
#[derive(Clone, Copy)]
struct Prop {
    name: &'static str,
    kind: Kind,
    optional: bool,
}
const BLOCKS: &[&str] = &[
    "Text",
    "Callout",
    "KeyValues",
    "Table",
    "Checklist",
    "Steps",
    "FileList",
    "Diagram",
    "Code",
    "BeforeAfter",
    "Finding",
];
const PAGE: &[&str] = &[
    "Section",
    "Text",
    "Callout",
    "KeyValues",
    "Table",
    "Checklist",
    "Steps",
    "FileList",
    "Diagram",
    "Code",
    "BeforeAfter",
    "Finding",
];
fn required(name: &'static str, kind: Kind) -> Prop {
    Prop {
        name,
        kind,
        optional: false,
    }
}
fn optional(name: &'static str, kind: Kind) -> Prop {
    Prop {
        name,
        kind,
        optional: true,
    }
}
fn signature(name: &str) -> Option<Vec<Prop>> {
    use Kind::*;
    Some(match name {
        "Stack" => vec![required("children", Nodes(PAGE))],
        "Section" => vec![
            required("title", String),
            required("children", Nodes(BLOCKS)),
            optional("open", Bool),
        ],
        "Text" => vec![required("markdown", String)],
        "Callout" => vec![
            required("tone", Enum(&["info", "good", "warning", "risk"])),
            required("text", String),
            optional("title", String),
        ],
        "KeyValue" => vec![required("label", String), required("value", String)],
        "KeyValues" => vec![required("rows", Nodes(&["KeyValue"]))],
        "Table" => vec![required("columns", Strings), required("rows", Rows)],
        "CheckItem" => vec![
            required("text", String),
            optional("path", String),
            optional("line", Number),
        ],
        "Checklist" => vec![required("items", Nodes(&["CheckItem"]))],
        "Step" => vec![required("title", String), required("text", String)],
        "Steps" => vec![required("items", Nodes(&["Step"]))],
        "FileEntry" => vec![
            required("path", String),
            required("note", String),
            optional("line", Number),
        ],
        "FileList" => vec![required("files", Nodes(&["FileEntry"]))],
        "Diagram" => vec![required("mermaid", String), optional("caption", String)],
        "Code" => vec![
            required("code", String),
            optional("language", String),
            optional("caption", String),
        ],
        "BeforeAfter" => vec![
            required("before", String),
            required("after", String),
            optional("title", String),
        ],
        "Finding" => vec![
            required("severity", Enum(&["bug", "risk", "nit"])),
            required("title", String),
            required("text", String),
            optional("path", String),
            optional("line", Number),
        ],
        _ => return None,
    })
}

fn element(value: &Value) -> bool {
    value["type"] == "element"
        && value["typeName"].is_string()
        && (value["props"].is_object() || value["props"].is_array())
        && value["partial"].is_boolean()
}
fn dynamic(value: &Value) -> bool {
    if is_ast(value) {
        return true;
    }
    match value {
        Value::Array(a) => a.iter().any(dynamic),
        Value::Object(o) => o.get("steps").is_some_and(Value::is_array) || o.values().any(dynamic),
        _ => false,
    }
}
struct Resolver<'a> {
    statements: &'a BTreeMap<String, Value>,
    visiting: BTreeSet<String>,
    used: BTreeSet<String>,
    problems: Vec<String>,
    expanded: usize,
    output_bytes: usize,
    partial: bool,
}
impl Resolver<'_> {
    fn budget(&mut self, depth: usize) -> Result<()> {
        ensure!(
            depth <= MAX_DEPTH,
            "Expanded brief nesting exceeds {MAX_DEPTH}"
        );
        self.expanded += 1;
        ensure!(
            self.expanded <= MAX_NODES,
            "Expanded brief exceeds {MAX_NODES} nodes"
        );
        Ok(())
    }
    fn text_budget(&mut self, text: &str) -> Result<()> {
        self.output_bytes = self.output_bytes.saturating_add(text.len());
        ensure!(
            self.output_bytes <= MAX_OUTPUT_BYTES,
            "Expanded brief text exceeds {MAX_OUTPUT_BYTES} bytes"
        );
        Ok(())
    }
    fn string_budget(&mut self, value: &Value) -> Result<()> {
        if let Some(text) = value.as_str() {
            self.text_budget(text)?;
        }
        Ok(())
    }
    // Account for the complete retained copy, including object keys, before
    // cloning it. Unlike expression recursion, mappedProps really duplicates
    // its entire argument subtree and must pay for every nested copy.
    fn copy_budget(&mut self, value: &Value) -> Result<()> {
        let mut pending = vec![value];
        while let Some(node) = pending.pop() {
            self.budget(0)?;
            self.string_budget(node)?;
            match node {
                Value::Array(a) => pending.extend(a),
                Value::Object(o) => {
                    for key in o.keys() {
                        self.text_budget(key)?;
                    }
                    pending.extend(o.values());
                }
                _ => {}
            }
        }
        Ok(())
    }
    fn expression_shell(&mut self, node: &Value, replaced: &[&str]) -> Result<Value> {
        let mut output = Map::new();
        for (key, value) in node.as_object().unwrap() {
            self.text_budget(key)?;
            if !replaced.contains(&key.as_str()) {
                self.copy_budget(value)?;
                output.insert(key.clone(), value.clone());
            }
        }
        // Replaced fields are constructed recursively rather than first cloning
        // their old subtrees. Deep, compact expressions only pay once per leaf.
        Ok(Value::Object(output))
    }
    fn named_problem(&mut self, prefix: &str, name: &str, suffix: &str) -> Result<()> {
        self.text_budget(prefix)?;
        self.text_budget(name)?;
        self.text_budget(suffix)?;
        self.problems.push([prefix, name, suffix].concat());
        Ok(())
    }
    fn reference(&mut self, name: &str, depth: usize, expression: bool) -> Result<Value> {
        if self.visiting.contains(name) || !self.statements.contains_key(name) {
            self.named_problem("`", name, "` is unresolved or cyclic")?;
            return Ok(if expression {
                self.text_budget("kPhn")?;
                self.text_budget(name)?;
                json!({"k":"Ph","n":name})
            } else {
                Value::Null
            });
        }
        self.used.insert(name.to_owned());
        let target = &self.statements[name];
        if ast_kind(target) == "Comp" && reserved(target["name"].as_str().unwrap_or("")) {
            let ref_type = if target["name"] == "Mutation" {
                "mutation"
            } else {
                "query"
            };
            self.text_budget("kRuntimeRefnrefType")?;
            self.text_budget(name)?;
            self.text_budget(ref_type)?;
            return Ok(json!({"k":"RuntimeRef","n":name,"refType":ref_type}));
        }
        self.visiting.insert(name.to_owned());
        let result = if expression {
            self.expression(target, depth + 1, &BTreeSet::new())
        } else {
            self.value(target, depth + 1)
        };
        self.visiting.remove(name);
        let mut result = result?;
        if !expression && element(&result) {
            self.text_budget("statementId")?;
            self.text_budget(name)?;
            result["statementId"] = name.into();
        }
        Ok(result)
    }
    fn expression(
        &mut self,
        node: &Value,
        depth: usize,
        scope: &BTreeSet<String>,
    ) -> Result<Value> {
        self.budget(depth)?;
        let kind = ast_kind(node);
        if kind == "Ref" && !scope.contains(node["n"].as_str().unwrap_or("")) {
            return self.reference(node["n"].as_str().unwrap_or(""), depth, true);
        }
        if kind == "Comp" {
            let name = node["name"].as_str().unwrap_or("");
            if node["fn"] == true && builtin(name).is_none() && !reserved(name) {
                self.named_problem("Unknown function `@", name, "`")?;
                self.text_budget("kNull")?;
                return Ok(json!({"k":"Null"}));
            }
        }
        let replaced: &[&str] = match kind {
            "Comp" => &["args"],
            "Arr" => &["els"],
            "Obj" => &["entries"],
            "BinOp" => &["left", "right"],
            "UnaryOp" => &["operand"],
            "Ternary" => &["cond", "then", "else"],
            "Member" => &["obj"],
            "Index" => &["obj", "index"],
            "Assign" => &["value"],
            _ => &[],
        };
        let mut output = self.expression_shell(node, replaced)?;
        match kind {
            "Comp" => {
                let name = node["name"].as_str().unwrap_or("");
                let args = node["args"].as_array().unwrap();
                let iterator = if name == "Each" && args.len() >= 3 {
                    match ast_kind(&args[1]) {
                        "Ref" => args[1]["n"].as_str(),
                        "Str" => args[1]["v"].as_str(),
                        _ => None,
                    }
                } else {
                    None
                }
                .filter(|s| !s.is_empty());
                let mut nested = scope.clone();
                if let Some(name) = iterator {
                    nested.insert(name.to_owned());
                }
                let mut values = Vec::new();
                for (i, arg) in args.iter().enumerate() {
                    values.push(if iterator.is_some() && i == 1 {
                        self.copy_budget(arg)?;
                        arg.clone()
                    } else {
                        self.expression(arg, depth + 1, &nested)?
                    });
                }
                if iterator.is_none() {
                    if let Some((params, lazy)) = builtin(name) {
                        if !lazy {
                            self.text_budget("mappedProps")?;
                            output["mappedProps"] = self.named(&values, params.iter().copied())?;
                        }
                    } else if let Some(props) = signature(name) {
                        self.text_budget("mappedProps")?;
                        output["mappedProps"] =
                            self.named(&values, props.iter().map(|p| p.name))?;
                    } else if !reserved(name) {
                        self.named_problem("Unknown component \"", name, "\"")?;
                    }
                }
                output["args"] = values.into();
            }
            "Arr" => {
                let mut values = Vec::new();
                for item in node["els"].as_array().unwrap() {
                    values.push(self.expression(item, depth + 1, scope)?);
                }
                output["els"] = values.into();
            }
            "Obj" => {
                let mut entries = Vec::new();
                for pair in node["entries"].as_array().unwrap() {
                    self.string_budget(&pair[0])?;
                    entries.push(Value::Array(vec![
                        pair[0].clone(),
                        self.expression(&pair[1], depth + 1, scope)?,
                    ]));
                }
                output["entries"] = entries.into();
            }
            "BinOp" => {
                output["left"] = self.expression(&node["left"], depth + 1, scope)?;
                output["right"] = self.expression(&node["right"], depth + 1, scope)?;
            }
            "UnaryOp" => output["operand"] = self.expression(&node["operand"], depth + 1, scope)?,
            "Ternary" => {
                for field in ["cond", "then", "else"] {
                    output[field] = self.expression(&node[field], depth + 1, scope)?;
                }
            }
            "Member" => output["obj"] = self.expression(&node["obj"], depth + 1, scope)?,
            "Index" => {
                output["obj"] = self.expression(&node["obj"], depth + 1, scope)?;
                output["index"] = self.expression(&node["index"], depth + 1, scope)?;
            }
            "Assign" => output["value"] = self.expression(&node["value"], depth + 1, scope)?,
            _ => {}
        }
        Ok(output)
    }
    fn named<'a>(&mut self, args: &[Value], names: impl Iterator<Item = &'a str>) -> Result<Value> {
        let mut output = Map::new();
        for (name, value) in names.zip(args) {
            self.text_budget(name)?;
            self.copy_budget(value)?;
            output.insert(name.to_owned(), value.clone());
        }
        Ok(Value::Object(output))
    }
    fn check(&mut self, value: Value, kind: Kind, path: &str) -> Option<Value> {
        // The canonical parser deliberately defers dynamic AST validation, and
        // checks component position, not the component name in a union slot.
        if value.is_null() || is_ast(&value) {
            return Some(value);
        }
        let valid = if element(&value) {
            false
        } else {
            match kind {
                Kind::String => value.is_string(),
                Kind::Number => value.is_number(),
                Kind::Bool => value.is_boolean(),
                Kind::Enum(names) => {
                    self.partial
                        || (!value.is_string() && !value.is_number() && !value.is_boolean())
                        || value.as_str().is_some_and(|s| names.contains(&s))
                }
                Kind::Strings | Kind::Rows | Kind::Nodes(_) => value.is_array(),
            }
        };
        if !valid {
            self.problems
                .push(format!("{path} has the wrong type or value"));
            return None;
        }
        match kind {
            Kind::Strings | Kind::Rows | Kind::Nodes(_) => {
                let Value::Array(items) = value else {
                    return Some(value);
                };
                let mut output = Vec::new();
                for (i, item) in items.into_iter().enumerate() {
                    let path = format!("{path}/{i}");
                    let next = match kind {
                        Kind::Strings => self.check(item, Kind::String, &path),
                        Kind::Rows => self.check(item, Kind::Strings, &path),
                        Kind::Nodes(names) => {
                            if item.is_null() || is_ast(&item) || element(&item) {
                                Some(item)
                            } else {
                                self.problems.push(format!(
                                    "{path} expects a component ({})",
                                    names.join(" | ")
                                ));
                                None
                            }
                        }
                        _ => unreachable!(),
                    };
                    if let Some(next) = next {
                        output.push(next);
                    }
                }
                Some(output.into())
            }
            _ => Some(value),
        }
    }
    fn value(&mut self, node: &Value, depth: usize) -> Result<Value> {
        self.budget(depth)?;
        match ast_kind(node) {
            "Str" | "Num" | "Bool" => {
                self.string_budget(&node["v"])?;
                Ok(node["v"].clone())
            }
            "Null" | "Ph" => Ok(Value::Null),
            "Ref" => self.reference(node["n"].as_str().unwrap_or(""), depth, false),
            "Arr" => {
                let mut output = Vec::new();
                for item in node["els"].as_array().unwrap() {
                    if ast_kind(item) == "Ph" {
                        continue;
                    }
                    let value = self.value(item, depth + 1)?;
                    if value.is_null() && matches!(ast_kind(item), "Comp" | "Ref") {
                        continue;
                    }
                    output.push(value);
                }
                Ok(output.into())
            }
            "Obj" => {
                let mut output = Map::new();
                for pair in node["entries"].as_array().unwrap() {
                    self.string_budget(&pair[0])?;
                    output.insert(
                        pair[0].as_str().unwrap().to_owned(),
                        self.value(&pair[1], depth + 1)?,
                    );
                }
                Ok(output.into())
            }
            "Comp" => {
                let name = node["name"].as_str().unwrap_or("");
                if node["fn"] == true && builtin(name).is_none() && !reserved(name) {
                    self.named_problem("Unknown function `@", name, "`")?;
                    return Ok(Value::Null);
                }
                if reserved(name) {
                    self.problems
                        .push(format!("{name}() must be a top-level statement"));
                    return Ok(Value::Null);
                }
                if builtin(name).is_some() {
                    return self.expression(node, depth + 1, &BTreeSet::new());
                }
                let Some(props) = signature(name) else {
                    self.named_problem("Unknown component \"", name, "\"")?;
                    return Ok(Value::Null);
                };
                let args = node["args"].as_array().unwrap();
                let mut values = Map::new();
                let mut invalid = false;
                for (i, prop) in props.iter().enumerate() {
                    if let Some(arg) = args.get(i) {
                        let value = self.value(arg, depth + 1)?;
                        match self.check(value, prop.kind, &format!("{name}/{}", prop.name)) {
                            Some(v) => {
                                if v.is_null() && !prop.optional {
                                    invalid = true;
                                    self.problems
                                        .push(format!("{name}.{} cannot be null", prop.name));
                                }
                                self.text_budget(prop.name)?;
                                values.insert(prop.name.to_owned(), v);
                            }
                            None => {
                                if !prop.optional {
                                    invalid = true;
                                }
                            }
                        }
                    } else if !prop.optional {
                        self.problems
                            .push(format!("{name}.{} is required", prop.name));
                        invalid = true;
                    }
                }
                if args.len() > props.len() {
                    self.problems.push(format!(
                        "{name} takes {} argument(s), got {} (excess dropped)",
                        props.len(),
                        args.len()
                    ));
                }
                if invalid {
                    return Ok(Value::Null);
                }
                let has_dynamic = values.values().any(dynamic);
                self.text_budget("typeelementtypeNamepropspartialhasDynamicProps")?;
                self.text_budget(name)?;
                Ok(Value::Object(Map::from_iter([
                    ("type".into(), "element".into()),
                    ("typeName".into(), name.into()),
                    ("props".into(), Value::Object(values)),
                    ("partial".into(), self.partial.into()),
                    ("hasDynamicProps".into(), has_dynamic.into()),
                ])))
            }
            _ => self.expression(node, depth + 1, &BTreeSet::new()),
        }
    }
}
fn plain(value: Value) -> Value {
    match value {
        Value::Array(items) => items
            .into_iter()
            .filter(|v| !v.is_null())
            .map(plain)
            .collect(),
        Value::Object(mut object) if object.get("type").is_some_and(|v| v == "element") => {
            let props = match object.remove("props") {
                Some(Value::Object(props)) => props
                    .into_iter()
                    .map(|(key, value)| (key, plain(value)))
                    .collect::<Map<_, _>>(),
                _ => Map::new(),
            };
            // Move the already-accounted values rather than recursively copying
            // them during the final lowering, which only removes metadata.
            Value::Object(Map::from_iter([
                (
                    "type".into(),
                    object.remove("typeName").unwrap_or(Value::Null),
                ),
                ("props".into(), Value::Object(props)),
            ]))
        }
        value => value,
    }
}

fn inspect(lang: &str) -> Result<(Value, Vec<String>)> {
    let (declarations, partial) = syntax::parse_declarations(lang)?;
    let root = declarations
        .get("root")
        .ok_or_else(|| anyhow!("There is no `root = Stack([...])` statement"))?;
    let mut resolver = Resolver {
        statements: &declarations,
        visiting: BTreeSet::new(),
        used: BTreeSet::from(["root".to_owned()]),
        problems: Vec::new(),
        expanded: 0,
        output_bytes: 0,
        partial,
    };
    let mut tree = resolver.value(root, 0)?;
    if element(&tree) {
        resolver.text_budget("statementIdroot")?;
        tree["statementId"] = "root".into();
    }
    // State initializers are parsed/materialized too. Query and mutation argument
    // AST remains unevaluated, matching the backend parser's no-tool-call boundary.
    for (name, value) in &declarations {
        if name.starts_with('$')
            && !(ast_kind(value) == "Comp" && reserved(value["name"].as_str().unwrap_or("")))
        {
            resolver.value(value, 0)?;
        }
    }
    for (name, value) in &declarations {
        if !name.starts_with('$')
            && !(ast_kind(value) == "Comp" && reserved(value["name"].as_str().unwrap_or("")))
            && !resolver.used.contains(name)
        {
            resolver.problems.push(format!(
                "`{name}` is defined but never used; put it in its parent's list or delete it"
            ));
        }
    }
    if partial {
        resolver
            .problems
            .push("The program is cut off before its last statement ends".into());
    }
    ensure!(element(&tree), "There is no renderable root component");
    ensure!(
        tree["props"]["children"].as_array().is_some_and(|a| a
            .iter()
            .any(|v| !v.is_null() && v != &json!(false) && v != &json!(0) && v != &json!(""))),
        "The page has no blocks"
    );
    Ok((plain(tree), resolver.problems))
}

/// Return the same plain tree as the TypeScript briefTree boundary, including
/// recovered partial syntax and inert reactive AST. Invalid array items are pruned.
pub fn parse(lang: &str) -> Result<Value> {
    inspect(lang).map(|(tree, _)| tree)
}
/// Generation-time validation rejects incomplete, unresolved, orphaned and
/// schema-invalid output even when the native client can display a partial tree.
pub fn validate(lang: &str) -> Result<Value> {
    let (tree, problems) = inspect(lang)?;
    ensure!(problems.is_empty(), "{}", problems.join("\n"));
    Ok(tree)
}

/// Extract a program from a model answer. This does not make invalid syntax valid.
pub fn program_of(text: &str) -> String {
    let trimmed = text.trim();
    if let Some(rest) = trimmed.strip_prefix("```") {
        if let Some((_, body)) = rest.split_once('\n') {
            if let Some((inside, _)) = body.rsplit_once("```") {
                return inside.trim().to_owned();
            }
        }
    }
    let offset = trimmed
        .lines()
        .scan(0usize, |pos, line| {
            let start = *pos;
            *pos += line.len() + 1;
            Some((start, line))
        })
        .find(|(_, line)| {
            line.trim_start()
                .strip_prefix("root")
                .is_some_and(|tail| tail.trim_start().starts_with('='))
        })
        .map(|(pos, _)| pos);
    trimmed[offset.unwrap_or(0)..].trim().to_owned()
}

// Generation guidance stays on the canonical brief data subset; the parser also
// accepts the upstream reactive syntax present in previously saved programs.
/// @cc [owner:OctavianTocan,label:product] rust-brief-diagram-captions-short
/// Diagram instructions MUST ask for one caption sentence under 60 characters
/// naming the diagram without repeating surrounding text (16 Oct 12:10, N33).
pub fn prompt() -> String {
    r#"You are a staff engineer designing the briefing page a reviewer reads before opening a pull request diff. Read the changed code and its callers first using the PR tools. Every claim must come from code you read; label inferences. Pick only blocks that make this change easier to understand. A short page beats a padded page. Give the page no title; lead with the most important Callout or Text. Do not repeat the PR title or description. Do not list every file. Write strings in the requested language, leaving code, paths and identifiers unchanged.
Output only an OpenUI Lang program, no fences or commentary. Use only assignments of data: name = Component(positional arguments). Start with root = Stack([...]). References may point forward. Strings use double quotes with backslash escapes. Arrays may contain literals, component calls or references. Optional arguments may be omitted or null. Do not use objects, expressions, bindings, built-in functions, tool calls, imports or executable code. Every definition must be referenced from root. Use these signatures only:
Stack(children: Block[]) -- root only; may include Section.
Section(title: string, children: Block[], open?: boolean) -- secondary foldable content; never nest Sections.
Text(markdown: string) -- one to four sentences, inline code and links, no headings or lists.
Callout(tone: "info" | "good" | "warning" | "risk", text: string, title?: string)
KeyValues(rows: KeyValue[])
KeyValue(label: string, value: string)
Table(columns: string[], rows: string[][]) -- at most six columns and twelve rows.
Checklist(items: CheckItem[])
CheckItem(text: string, path?: string, line?: number) -- concrete checks, most important first.
Steps(items: Step[])
Step(title: string, text: string)
FileList(files: FileEntry[])
FileEntry(path: string, note: string, line?: number) -- changed files worth reading first; note under twelve words.
Diagram(mermaid: string, caption?: string) -- flowchart TD or sequenceDiagram, at most ten nodes, labels under 25 characters. Use only when shape is the point. A caption, when given, is one short sentence under 60 characters naming what the diagram shows; the app prints it above the diagram, so never repeat the surrounding text.
Code(code: string, language?: string, caption?: string) -- under fifteen lines, only when exact code matters.
BeforeAfter(before: string, after: string, title?: string) -- short snippets or values.
Finding(severity: "bug" | "risk" | "nit", title: string, text: string, path?: string, line?: number)
Block means Text, Callout, KeyValues, Table, Checklist, Steps, FileList, Diagram, Code, BeforeAfter or Finding. Helper components KeyValue, CheckItem, Step and FileEntry belong only in their named containers. Paths in CheckItem, FileEntry and Finding must be exact changed paths. Never invent a path or line. Mention code locations using repository-relative Markdown links, including #L when a verified line is known.
Example:
root = Stack([lead, checks])
lead = Callout("risk", "The cache has no eviction limit.", "Unbounded growth")
checks = Checklist([CheckItem("Verify eviction after the size limit", "src/cache.ts", 42)])
"#.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn matches_android_fixture() {
        let lang = include_str!("../../android/core/src/test/resources/brief-fixture.lang");
        let expected: Value = serde_json::from_str(include_str!(
            "../../android/core/src/test/resources/brief-fixture.json"
        ))
        .unwrap();
        assert_eq!(validate(lang).unwrap(), expected);
    }
    #[test]
    fn matches_locked_upstream_parser() {
        // Generated offline with the locked @openuidev/lang-core 1.0.0 and the
        // exact core brief library. Node/npm are not runtime or test dependencies.
        let cases: Vec<Value> =
            serde_json::from_str(include_str!("presentation/fixtures/openui-1.0.0.json")).unwrap();
        for case in cases {
            let source = case["source"].as_str().unwrap();
            assert_eq!(
                parse(source).unwrap_or(Value::Null),
                case["tree"],
                "tree: {source}"
            );
            assert_eq!(
                validate(source).is_ok(),
                case["valid"].as_bool().unwrap(),
                "validity: {source}"
            );
        }
    }
    #[test]
    fn bounds_untrusted_input_and_expansion() {
        assert!(parse(&"x".repeat(MAX_SOURCE + 1)).is_err());
        assert!(parse(&format!(
            "root=Stack({}{}",
            "[".repeat(MAX_DEPTH + 1),
            "]".repeat(MAX_DEPTH + 1)
        ))
        .is_err());
        let mut source = "root=Stack([n0])\n".to_owned();
        for i in 0..20 {
            source.push_str(&format!("n{i}=Section(\"x\",[n{},n{}])\n", i + 1, i + 1));
        }
        source.push_str("n20=Text(\"leaf\")");
        assert!(parse(&source).is_err());
        let nested = format!(
            "root=Stack([Text(@First({}\"x\"{}))])",
            "Text(".repeat(24),
            ")".repeat(24)
        );
        assert!(parse(&nested).is_err());
        let repeated = format!(
            "root=Stack([{}])\nlarge=\"{}\"",
            vec!["Text(large)"; 64].join(","),
            "x".repeat(256 * 1024)
        );
        assert!(parse(&repeated).is_err());
    }
    fn assert_text_expansion_rejected(source: &str) {
        assert!(source.len() < MAX_SOURCE);
        for parse_or_validate in [parse, validate] {
            let error = parse_or_validate(source).unwrap_err().to_string();
            assert!(error.contains("Expanded brief text exceeds"), "{error}");
        }
    }
    #[test]
    fn bounds_repeated_object_keys_in_parse_and_validate() {
        // A small source previously retained 16 MiB of unaccounted keys. Both
        // entry points must stop at 8 MiB while materializing the initializer.
        let source = format!(
            "root=Stack([Text(\"ok\")])\nn={{\"{}\":0}}\n$state=[{}]",
            "x".repeat(4096),
            vec!["n"; 4096].join(",")
        );
        assert_text_expansion_rejected(&source);
    }
    #[test]
    fn bounds_retained_inert_ast_metadata() {
        let source = format!(
            "root=Stack([Text(\"ok\")])\nn=$value.{}\n$state=[{}]",
            "x".repeat(16 * 1024),
            vec!["n"; 1024].join(",")
        );
        assert_text_expansion_rejected(&source);
    }
    #[test]
    fn bounds_inert_object_keys_and_mapped_props_copies() {
        // The argument itself fits. Its mappedProps copy must include the same
        // keys in the byte budget before copying another multi-MiB subtree.
        let source = format!(
            "root=Stack([Text(@First([{}]))])\nn={{\"{}\":0}}",
            vec!["n"; 384].join(","),
            "x".repeat(16 * 1024)
        );
        assert_text_expansion_rejected(&source);
    }
    #[test]
    fn counts_keys_in_named_copies_before_clone() {
        let declarations = BTreeMap::new();
        let key = "x".repeat(1024);
        let value = Value::Object(Map::from_iter([(key.clone(), Value::Null)]));
        let mut resolver = Resolver {
            statements: &declarations,
            visiting: BTreeSet::new(),
            used: BTreeSet::new(),
            problems: Vec::new(),
            expanded: 0,
            output_bytes: MAX_OUTPUT_BYTES - key.len(),
            partial: false,
        };
        let error = resolver
            .named(&[value], std::iter::once("value"))
            .unwrap_err()
            .to_string();
        assert!(error.contains("Expanded brief text exceeds"), "{error}");
        assert_eq!(resolver.expanded, 1);
    }
    #[test]
    fn preserves_compact_deep_inert_expression() {
        // Recursively replaced operands are not retained copies. Charging each
        // ancestor's original subtree would incorrectly reject this small AST.
        let text = "x".repeat(256 * 1024);
        let source = format!("root=Stack([Text({}\"{text}\")])", "!".repeat(60));
        let tree = validate(&source).unwrap();
        let mut node = &tree["props"]["children"][0]["props"]["markdown"];
        for _ in 0..60 {
            assert_eq!(node["k"], "UnaryOp");
            node = &node["operand"];
        }
        assert_eq!(node["v"], text);
    }
    #[test]
    fn bounds_repeated_unknown_component_diagnostics() {
        let source = format!(
            "root=Stack([Text(\"ok\")])\nn={}()\n$state=[{}]",
            "X".repeat(4096),
            vec!["n"; 4096].join(",")
        );
        assert_text_expansion_rejected(&source);
    }
    #[test]
    fn extracts_wrapped_program() {
        let source = "root=Stack([Text(\"hello\")])";
        assert_eq!(program_of(&format!("```openui\n{source}\n```")), source);
        assert_eq!(program_of(&format!("Here is the page:\n{source}")), source);
    }
}
