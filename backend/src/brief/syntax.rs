//! Bounded OpenUI declarations parsing and partial-input recovery. Expressions
//! are returned as inert AST data for the parent module's tree lowering.
use super::{builtin, MAX_DEPTH, MAX_NODES, MAX_SOURCE};
use anyhow::{ensure, Result};
use serde_json::{json, Value};
use std::collections::BTreeMap;

/// Apply source, token and expression limits before returning declarations and
/// whether the input needed streaming recovery.
pub(super) fn parse_declarations(input: &str) -> Result<(BTreeMap<String, Value>, bool)> {
    ensure!(
        input.len() <= MAX_SOURCE,
        "Brief exceeds {MAX_SOURCE} bytes"
    );
    let (source, partial) = preprocess(input);
    let declarations = statements(&tokenize(&source)?)?;
    Ok((declarations, partial))
}

#[derive(Clone, Debug, PartialEq)]
enum Token {
    Name(String),
    State(String),
    Builtin(String),
    Str(String),
    Num(Value),
    True,
    False,
    Null,
    Punct(char),
    Op(&'static str),
    Newline,
}
fn js_number(n: f64) -> Value {
    if !n.is_finite() {
        return Value::Null;
    }
    if n.fract() == 0.0 && n >= i64::MIN as f64 && n < (i64::MAX as f64) {
        return Value::from(n as i64);
    }
    if n.fract() == 0.0 && n >= 0.0 && n < (u64::MAX as f64) {
        return Value::from(n as u64);
    }
    serde_json::Number::from_f64(n)
        .map(Value::Number)
        .unwrap_or(Value::Null)
}
fn ast(kind: &str, value: Value) -> Value {
    json!({"k":kind,"v":value})
}

// Fences and comments are stripped before streaming recovery. A quoted fence is
// data; several fenced program fragments are concatenated, as upstream does.
fn strip_fences(input: &str) -> String {
    fn next_fence(s: &str, mut pos: usize) -> Option<usize> {
        let b = s.as_bytes();
        while pos < b.len() {
            if b[pos] == b'"' {
                pos += 1;
                while pos < b.len() {
                    if b[pos] == b'\\' {
                        pos = (pos + 2).min(b.len());
                    } else if b[pos] == b'"' {
                        pos += 1;
                        break;
                    } else {
                        pos += 1;
                    }
                }
            } else if b[pos..].starts_with(b"```") {
                return Some(pos);
            } else {
                pos += 1;
            }
        }
        None
    }
    let mut parts = Vec::new();
    let mut pos = 0;
    while let Some(start) = next_fence(input, pos) {
        let Some(newline) = input[start + 3..].find('\n') else {
            parts.push("");
            break;
        };
        let from = start + 3 + newline + 1;
        if let Some(end) = next_fence(input, from) {
            parts.push(&input[from..end]);
            pos = end + 3;
        } else {
            parts.push(&input[from..]);
            break;
        }
    }
    if parts.is_empty() {
        input.to_owned()
    } else {
        parts.join("\n")
    }
}
fn preprocess(input: &str) -> (String, bool) {
    let fenced = strip_fences(input.trim());
    let mut clean = String::new();
    let mut quote = None;
    let mut escaped = false;
    let mut comment = false;
    let mut chars = fenced.chars().peekable();
    while let Some(c) = chars.next() {
        if comment {
            if c == '\n' {
                comment = false;
                clean.push(c);
            }
            continue;
        }
        if let Some(q) = quote {
            clean.push(c);
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == q {
                quote = None;
            }
        } else if c == '#' || (c == '/' && chars.peek() == Some(&'/')) {
            comment = true;
        } else {
            clean.push(c);
            if c == '"' || c == '\'' {
                quote = Some(c);
            }
        }
    }
    let mut clean = clean.trim().to_owned();
    let mut stack = Vec::new();
    quote = None;
    escaped = false;
    for c in clean.chars() {
        if escaped {
            escaped = false;
            continue;
        }
        if let Some(q) = quote {
            if c == '\\' {
                escaped = true;
            } else if c == q {
                quote = None;
            }
            continue;
        }
        match c {
            '"' | '\'' => quote = Some(c),
            '(' | '[' | '{' => stack.push(c),
            ')' if stack.last() == Some(&'(') => {
                stack.pop();
            }
            ']' if stack.last() == Some(&'[') => {
                stack.pop();
            }
            '}' if stack.last() == Some(&'{') => {
                stack.pop();
            }
            _ => {}
        }
    }
    let incomplete = quote.is_some() || !stack.is_empty();
    if let Some(q) = quote {
        if escaped {
            clean.push('\\');
        }
        clean.push(q);
    }
    for c in stack.into_iter().rev() {
        clean.push(match c {
            '(' => ')',
            '[' => ']',
            _ => '}',
        });
    }
    (clean, incomplete)
}
fn tokenize(input: &str) -> Result<Vec<Token>> {
    let mut out = Vec::new();
    let mut pos = 0;
    let bytes = input.as_bytes();
    while pos < bytes.len() {
        ensure!(out.len() <= MAX_NODES * 8, "Brief has too many tokens");
        let c = bytes[pos] as char;
        match c {
            ' ' | '\t' | '\r' => pos += 1,
            '\n' => {
                out.push(Token::Newline);
                pos += 1;
            }
            '(' | ')' | '[' | ']' | '{' | '}' | ',' | ':' | '.' | '?' => {
                out.push(Token::Punct(c));
                pos += 1;
            }
            '"' | '\'' => {
                let start = pos;
                pos += 1;
                while pos < bytes.len() {
                    if bytes[pos] == b'\\' {
                        pos = (pos + 2).min(bytes.len());
                    } else if bytes[pos] == c as u8 {
                        pos += 1;
                        break;
                    } else {
                        pos += 1;
                    }
                }
                let raw = &input[start..pos];
                let text = if c == '"' {
                    serde_json::from_str::<String>(raw)
                        .unwrap_or_else(|_| raw.trim_matches('"').to_owned())
                } else {
                    let mut text = String::new();
                    let mut chars = raw[1..raw.len().saturating_sub(1)].chars();
                    while let Some(c) = chars.next() {
                        if c == '\\' {
                            if let Some(e) = chars.next() {
                                text.push(match e {
                                    'n' => '\n',
                                    't' => '\t',
                                    _ => e,
                                });
                            }
                        } else {
                            text.push(c);
                        }
                    }
                    text
                };
                out.push(Token::Str(text));
            }
            '=' | '!' | '>' | '<' | '&' | '|' | '+' | '*' | '/' | '%' => {
                let doubled = bytes.get(pos + 1)
                    == Some(&(if c == '&' || c == '|' { c as u8 } else { b'=' }));
                let op = match (c, doubled) {
                    ('=', true) => "==",
                    ('=', false) => "=",
                    ('!', true) => "!=",
                    ('!', false) => "!",
                    ('>', true) => ">=",
                    ('>', false) => ">",
                    ('<', true) => "<=",
                    ('<', false) => "<",
                    ('&', _) => "&&",
                    ('|', _) => "||",
                    ('+', _) => "+",
                    ('*', _) => "*",
                    ('/', _) => "/",
                    _ => "%",
                };
                pos += if doubled && matches!(c, '=' | '!' | '>' | '<' | '&' | '|') {
                    2
                } else {
                    1
                };
                out.push(Token::Op(op));
            }
            '-' | '0'..='9' => {
                let after_value = matches!(
                    out.last(),
                    Some(
                        Token::Num(_)
                            | Token::Str(_)
                            | Token::Name(_)
                            | Token::State(_)
                            | Token::Builtin(_)
                            | Token::Punct(')' | ']')
                            | Token::True
                            | Token::False
                            | Token::Null
                    )
                );
                if c == '-' && (after_value || !bytes.get(pos + 1).is_some_and(u8::is_ascii_digit))
                {
                    out.push(Token::Op("-"));
                    pos += 1;
                    continue;
                }
                let start = pos;
                if c == '-' {
                    pos += 1;
                }
                while bytes.get(pos).is_some_and(u8::is_ascii_digit) {
                    pos += 1;
                }
                if bytes.get(pos) == Some(&b'.')
                    && bytes.get(pos + 1).is_some_and(u8::is_ascii_digit)
                {
                    pos += 1;
                    while bytes.get(pos).is_some_and(u8::is_ascii_digit) {
                        pos += 1;
                    }
                }
                if matches!(bytes.get(pos), Some(b'e' | b'E')) {
                    pos += 1;
                    if matches!(bytes.get(pos), Some(b'+' | b'-')) {
                        pos += 1;
                    }
                    while bytes.get(pos).is_some_and(u8::is_ascii_digit) {
                        pos += 1;
                    }
                }
                let number = input[start..pos]
                    .parse::<f64>()
                    .ok()
                    .map(js_number)
                    .unwrap_or(Value::Null);
                out.push(Token::Num(number));
            }
            '$' | '@'
                if bytes
                    .get(pos + 1)
                    .is_some_and(|b| b.is_ascii_alphabetic() || *b == b'_') =>
            {
                let prefix = c;
                pos += 1;
                let start = pos;
                while bytes
                    .get(pos)
                    .is_some_and(|b| b.is_ascii_alphanumeric() || *b == b'_')
                {
                    pos += 1;
                }
                out.push(if prefix == '$' {
                    Token::State(format!("${}", &input[start..pos]))
                } else {
                    Token::Builtin(input[start..pos].to_owned())
                });
            }
            'a'..='z' | 'A'..='Z' | '_' => {
                let start = pos;
                pos += 1;
                while bytes
                    .get(pos)
                    .is_some_and(|b| b.is_ascii_alphanumeric() || *b == b'_')
                {
                    pos += 1;
                }
                out.push(match &input[start..pos] {
                    "true" => Token::True,
                    "false" => Token::False,
                    "null" => Token::Null,
                    n => Token::Name(n.to_owned()),
                });
            }
            _ => {
                pos += input[pos..].chars().next().unwrap().len_utf8();
            }
        }
    }
    Ok(out)
}
struct Reader<'a> {
    tokens: &'a [Token],
    pos: usize,
    nodes: usize,
}
impl Reader<'_> {
    fn peek(&self) -> Option<&Token> {
        self.tokens.get(self.pos)
    }
    fn take(&mut self) -> Option<Token> {
        let t = self.peek()?.clone();
        self.pos += 1;
        Some(t)
    }
    fn eat(&mut self, t: Token) {
        if self.peek() == Some(&t) {
            self.pos += 1;
        }
    }
    fn list(&mut self, end: char, depth: usize) -> Result<Vec<Value>> {
        let mut values = Vec::new();
        while self.peek().is_some() && self.peek() != Some(&Token::Punct(end)) {
            values.push(self.expr(0, depth + 1)?);
            self.eat(Token::Punct(','));
        }
        self.eat(Token::Punct(end));
        Ok(values)
    }
    fn expr(&mut self, min: usize, depth: usize) -> Result<Value> {
        ensure!(depth <= MAX_DEPTH, "Brief nesting exceeds {MAX_DEPTH}");
        self.nodes += 1;
        ensure!(self.nodes <= MAX_NODES, "Brief has too many expressions");
        let token = self.take();
        let mut left = match token {
            Some(Token::Str(v)) => ast("Str", v.into()),
            Some(Token::Num(v)) => ast("Num", v),
            Some(Token::True) => ast("Bool", true.into()),
            Some(Token::False) => ast("Bool", false.into()),
            Some(Token::Null) | None => json!({"k":"Null"}),
            Some(Token::Punct('[')) => json!({"k":"Arr","els":self.list(']',depth)?}),
            Some(Token::Punct('{')) => {
                let mut entries = Vec::new();
                while self.peek().is_some() && self.peek() != Some(&Token::Punct('}')) {
                    let key = match self.take() {
                        Some(Token::Str(s) | Token::Name(s)) => s,
                        Some(Token::State(s)) => s.trim_start_matches('$').to_owned(),
                        Some(Token::Num(v)) => v.to_string(),
                        _ => "?".into(),
                    };
                    self.eat(Token::Punct(':'));
                    entries.push(json!([key, self.expr(0, depth + 1)?]));
                    self.eat(Token::Punct(','));
                }
                self.eat(Token::Punct('}'));
                json!({"k":"Obj","entries":entries})
            }
            Some(Token::State(n)) => {
                if self.peek() == Some(&Token::Op("=")) {
                    self.pos += 1;
                    json!({"k":"Assign","target":n,"value":self.expr(0,depth+1)?})
                } else {
                    json!({"k":"StateRef","n":n})
                }
            }
            Some(t @ (Token::Name(_) | Token::Builtin(_))) => {
                let is_builtin = matches!(t, Token::Builtin(_));
                let name = match t {
                    Token::Name(n) | Token::Builtin(n) => n,
                    _ => unreachable!(),
                };
                let call = is_builtin
                    || name.as_bytes()[0].is_ascii_uppercase()
                        && (builtin(&name).is_none() || name == "Action");
                if call && self.peek() == Some(&Token::Punct('(')) {
                    self.pos += 1;
                    let mut v = json!({"k":"Comp","name":name,"args":self.list(')',depth)?});
                    if is_builtin && builtin(&name).is_none() {
                        v["fn"] = true.into();
                    }
                    v
                } else {
                    json!({"k":"Ref","n":name})
                }
            }
            Some(Token::Op(op @ ("!" | "-"))) => {
                json!({"k":"UnaryOp","op":op,"operand":self.expr(8,depth+1)?})
            }
            Some(Token::Punct('(')) => {
                let inner = self.expr(0, depth + 1)?;
                self.eat(Token::Punct(')'));
                inner
            }
            _ => json!({"k":"Null"}),
        };
        let mut chain_depth = 0;
        loop {
            let prec = match self.peek() {
                Some(Token::Punct('?')) => 1,
                Some(Token::Op("||")) => 2,
                Some(Token::Op("&&")) => 3,
                Some(Token::Op("==" | "!=")) => 4,
                Some(Token::Op(">" | "<" | ">=" | "<=")) => 5,
                Some(Token::Op("+" | "-")) => 6,
                Some(Token::Op("*" | "/" | "%")) => 7,
                Some(Token::Punct('.' | '[')) => 9,
                _ => 0,
            };
            if prec <= min {
                break;
            }
            chain_depth += 1;
            ensure!(
                depth + chain_depth <= MAX_DEPTH,
                "Brief expression chain exceeds {MAX_DEPTH}"
            );
            left = match self.take().unwrap() {
                Token::Op(op) => {
                    json!({"k":"BinOp","op":op,"left":left,"right":self.expr(prec,depth+1)?})
                }
                Token::Punct('?') => {
                    let then = self.expr(0, depth + 1)?;
                    self.eat(Token::Punct(':'));
                    json!({"k":"Ternary","cond":left,"then":then,"else":self.expr(0,depth+1)?})
                }
                Token::Punct('.') => {
                    let field = match self.take() {
                        Some(Token::Name(n) | Token::Str(n)) => n,
                        Some(Token::State(n)) => n.trim_start_matches('$').to_owned(),
                        Some(Token::Num(n)) => n.to_string(),
                        _ => "?".into(),
                    };
                    json!({"k":"Member","obj":left,"field":field})
                }
                Token::Punct('[') => {
                    let index = self.expr(0, depth + 1)?;
                    self.eat(Token::Punct(']'));
                    json!({"k":"Index","obj":left,"index":index})
                }
                _ => unreachable!(),
            };
        }
        Ok(left)
    }
}
fn statements(tokens: &[Token]) -> Result<BTreeMap<String, Value>> {
    let mut out = BTreeMap::new();
    let mut pos = 0;
    let mut nodes = 0;
    while pos < tokens.len() {
        if tokens[pos] == Token::Newline {
            pos += 1;
            continue;
        }
        let name = match &tokens[pos] {
            Token::Name(n) | Token::State(n) => Some(n.clone()),
            _ => None,
        };
        if name.is_none() || tokens.get(pos + 1) != Some(&Token::Op("=")) {
            while pos < tokens.len() && tokens[pos] != Token::Newline {
                pos += 1;
            }
            continue;
        }
        pos += 2;
        let mut body = Vec::new();
        let mut depth = 0usize;
        let mut ternary = 0usize;
        while pos < tokens.len() {
            let t = &tokens[pos];
            if *t == Token::Newline {
                if depth == 0 && ternary == 0 {
                    let mut peek = pos + 1;
                    while tokens.get(peek) == Some(&Token::Newline) {
                        peek += 1;
                    }
                    if tokens.get(peek) != Some(&Token::Punct('?')) {
                        break;
                    }
                }
                pos += 1;
                continue;
            }
            match t {
                Token::Punct('(' | '[' | '{') => depth += 1,
                Token::Punct(')' | ']' | '}') => depth = depth.saturating_sub(1),
                Token::Punct('?') if depth == 0 => ternary += 1,
                Token::Punct(':') if depth == 0 => ternary = ternary.saturating_sub(1),
                _ => {}
            }
            body.push(t.clone());
            pos += 1;
        }
        if !body.is_empty() {
            let mut reader = Reader {
                tokens: &body,
                pos: 0,
                nodes,
            };
            let value = reader.expr(0, 0)?;
            nodes = reader.nodes;
            out.insert(name.unwrap(), value);
        }
    }
    Ok(out)
}
