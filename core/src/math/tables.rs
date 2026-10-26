//! Command tables and math alphabets.

use super::parse::Class::{self, *};

/// name, character, class.
pub const SYMBOLS: &[(&str, char, Class)] = &[
    // Binary operators.
    ("pm", '±', Bin), ("mp", '∓', Bin), ("times", '×', Bin), ("div", '÷', Bin), ("cdot", '⋅', Bin),
    ("ast", '∗', Bin), ("star", '⋆', Bin), ("circ", '∘', Bin), ("bullet", '∙', Bin), ("oplus", '⊕', Bin),
    ("ominus", '⊖', Bin), ("otimes", '⊗', Bin), ("oslash", '⊘', Bin), ("odot", '⊙', Bin), ("cup", '∪', Bin),
    ("cap", '∩', Bin), ("setminus", '∖', Bin), ("wedge", '∧', Bin), ("land", '∧', Bin), ("vee", '∨', Bin),
    ("lor", '∨', Bin), ("sqcup", '⊔', Bin), ("sqcap", '⊓', Bin), ("uplus", '⊎', Bin), ("amalg", '⨿', Bin),
    ("dagger", '†', Bin), ("ddagger", '‡', Bin), ("wr", '≀', Bin), ("diamond", '⋄', Bin), ("bigtriangleup", '△', Bin),
    ("bigtriangledown", '▽', Bin), ("triangleleft", '◁', Bin), ("triangleright", '▷', Bin),
    ("minus", '−', Bin), ("plus", '+', Bin),
    // Relations.
    ("leq", '≤', Rel), ("le", '≤', Rel), ("geq", '≥', Rel), ("ge", '≥', Rel), ("neq", '≠', Rel), ("ne", '≠', Rel),
    ("approx", '≈', Rel), ("equiv", '≡', Rel), ("sim", '∼', Rel), ("simeq", '≃', Rel), ("cong", '≅', Rel),
    ("propto", '∝', Rel), ("ll", '≪', Rel), ("gg", '≫', Rel), ("subset", '⊂', Rel), ("supset", '⊃', Rel),
    ("subseteq", '⊆', Rel), ("supseteq", '⊇', Rel), ("sqsubseteq", '⊑', Rel), ("sqsupseteq", '⊒', Rel),
    ("in", '∈', Rel), ("notin", '∉', Rel), ("ni", '∋', Rel), ("perp", '⊥', Rel), ("parallel", '∥', Rel),
    ("mid", '∣', Rel), ("nmid", '∤', Rel), ("vdash", '⊢', Rel), ("dashv", '⊣', Rel), ("models", '⊨', Rel),
    ("asymp", '≍', Rel), ("doteq", '≐', Rel), ("prec", '≺', Rel), ("succ", '≻', Rel), ("preceq", '⪯', Rel),
    ("succeq", '⪰', Rel), ("lesssim", '≲', Rel), ("gtrsim", '≳', Rel), ("subsetneq", '⊊', Rel), ("supsetneq", '⊋', Rel),
    ("nsubseteq", '⊈', Rel), ("nsupseteq", '⊉', Rel), ("bowtie", '⋈', Rel), ("smile", '⌣', Rel), ("frown", '⌢', Rel),
    ("coloneqq", '≔', Rel), ("colon", ':', Punct), ("lt", '<', Rel), ("gt", '>', Rel),
    // Arrows.
    ("to", '→', Rel), ("rightarrow", '→', Rel), ("leftarrow", '←', Rel), ("gets", '←', Rel), ("leftrightarrow", '↔', Rel),
    ("Rightarrow", '⇒', Rel), ("Leftarrow", '⇐', Rel), ("Leftrightarrow", '⇔', Rel), ("implies", '⟹', Rel),
    ("impliedby", '⟸', Rel), ("iff", '⟺', Rel), ("mapsto", '↦', Rel), ("longmapsto", '⟼', Rel),
    ("longrightarrow", '⟶', Rel), ("longleftarrow", '⟵', Rel), ("longleftrightarrow", '⟷', Rel),
    ("Longrightarrow", '⟹', Rel), ("Longleftarrow", '⟸', Rel), ("Longleftrightarrow", '⟺', Rel),
    ("uparrow", '↑', Rel), ("downarrow", '↓', Rel), ("updownarrow", '↕', Rel), ("Uparrow", '⇑', Rel),
    ("Downarrow", '⇓', Rel), ("Updownarrow", '⇕', Rel), ("nearrow", '↗', Rel), ("searrow", '↘', Rel),
    ("swarrow", '↙', Rel), ("nwarrow", '↖', Rel), ("hookrightarrow", '↪', Rel), ("hookleftarrow", '↩', Rel),
    ("rightharpoonup", '⇀', Rel), ("rightharpoondown", '⇁', Rel), ("leftharpoonup", '↼', Rel),
    ("leftharpoondown", '↽', Rel), ("rightleftharpoons", '⇌', Rel), ("leadsto", '⇝', Rel),
    // Ordinary symbols.
    ("infty", '∞', Ord), ("partial", '∂', Ord), ("nabla", '∇', Ord), ("forall", '∀', Ord), ("exists", '∃', Ord),
    ("nexists", '∄', Ord), ("emptyset", '∅', Ord), ("varnothing", '∅', Ord), ("hbar", 'ℏ', Ord), ("ell", 'ℓ', Ord),
    ("Re", 'ℜ', Ord), ("Im", 'ℑ', Ord), ("aleph", 'ℵ', Ord), ("beth", 'ℶ', Ord), ("wp", '℘', Ord),
    ("prime", '′', Ord), ("angle", '∠', Ord), ("measuredangle", '∡', Ord), ("triangle", '△', Ord), ("top", '⊤', Ord),
    ("bot", '⊥', Ord), ("neg", '¬', Ord), ("lnot", '¬', Ord), ("flat", '♭', Ord), ("natural", '♮', Ord),
    ("sharp", '♯', Ord), ("clubsuit", '♣', Ord), ("diamondsuit", '♢', Ord), ("heartsuit", '♡', Ord),
    ("spadesuit", '♠', Ord), ("surd", '√', Ord), ("Box", '□', Ord), ("square", '□', Ord), ("blacksquare", '■', Ord),
    ("checkmark", '✓', Ord), ("degree", '°', Ord), ("copyright", '©', Ord), ("S", '§', Ord), ("P", '¶', Ord),
    ("imath", 'ı', Ord), ("jmath", 'ȷ', Ord), ("mho", '℧', Ord), ("complement", '∁', Ord), ("Finv", 'Ⅎ', Ord),
    ("therefore", '∴', Rel), ("because", '∵', Rel), ("backslash", '\\', Ord), ("vert", '|', Ord), ("lvert", '|', Open),
    ("rvert", '|', Close), ("Vert", '‖', Ord), ("lVert", '‖', Open), ("rVert", '‖', Close), ("|", '‖', Ord),
    ("langle", '⟨', Open), ("rangle", '⟩', Close), ("lfloor", '⌊', Open), ("rfloor", '⌋', Close),
    ("lceil", '⌈', Open), ("rceil", '⌉', Close), ("lbrace", '{', Open), ("rbrace", '}', Close),
    ("lbrack", '[', Open), ("rbrack", ']', Close), ("{", '{', Open), ("}", '}', Close),
    ("%", '%', Ord), ("$", '$', Ord), ("&", '&', Ord), ("_", '_', Ord), ("#", '#', Ord),
    ("textdollar", '$', Ord), ("lq", '‘', Ord), ("rq", '’', Ord), ("ldq", '“', Ord), ("rdq", '”', Ord),
    ("slash", '/', Ord), ("smallsetminus", '∖', Bin), ("lessgtr", '≶', Rel), ("gtrless", '≷', Rel),
    ("triangleq", '≜', Rel), ("vartriangle", '△', Ord), ("cdotp", '⋅', Punct), ("ldotp", '.', Punct),
    ("Join", '⋈', Rel), ("diagup", '╱', Ord), ("diagdown", '╲', Ord),
];

/// Greek and its variants; lower case is set in math italic, capitals upright.
pub const GREEK: &[(&str, char)] = &[
    ("alpha", 'α'), ("beta", 'β'), ("gamma", 'γ'), ("delta", 'δ'), ("epsilon", 'ϵ'), ("varepsilon", 'ε'),
    ("zeta", 'ζ'), ("eta", 'η'), ("theta", 'θ'), ("vartheta", 'ϑ'), ("iota", 'ι'), ("kappa", 'κ'),
    ("varkappa", 'ϰ'), ("lambda", 'λ'), ("mu", 'μ'), ("nu", 'ν'), ("xi", 'ξ'), ("omicron", 'ο'), ("pi", 'π'),
    ("varpi", 'ϖ'), ("rho", 'ρ'), ("varrho", 'ϱ'), ("sigma", 'σ'), ("varsigma", 'ς'), ("tau", 'τ'),
    ("upsilon", 'υ'), ("phi", 'ϕ'), ("varphi", 'φ'), ("chi", 'χ'), ("psi", 'ψ'), ("omega", 'ω'),
    ("Alpha", 'Α'), ("Beta", 'Β'), ("Gamma", 'Γ'), ("Delta", 'Δ'), ("Epsilon", 'Ε'), ("Zeta", 'Ζ'), ("Eta", 'Η'),
    ("Theta", 'Θ'), ("Iota", 'Ι'), ("Kappa", 'Κ'), ("Lambda", 'Λ'), ("Mu", 'Μ'), ("Nu", 'Ν'), ("Xi", 'Ξ'),
    ("Omicron", 'Ο'), ("Pi", 'Π'), ("Rho", 'Ρ'), ("Sigma", 'Σ'), ("Tau", 'Τ'), ("Upsilon", 'Υ'), ("Phi", 'Φ'),
    ("Chi", 'Χ'), ("Psi", 'Ψ'), ("Omega", 'Ω'),
];

/// name, character, limits above and below in display style.
pub const BIG_OPS: &[(&str, char, bool)] = &[
    ("sum", '∑', true), ("prod", '∏', true), ("coprod", '∐', true), ("int", '∫', false), ("iint", '∬', false),
    ("iiint", '∭', false), ("oint", '∮', false), ("oiint", '∯', false), ("bigcup", '⋃', true), ("bigcap", '⋂', true),
    ("bigvee", '⋁', true), ("bigwedge", '⋀', true), ("bigoplus", '⨁', true), ("bigotimes", '⨂', true),
    ("bigodot", '⨀', true), ("biguplus", '⨄', true), ("bigsqcup", '⨆', true),
];

/// name, limits above and below in display style.
pub const NAMED_OPS: &[(&str, bool)] = &[
    ("sin", false), ("cos", false), ("tan", false), ("cot", false), ("sec", false), ("csc", false),
    ("arcsin", false), ("arccos", false), ("arctan", false), ("sinh", false), ("cosh", false), ("tanh", false),
    ("coth", false), ("log", false), ("ln", false), ("lg", false), ("exp", false), ("lim", true), ("limsup", true),
    ("liminf", true), ("max", true), ("min", true), ("sup", true), ("inf", true), ("det", true), ("gcd", true),
    ("Pr", true), ("deg", false), ("dim", false), ("ker", false), ("hom", false), ("arg", false), ("mod", false),
    ("bmod", false), ("injlim", true), ("projlim", true),
];

/// Accents that sit over one character.
pub const ACCENTS: &[(&str, char)] = &[
    ("hat", '\u{302}'), ("check", '\u{30c}'), ("tilde", '\u{303}'), ("acute", '\u{301}'), ("grave", '\u{300}'),
    ("dot", '\u{307}'), ("ddot", '\u{308}'), ("dddot", '\u{20db}'), ("breve", '\u{306}'), ("bar", '\u{304}'),
    ("vec", '\u{20d7}'), ("mathring", '\u{30a}'),
];

/// Accents that stretch over their argument.
pub const WIDE_ACCENTS: &[(&str, char)] = &[("widehat", '\u{302}'), ("widetilde", '\u{303}')];

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Alphabet {
    Italic,
    Roman,
    Bold,
    BoldItalic,
    Script,
    Fraktur,
    Blackboard,
    Sans,
    Mono,
}

pub fn alphabet_command(name: &str) -> Option<Alphabet> {
    Some(match name {
        "mathit" => Alphabet::Italic,
        "mathrm" | "mathnormal" => Alphabet::Roman,
        "mathbf" | "bm" | "mathbfup" => Alphabet::Bold,
        "boldsymbol" | "mathbfit" => Alphabet::BoldItalic,
        "mathcal" | "mathscr" => Alphabet::Script,
        "mathfrak" => Alphabet::Fraktur,
        "mathbb" => Alphabet::Blackboard,
        "mathsf" => Alphabet::Sans,
        "mathtt" => Alphabet::Mono,
        _ => return None,
    })
}

/// A Latin or Greek character in the given math alphabet. Anything else comes back as it was.
pub fn alphabet(c: char, a: Alphabet) -> char {
    use Alphabet::*;
    let u = c as u32;
    let pick = |upper: u32, lower: u32, holes: &[(char, char)]| -> char {
        if let Some(&(_, to)) = holes.iter().find(|h| h.0 == c) {
            return to;
        }
        let v = match c {
            'A'..='Z' => upper + (u - 'A' as u32),
            'a'..='z' => lower + (u - 'a' as u32),
            _ => return c,
        };
        char::from_u32(v).unwrap_or(c)
    };
    if c.is_ascii_alphabetic() {
        return match a {
            Roman => c,
            Italic => pick(0x1D434, 0x1D44E, &[('h', 'ℎ')]),
            Bold => pick(0x1D400, 0x1D41A, &[]),
            BoldItalic => pick(0x1D468, 0x1D482, &[]),
            Script => pick(
                0x1D49C,
                0x1D4B6,
                &[('B', 'ℬ'), ('E', 'ℰ'), ('F', 'ℱ'), ('H', 'ℋ'), ('I', 'ℐ'), ('L', 'ℒ'), ('M', 'ℳ'), ('R', 'ℛ'), ('e', 'ℯ'), ('g', 'ℊ'), ('o', 'ℴ')],
            ),
            Fraktur => pick(0x1D504, 0x1D51E, &[('C', 'ℭ'), ('H', 'ℌ'), ('I', 'ℑ'), ('R', 'ℜ'), ('Z', 'ℨ')]),
            Blackboard => pick(
                0x1D538,
                0x1D552,
                &[('C', 'ℂ'), ('H', 'ℍ'), ('N', 'ℕ'), ('P', 'ℙ'), ('Q', 'ℚ'), ('R', 'ℝ'), ('Z', 'ℤ')],
            ),
            Sans => pick(0x1D5A0, 0x1D5BA, &[]),
            Mono => pick(0x1D670, 0x1D68A, &[]),
        };
    }
    if c.is_ascii_digit() {
        let base = match a {
            Bold | BoldItalic => 0x1D7CE,
            Blackboard => 0x1D7D8,
            Sans => 0x1D7E2,
            Mono => 0x1D7F6,
            _ => return c,
        };
        return char::from_u32(base + (u - '0' as u32)).unwrap_or(c);
    }
    // Greek: lower case follows the alphabet, capitals stay as typed unless bold or italic.
    let (cap_base, small_base) = match a {
        Italic => (0x1D6E2, 0x1D6FC),
        Bold => (0x1D6A8, 0x1D6C2),
        BoldItalic => (0x1D71C, 0x1D736),
        _ => return c,
    };
    match c {
        '\u{391}'..='\u{3a9}' if a != Italic => char::from_u32(cap_base + (u - 0x391)).unwrap_or(c),
        '\u{3b1}'..='\u{3c9}' => char::from_u32(small_base + (u - 0x3B1)).unwrap_or(c),
        '\u{3f5}' => char::from_u32(small_base + 26).unwrap_or(c),
        '\u{3d1}' => char::from_u32(small_base + 27).unwrap_or(c),
        '\u{3f0}' => char::from_u32(small_base + 28).unwrap_or(c),
        '\u{3d5}' => char::from_u32(small_base + 29).unwrap_or(c),
        '\u{3f1}' => char::from_u32(small_base + 30).unwrap_or(c),
        '\u{3d6}' => char::from_u32(small_base + 31).unwrap_or(c),
        _ => c,
    }
}
