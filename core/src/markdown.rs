//! Message formatting, parsed once here so every client renders the same thing.
//!
//! Supported (Telegram-like): **bold**, *italic* or _italic_, ~~strike~~, `code`,
//! ```code block```, and bare http(s) links. A marker only counts when it has a
//! matching closing marker in the same paragraph and touches text on the inner side,
//! so `2 * 3 * 4` and snake_case stay as typed.

use serde_json::{Value, json};

#[derive(Clone, Copy, Default, PartialEq)]
struct Style {
    bold: bool,
    italic: bool,
    strike: bool,
}

struct Out {
    spans: Vec<Value>,
}

impl Out {
    fn push(&mut self, text: &str, st: Style, extra: Option<(&str, Value)>) {
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
    let mut out = Out { spans: Vec::new() };
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
                styled(&rest[..start], out);
                out.push(&after[..end], Style::default(), Some(("code", json!(true))));
                rest = &after[end + 1..];
            }
            _ => {
                styled(&rest[..start + 1], out);
                rest = after;
            }
        }
    }
    styled(rest, out);
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
        out.push(&rest[..start], st, None);
        let url = &tail[..end];
        out.push(url, st, Some(("link", json!(url))));
        rest = &tail[end..];
    }
    out.push(rest, st, None);
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
    fn finds_links() {
        assert_eq!(spans("см. https://neochat.app/docs."), vec![
            json!({"text": "см. "}),
            json!({"text": "https://neochat.app/docs", "link": "https://neochat.app/docs"}),
            json!({"text": "."}),
        ]);
    }
}
