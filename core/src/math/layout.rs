//! Typesetting after the TeXbook (appendix G), with the constants of the font's MATH table.
//! Boxes are measured in em of the base font size; `y` grows upward until the very end.

use super::font::{Part, font};
use super::parse::{Class, Node, Style, Table};
use serde_json::{Value, json};
use std::collections::BTreeMap;

#[derive(Clone, Copy)]
pub struct Cx {
    pub style: Style,
    pub cramped: bool,
}

impl Cx {
    fn s(self) -> f32 {
        match self.style {
            Style::Display | Style::Text => 1.0,
            Style::Script => font().k.script_scale,
            Style::ScriptScript => font().k.script_script_scale,
        }
    }
    fn script(self) -> bool {
        matches!(self.style, Style::Script | Style::ScriptScript)
    }
    fn sup(self) -> Cx {
        Cx { style: if self.script() { Style::ScriptScript } else { Style::Script }, cramped: self.cramped }
    }
    fn sub(self) -> Cx {
        Cx { cramped: true, ..self.sup() }
    }
    fn num(self) -> Cx {
        let style = match self.style {
            Style::Display => Style::Text,
            Style::Text => Style::Script,
            _ => Style::ScriptScript,
        };
        Cx { style, cramped: self.cramped }
    }
    fn den(self) -> Cx {
        Cx { cramped: true, ..self.num() }
    }
    fn cramp(self) -> Cx {
        Cx { cramped: true, ..self }
    }
}

#[derive(Clone)]
enum Item {
    Glyph { gid: u16, x: f32, y: f32, s: f32 },
    Rule { x: f32, y: f32, w: f32, h: f32 },
}

#[derive(Clone, Default)]
pub struct B {
    w: f32,
    h: f32,
    d: f32,
    items: Vec<Item>,
    /// Italic correction.
    ic: f32,
    /// A lone character: scripts hang from its own edges, not from a box.
    single: bool,
    /// Where an accent over this box is centered, when the font says so.
    accent: Option<f32>,
}

impl B {
    fn space(w: f32) -> B {
        B { w, ..B::default() }
    }

    fn rule(w: f32, h: f32, y: f32) -> B {
        B { w, h: y + h, d: -y, items: vec![Item::Rule { x: 0.0, y, w, h }], ..B::default() }
    }

    /// Puts `other` at (dx, dy) of this box; the caller keeps the metrics.
    fn put(&mut self, other: &B, dx: f32, dy: f32) {
        for it in &other.items {
            self.items.push(match *it {
                Item::Glyph { gid, x, y, s } => Item::Glyph { gid, x: x + dx, y: y + dy, s },
                Item::Rule { x, y, w, h } => Item::Rule { x: x + dx, y: y + dy, w, h },
            });
        }
    }

    fn raised(mut self, dy: f32) -> B {
        for it in &mut self.items {
            match it {
                Item::Glyph { y, .. } | Item::Rule { y, .. } => *y += dy,
            }
        }
        self.h += dy;
        self.d -= dy;
        self
    }

    fn grown(mut self, left: f32, right: f32) -> B {
        for it in &mut self.items {
            match it {
                Item::Glyph { x, .. } | Item::Rule { x, .. } => *x += left,
            }
        }
        self.w += left + right;
        self
    }
}

fn hbox(parts: Vec<B>) -> B {
    let mut out = B::default();
    let mut x = 0.0;
    for p in &parts {
        out.put(p, x, 0.0);
        x += p.w;
        out.h = out.h.max(p.h);
        out.d = out.d.max(p.d);
    }
    out.w = x;
    out
}

fn gid_box(gid: u16, s: f32) -> B {
    let f = font();
    let g = f.glyph(gid);
    B {
        w: f.advance(gid) * s,
        h: g.bbox[3].max(0.0) * s,
        d: (-g.bbox[1]).max(0.0) * s,
        items: vec![Item::Glyph { gid, x: 0.0, y: 0.0, s }],
        ic: f.italic_correction(gid) * s,
        single: true,
        accent: f.accent_attachment(gid).map(|a| a * s),
    }
}

fn char_box(ch: char, s: f32) -> B {
    match font().gid(ch) {
        Some(g) => gid_box(g, s),
        None => B::space(0.0),
    }
}

/// A vertical glyph of at least `target` em, built from variants or pieces, with its ink spanning y in [0, length].
fn vertical_run(ch: char, target: f32, s: f32) -> (B, f32) {
    let f = font();
    let Some(gid) = f.gid(ch) else { return (B::default(), 0.0) };
    let (parts, len) = f.stretch(gid, target / s, true);
    let mut b = B::default();
    for Part { gid, at } in parts {
        b.w = b.w.max(f.advance(gid) * s);
        b.items.push(Item::Glyph { gid, x: 0.0, y: at * s, s });
    }
    b.h = len * s;
    (b, len * s)
}

/// A delimiter stretched to `target` em and centered on the math axis.
fn delimiter(ch: char, target: f32, cx: Cx) -> B {
    let s = cx.s();
    let (run, len) = vertical_run(ch, target, s);
    run.raised(font().k.axis * s - len / 2.0)
}

fn glue(l: Class, r: Class, script: bool) -> f32 {
    use Class::*;
    // 1 thin, 2 medium, 3 thick; negative marks a space dropped in script styles.
    let t: i8 = match (l, r) {
        (Ord, Op) | (Op, Ord) | (Op, Op) | (Close, Op) | (Inner, Op) => 1,
        (Ord, Bin) | (Close, Bin) | (Inner, Bin) | (Bin, Ord) | (Bin, Op) | (Bin, Open) | (Bin, Inner) => -2,
        (Ord, Rel) | (Op, Rel) | (Close, Rel) | (Inner, Rel) | (Rel, Ord) | (Rel, Op) | (Rel, Open) | (Rel, Inner) => -3,
        (Ord, Inner) | (Op, Inner) | (Close, Inner) | (Inner, Inner) | (Inner, Ord) | (Inner, Open) | (Inner, Punct) => -1,
        (Punct, _) => -1,
        _ => 0,
    };
    if t < 0 && script {
        return 0.0;
    }
    match t.abs() {
        1 => 3.0 / 18.0,
        2 => 4.0 / 18.0,
        3 => 5.0 / 18.0,
        _ => 0.0,
    }
}

fn row(items: &[Node], mut cx: Cx) -> B {
    let mut atoms: Vec<(B, Class, f32)> = Vec::new();
    let mut pending = 0.0;
    for n in items {
        match n {
            Node::StyleSwitch(st) => cx.style = *st,
            Node::Space(w) => pending += w * cx.s(),
            _ => {
                let class = n.class();
                atoms.push((layout(n, cx), class, pending));
                pending = 0.0;
            }
        }
    }
    // A binary operator with nothing to operate on is an ordinary symbol.
    for i in 0..atoms.len() {
        if atoms[i].1 != Class::Bin {
            continue;
        }
        let before = if i == 0 { None } else { Some(atoms[i - 1].1) };
        let after = atoms.get(i + 1).map(|a| a.1);
        use Class::*;
        let lonely_left = matches!(before, None | Some(Bin | Op | Rel | Open | Punct));
        let lonely_right = matches!(after, None | Some(Rel | Close | Punct));
        if lonely_left || lonely_right {
            atoms[i].1 = Ord;
        }
    }
    if atoms.len() == 1 && atoms[0].2 == 0.0 && pending == 0.0 {
        return atoms.pop().unwrap().0;
    }
    let mut parts = Vec::new();
    let mut last: Option<Class> = None;
    for (b, class, before) in atoms {
        let g = last.map_or(0.0, |l| glue(l, class, cx.script())) * cx.s();
        parts.push(B::space(g + before));
        parts.push(b);
        last = Some(class);
    }
    parts.push(B::space(pending));
    hbox(parts)
}

fn layout(n: &Node, cx: Cx) -> B {
    let f = font();
    let k = &f.k;
    let s = cx.s();
    match n {
        Node::Ch(c, _) => char_box(*c, s),
        Node::Group(items) => row(items, cx),
        Node::Text(t) => {
            let mut parts = Vec::new();
            for c in t.chars() {
                parts.push(if c == ' ' { B::space(0.25 * s) } else { char_box(c, s) });
            }
            let mut b = hbox(parts);
            b.single = false;
            b
        }
        Node::Space(w) => B::space(w * s),
        Node::StyleSwitch(_) => B::default(),
        Node::BigOp { ch, limits: _ } => big_op(*ch, cx),
        Node::NamedOp { name, .. } => {
            let mut b = hbox(name.chars().map(|c| char_box(c, s)).collect());
            b.single = false;
            b
        }
        Node::Frac { num, den, rule, delims, style } => {
            let cx = Cx { style: style.unwrap_or(cx.style), ..cx };
            fraction(num, den, *rule, *delims, cx)
        }
        Node::Sqrt { index, body } => sqrt(index.as_deref(), body, cx),
        Node::Script { base, sub, sup } => scripts(base, sub.as_deref(), sup.as_deref(), cx),
        Node::Left { open, body, close } => left_right(*open, body, *close, cx),
        Node::Middle(_) => B::default(),
        Node::Big { size, ch, .. } => delimiter(*ch, size * s, cx),
        Node::Accent { ch, body, wide } => accent(*ch, body, *wide, cx),
        Node::Bar { body, over } => {
            let b = layout(body, if *over { cx.cramp() } else { cx });
            let mut out = B { w: b.w, h: b.h, d: b.d, ..B::default() };
            out.put(&b, 0.0, 0.0);
            if *over {
                let rule = k.overbar_rule * s;
                let y = b.h + k.overbar_gap * s;
                out.put(&B::rule(b.w, rule, y), 0.0, 0.0);
                out.h = y + rule + k.overbar_extra * s;
            } else {
                let rule = k.underbar_rule * s;
                let y = -(b.d + k.underbar_gap * s + rule);
                out.put(&B::rule(b.w, rule, y), 0.0, 0.0);
                out.d = -y + k.underbar_extra * s;
            }
            out
        }
        Node::Stretch { ch, body, over } => {
            let b = layout(body, if *over { cx.cramp() } else { cx });
            let glyph = stretchy(*ch, b.w, cx);
            limits(b, glyph, *over, cx)
        }
        Node::Stack { base, over, under } => {
            let over_b = over.as_ref().map(|o| layout(o, cx.sup()));
            let under_b = under.as_ref().map(|u| layout(u, cx.sub()));
            // A bare arrow takes the width of its labels.
            let b = if let Node::Stretch { ch, body, .. } = &**base {
                if matches!(&**body, Node::Group(g) if g.is_empty()) {
                    let want = over_b.iter().chain(under_b.iter()).map(|x| x.w).fold(0.0, f32::max) + 1.2 * s;
                    arrow(*ch, want, cx)
                } else {
                    layout(base, cx)
                }
            } else {
                layout(base, cx)
            };
            place_limits(b, over_b, under_b, cx)
        }
        Node::Table(t) => table(t, cx),
    }
}

/// A horizontal stretchy glyph of the given width, its ink resting on the baseline.
fn stretchy(ch: char, width: f32, cx: Cx) -> B {
    let s = cx.s();
    let f = font();
    let Some(gid) = f.gid(ch) else { return B::default() };
    let (parts, len) = f.stretch(gid, width / s, false);
    let mut out = B::default();
    let (mut lo, mut hi) = (f32::MAX, f32::MIN);
    for Part { gid, at } in parts {
        let g = f.glyph(gid);
        lo = lo.min(g.bbox[1]);
        hi = hi.max(g.bbox[3]);
        out.items.push(Item::Glyph { gid, x: at * s, y: 0.0, s });
    }
    if lo > hi {
        return B::default();
    }
    out.w = len * s;
    // Ink bottom on the baseline.
    let mut out = out.raised(-lo * s);
    out.h = (hi - lo) * s;
    out.d = 0.0;
    out
}

/// A stretchy arrow centered on the math axis.
fn arrow(ch: char, width: f32, cx: Cx) -> B {
    let a = stretchy(ch, width, cx);
    let center = a.h / 2.0;
    let mut b = a.raised(font().k.axis * cx.s() - center);
    b.d = b.d.max(0.0);
    b
}

/// Puts a stretchy glyph over or under `body`.
fn limits(body: B, glyph: B, over: bool, cx: Cx) -> B {
    let s = cx.s();
    let k = &font().k;
    let mut g = glyph;
    // Center the glyph on the body.
    let shift = (body.w - g.w) / 2.0;
    g = g.grown(shift.max(0.0), 0.0);
    let w = body.w.max(g.w);
    let dx = (w - body.w) / 2.0;
    let mut out = B { w, h: body.h, d: body.d, ..B::default() };
    out.put(&body, dx, 0.0);
    if over {
        let y = body.h + k.upper_gap_min * s;
        out.put(&g, 0.0, y);
        out.h = y + g.h;
    } else {
        let y = -(body.d + k.lower_gap_min * s + g.h);
        out.put(&g, 0.0, y);
        out.d = -y;
    }
    out
}

fn big_op(ch: char, cx: Cx) -> B {
    let f = font();
    let s = cx.s();
    let Some(gid) = f.gid(ch) else { return B::default() };
    let gid = if cx.style == Style::Display { f.variant_at_least(gid, f.k.display_op_min, true) } else { gid };
    let mut b = gid_box(gid, s);
    // Large operators sit centered on the axis.
    let shift = (b.h - b.d) / 2.0 - f.k.axis * s;
    b = b.raised(-shift);
    b.single = false;
    b
}

/// Over/under placement (limits) around a base.
fn place_limits(base: B, over: Option<B>, under: Option<B>, cx: Cx) -> B {
    let s = cx.s();
    let k = &font().k;
    let w = [base.w, over.as_ref().map_or(0.0, |b| b.w), under.as_ref().map_or(0.0, |b| b.w)].into_iter().fold(0.0, f32::max);
    let mut out = B { w, h: base.h, d: base.d, ..B::default() };
    // The base sits shifted by half its italic correction, the labels by the other half.
    let at = |b: &B, skew: f32| (w - b.w) / 2.0 + skew;
    out.put(&base, at(&base, 0.0), 0.0);
    if let Some(o) = over {
        let y = base.h + (k.upper_gap_min * s + o.d).max(k.upper_rise_min * s);
        out.put(&o, at(&o, base.ic / 2.0), y);
        out.h = out.h.max(y + o.h);
    }
    if let Some(u) = under {
        let y = -(base.d + (k.lower_gap_min * s + u.h).max(k.lower_drop_min * s));
        out.put(&u, at(&u, -base.ic / 2.0), y);
        out.d = out.d.max(-y + u.d);
    }
    out
}

fn scripts(base: &Node, sub: Option<&Node>, sup: Option<&Node>, cx: Cx) -> B {
    let f = font();
    let k = &f.k;
    let s = cx.s();
    let wants_limits = match base {
        Node::BigOp { limits, .. } | Node::NamedOp { limits, .. } => *limits && cx.style == Style::Display,
        Node::Stretch { .. } => true,
        _ => false,
    };
    let b = layout(base, cx);
    let sup_b = sup.map(|n| layout(n, cx.sup()));
    let sub_b = sub.map(|n| layout(n, cx.sub()));
    if wants_limits {
        return place_limits(b, sup_b, sub_b, cx);
    }
    let (ssup, ssub) = (cx.sup().s(), cx.sub().s());
    let (mut u, mut v) = if b.single { (0.0, 0.0) } else { (b.h - k.sup_drop_max * ssup, b.d + k.sub_drop_min * ssub) };
    let up = if cx.cramped { k.sup_shift_up_cramped } else { k.sup_shift_up } * s;
    let mut out = B { w: b.w, h: b.h, d: b.d, ..B::default() };
    out.put(&b, 0.0, 0.0);
    let after = k.space_after_script * s;
    // Italic correction pushes the superscript past the slanted edge; big operators lean the other way.
    let is_op = matches!(base, Node::BigOp { .. });
    let sup_x = b.w + if is_op { 0.0 } else { b.ic };
    let sub_x = b.w - if is_op { b.ic } else { 0.0 };
    match (sup_b, sub_b) {
        (Some(p), None) => {
            u = u.max(up).max(p.d + k.sup_bottom_min * s);
            out.put(&p, sup_x, u);
            out.w = sup_x + p.w + after;
            out.h = out.h.max(u + p.h);
        }
        (None, Some(q)) => {
            v = v.max(k.sub_shift_down * s).max(q.h - k.sub_top_max * s);
            out.put(&q, sub_x, -v);
            out.w = sub_x + q.w + after;
            out.d = out.d.max(v + q.d);
        }
        (Some(p), Some(q)) => {
            v = v.max(k.sub_shift_down * s);
            u = u.max(up).max(p.d + k.sup_bottom_min * s);
            let gap = (u - p.d) - (q.h - v);
            if gap < k.sub_sup_gap_min * s {
                v += k.sub_sup_gap_min * s - gap;
                let psi = k.sup_bottom_max_with_sub * s - (u - p.d);
                if psi > 0.0 {
                    u += psi;
                    v -= psi;
                }
            }
            out.put(&p, sup_x, u);
            out.put(&q, sub_x, -v);
            out.w = (sup_x + p.w).max(sub_x + q.w) + after;
            out.h = out.h.max(u + p.h);
            out.d = out.d.max(v + q.d);
        }
        (None, None) => {}
    }
    out
}

fn fraction(num: &Node, den: &Node, rule: bool, delims: Option<(char, char)>, cx: Cx) -> B {
    let k = &font().k;
    let s = cx.s();
    let display = cx.style == Style::Display;
    let n = layout(num, cx.num());
    let d = layout(den, cx.den());
    let axis = k.axis * s;
    let w = n.w.max(d.w);
    let (mut up, mut down);
    if rule {
        let t = k.frac_rule * s;
        up = if display { k.frac_num_up_display } else { k.frac_num_up } * s;
        down = if display { k.frac_den_down_display } else { k.frac_den_down } * s;
        let num_gap = if display { k.frac_num_gap_display } else { k.frac_num_gap } * s;
        let den_gap = if display { k.frac_den_gap_display } else { k.frac_den_gap } * s;
        let gap_above = (up - n.d) - (axis + t / 2.0);
        if gap_above < num_gap {
            up += num_gap - gap_above;
        }
        let gap_below = (axis - t / 2.0) - (d.h - down);
        if gap_below < den_gap {
            down += den_gap - gap_below;
        }
    } else {
        up = if display { k.stack_top_up_display } else { k.stack_top_up } * s;
        down = if display { k.stack_bottom_down_display } else { k.stack_bottom_down } * s;
        let min = if display { k.stack_gap_min_display } else { k.stack_gap_min } * s;
        let gap = (up - n.d) - (d.h - down);
        if gap < min {
            up += (min - gap) / 2.0;
            down += (min - gap) / 2.0;
        }
    }
    let pad = 0.12 * s;
    let mut out = B { w: w + 2.0 * pad, h: up + n.h, d: down + d.d, ..B::default() };
    out.put(&n, pad + (w - n.w) / 2.0, up);
    out.put(&d, pad + (w - d.w) / 2.0, -down);
    if rule {
        out.put(&B::rule(w, k.frac_rule * s, axis - k.frac_rule * s / 2.0), pad, 0.0);
    }
    match delims {
        Some((l, r)) => {
            let size = if display { 2.39 } else { 1.0 } * s;
            hbox(vec![delimiter(l, size, cx), out, delimiter(r, size, cx)])
        }
        None => out,
    }
}

fn sqrt(index: Option<&Node>, body: &Node, cx: Cx) -> B {
    let f = font();
    let k = &f.k;
    let s = cx.s();
    let b = layout(body, cx.cramp());
    let rule = k.radical_rule * s;
    let gap = if cx.style == Style::Display { k.radical_gap_display } else { k.radical_gap } * s;
    let needed = b.h + b.d + gap + rule;
    let (run, len) = vertical_run('\u{221a}', needed, s);
    // The radical's top stroke meets the bar; the extra height is split above and below.
    let extra = (len - needed).max(0.0);
    let top = b.h + gap + rule + extra / 2.0;
    let run = run.raised(top - len);
    let rw = run.w;
    let index_b = index.map(|i| layout(i, Cx { style: Style::ScriptScript, cramped: false }));
    let mut out = B::default();
    let mut x = 0.0;
    if let Some(ib) = &index_b {
        // The bottom of the index stands a fixed share of the radical's height above the radical's bottom.
        let y = top - len + k.radical_raise * len + ib.d;
        let at = k.radical_kern_before * s;
        out.put(ib, at, y);
        out.h = out.h.max(y + ib.h);
        x = (at + ib.w + k.radical_kern_after * s).max(0.0);
    }
    out.put(&run, x, 0.0);
    out.put(&b, x + rw, 0.0);
    out.put(&B::rule(b.w, rule, top - rule), x + rw, 0.0);
    out.w = x + rw + b.w;
    out.h = out.h.max(top + k.radical_extra * s);
    out.d = out.d.max(b.d).max(len - top);
    out
}

fn left_right(open: Option<char>, body: &[Node], close: Option<char>, cx: Cx) -> B {
    let k = &font().k;
    let s = cx.s();
    // Split at \middle so every piece is measured and the delimiters match the tallest one.
    let mut pieces: Vec<B> = Vec::new();
    let mut middles: Vec<Option<char>> = Vec::new();
    let mut start = 0;
    for (i, n) in body.iter().enumerate() {
        if let Node::Middle(d) = n {
            pieces.push(row(&body[start..i], cx));
            middles.push(*d);
            start = i + 1;
        }
    }
    pieces.push(row(&body[start..], cx));
    let h = pieces.iter().map(|p| p.h).fold(0.0, f32::max);
    let d = pieces.iter().map(|p| p.d).fold(0.0, f32::max);
    let axis = k.axis * s;
    let delta = (h - axis).max(d + axis);
    let target = (delta * 1.802).max(2.0 * delta - 0.5 * s);
    let mut parts = Vec::new();
    if let Some(c) = open {
        parts.push(delimiter(c, target, cx));
    }
    let mut it = pieces.into_iter();
    parts.push(it.next().unwrap_or_default());
    for (p, m) in it.zip(middles) {
        if let Some(c) = m {
            parts.push(B::space(0.17 * s));
            parts.push(delimiter(c, target, cx));
            parts.push(B::space(0.17 * s));
        }
        parts.push(p);
    }
    if let Some(c) = close {
        parts.push(delimiter(c, target, cx));
    }
    hbox(parts)
}

fn accent(ch: char, body: &Node, wide: bool, cx: Cx) -> B {
    let f = font();
    let k = &f.k;
    let s = cx.s();
    // An accent over i or j replaces the dot.
    let dotless = match body {
        Node::Ch('\u{1D456}', c) => Some(Node::Ch('\u{1D6A4}', *c)),
        Node::Ch('\u{1D457}', c) => Some(Node::Ch('\u{1D6A5}', *c)),
        _ => None,
    };
    let b = layout(dotless.as_ref().unwrap_or(body), cx.cramp());
    let Some(gid) = f.gid(ch) else { return b };
    let gid = if wide { f.variant_at_least(gid, b.w / s, false) } else { gid };
    let g = f.glyph(gid);
    let mut glyph = gid_box(gid, s);
    glyph.items.clear();
    let attach_glyph = f.accent_attachment(gid).unwrap_or((g.bbox[0] + g.bbox[2]) / 2.0) * s;
    let attach_body = if wide { b.w / 2.0 } else { b.accent.unwrap_or(b.w / 2.0 + b.ic / 2.0) };
    let dx = attach_body - attach_glyph;
    let dy = (b.h - k.accent_base * s).max(0.0);
    let mut out = B { w: b.w, h: b.h.max(dy + g.bbox[3] * s), d: b.d, ..B::default() };
    out.put(&b, 0.0, 0.0);
    out.items.push(Item::Glyph { gid, x: dx, y: dy, s });
    // An accent can hang past the base on the left; only the width of the base counts.
    out
}

fn table(t: &Table, cx: Cx) -> B {
    let k = &font().k;
    let s = cx.s();
    let cell_cx = if cx.script() { cx } else { Cx { style: if t.aligned { Style::Display } else { Style::Text }, cramped: false } };
    let cs = cell_cx.s();
    let cols = t.cols.len();
    let cells: Vec<Vec<B>> = t.rows.iter().map(|r| r.iter().map(|c| layout(c, cell_cx)).collect()).collect();
    let mut widths = vec![0.0f32; cols];
    for r in &cells {
        for (i, c) in r.iter().enumerate() {
            widths[i] = widths[i].max(c.w);
        }
    }
    // Column gaps: one em, but `r|l` pairs of aligned blocks touch and the pairs stand two em apart.
    let gap_after = |i: usize| -> f32 {
        if i + 1 >= cols {
            return 0.0;
        }
        if t.aligned && t.cols.len() > 1 { if i % 2 == 0 { 0.0 } else { 2.0 * cs } } else { 1.0 * cs }
    };
    let mut xs = Vec::new();
    let mut x = 0.0;
    for i in 0..cols {
        xs.push(x);
        x += widths[i] + gap_after(i);
    }
    let total_w = x;
    let strut = (0.84 * cs, 0.36 * cs);
    let mut ys = Vec::new();
    let mut y = 0.0f32;
    let mut prev_d = 0.0;
    let jot = if t.aligned { 0.3 * cs } else { 0.0 };
    for (i, r) in cells.iter().enumerate() {
        let h = r.iter().map(|c| c.h).fold(strut.0, f32::max);
        let d = r.iter().map(|c| c.d).fold(strut.1, f32::max);
        if i > 0 {
            y -= prev_d + h + jot;
        }
        ys.push(y);
        prev_d = d;
    }
    let top = cells.first().map_or(0.0, |r| r.iter().map(|c| c.h).fold(strut.0, f32::max));
    let bottom = -ys.last().copied().unwrap_or(0.0) + prev_d;
    let height = top + bottom;
    let mut body = B { w: total_w, h: top, d: bottom, ..B::default() };
    for (ri, r) in cells.iter().enumerate() {
        for (ci, c) in r.iter().enumerate() {
            let dx = match t.cols[ci] {
                'l' => 0.0,
                'r' => widths[ci] - c.w,
                _ => (widths[ci] - c.w) / 2.0,
            };
            body.put(c, xs[ci] + dx, ys[ri]);
        }
    }
    // The block is centered on the axis.
    let body = body.raised(k.axis * s - (top - bottom) / 2.0);
    let target = height.max(1.0 * s);
    let mut parts = Vec::new();
    if let Some(c) = t.left {
        parts.push(delimiter(c, target, cx));
        parts.push(B::space(0.1 * s));
    }
    parts.push(body);
    if let Some(c) = t.right {
        parts.push(B::space(0.1 * s));
        parts.push(delimiter(c, target, cx));
    } else if t.left.is_some() {
        parts.push(B::space(0.1 * s));
    }
    hbox(parts)
}

/// Lays out a formula; `display` is the style of a formula standing on its own line.
pub fn typeset(root: &Node, display: bool) -> B {
    let cx = Cx { style: if display { Style::Display } else { Style::Text }, cramped: false };
    layout(root, cx)
}

fn r3(x: f32) -> f64 {
    (x as f64 * 1000.0).round() / 1000.0
}

/// The display list: glyph outlines (y down, em units) used by the formula, glyph placements and rules.
/// Each glyph is [id, x, y, scale]; each rule [x, y, w, h]; y is measured from the baseline, downward.
pub fn emit(b: &B) -> Value {
    let f = font();
    let mut glyphs: BTreeMap<String, Value> = BTreeMap::new();
    let mut placed = Vec::new();
    let mut rules = Vec::new();
    for it in &b.items {
        match *it {
            Item::Glyph { gid, x, y, s } => {
                glyphs.entry(gid.to_string()).or_insert_with(|| {
                    let g = f.glyph(gid);
                    let mut out = Vec::with_capacity(g.path.len());
                    let mut i = 0;
                    while i < g.path.len() {
                        let cmd = g.path[i] as usize;
                        out.push(json!(cmd));
                        let n = [2, 2, 4, 6, 0][cmd.min(4)];
                        for j in 0..n {
                            let v = g.path[i + 1 + j];
                            // Odd entries are y, which flips to point down.
                            out.push(json!(r3(if j % 2 == 1 { -v } else { v })));
                        }
                        i += 1 + n;
                    }
                    Value::Array(out)
                });
                placed.push(json!([gid, r3(x), r3(-y), r3(s)]));
            }
            Item::Rule { x, y, w, h } => rules.push(json!([r3(x), r3(-(y + h)), r3(w), r3(h)])),
        }
    }
    json!({"w": r3(b.w), "h": r3(b.h.max(0.0)), "d": r3(b.d.max(0.0)), "glyphs": glyphs, "g": placed, "r": rules})
}
