//! LaTeX math (the subset chats use) to a tree. Anything outside the subset is an error,
//! so the caller leaves the message text as typed instead of drawing half a formula.

use super::font::font;
use super::tables::*;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Class {
    Ord,
    Op,
    Bin,
    Rel,
    Open,
    Close,
    Punct,
    Inner,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Style {
    Display,
    Text,
    Script,
    ScriptScript,
}

#[derive(Debug)]
pub enum Node {
    /// One character, its alphabet already applied.
    Ch(char, Class),
    Group(Vec<Node>),
    /// `\text{..}`: upright, spaces kept.
    Text(String),
    Space(f32),
    Frac { num: Box<Node>, den: Box<Node>, rule: bool, delims: Option<(char, char)>, style: Option<Style> },
    Sqrt { index: Option<Box<Node>>, body: Box<Node> },
    Script { base: Box<Node>, sub: Option<Box<Node>>, sup: Option<Box<Node>> },
    /// `\sum`, `\int`, ...
    BigOp { ch: char, limits: bool },
    /// `\sin`, `\lim`, `\operatorname{..}`.
    NamedOp { name: String, limits: bool },
    Left { open: Option<char>, body: Vec<Node>, close: Option<char> },
    Middle(Option<char>),
    /// `\big(`, `\Bigg]`, ...
    Big { size: f32, ch: char, class: Class },
    Accent { ch: char, body: Box<Node>, wide: bool },
    /// `\overline` and `\underline`.
    Bar { body: Box<Node>, over: bool },
    /// `\overbrace`, `\underbrace`, `\overrightarrow`: a stretchy glyph over or under the body.
    Stretch { ch: char, body: Box<Node>, over: bool },
    /// `\overset`, `\underset`, `\xrightarrow`: the base with text above and/or below.
    Stack { base: Box<Node>, over: Option<Box<Node>>, under: Option<Box<Node>> },
    Table(Table),
    StyleSwitch(Style),
}

#[derive(Debug)]
pub struct Table {
    pub rows: Vec<Vec<Node>>,
    /// Alignment of each column: l, c or r.
    pub cols: Vec<char>,
    pub left: Option<char>,
    pub right: Option<char>,
    /// Cells in display style, columns paired as `right|left` with no gap inside a pair.
    pub aligned: bool,
}

impl Node {
    pub fn class(&self) -> Class {
        match self {
            Node::Ch(_, c) | Node::Big { class: c, .. } => *c,
            Node::BigOp { .. } | Node::NamedOp { .. } => Class::Op,
            Node::Frac { .. } | Node::Left { .. } => Class::Inner,
            Node::Script { base, .. } => base.class(),
            Node::Stack { base, .. } => base.class(),
            _ => Class::Ord,
        }
    }
}

const MAX_SOURCE: usize = 4000;
const MAX_DEPTH: usize = 24;
const MAX_NODES: usize = 3000;

pub fn parse(src: &str) -> Result<Node, String> {
    if src.chars().count() > MAX_SOURCE {
        return Err("too long".into());
    }
    let mut p = Parser { chars: src.chars().collect(), pos: 0, depth: 0, nodes: 0 };
    let row = p.row()?;
    if p.pos < p.chars.len() {
        return Err(format!("unexpected {:?}", p.chars[p.pos]));
    }
    Ok(Node::Group(row))
}

struct Parser {
    chars: Vec<char>,
    pos: usize,
    depth: usize,
    nodes: usize,
}

/// What ended a row.
#[derive(PartialEq)]
enum Stop {
    End,
    CloseBrace,
    Amp,
    NewRow,
    Right,
    Middle,
    EndEnv,
}

impl Parser {
    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }

    fn skip_space(&mut self) {
        while self.peek().is_some_and(char::is_whitespace) {
            self.pos += 1;
        }
    }

    /// The command name at `\`, without consuming it.
    fn peek_cmd(&self) -> Option<String> {
        if self.peek() != Some('\\') {
            return None;
        }
        let mut i = self.pos + 1;
        let first = *self.chars.get(i)?;
        if !first.is_ascii_alphabetic() {
            return Some(first.to_string());
        }
        let mut name = String::new();
        while let Some(&c) = self.chars.get(i) {
            if !c.is_ascii_alphabetic() {
                break;
            }
            name.push(c);
            i += 1;
        }
        Some(name)
    }

    fn take_cmd(&mut self) -> Option<String> {
        let name = self.peek_cmd()?;
        self.pos += 1 + name.chars().count();
        Some(name)
    }

    fn count(&mut self) -> Result<(), String> {
        self.nodes += 1;
        if self.nodes > MAX_NODES {
            return Err("too big".into());
        }
        Ok(())
    }

    fn enter(&mut self) -> Result<(), String> {
        self.depth += 1;
        if self.depth > MAX_DEPTH {
            return Err("too deep".into());
        }
        Ok(())
    }

    /// Items up to a terminator; the terminator is left unconsumed.
    fn row_until(&mut self) -> Result<(Vec<Node>, Stop), String> {
        self.enter()?;
        let mut items: Vec<Node> = Vec::new();
        // `\over` splits the row; the position of the divider and its name.
        let mut divider: Option<(usize, String)> = None;
        let stop = loop {
            self.skip_space();
            let Some(c) = self.peek() else { break Stop::End };
            match c {
                '}' => break Stop::CloseBrace,
                '&' => break Stop::Amp,
                '^' | '_' | '\'' => {
                    self.script(&mut items)?;
                    continue;
                }
                '\\' => {
                    let name = self.peek_cmd().ok_or("dangling backslash")?;
                    match name.as_str() {
                        "\\" => break Stop::NewRow,
                        "right" => break Stop::Right,
                        "middle" => break Stop::Middle,
                        "end" => break Stop::EndEnv,
                        "over" | "choose" | "atop" => {
                            if divider.is_some() {
                                return Err("ambiguous fraction".into());
                            }
                            self.take_cmd();
                            divider = Some((items.len(), name));
                            continue;
                        }
                        "limits" | "nolimits" => {
                            self.take_cmd();
                            match items.last_mut() {
                                Some(Node::BigOp { limits, .. }) | Some(Node::NamedOp { limits, .. }) => *limits = name == "limits",
                                _ => return Err("limits without an operator".into()),
                            }
                            continue;
                        }
                        _ => {}
                    }
                }
                _ => {}
            }
            let node = self.atom()?;
            self.count()?;
            items.push(node);
        };
        self.depth -= 1;
        if let Some((at, kind)) = divider {
            let den = Node::Group(items.split_off(at));
            let num = Node::Group(items);
            let (rule, delims) = match kind.as_str() {
                "over" => (true, None),
                "choose" => (false, Some(('(', ')'))),
                _ => (false, None),
            };
            return Ok((vec![Node::Frac { num: Box::new(num), den: Box::new(den), rule, delims, style: None }], stop));
        }
        Ok((items, stop))
    }

    fn row(&mut self) -> Result<Vec<Node>, String> {
        let (items, stop) = self.row_until()?;
        if stop != Stop::End {
            return Err("unbalanced".into());
        }
        Ok(items)
    }

    /// `{ ... }` or a single token, as a node.
    fn arg(&mut self) -> Result<Node, String> {
        // `\frac\frac\frac...` nests without braces, so arguments count towards the depth too.
        self.enter()?;
        let node = self.arg_inner();
        self.depth -= 1;
        node
    }

    fn arg_inner(&mut self) -> Result<Node, String> {
        self.skip_space();
        match self.peek() {
            Some('{') => {
                self.pos += 1;
                let (items, stop) = self.row_until()?;
                if stop != Stop::CloseBrace {
                    return Err("missing }".into());
                }
                self.pos += 1;
                Ok(Node::Group(items))
            }
            Some('}') | Some('&') | None => Err("missing argument".into()),
            Some('^') | Some('_') => Err("missing argument".into()),
            _ => {
                if self.peek_cmd().is_some_and(|c| matches!(c.as_str(), "right" | "end" | "\\" | "over" | "middle")) {
                    return Err("missing argument".into());
                }
                self.atom()
            }
        }
    }

    /// `{text}` as raw characters.
    fn raw_arg(&mut self) -> Result<String, String> {
        self.skip_space();
        if self.peek() != Some('{') {
            return Err("expected {".into());
        }
        self.pos += 1;
        let mut depth = 1;
        let mut out = String::new();
        while let Some(c) = self.peek() {
            self.pos += 1;
            match c {
                '{' => depth += 1,
                '}' => {
                    depth -= 1;
                    if depth == 0 {
                        return Ok(out);
                    }
                }
                _ => {}
            }
            out.push(c);
        }
        Err("missing }".into())
    }

    /// Optional `[...]` argument.
    fn opt_arg(&mut self) -> Result<Option<Node>, String> {
        self.skip_space();
        if self.peek() != Some('[') {
            return Ok(None);
        }
        self.pos += 1;
        let mut depth = 0;
        let start = self.pos;
        while let Some(c) = self.peek() {
            match c {
                '{' => depth += 1,
                '}' => depth -= 1,
                ']' if depth == 0 => break,
                _ => {}
            }
            self.pos += 1;
        }
        if self.peek() != Some(']') {
            return Err("missing ]".into());
        }
        let inner: String = self.chars[start..self.pos].iter().collect();
        self.pos += 1;
        let mut sub = Parser { chars: inner.chars().collect(), pos: 0, depth: self.depth, nodes: 0 };
        Ok(Some(Node::Group(sub.row()?)))
    }

    /// Applies `^`, `_` and `'` to the last item of the row.
    fn script(&mut self, items: &mut Vec<Node>) -> Result<(), String> {
        let base = items.pop().unwrap_or(Node::Group(Vec::new()));
        let (mut sub, mut sup): (Option<Box<Node>>, Option<Box<Node>>) = (None, None);
        // `f''` is one superscript made of primes; `f'^2` would be a second one, as in TeX.
        let mut primed = false;
        loop {
            self.skip_space();
            match self.peek() {
                Some('\'') => {
                    self.pos += 1;
                    let prime = Node::Ch('\u{2032}', Class::Ord);
                    match &mut sup {
                        None => sup = Some(Box::new(Node::Group(vec![prime]))),
                        Some(s) if primed => match &mut **s {
                            Node::Group(g) => g.push(prime),
                            _ => unreachable!("primes are kept in a group"),
                        },
                        Some(_) => return Err("double superscript".into()),
                    }
                    primed = true;
                }
                Some('^') => {
                    self.pos += 1;
                    if sup.is_some() {
                        return Err("double superscript".into());
                    }
                    sup = Some(Box::new(self.arg()?));
                }
                Some('_') => {
                    self.pos += 1;
                    if sub.is_some() {
                        return Err("double subscript".into());
                    }
                    sub = Some(Box::new(self.arg()?));
                }
                _ => break,
            }
        }
        self.count()?;
        items.push(Node::Script { base: Box::new(base), sub, sup });
        Ok(())
    }

    fn check_glyph(&self, ch: char) -> Result<(), String> {
        if font().gid(ch).is_some() { Ok(()) } else { Err(format!("no glyph for {ch:?}")) }
    }

    fn ch(&self, ch: char, class: Class) -> Result<Node, String> {
        self.check_glyph(ch)?;
        Ok(Node::Ch(ch, class))
    }

    /// One atom, without its scripts.
    fn atom(&mut self) -> Result<Node, String> {
        self.skip_space();
        let Some(c) = self.peek() else { return Err("unexpected end".into()) };
        match c {
            '{' => return self.arg(),
            '\\' => return self.command(),
            '$' | '#' | '}' | '&' | '^' | '_' | '\'' => return Err(format!("unexpected {c:?}")),
            _ => {}
        }
        self.pos += 1;
        Ok(match c {
            '~' => Node::Space(0.33),
            '+' => Node::Ch('+', Class::Bin),
            '-' => Node::Ch('\u{2212}', Class::Bin),
            '*' => Node::Ch('\u{2217}', Class::Bin),
            '=' | '<' | '>' | ':' => Node::Ch(c, Class::Rel),
            ',' | ';' => Node::Ch(c, Class::Punct),
            '(' | '[' => Node::Ch(c, Class::Open),
            ')' | ']' => Node::Ch(c, Class::Close),
            'A'..='Z' | 'a'..='z' => Node::Ch(alphabet(c, Alphabet::Italic), Class::Ord),
            '\u{3b1}'..='\u{3c9}' | '\u{3d1}' | '\u{3d5}' | '\u{3d6}' | '\u{3f0}' | '\u{3f1}' | '\u{3f5}' => Node::Ch(alphabet(c, Alphabet::Italic), Class::Ord),
            _ => {
                let class = SYMBOLS.iter().find(|s| s.1 == c).map_or(Class::Ord, |s| s.2);
                self.ch(c, class)?
            }
        })
    }

    fn delimiter(&mut self) -> Result<Option<char>, String> {
        self.skip_space();
        if let Some(name) = self.peek_cmd() {
            self.take_cmd();
            return delimiter_command(&name).map(Some).ok_or_else(|| format!("bad delimiter \\{name}"));
        }
        let c = self.peek().ok_or("missing delimiter")?;
        self.pos += 1;
        match c {
            '.' => Ok(None),
            '(' | ')' | '[' | ']' | '|' | '/' => Ok(Some(c)),
            '<' => Ok(Some('\u{27e8}')),
            '>' => Ok(Some('\u{27e9}')),
            _ => Err(format!("bad delimiter {c:?}")),
        }
    }

    fn command(&mut self) -> Result<Node, String> {
        let name = self.take_cmd().ok_or("dangling backslash")?;
        let n = name.as_str();
        // Spacing.
        let space = match n {
            "," | "thinspace" => Some(3.0 / 18.0),
            ":" | ">" | "medspace" => Some(4.0 / 18.0),
            ";" | "thickspace" => Some(5.0 / 18.0),
            "!" | "negthinspace" => Some(-3.0 / 18.0),
            " " => Some(0.33),
            "quad" => Some(1.0),
            "qquad" => Some(2.0),
            _ => None,
        };
        if let Some(w) = space {
            return Ok(Node::Space(w));
        }
        if let Some(&(_, ch, class)) = SYMBOLS.iter().find(|s| s.0 == n) {
            return self.ch(ch, class);
        }
        if let Some(&(_, ch)) = GREEK.iter().find(|s| s.0 == n) {
            let alpha = if ('\u{391}'..='\u{3a9}').contains(&ch) { Alphabet::Roman } else { Alphabet::Italic };
            return self.ch(alphabet(ch, alpha), Class::Ord);
        }
        if let Some(&(_, ch, limits)) = BIG_OPS.iter().find(|s| s.0 == n) {
            self.check_glyph(ch)?;
            return Ok(Node::BigOp { ch, limits });
        }
        if let Some(&(_, limits)) = NAMED_OPS.iter().find(|s| s.0 == n) {
            return Ok(Node::NamedOp { name: n.to_string(), limits });
        }
        if let Some(&(_, ch)) = ACCENTS.iter().find(|s| s.0 == n) {
            let body = self.arg()?;
            return Ok(Node::Accent { ch, body: Box::new(body), wide: false });
        }
        if let Some(&(_, ch)) = WIDE_ACCENTS.iter().find(|s| s.0 == n) {
            let body = self.arg()?;
            return Ok(Node::Accent { ch, body: Box::new(body), wide: true });
        }
        if let Some(alpha) = alphabet_command(n) {
            let body = self.arg()?;
            return restyle(body, alpha);
        }
        if let Some((size, class)) = big_size(n) {
            let ch = self.delimiter()?.ok_or("big needs a delimiter")?;
            return Ok(Node::Big { size, ch, class });
        }
        Ok(match n {
            "frac" | "dfrac" | "tfrac" | "cfrac" => {
                let num = self.arg()?;
                let den = self.arg()?;
                let style = match n {
                    "dfrac" | "cfrac" => Some(Style::Display),
                    "tfrac" => Some(Style::Text),
                    _ => None,
                };
                Node::Frac { num: Box::new(num), den: Box::new(den), rule: true, delims: None, style }
            }
            "binom" | "dbinom" | "tbinom" => {
                let num = self.arg()?;
                let den = self.arg()?;
                let style = match n {
                    "dbinom" => Some(Style::Display),
                    "tbinom" => Some(Style::Text),
                    _ => None,
                };
                Node::Frac { num: Box::new(num), den: Box::new(den), rule: false, delims: Some(('(', ')')), style }
            }
            "sqrt" => {
                let index = self.opt_arg()?.map(Box::new);
                let body = self.arg()?;
                Node::Sqrt { index, body: Box::new(body) }
            }
            "root" => {
                let index = self.arg()?;
                self.skip_space();
                if self.take_cmd().as_deref() != Some("of") {
                    return Err("\\root needs \\of".into());
                }
                let body = self.arg()?;
                Node::Sqrt { index: Some(Box::new(index)), body: Box::new(body) }
            }
            "text" | "textrm" | "mbox" | "textnormal" | "textup" => Node::Text(self.text(Alphabet::Roman)?),
            "textbf" => Node::Text(self.text(Alphabet::Bold)?),
            "textit" | "emph" => Node::Text(self.text(Alphabet::Italic)?),
            "textsf" => Node::Text(self.text(Alphabet::Sans)?),
            "texttt" => Node::Text(self.text(Alphabet::Mono)?),
            "operatorname" => {
                let star = self.peek() == Some('*');
                if star {
                    self.pos += 1;
                }
                let name = self.raw_arg()?;
                if name.is_empty() || !name.chars().all(|c| c.is_alphanumeric()) {
                    return Err("bad operator name".into());
                }
                for ch in name.chars() {
                    self.check_glyph(ch)?;
                }
                Node::NamedOp { name, limits: star }
            }
            "left" => {
                let open = self.delimiter()?;
                let mut body = Vec::new();
                loop {
                    let (items, stop) = self.row_until()?;
                    body.extend(items);
                    match stop {
                        Stop::Right => {
                            self.take_cmd();
                            let close = self.delimiter()?;
                            return Ok(Node::Left { open, body, close });
                        }
                        Stop::Middle => {
                            self.take_cmd();
                            let d = self.delimiter()?;
                            body.push(Node::Middle(d));
                        }
                        _ => return Err("\\left without \\right".into()),
                    }
                }
            }
            "overline" => Node::Bar { body: Box::new(self.arg()?), over: true },
            "underline" => Node::Bar { body: Box::new(self.arg()?), over: false },
            "overbrace" => Node::Stretch { ch: '\u{23de}', body: Box::new(self.arg()?), over: true },
            "underbrace" => Node::Stretch { ch: '\u{23df}', body: Box::new(self.arg()?), over: false },
            "overrightarrow" => Node::Stretch { ch: '\u{20d7}', body: Box::new(self.arg()?), over: true },
            "overleftarrow" => Node::Stretch { ch: '\u{20d6}', body: Box::new(self.arg()?), over: true },
            "overset" | "stackrel" => {
                let over = self.arg()?;
                let base = self.arg()?;
                Node::Stack { base: Box::new(base), over: Some(Box::new(over)), under: None }
            }
            "underset" => {
                let under = self.arg()?;
                let base = self.arg()?;
                Node::Stack { base: Box::new(base), over: None, under: Some(Box::new(under)) }
            }
            "xrightarrow" | "xleftarrow" => {
                let under = self.opt_arg()?.map(Box::new);
                let over = self.arg()?;
                let ch = if n == "xrightarrow" { '\u{2192}' } else { '\u{2190}' };
                Node::Stack { base: Box::new(Node::Stretch { ch, body: Box::new(Node::Group(Vec::new())), over: true }), over: Some(Box::new(over)), under }
            }
            "displaystyle" => Node::StyleSwitch(Style::Display),
            "textstyle" => Node::StyleSwitch(Style::Text),
            "scriptstyle" => Node::StyleSwitch(Style::Script),
            "scriptscriptstyle" => Node::StyleSwitch(Style::ScriptScript),
            "begin" => self.environment()?,
            "ldots" | "dots" | "dotsc" | "dotsb" | "dotsm" | "dotsi" | "mathellipsis" => Node::Ch('\u{2026}', Class::Inner),
            "cdots" | "hdots" => Node::Ch('\u{22ef}', Class::Inner),
            "vdots" => Node::Ch('\u{22ee}', Class::Ord),
            "ddots" => Node::Ch('\u{22f1}', Class::Inner),
            "not" => {
                let rel = self.arg()?;
                return negate(rel);
            }
            "hspace" | "hskip" | "kern" | "mskip" => {
                let raw = self.raw_arg()?;
                Node::Space(parse_length(&raw)?)
            }
            _ => return Err(format!("unknown \\{n}")),
        })
    }

    /// Raw `{...}` text mode argument in the given alphabet (Latin letters only).
    fn text(&mut self, alpha: Alphabet) -> Result<String, String> {
        let raw = self.raw_arg()?;
        let mut out = String::new();
        let mut it = raw.chars().peekable();
        while let Some(c) = it.next() {
            let c = if c == '\\' {
                match it.next() {
                    Some(e @ ('%' | '$' | '&' | '_' | '#' | '{' | '}')) => e,
                    Some(' ') | Some(',') | Some(';') | Some(':') => ' ',
                    _ => return Err("command inside \\text".into()),
                }
            } else if c == '\n' || c == '\t' {
                ' '
            } else {
                c
            };
            if c == '{' || c == '}' {
                continue;
            }
            let mapped = if c.is_ascii_alphabetic() { alphabet(c, alpha) } else { c };
            if c != ' ' {
                self.check_glyph(mapped).or_else(|_| self.check_glyph(c))?;
            }
            out.push(if font().gid(mapped).is_some() { mapped } else { c });
        }
        Ok(out)
    }

    fn environment(&mut self) -> Result<Node, String> {
        let name = self.raw_arg()?;
        let mut cols: Vec<char> = Vec::new();
        let (left, right, aligned, default_cols): (Option<char>, Option<char>, bool, &str) = match name.as_str() {
            "matrix" => (None, None, false, "c"),
            "pmatrix" => (Some('('), Some(')'), false, "c"),
            "bmatrix" => (Some('['), Some(']'), false, "c"),
            "Bmatrix" => (Some('{'), Some('}'), false, "c"),
            "vmatrix" => (Some('|'), Some('|'), false, "c"),
            "Vmatrix" => (Some('\u{2016}'), Some('\u{2016}'), false, "c"),
            "cases" => (Some('{'), None, false, "l"),
            "array" => (None, None, false, "c"),
            "aligned" | "align" | "align*" | "split" | "eqnarray" => (None, None, true, "rl"),
            "gathered" | "gather" | "gather*" | "equation" | "equation*" | "displaymath" | "math" => (None, None, true, "c"),
            _ => return Err(format!("unknown environment {name}")),
        };
        if name == "array" {
            for c in self.raw_arg()?.chars() {
                match c {
                    'l' | 'c' | 'r' => cols.push(c),
                    '|' | ' ' => {}
                    _ => return Err("bad column spec".into()),
                }
            }
            if cols.is_empty() {
                return Err("empty column spec".into());
            }
        }
        let mut rows: Vec<Vec<Node>> = vec![Vec::new()];
        loop {
            let (items, stop) = self.row_until()?;
            rows.last_mut().unwrap().push(Node::Group(items));
            match stop {
                Stop::Amp => self.pos += 1,
                Stop::NewRow => {
                    self.take_cmd();
                    // `\\[2pt]` spacing is ignored.
                    self.opt_arg()?;
                    rows.push(Vec::new());
                }
                Stop::EndEnv => {
                    self.take_cmd();
                    let end = self.raw_arg()?;
                    if end != name {
                        return Err("mismatched \\end".into());
                    }
                    break;
                }
                _ => return Err("unterminated environment".into()),
            }
        }
        // A trailing `\\` leaves an empty last row.
        if rows.len() > 1 && rows.last().is_some_and(|r| r.len() == 1 && matches!(&r[0], Node::Group(g) if g.is_empty())) {
            rows.pop();
        }
        let width = rows.iter().map(Vec::len).max().unwrap_or(1);
        if cols.is_empty() {
            let spec: Vec<char> = default_cols.chars().collect();
            cols = (0..width).map(|i| spec[i % spec.len()]).collect();
        }
        if width > 24 || rows.len() > 60 {
            return Err("table too big".into());
        }
        for row in &mut rows {
            while row.len() < width {
                row.push(Node::Group(Vec::new()));
            }
            // As in amsmath, the left-aligned half of an aligned pair starts with an empty atom,
            // so a leading relation (`&= x`) gets its space.
            if aligned && width > 1 {
                for cell in row.iter_mut().skip(1).step_by(2) {
                    if let Node::Group(items) = cell {
                        items.insert(0, Node::Group(Vec::new()));
                    }
                }
            }
        }
        while cols.len() < width {
            cols.push(*cols.last().unwrap_or(&'c'));
        }
        self.count()?;
        Ok(Node::Table(Table { rows, cols, left, right, aligned }))
    }
}

fn parse_length(raw: &str) -> Result<f32, String> {
    let raw = raw.trim();
    let split = raw.find(|c: char| c.is_ascii_alphabetic()).unwrap_or(raw.len());
    let value: f32 = raw[..split].trim().parse().map_err(|_| "bad length".to_string())?;
    let em = match raw[split..].trim() {
        "em" => value,
        "ex" => value * 0.5,
        "pt" => value / 10.0,
        "mu" => value / 18.0,
        _ => return Err("bad length unit".into()),
    };
    if !(-10.0..=10.0).contains(&em) {
        return Err("length out of range".into());
    }
    Ok(em)
}

/// `\not` before a relation: the same relation with a slash through it, when the font has one.
fn negate(rel: Node) -> Result<Node, String> {
    if let Node::Ch(c, class) = rel {
        let negated = match c {
            '=' => '\u{2260}',
            '\u{2208}' => '\u{2209}',
            '\u{220b}' => '\u{220c}',
            '\u{2282}' => '\u{2284}',
            '\u{2283}' => '\u{2285}',
            '\u{2286}' => '\u{2288}',
            '\u{2287}' => '\u{2289}',
            '\u{2261}' => '\u{2262}',
            '\u{2248}' => '\u{2249}',
            '\u{223c}' => '\u{2241}',
            '\u{2243}' => '\u{2244}',
            '\u{2245}' => '\u{2247}',
            '<' => '\u{226e}',
            '>' => '\u{226f}',
            '\u{2264}' => '\u{2270}',
            '\u{2265}' => '\u{2271}',
            '\u{2203}' => '\u{2204}',
            '\u{2223}' => '\u{2224}',
            '\u{2225}' => '\u{2226}',
            _ => return Err("cannot negate".into()),
        };
        return Ok(Node::Ch(negated, class));
    }
    Err("cannot negate".into())
}

fn delimiter_command(name: &str) -> Option<char> {
    Some(match name {
        "{" | "lbrace" => '{',
        "}" | "rbrace" => '}',
        "langle" => '\u{27e8}',
        "rangle" => '\u{27e9}',
        "lfloor" => '\u{230a}',
        "rfloor" => '\u{230b}',
        "lceil" => '\u{2308}',
        "rceil" => '\u{2309}',
        "|" | "Vert" | "lVert" | "rVert" => '\u{2016}',
        "vert" | "lvert" | "rvert" => '|',
        "backslash" => '\\',
        "uparrow" => '\u{2191}',
        "downarrow" => '\u{2193}',
        "updownarrow" => '\u{2195}',
        "lbrack" => '[',
        "rbrack" => ']',
        "lgroup" => '\u{27ee}',
        "rgroup" => '\u{27ef}',
        _ => return None,
    })
}

/// `\big`, `\Bigl`, `\biggr`...: the target size in em and the class of the delimiter.
fn big_size(name: &str) -> Option<(f32, Class)> {
    let (base, class) = if let Some(b) = name.strip_suffix('l') {
        (b, Class::Open)
    } else if let Some(b) = name.strip_suffix('r') {
        (b, Class::Close)
    } else if let Some(b) = name.strip_suffix('m') {
        (b, Class::Rel)
    } else {
        (name, Class::Ord)
    };
    let size = match base {
        "big" => 1.2,
        "Big" => 1.8,
        "bigg" => 2.4,
        "Bigg" => 3.0,
        _ => return None,
    };
    Some((size, class))
}

/// Applies an alphabet to every character of a node (`\mathbf{x+1}`).
fn restyle(node: Node, alpha: Alphabet) -> Result<Node, String> {
    Ok(match node {
        Node::Ch(c, class) => {
            let mapped = alphabet(unstyle(c), alpha);
            let mapped = if font().gid(mapped).is_some() { mapped } else { unstyle(c) };
            Node::Ch(mapped, class)
        }
        Node::Group(items) => Node::Group(items.into_iter().map(|n| restyle(n, alpha)).collect::<Result<_, _>>()?),
        Node::Script { base, sub, sup } => Node::Script {
            base: Box::new(restyle(*base, alpha)?),
            sub: sub.map(|s| restyle(*s, alpha).map(Box::new)).transpose()?,
            sup: sup.map(|s| restyle(*s, alpha).map(Box::new)).transpose()?,
        },
        Node::NamedOp { name, limits } => Node::NamedOp { name, limits },
        Node::Frac { num, den, rule, delims, style } => {
            Node::Frac { num: Box::new(restyle(*num, alpha)?), den: Box::new(restyle(*den, alpha)?), rule, delims, style }
        }
        Node::Text(t) => Node::Text(t.chars().map(|c| if c.is_ascii_alphabetic() { alphabet(c, alpha) } else { c }).collect()),
        other => other,
    })
}

/// The plain letter behind a math alphanumeric, so a second `\mathbf` starts from the base.
fn unstyle(c: char) -> char {
    let u = c as u32;
    let plain = |from: u32, to: u32| (from..=to).contains(&u);
    for (base, ascii) in [
        (0x1D400, b'A'), (0x1D41A, b'a'), (0x1D434, b'A'), (0x1D44E, b'a'), (0x1D468, b'A'), (0x1D482, b'a'),
        (0x1D49C, b'A'), (0x1D4B6, b'a'), (0x1D504, b'A'), (0x1D51E, b'a'), (0x1D538, b'A'), (0x1D552, b'a'),
        (0x1D5A0, b'A'), (0x1D5BA, b'a'), (0x1D670, b'A'), (0x1D68A, b'a'),
    ] {
        if plain(base, base + 25) {
            return (ascii + (u - base) as u8) as char;
        }
    }
    if c == '\u{210e}' {
        return 'h';
    }
    if plain(0x1D6FC, 0x1D714) {
        return char::from_u32(0x3B1 + (u - 0x1D6FC)).unwrap_or(c);
    }
    c
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(s: &str) {
        if let Err(e) = parse(s) {
            panic!("{s}: {e}");
        }
    }

    fn bad(s: &str) {
        assert!(parse(s).is_err(), "{s} should not parse");
    }

    #[test]
    fn accepts_common_formulas() {
        ok(r"x^2 + y^2 = z^2");
        ok(r"\frac{-b \pm \sqrt{b^2-4ac}}{2a}");
        ok(r"\sum_{i=1}^{n} i = \frac{n(n+1)}{2}");
        ok(r"\int_0^\infty e^{-x^2}\,dx = \frac{\sqrt\pi}{2}");
        ok(r"\left(\frac{a}{b}\right)^{n}");
        ok(r"\begin{pmatrix} a & b \\ c & d \end{pmatrix}");
        ok(r"f(x) = \begin{cases} 1 & x > 0 \\ 0 & \text{иначе} \end{cases}");
        ok(r"\begin{aligned} a &= b + c \\ &= d \end{aligned}");
        ok(r"\lim_{x \to 0} \frac{\sin x}{x} = 1");
        ok(r"\mathbb{R}^n, \mathcal{L}, \mathbf{v}, \hat{x}, \vec{v}, \overline{AB}");
        ok(r"f'(x) + f''(x)");
        ok(r"{n \choose k} = \binom{n}{k}");
        ok(r"\sqrt[3]{x}");
        ok(r"\overbrace{a+b}^{n} \underbrace{c}_{m}");
        ok("");
    }

    #[test]
    fn rejects_what_it_cannot_draw() {
        bad(r"\unknowncommand");
        bad(r"\frac{1}{");
        bad(r"x^2^3");
        bad(r"x_1_2");
        bad(r"\begin{pmatrix} a \end{bmatrix}");
        bad(r"\left( x");
        bad(r"a $ b");
        bad(r"}");
        bad(r"\text{\foo}");
    }

    #[test]
    fn limits_size() {
        bad(&"x".repeat(MAX_SOURCE + 1));
        bad(&"{".repeat(100));
        bad(&r"\frac".repeat(500));
        bad(&r"\sqrt".repeat(500));
    }
}
