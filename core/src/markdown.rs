//! Message formatting, parsed once here so every client renders the same thing.
//!
//! Supported (Telegram-like): **bold**, *italic* or _italic_, ~~strike~~, `code`,
//! ```code block```, and bare http(s) links. A marker only counts when it has a
//! matching closing marker in the same paragraph and touches text on the inner side,
//! so `2 * 3 * 4` and snake_case stay as typed.
//!
//! LaTeX formulas: `$x^2$` inline, `$$x^2$$` or `\[x^2\]` on their own line, `\(x^2\)` inline.
//! A dollar sign opens a formula only when it touches the formula and the text before it is
//! not a word, and closes it only when no digit follows, so "$5 and $10" stays as typed.
//! Text that is not valid LaTeX stays as typed too. Formulas are taken out before bold and
//! italic are looked for, because `_` and `*` mean something else inside them.

use serde_json::{Value, json};

#[derive(Clone, Copy, Default, PartialEq)]
struct Style {
    bold: bool,
    italic: bool,
    strike: bool,
}

/// Formulas per message that get drawn, and how much they may weigh together; the rest stays text.
const MAX_FORMULAS: usize = 24;
const MAX_FORMULA_BYTES: usize = 160_000;

/// First placeholder character (private use area) standing for a formula inside styled text.
const PLACEHOLDER: u32 = 0xE000;

struct Out {
    spans: Vec<Value>,
    /// Formula spans of the segment being styled, referenced by placeholder characters.
    math: Vec<Value>,
    /// Formulas taken from the message so far and their size.
    formulas: usize,
    formula_bytes: usize,
}

impl Out {
    fn push(&mut self, text: &str, st: Style, extra: Option<(&str, Value)>) {
        if text.is_empty() {
            return;
        }
        if extra.is_none() && !self.math.is_empty() && text.chars().any(|c| placeholder(c).is_some()) {
            let mut from = 0;
            for (at, c) in text.char_indices() {
                let Some(i) = placeholder(c) else { continue };
                self.push_text(&text[from..at], st, None);
                if let Some(span) = self.math.get(i) {
                    self.spans.push(span.clone());
                }
                from = at + c.len_utf8();
            }
            self.push_text(&text[from..], st, None);
            return;
        }
        self.push_text(text, st, extra);
    }

    fn push_text(&mut self, text: &str, st: Style, extra: Option<(&str, Value)>) {
        if text.is_empty() {
            return;
        }
        let mut v = json!({"text": text});
        if st.bold {
            v["bold"] = json!(true);
        }
        if st.italic {
            v["italic"] = json!(true);
        }
        if st.strike {
            v["strike"] = json!(true);
        }
        if let Some((k, val)) = extra {
            v[k] = val;
        }
        // Merge with the previous span when the styling is identical.
        if let Some(last) = self.spans.last_mut() {
            let same = last.as_object().map(|o| o.iter().filter(|(k, _)| *k != "text").collect::<Vec<_>>())
                == v.as_object().map(|o| o.iter().filter(|(k, _)| *k != "text").collect::<Vec<_>>());
            if same && v.get("link").is_none() {
                let t = format!("{}{}", last["text"].as_str().unwrap_or(""), text);
                last["text"] = json!(t);
                return;
            }
        }
        self.spans.push(v);
    }
}

/// Spans of a message: [{text, bold?, italic?, strike?, code?, pre?, link?}].
pub fn spans(text: &str) -> Vec<Value> {
    let mut out = Out { spans: Vec::new(), math: Vec::new(), formulas: 0, formula_bytes: 0 };
    let mut rest = text;
    // Code blocks first: nothing inside them is formatted.
    while let Some(start) = rest.find("```") {
        let after = &rest[start + 3..];
        let Some(end) = after.find("```") else { break };
        inline(&rest[..start], &mut out);
        let mut code = &after[..end];
        // An optional language tag on the opening line is dropped.
        if let Some(nl) = code.find('\n') {
            if !code[..nl].contains(' ') && nl < 20 {
                code = &code[nl + 1..];
            }
        }
        out.push(code.trim_end_matches('\n'), Style::default(), Some(("pre", json!(true))));
        rest = &after[end + 3..];
    }
    inline(rest, &mut out);
    out.spans
}

fn inline(text: &str, out: &mut Out) {
    // Inline code splits the text; formatting does not cross it.
    let mut rest = text;
    while let Some(start) = rest.find('`') {
        let after = &rest[start + 1..];
        match after.find('`') {
            Some(end) if end > 0 && !after[..end].contains('\n') => {
                styled_with_math(&rest[..start], out);
                out.push(&after[..end], Style::default(), Some(("code", json!(true))));
                rest = &after[end + 1..];
            }
            _ => {
                styled_with_math(&rest[..start + 1], out);
                rest = after;
            }
        }
    }
    styled_with_math(rest, out);
}

fn placeholder(c: char) -> Option<usize> {
    let i = (c as u32).checked_sub(PLACEHOLDER)? as usize;
    (i < MAX_FORMULAS).then_some(i)
}

/// Formulas become placeholder characters, the rest is formatted as usual, and `Out::push` puts the formulas back.
fn styled_with_math(s: &str, out: &mut Out) {
    if !s.contains(['$', '\\']) || s.chars().any(|c| placeholder(c).is_some()) {
        return styled(s, out);
    }
    let (marked, formulas) = take_formulas(s, out.formulas, out.formula_bytes);
    out.formulas += formulas.len();
    out.formula_bytes += formulas.iter().map(|f| f["math"].to_string().len()).sum::<usize>();
    let saved = std::mem::replace(&mut out.math, formulas);
    styled(&marked, out);
    out.math = saved;
}

/// Replaces the valid formulas of `s` with placeholders. `used` formulas weighing `used_bytes` are already drawn.
fn take_formulas(s: &str, used: usize, used_bytes: usize) -> (String, Vec<Value>) {
    let mut marked = String::with_capacity(s.len());
    let mut found: Vec<Value> = Vec::new();
    let mut bytes = used_bytes;
    let mut i = 0;
    while i < s.len() {
        let rest = &s[i..];
        let c = rest.chars().next().unwrap();
        // Links are left alone: a dollar sign in an address is not a formula.
        if rest.starts_with("http://") || rest.starts_with("https://") {
            let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
            marked.push_str(&rest[..end]);
            i += end;
            continue;
        }
        if rest.starts_with("\\$") {
            marked.push('$');
            i += 2;
            continue;
        }
        let candidate = if rest.starts_with("$$") {
            delimited(rest, "$$", "$$", true)
        } else if rest.starts_with("\\[") {
            delimited(rest, "\\[", "\\]", true)
        } else if rest.starts_with("\\(") {
            delimited(rest, "\\(", "\\)", false)
        } else if c == '$' && !s[..i].chars().next_back().is_some_and(char::is_alphanumeric) {
            dollar_formula(rest)
        } else {
            None
        };
        if let Some((tex, len, display)) = candidate {
            if used + found.len() < MAX_FORMULAS && bytes < MAX_FORMULA_BYTES && !tex.trim().is_empty() {
                if let Some(shape) = crate::math::render(tex.trim(), display) {
                    bytes += shape.to_string().len();
                    marked.push(char::from_u32(PLACEHOLDER + found.len() as u32).unwrap());
                    found.push(json!({"text": &rest[..len], "math": shape, "display": display}));
                    i += len;
                    continue;
                }
            }
            // A failed `$$` must not leave one `$` that pairs up with something else.
            if rest.starts_with("$$") {
                marked.push_str("$$");
                i += 2;
                continue;
            }
        }
        marked.push(c);
        i += c.len_utf8();
    }
    (marked, found)
}

/// `$$...$$`, `\[...\]` and `\(...\)`: (formula, bytes used, display).
fn delimited<'a>(rest: &'a str, open: &str, close: &str, display: bool) -> Option<(&'a str, usize, bool)> {
    let end = rest[open.len()..].find(close)?;
    Some((&rest[open.len()..open.len() + end], open.len() + end + close.len(), display))
}

/// `$...$` on one line: text right next to the dollar signs, no digit right after the closing one.
fn dollar_formula(rest: &str) -> Option<(&str, usize, bool)> {
    let body = &rest[1..];
    if body.starts_with(|c: char| c.is_whitespace() || c == '$') {
        return None;
    }
    let mut from = 0;
    while let Some(off) = body[from..].find('$') {
        let end = from + off;
        // `\$` inside a formula is a literal dollar.
        if body[..end].ends_with('\\') {
            from = end + 1;
            continue;
        }
        let tex = &body[..end];
        let after = body[end + 1..].chars().next();
        if tex.contains('\n') || tex.ends_with(char::is_whitespace) || after.is_some_and(|c| c.is_ascii_digit()) {
            return None;
        }
        return Some((tex, 1 + end + 1, false));
    }
    None
}

const MARKERS: [&str; 4] = ["**", "~~", "*", "_"];

fn is_word(c: Option<char>) -> bool {
    c.is_some_and(|c| c.is_alphanumeric())
}

/// Finds a closing `m` for an opener at byte `from`, within the same paragraph.
fn closing(s: &str, m: &str, from: usize) -> Option<usize> {
    let mut i = from;
    while let Some(off) = s[i..].find(m) {
        let pos = i + off;
        if s[from..pos].contains("\n\n") {
            return None;
        }
        let before = s[..pos].chars().next_back();
        let after = s[pos + m.len()..].chars().next();
        let inner_ok = pos > from && !before.is_some_and(char::is_whitespace);
        // `_` must not sit inside a word (snake_case), `*` must not be part of `**`.
        let edge_ok = if m == "_" { !is_word(after) } else { true };
        let not_double = !(m == "*" && (after == Some('*') || before == Some('*')));
        if inner_ok && edge_ok && not_double {
            return Some(pos);
        }
        i = pos + m.len();
    }
    None
}

fn styled(s: &str, out: &mut Out) {
    let mut st = Style::default();
    // Stack of (marker, closing byte position) for the currently open markers.
    let mut open: Vec<(&str, usize)> = Vec::new();
    let mut buf = String::new();
    let mut i = 0;
    while i < s.len() {
        if let Some(&(m, end)) = open.last() {
            if i == end {
                links(&buf, st, out);
                buf.clear();
                set(&mut st, m, false);
                open.pop();
                i += m.len();
                continue;
            }
        }
        let mut matched = false;
        for m in MARKERS {
            if !s[i..].starts_with(m) || open.iter().any(|(o, _)| *o == m) {
                continue;
            }
            let before = s[..i].chars().next_back();
            let after = s[i + m.len()..].chars().next();
            if after.is_none_or(char::is_whitespace) || (m == "_" && is_word(before)) {
                continue;
            }
            // The closing marker must come before the enclosing one closes.
            let limit = open.last().map(|o| o.1).unwrap_or(s.len());
            if let Some(end) = closing(&s[..limit], m, i + m.len()) {
                links(&buf, st, out);
                buf.clear();
                set(&mut st, m, true);
                open.push((m, end));
                i += m.len();
                matched = true;
                break;
            }
        }
        if !matched {
            let c = s[i..].chars().next().unwrap();
            buf.push(c);
            i += c.len_utf8();
        }
    }
    links(&buf, st, out);
}

fn set(st: &mut Style, m: &str, on: bool) {
    match m {
        "**" => st.bold = on,
        "~~" => st.strike = on,
        _ => st.italic = on,
    }
}

/// Splits plain text into text and http(s) link spans.
fn links(s: &str, st: Style, out: &mut Out) {
    let mut rest = s;
    loop {
        let found = ["https://", "http://"].iter().filter_map(|p| rest.find(p)).min();
        let Some(start) = found else { break };
        let tail = &rest[start..];
        let mut end = tail.find(|c: char| c.is_whitespace() || c == '<' || c == '>' || c == '"').unwrap_or(tail.len());
        // Trailing punctuation belongs to the sentence, not the link.
        while end > 0 && tail[..end].ends_with(['.', ',', '!', '?', ':', ';', ')', '»']) {
            end -= tail[..end].chars().next_back().unwrap().len_utf8();
        }
        if end <= "https://".len() {
            out.push(&rest[..start + end.max(1)], st, None);
            rest = &rest[start + end.max(1)..];
            continue;
        }
        mentions(&rest[..start], st, out);
        let url = &tail[..end];
        out.push(url, st, Some(("link", json!(url))));
        rest = &tail[end..];
    }
    mentions(rest, st, out);
}

fn mentions(text: &str, st: Style, out: &mut Out) {
    let mut from = 0;
    for (at, _) in text.match_indices('@') {
        if at > 0 && text[..at].chars().next_back().is_some_and(|c| c.is_alphanumeric() || c == '_' || c == '@') { continue; }
        let name: String = text[at+1..].chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_').collect();
        if !(3..=32).contains(&name.len()) { continue; }
        let end = at+1+name.len();
        if text[end..].chars().next().is_some_and(|c| c.is_alphanumeric() || c == '_') { continue; }
        out.push(&text[from..at], st, None);
        out.push(&text[at..end], st, Some(("mention",json!(name))));
        from = end;
    }
    out.push(&text[from..], st, None);
}

#[cfg(test)]
mod tests {
    use super::spans;
    use serde_json::json;

    #[test]
    fn formats_like_telegram() {
        assert_eq!(spans("просто текст"), vec![json!({"text": "просто текст"})]);
        assert_eq!(spans("это **жирно** и *курсив*"), vec![
            json!({"text": "это "}),
            json!({"text": "жирно", "bold": true}),
            json!({"text": " и "}),
            json!({"text": "курсив", "italic": true}),
        ]);
        assert_eq!(spans("~~нет~~ `a*b*c`"), vec![json!({"text": "нет", "strike": true}), json!({"text": " "}), json!({"text": "a*b*c", "code": true})]);
        assert_eq!(spans("```rust\nfn main() {}\n```"), vec![json!({"text": "fn main() {}", "pre": true})]);
        assert_eq!(spans("**жирный _и курсив_**"), vec![
            json!({"text": "жирный ", "bold": true}),
            json!({"text": "и курсив", "bold": true, "italic": true}),
        ]);
    }

    #[test]
    fn leaves_plain_symbols_alone() {
        assert_eq!(spans("2 * 3 * 4"), vec![json!({"text": "2 * 3 * 4"})]);
        assert_eq!(spans("snake_case_name"), vec![json!({"text": "snake_case_name"})]);
        assert_eq!(spans("**не закрыто"), vec![json!({"text": "**не закрыто"})]);
        assert_eq!(spans("a ** b"), vec![json!({"text": "a ** b"})]);
    }

    #[test]
    fn draws_formulas() {
        let s = spans(r"площадь $\pi r^2$ круга");
        assert_eq!(s.len(), 3);
        assert_eq!(s[0], json!({"text": "площадь "}));
        assert_eq!(s[1]["text"], r"$\pi r^2$");
        assert_eq!(s[1]["display"], false);
        assert!(s[1]["math"]["w"].as_f64().unwrap() > 0.5);
        assert_eq!(s[2], json!({"text": " круга"}));
        // On their own line, in either notation.
        for src in [r"$$\frac{a}{b}$$", r"\[\frac{a}{b}\]"] {
            let s = spans(src);
            assert_eq!((s.len(), s[0]["display"].clone(), s[0]["text"].clone()), (1, json!(true), json!(src)));
        }
        assert_eq!(spans(r"\(x\)")[0]["display"], false);
    }

    #[test]
    fn formulas_do_not_borrow_markdown() {
        // `_` inside a formula is a subscript, not italic.
        let s = spans("$x_1 + y_2$");
        assert_eq!(s.len(), 1);
        assert!(s[0].get("italic").is_none() && s[0].get("math").is_some());
        // Bold can wrap text around a formula.
        let s = spans("**сумма $a+b$ равна**");
        assert_eq!((s.len(), s[0]["bold"].clone(), s[1].get("math").is_some()), (3, json!(true), true));
        assert_eq!(s[2], json!({"text": " равна", "bold": true}));
        // Code is code.
        assert_eq!(spans("`$x$`"), vec![json!({"text": "$x$", "code": true})]);
    }

    #[test]
    fn money_and_broken_latex_stay_text() {
        for src in ["стоит $5, а потом $10", "US$5 and US$10", "$ x $", r"$\frac{1}{$", "$$bad{$$", "100$"] {
            assert_eq!(spans(src), vec![json!({"text": src})], "{src}");
        }
        // A dollar sign in an address stays part of the link.
        assert_eq!(spans("https://a.b/$x$"), vec![json!({"text": "https://a.b/$x$", "link": "https://a.b/$x$"})]);
        assert_eq!(spans(r"\$5 и \$10"), vec![json!({"text": "$5 и $10"})]);
        // A real formula next to a price.
        let s = spans("за $5 берут $x$");
        assert_eq!(s.len(), 2);
        assert_eq!(s[1]["text"], "$x$");
    }

    #[test]
    fn finds_links() {
        assert_eq!(spans("см. https://neochat.app/docs."), vec![
            json!({"text": "см. "}),
            json!({"text": "https://neochat.app/docs", "link": "https://neochat.app/docs"}),
            json!({"text": "."}),
        ]);
    }
}
