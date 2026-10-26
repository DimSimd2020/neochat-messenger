//! LaTeX formulas in messages: a parser for the subset chats use and a typesetter that
//! turns it into vector shapes (glyph outlines and rules), so every client draws the same
//! formula with its own canvas and the text color of the bubble.
//!
//! The font is XITS Math (SIL Open Font License, `fonts/OFL-XITS.txt`).

mod font;
mod layout;
mod parse;
mod tables;

use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// Largest display list of one formula, as JSON.
pub const MAX_SHAPE_BYTES: usize = 60_000;

/// The display list of a formula, or None when it is not valid LaTeX of the supported subset.
/// `display` sets a formula on its own line (large operators, limits above and below).
///
/// Shape: `{w, h, d, glyphs: {id: path}, g: [[id, x, y, scale]], r: [[x, y, w, h]]}` in em of the
/// surrounding text; `h` is the height above the baseline, `d` the depth below it, y grows downward.
/// A path is a flat list of commands: 0 M x y, 1 L x y, 2 Q x1 y1 x y, 3 C x1 y1 x2 y2 x y, 4 Z.
pub fn render(tex: &str, display: bool) -> Option<Value> {
    static CACHE: OnceLock<Mutex<HashMap<(String, bool), Option<Value>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(Default::default);
    let key = (tex.to_string(), display);
    if let Some(hit) = cache.lock().unwrap().get(&key) {
        return hit.clone();
    }
    // A formula that draws as more than this is not a formula a chat needs.
    let result = parse::parse(tex)
        .ok()
        .map(|tree| layout::emit(&layout::typeset(&tree, display)))
        .filter(|shape| shape.to_string().len() <= MAX_SHAPE_BYTES);
    let mut map = cache.lock().unwrap();
    if map.len() > 512 {
        map.clear();
    }
    map.insert(key, result.clone());
    result
}

#[cfg(test)]
mod tests {
    use super::render;

    fn size(tex: &str, display: bool) -> (f64, f64, f64) {
        let v = render(tex, display).unwrap_or_else(|| panic!("{tex} did not render"));
        (v["w"].as_f64().unwrap(), v["h"].as_f64().unwrap(), v["d"].as_f64().unwrap())
    }

    #[test]
    fn sizes_follow_the_formula() {
        let (w1, ..) = size("x", false);
        let (w2, ..) = size("x+y", false);
        assert!(w1 > 0.3 && w1 < 0.8, "{w1}");
        assert!(w2 > w1 * 2.0);
        // A superscript raises the box, a subscript lowers it.
        let (_, h, d) = size("x", false);
        assert!(size("x^2", false).1 > h);
        assert!(size("x_2", false).2 > d);
        // A fraction in display style is taller than in text style.
        assert!(size(r"\frac{a}{b}", true).1 + size(r"\frac{a}{b}", true).2 > size(r"\frac{a}{b}", false).1 + size(r"\frac{a}{b}", false).2);
        // A large operator grows in display style.
        assert!(size(r"\sum", true).1 > size(r"\sum", false).1);
        // Delimiters stretch with their content.
        assert!(size(r"\left(\frac{a}{b}\right)", false).1 > size("(a)", false).1);
        assert!(size(r"\begin{pmatrix}a&b\\c&d\end{pmatrix}", false).0 > size("a b", false).0);
    }

    #[test]
    fn invalid_input_is_none() {
        assert!(render(r"\frac{1}{", false).is_none());
        assert!(render(r"\nope", false).is_none());
        assert!(render("a $ b", false).is_none());
    }

    #[test]
    fn survives_noise() {
        // Pieces of LaTeX glued together at random: whatever comes out, it must not panic or hang.
        let pieces = [
            r"\frac", r"\sqrt", "{", "}", "^", "_", "&", r"\\", r"\left(", r"\right)", r"\left.", r"\begin{pmatrix}", r"\end{pmatrix}",
            r"\begin{cases}", r"\end{cases}", "x", "1", r"\sum", r"\int", " ", r"\over", "'", r"\text{", r"\overbrace", "[", "]", r"\middle|",
            r"\xrightarrow", r"\hat", r"\big(", "$", "é", r"\lim", r"\limits", r"\binom", r"\not", r"\mathbb", r"\begin{aligned}", r"\end{aligned}",
        ];
        let mut seed = 0x2545F4914F6CDD1Du64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed as usize
        };
        for i in 0..4000 {
            let len = next() % 24;
            let tex: String = (0..len).map(|_| pieces[next() % pieces.len()]).collect();
            let _ = render(&tex, i % 2 == 0);
        }
    }

    #[test]
    fn output_is_json_ready() {
        let v = render(r"e^{i\pi}+1=0", false).unwrap();
        assert!(v["g"].as_array().unwrap().len() >= 6);
        assert!(!v["glyphs"].as_object().unwrap().is_empty());
        let text = v.to_string();
        assert!(!text.contains("NaN") && !text.contains("null"));
    }
}
