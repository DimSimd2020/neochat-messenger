//! The bundled math font (XITS Math, SIL OFL): glyph metrics, outlines and the OpenType MATH table.
//! Every length is in em of the font, so layout never meets font units.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use ttf_parser::{Face, GlyphId, OutlineBuilder, math};

static DATA: &[u8] = include_bytes!("../../fonts/XITSMath-Regular.otf");

/// Constants of the MATH table that layout uses, in em.
#[derive(Default)]
pub struct K {
    pub script_scale: f32,
    pub script_script_scale: f32,
    pub display_op_min: f32,
    pub axis: f32,
    pub accent_base: f32,
    pub sub_shift_down: f32,
    pub sub_top_max: f32,
    pub sub_drop_min: f32,
    pub sup_shift_up: f32,
    pub sup_shift_up_cramped: f32,
    pub sup_bottom_min: f32,
    pub sup_drop_max: f32,
    pub sub_sup_gap_min: f32,
    pub sup_bottom_max_with_sub: f32,
    pub space_after_script: f32,
    pub upper_gap_min: f32,
    pub upper_rise_min: f32,
    pub lower_gap_min: f32,
    pub lower_drop_min: f32,
    pub stack_top_up: f32,
    pub stack_top_up_display: f32,
    pub stack_bottom_down: f32,
    pub stack_bottom_down_display: f32,
    pub stack_gap_min: f32,
    pub stack_gap_min_display: f32,
    pub frac_num_up: f32,
    pub frac_num_up_display: f32,
    pub frac_den_down: f32,
    pub frac_den_down_display: f32,
    pub frac_num_gap: f32,
    pub frac_num_gap_display: f32,
    pub frac_rule: f32,
    pub frac_den_gap: f32,
    pub frac_den_gap_display: f32,
    pub overbar_gap: f32,
    pub overbar_rule: f32,
    pub overbar_extra: f32,
    pub underbar_gap: f32,
    pub underbar_rule: f32,
    pub underbar_extra: f32,
    pub radical_gap: f32,
    pub radical_gap_display: f32,
    pub radical_rule: f32,
    pub radical_extra: f32,
    pub radical_kern_before: f32,
    pub radical_kern_after: f32,
    pub radical_raise: f32,
    pub min_overlap: f32,
}

/// One glyph: ink box and outline, y up.
pub struct Glyph {
    pub bbox: [f32; 4],
    /// Path commands: 0 M x y, 1 L x y, 2 Q x1 y1 x y, 3 C x1 y1 x2 y2 x y, 4 Z; y is up here.
    pub path: Vec<f32>,
}

/// One piece of a stretched glyph: where to draw which glyph.
pub struct Part {
    pub gid: u16,
    /// Distance from the start of the stretched run to the glyph's ink start.
    pub at: f32,
}

pub struct Font {
    face: Face<'static>,
    upem: f32,
    pub k: K,
    glyphs: Mutex<HashMap<u16, Arc<Glyph>>>,
}

struct Pen {
    path: Vec<f32>,
    min: [f32; 2],
    max: [f32; 2],
}

impl Pen {
    fn point(&mut self, x: f32, y: f32) {
        self.min = [self.min[0].min(x), self.min[1].min(y)];
        self.max = [self.max[0].max(x), self.max[1].max(y)];
    }
}

impl OutlineBuilder for Pen {
    fn move_to(&mut self, x: f32, y: f32) {
        self.point(x, y);
        self.path.extend([0.0, x, y]);
    }
    fn line_to(&mut self, x: f32, y: f32) {
        self.point(x, y);
        self.path.extend([1.0, x, y]);
    }
    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        self.point(x1, y1);
        self.point(x, y);
        self.path.extend([2.0, x1, y1, x, y]);
    }
    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        self.point(x1, y1);
        self.point(x2, y2);
        self.point(x, y);
        self.path.extend([3.0, x1, y1, x2, y2, x, y]);
    }
    fn close(&mut self) {
        self.path.push(4.0);
    }
}

pub fn font() -> &'static Font {
    static FONT: OnceLock<Font> = OnceLock::new();
    FONT.get_or_init(|| Font::load().expect("bundled math font is valid"))
}

impl Font {
    fn load() -> Option<Font> {
        let face = Face::parse(DATA, 0).ok()?;
        let upem = face.units_per_em() as f32;
        let c = face.tables().math?.constants?;
        let v = |m: math::MathValue| m.value as f32 / upem;
        let k = K {
            script_scale: c.script_percent_scale_down() as f32 / 100.0,
            script_script_scale: c.script_script_percent_scale_down() as f32 / 100.0,
            display_op_min: c.display_operator_min_height() as f32 / upem,
            axis: v(c.axis_height()),
            accent_base: v(c.accent_base_height()),
            sub_shift_down: v(c.subscript_shift_down()),
            sub_top_max: v(c.subscript_top_max()),
            sub_drop_min: v(c.subscript_baseline_drop_min()),
            sup_shift_up: v(c.superscript_shift_up()),
            sup_shift_up_cramped: v(c.superscript_shift_up_cramped()),
            sup_bottom_min: v(c.superscript_bottom_min()),
            sup_drop_max: v(c.superscript_baseline_drop_max()),
            sub_sup_gap_min: v(c.sub_superscript_gap_min()),
            sup_bottom_max_with_sub: v(c.superscript_bottom_max_with_subscript()),
            space_after_script: v(c.space_after_script()),
            upper_gap_min: v(c.upper_limit_gap_min()),
            upper_rise_min: v(c.upper_limit_baseline_rise_min()),
            lower_gap_min: v(c.lower_limit_gap_min()),
            lower_drop_min: v(c.lower_limit_baseline_drop_min()),
            stack_top_up: v(c.stack_top_shift_up()),
            stack_top_up_display: v(c.stack_top_display_style_shift_up()),
            stack_bottom_down: v(c.stack_bottom_shift_down()),
            stack_bottom_down_display: v(c.stack_bottom_display_style_shift_down()),
            stack_gap_min: v(c.stack_gap_min()),
            stack_gap_min_display: v(c.stack_display_style_gap_min()),
            frac_num_up: v(c.fraction_numerator_shift_up()),
            frac_num_up_display: v(c.fraction_numerator_display_style_shift_up()),
            frac_den_down: v(c.fraction_denominator_shift_down()),
            frac_den_down_display: v(c.fraction_denominator_display_style_shift_down()),
            frac_num_gap: v(c.fraction_numerator_gap_min()),
            frac_num_gap_display: v(c.fraction_num_display_style_gap_min()),
            frac_rule: v(c.fraction_rule_thickness()),
            frac_den_gap: v(c.fraction_denominator_gap_min()),
            frac_den_gap_display: v(c.fraction_denom_display_style_gap_min()),
            overbar_gap: v(c.overbar_vertical_gap()),
            overbar_rule: v(c.overbar_rule_thickness()),
            overbar_extra: v(c.overbar_extra_ascender()),
            underbar_gap: v(c.underbar_vertical_gap()),
            underbar_rule: v(c.underbar_rule_thickness()),
            underbar_extra: v(c.underbar_extra_descender()),
            radical_gap: v(c.radical_vertical_gap()),
            radical_gap_display: v(c.radical_display_style_vertical_gap()),
            radical_rule: v(c.radical_rule_thickness()),
            radical_extra: v(c.radical_extra_ascender()),
            radical_kern_before: v(c.radical_kern_before_degree()),
            radical_kern_after: v(c.radical_kern_after_degree()),
            radical_raise: c.radical_degree_bottom_raise_percent() as f32 / 100.0,
            min_overlap: face.tables().math?.variants?.min_connector_overlap as f32 / upem,
        };
        Some(Font { face, upem, k, glyphs: Mutex::new(HashMap::new()) })
    }

    pub fn gid(&self, ch: char) -> Option<u16> {
        self.face.glyph_index(ch).map(|g| g.0)
    }

    pub fn advance(&self, gid: u16) -> f32 {
        self.face.glyph_hor_advance(GlyphId(gid)).unwrap_or(0) as f32 / self.upem
    }

    /// Outline and ink box of a glyph, cached.
    pub fn glyph(&self, gid: u16) -> Arc<Glyph> {
        if let Some(g) = self.glyphs.lock().unwrap().get(&gid) {
            return g.clone();
        }
        let mut pen = Pen { path: Vec::new(), min: [f32::MAX; 2], max: [f32::MIN; 2] };
        let found = self.face.outline_glyph(GlyphId(gid), &mut pen).is_some();
        let u = self.upem;
        let (bbox, path) = if found {
            ([pen.min[0] / u, pen.min[1] / u, pen.max[0] / u, pen.max[1] / u], scale_path(&pen.path, 1.0 / u))
        } else {
            ([0.0; 4], Vec::new())
        };
        let g = Arc::new(Glyph { bbox, path });
        self.glyphs.lock().unwrap().insert(gid, g.clone());
        g
    }

    pub fn italic_correction(&self, gid: u16) -> f32 {
        self.math()
            .and_then(|m| m.glyph_info?.italic_corrections?.get(GlyphId(gid)))
            .map_or(0.0, |v| v.value as f32 / self.upem)
    }

    /// x of the point where an accent over this glyph is centered, if the font says so.
    pub fn accent_attachment(&self, gid: u16) -> Option<f32> {
        let m = self.math()?;
        Some(m.glyph_info?.top_accent_attachments?.get(GlyphId(gid))?.value as f32 / self.upem)
    }

    fn math(&self) -> Option<math::Table<'static>> {
        self.face.tables().math
    }

    /// The glyph variants of `gid` in growing size, as (glyph, size along the stretch axis).
    fn variants(&self, gid: u16, vertical: bool) -> Vec<(u16, f32)> {
        let Some(v) = self.math().and_then(|m| m.variants) else { return Vec::new() };
        let set = if vertical { v.vertical_constructions } else { v.horizontal_constructions };
        let Some(c) = set.get(GlyphId(gid)) else { return Vec::new() };
        c.variants.into_iter().map(|v| (v.variant_glyph.0, v.advance_measurement as f32 / self.upem)).collect()
    }

    /// A glyph stretched to at least `target` em along an axis, as the pieces to draw and their total length.
    /// Pieces are positioned from the start of the run; a single piece is a plain (or variant) glyph.
    pub fn stretch(&self, gid: u16, target: f32, vertical: bool) -> (Vec<Part>, f32) {
        let own = self.glyph(gid);
        let ink = |g: &Glyph| if vertical { g.bbox[3] - g.bbox[1] } else { g.bbox[2] - g.bbox[0] };
        let start = |g: &Glyph| if vertical { g.bbox[1] } else { g.bbox[0] };
        let mut best = (gid, ink(&own));
        if best.1 < target {
            for (v, size) in self.variants(gid, vertical) {
                best = (v, size);
                if size >= target {
                    break;
                }
            }
        }
        if best.1 >= target {
            let g = self.glyph(best.0);
            return (vec![Part { gid: best.0, at: -start(&g) }], ink(&g));
        }
        // Still short: build the glyph from pieces.
        let assembly = self.math().and_then(|m| {
            let v = m.variants?;
            let set = if vertical { v.vertical_constructions } else { v.horizontal_constructions };
            set.get(GlyphId(gid))?.assembly
        });
        let Some(asm) = assembly else {
            let g = self.glyph(best.0);
            return (vec![Part { gid: best.0, at: -start(&g) }], ink(&g));
        };
        let parts: Vec<_> = asm.parts.into_iter().collect();
        let overlap = self.k.min_overlap;
        let len = |n: usize| -> f32 {
            let mut total = 0.0;
            let mut count = 0;
            for p in &parts {
                let times = if p.part_flags.extender() { n } else { 1 };
                for _ in 0..times {
                    total += p.full_advance as f32 / self.upem;
                    count += 1;
                }
            }
            total - overlap * (count.max(1) - 1) as f32
        };
        let mut n = 1;
        while len(n) < target && n < 64 {
            n += 1;
        }
        // Parts come bottom to top (left to right); a vertical run is measured from its bottom.
        let mut out = Vec::new();
        let mut at = 0.0;
        for p in &parts {
            let times = if p.part_flags.extender() { n } else { 1 };
            for _ in 0..times {
                let g = self.glyph(p.glyph_id.0);
                out.push(Part { gid: p.glyph_id.0, at: at - start(&g) });
                at += p.full_advance as f32 / self.upem - overlap;
            }
        }
        (out, len(n))
    }

    /// Smallest variant of `gid` whose size along the axis reaches `target`, else the largest one.
    pub fn variant_at_least(&self, gid: u16, target: f32, vertical: bool) -> u16 {
        let own = self.glyph(gid);
        let size = if vertical { own.bbox[3] - own.bbox[1] } else { own.bbox[2] - own.bbox[0] };
        if size >= target {
            return gid;
        }
        let mut pick = gid;
        for (v, s) in self.variants(gid, vertical) {
            pick = v;
            if s >= target {
                break;
            }
        }
        pick
    }
}

fn scale_path(path: &[f32], by: f32) -> Vec<f32> {
    let mut out = Vec::with_capacity(path.len());
    let mut i = 0;
    while i < path.len() {
        let cmd = path[i];
        out.push(cmd);
        let n = match cmd as u8 {
            0 | 1 => 2,
            2 => 4,
            3 => 6,
            _ => 0,
        };
        for v in &path[i + 1..i + 1 + n] {
            out.push(v * by);
        }
        i += 1 + n;
    }
    out
}
