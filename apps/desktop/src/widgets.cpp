#include "widgets.h"

#include "theme.h"

#include <QAccessible>
#include <QHash>
#include <QKeyEvent>
#include <QPainter>
#include <QPainterPath>
#include <QSvgRenderer>
#include <QTimer>

namespace ui {

namespace {

const QHash<QString, QString> &icons()
{
    static const QHash<QString, QString> map{
        {"theme-device", R"(<rect x="3" y="4" width="18" height="12" rx="2.4"/><path d="M9 20h6M12 16v4M7 12h10" stroke-width="1.5"/>)"},
        {"panel", R"(<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16m6-12-3 4 3 4"/>)"},
        {"panel-open", R"(<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16m3-12 3 4-3 4"/>)"},
        {"chat", R"(<path d="M20 11.5a8 8 0 0 1-8.5 8L4 21l1.5-6A8 8 0 1 1 20 11.5Z"/><path d="M8 9h7M8 13h4"/>)"},
        {"people", R"(<circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 5v1"/>)"},
        {"devices", R"(<rect x="3" y="4" width="14" height="11" rx="2"/><path d="M7 20h6M10 15v5"/><rect x="16" y="10" width="5" height="11" rx="1.4"/>)"},
        {"settings", R"(<path d="m10 3-.7 2.3-2 .9-2.2-.8-2 3.4 1.6 1.7v2.4L3.1 15l2 3.4 2.2-.8 2 .9.7 2.5h4l.7-2.5 2-.9 2.2.8 2-3.4-1.6-1.8v-2.4L20.9 9l-2-3.4-2.2.8-2-.9L14 3Z"/><circle cx="12" cy="12" r="3"/>)"},
        {"moon", R"(<path d="M20.7 14A8.8 8.8 0 0 1 10 3.3 9 9 0 1 0 20.7 14Z"/><path d="m17.5 3 .6 1.9 1.9.6-1.9.6-.6 1.9-.6-1.9-1.9-.6 1.9-.6Z" stroke-width="1.2"/>)"},
        {"sun", R"(<circle cx="12" cy="12" r="3.8"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42"/>)"},
        {"search", R"(<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>)"},
        {"compose", R"(<path d="M12 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-7M15.5 3.5l5 5M10 14l1-5 7-7 5 5-7 7-6 1Z"/>)"},
        {"lock", R"(<rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>)"},
        {"more", R"(<circle cx="12" cy="5" r="1.25" fill="#C" stroke="none"/><circle cx="12" cy="12" r="1.25" fill="#C" stroke="none"/><circle cx="12" cy="19" r="1.25" fill="#C" stroke="none"/>)"},
        {"back", R"(<path d="m14 5-7 7 7 7"/>)"},
        {"attach", R"(<path d="m8 12 6-6a3 3 0 1 1 4.2 4.2l-8 8a5 5 0 0 1-7-7L12 2.5M7 14l7-7"/>)"},
        {"smile", R"(<circle cx="12" cy="12" r="9"/><path d="M8 14a4.5 4.5 0 0 0 8 0M8 9v.1M16 9v.1"/>)"},
        {"send", R"(<path d="M5 12h14m-6-6 6 6-6 6" stroke-width="2.1"/>)"},
        {"check", R"(<path d="m3 12 4 4L18 5M11 16 22 5"/>)"},
        {"tick", R"(<path d="m5 12 4 4L19 6"/>)"},
        {"clock", R"(<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>)"},
        {"alert", R"(<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.1"/>)"},
        {"file", R"(<path d="M6 3h8l4 4v14H6V3Zm8 0v5h4M9 12h6M9 16h4"/>)"},
        {"download", R"(<path d="M12 3v12m-5-5 5 5 5-5M5 18v3h14v-3"/>)"},
        {"person-add", R"(<circle cx="9" cy="7" r="3.5"/><path d="M2.5 21v-2a6.5 6.5 0 0 1 13 0v2M19 7v8m-4-4h8"/>)"},
        {"profile", R"(<circle cx="12" cy="8" r="4"/><path d="M4 21v-1a8 8 0 0 1 16 0v1"/>)"},
        {"forward", R"(<path d="m9 6 6 6-6 6"/>)"},
        {"close", R"(<path d="m6 6 12 12M18 6 6 18"/>)"},
        {"reply", R"(<path d="M9 7 4 12l5 5"/><path d="M4 12h10a6 6 0 0 1 6 6v1"/>)"},
        {"down", R"(<path d="m6 9 6 6 6-6"/>)"},
        {"copy", R"(<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>)"},
    };
    return map;
}

QSvgRenderer *renderer(const QString &name, const QColor &color, qreal stroke)
{
    static QHash<QString, QSvgRenderer *> cache;
    const QString hex = color.name(QColor::HexArgb);
    const QString key = name + hex + QString::number(stroke);
    if (auto *r = cache.value(key))
        return r;
    if (cache.size() > 600) {
        qDeleteAll(cache);
        cache.clear();
    }
    QString body = icons().value(name);
    const QString rgb = color.name(QColor::HexRgb);
    body.replace("#C", rgb);
    const QString svg = QString(R"(<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="%1" stroke-opacity="%2" fill-opacity="%2" stroke-width="%3" stroke-linecap="round" stroke-linejoin="round">%4</svg>)")
                            .arg(rgb)
                            .arg(color.alphaF())
                            .arg(stroke)
                            .arg(body);
    auto *r = new QSvgRenderer(svg.toUtf8());
    cache.insert(key, r);
    return r;
}

QColor avatarColor(const QString &seed, bool dark)
{
    // Calm hues; text on top stays readable in both themes.
    static const int hues[] = {214, 190, 160, 32, 268, 340, 8, 120};
    const size_t h = qHash(seed);
    QColor c = QColor::fromHsl(hues[h % 8], dark ? 70 : 90, dark ? 92 : 200);
    return c;
}

} // namespace

void paintIcon(QPainter *p, const QRectF &r, const QString &name, const QColor &color, qreal strokeWidth)
{
    renderer(name, color, strokeWidth)->render(p, r);
}

QString initials(const QString &name)
{
    const QStringList parts = name.split(' ', Qt::SkipEmptyParts);
    QString out;
    for (const QString &s : parts) {
        if (s.isEmpty() || !s.at(0).isLetterOrNumber())
            continue;
        out += s.at(0).toUpper();
        if (out.size() == 2)
            break;
    }
    return out.isEmpty() ? QStringLiteral("?") : out;
}

void paintAvatar(QPainter *p, const QRectF &r, const QString &name, const QString &seed)
{
    const bool dark = Theme::instance()->isDark();
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    p->setPen(Qt::NoPen);
    p->setBrush(avatarColor(seed, dark));
    const qreal radius = r.width() * 18.0 / 48.0;
    p->drawRoundedRect(r, radius, radius);
    QFont f = Theme::instance()->font(r.height() * 0.36 / Theme::instance()->fontScale(), QFont::DemiBold);
    p->setFont(f);
    p->setPen(dark ? QColor("#F2F5FB") : QColor("#1C2638"));
    p->drawText(r, Qt::AlignCenter, initials(name));
    p->restore();
}

QPainterPath roundedPath(const QRectF &r, qreal tl, qreal tr, qreal br, qreal bl)
{
    QPainterPath path;
    path.moveTo(r.left() + tl, r.top());
    path.lineTo(r.right() - tr, r.top());
    path.arcTo(QRectF(r.right() - 2 * tr, r.top(), 2 * tr, 2 * tr), 90, -90);
    path.lineTo(r.right(), r.bottom() - br);
    path.arcTo(QRectF(r.right() - 2 * br, r.bottom() - 2 * br, 2 * br, 2 * br), 0, -90);
    path.lineTo(r.left() + bl, r.bottom());
    path.arcTo(QRectF(r.left(), r.bottom() - 2 * bl, 2 * bl, 2 * bl), 270, -90);
    path.lineTo(r.left(), r.top() + tl);
    path.arcTo(QRectF(r.left(), r.top(), 2 * tl, 2 * tl), 180, -90);
    path.closeSubpath();
    return path;
}

void paintFocusRing(QPainter *p, const QRectF &r, qreal radius)
{
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    p->setBrush(Qt::NoBrush);
    p->setPen(QPen(Theme::p().accent, 2));
    p->drawRoundedRect(r.adjusted(1, 1, -1, -1), radius, radius);
    p->restore();
}

void animate(QVariantAnimation &a, qreal to, int ms, const QEasingCurve &curve)
{
    const qreal from = a.currentValue().isValid() ? a.currentValue().toReal() : (a.startValue().isValid() ? a.startValue().toReal() : 0.0);
    a.stop();
    a.setEasingCurve(curve);
    a.setStartValue(from);
    a.setEndValue(to);
    a.setDuration(ms);
    if (ms <= 0) {
        a.setDuration(1);
        a.start();
        a.setCurrentTime(1);
        return;
    }
    a.start();
}

static bool tabFocus(QFocusEvent *e)
{
    return e->reason() == Qt::TabFocusReason || e->reason() == Qt::BacktabFocusReason || e->reason() == Qt::ShortcutFocusReason;
}

// ---- Label ----

Label::Label(const QString &text, qreal px, int weight, Role role, QWidget *parent)
    : QLabel(text, parent), m_role(role), m_px(px), m_weight(weight)
{
    restyle();
    connect(Theme::instance(), &Theme::fontChanged, this, &Label::restyle);
}

void Label::setRole(Role role)
{
    m_role = role;
    update();
}

void Label::restyle()
{
    QFont f = Theme::instance()->font(m_px, m_weight);
    if (m_mono)
        f.setFamilies({QStringLiteral("Cascadia Mono"), QStringLiteral("Consolas")});
    setFont(f);
}

void Label::paintEvent(QPaintEvent *e)
{
    const Palette &c = Theme::p();
    const QColor color = m_role == Muted ? c.muted : m_role == Accent ? c.accent : m_role == Warning ? c.warning : c.text;
    if (palette().color(QPalette::WindowText) != color) {
        QPalette pal = palette();
        pal.setColor(QPalette::WindowText, color);
        pal.setColor(QPalette::Text, color);
        setPalette(pal);
    }
    QLabel::paintEvent(e);
}

// ---- IconButton ----

IconButton::IconButton(const QString &icon, const QString &label, QWidget *parent, int size)
    : QAbstractButton(parent), m_icon(icon), m_size(size)
{
    setLabel(label);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setFixedSize(size, size);
    m_hover.setStartValue(0.0);
    connect(&m_hover, &QVariantAnimation::valueChanged, this, [this] { update(); });
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
}

void IconButton::setLabel(const QString &label)
{
    setToolTip(label);
    setAccessibleName(label);
}

void IconButton::enterEvent(QEnterEvent *)
{
    animate(m_hover, 1, Theme::ms(160));
}

void IconButton::leaveEvent(QEvent *)
{
    animate(m_hover, 0, Theme::ms(160));
}

void IconButton::focusInEvent(QFocusEvent *e)
{
    m_keyboardFocus = tabFocus(e);
    QAbstractButton::focusInEvent(e);
}

void IconButton::focusOutEvent(QFocusEvent *e)
{
    m_keyboardFocus = false;
    QAbstractButton::focusOutEvent(e);
}

void IconButton::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QRectF r = rect();
    const qreal radius = m_rail ? 14 : r.width() / 2;
    const qreal hover = m_hover.currentValue().isValid() ? m_hover.currentValue().toReal() : 0;
    QColor bg = Qt::transparent;
    if (m_rail && isChecked()) {
        bg = c.selected;
    } else if (isDown()) {
        bg = c.raised;
    } else if (hover > 0) {
        bg = c.raised;
        bg.setAlphaF(hover * 0.85);
    }
    if (bg.alpha() > 0) {
        p.setPen(Qt::NoPen);
        p.setBrush(bg);
        p.drawRoundedRect(r, radius, radius);
    }
    const qreal s = m_size >= 44 ? 24 : 22;
    const QColor ic = (m_rail && isChecked()) ? c.accent : (isEnabled() ? c.text : c.muted);
    paintIcon(&p, QRectF((r.width() - s) / 2, (r.height() - s) / 2, s, s), m_icon, ic);
    if (hasFocus() && m_keyboardFocus)
        paintFocusRing(&p, r, radius);
}

// ---- Button ----

Button::Button(const QString &text, Kind kind, QWidget *parent) : QAbstractButton(parent), m_kind(kind)
{
    setText(text);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setFont(Theme::instance()->font(14.5, QFont::DemiBold));
    setMinimumHeight(42);
    for (auto *a : {&m_hover, &m_press}) {
        a->setStartValue(0.0);
        connect(a, &QVariantAnimation::valueChanged, this, [this] { update(); });
    }
    connect(this, &QAbstractButton::pressed, this, [this] { animate(m_press, 1, Theme::ms(140)); });
    connect(this, &QAbstractButton::released, this, [this] { animate(m_press, 0, Theme::ms(140)); });
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    connect(Theme::instance(), &Theme::fontChanged, this, [this] { setFont(Theme::instance()->font(14.5, QFont::DemiBold)); updateGeometry(); });
}

QSize Button::sizeHint() const
{
    const QFontMetrics fm(font());
    return {fm.horizontalAdvance(text()) + 36, 42};
}

void Button::enterEvent(QEnterEvent *)
{
    animate(m_hover, 1, Theme::ms(160));
}

void Button::leaveEvent(QEvent *)
{
    animate(m_hover, 0, Theme::ms(160));
}

void Button::focusInEvent(QFocusEvent *e)
{
    m_keyboardFocus = tabFocus(e);
    QAbstractButton::focusInEvent(e);
}

void Button::focusOutEvent(QFocusEvent *e)
{
    m_keyboardFocus = false;
    QAbstractButton::focusOutEvent(e);
}

void Button::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const qreal hover = m_hover.currentValue().isValid() ? m_hover.currentValue().toReal() : 0;
    const qreal press = m_press.currentValue().isValid() ? m_press.currentValue().toReal() : 0;
    const qreal inset = press * 1.0;
    const QRectF r = QRectF(rect()).adjusted(inset, inset, -inset, -inset);
    QColor bg, fg;
    switch (m_kind) {
    case Primary:
        bg = c.action;
        fg = c.onAccent;
        bg = bg.lighter(100 + int(hover * 8));
        break;
    case Secondary:
        bg = c.raised;
        fg = c.text;
        bg = Theme::instance()->isDark() ? bg.lighter(100 + int(hover * 14)) : bg.darker(100 + int(hover * 5));
        break;
    case Danger:
        bg = c.warningBg;
        fg = c.warning;
        bg = bg.lighter(100 + int(hover * 10));
        break;
    case Flat:
        bg = c.raised;
        bg.setAlphaF(hover * 0.8);
        fg = c.accent;
        break;
    }
    if (!isEnabled()) {
        bg.setAlphaF(bg.alphaF() * 0.5);
        fg = c.muted;
    }
    p.setPen(Qt::NoPen);
    p.setBrush(bg);
    p.drawRoundedRect(r, 12, 12);
    p.setPen(fg);
    p.setFont(font());
    p.drawText(r, Qt::AlignCenter, text());
    if (hasFocus() && m_keyboardFocus)
        paintFocusRing(&p, rect(), 12);
}

// ---- SearchField ----

SearchField::SearchField(const QString &placeholder, const QString &hint, QWidget *parent) : QWidget(parent), m_hint(hint)
{
    setFixedHeight(42);
    m_edit = new QLineEdit(this);
    m_edit->setFrame(false);
    m_edit->setPlaceholderText(placeholder);
    m_edit->setAccessibleName(placeholder);
    m_edit->setClearButtonEnabled(false);
    m_edit->setFont(Theme::instance()->font(14.5));
    m_edit->installEventFilter(this);
    auto applyColors = [this] {
        QPalette pal = m_edit->palette();
        pal.setColor(QPalette::Base, Qt::transparent);
        pal.setColor(QPalette::Text, Theme::p().text);
        pal.setColor(QPalette::PlaceholderText, Theme::p().muted);
        m_edit->setPalette(pal);
        update();
    };
    applyColors();
    connect(Theme::instance(), &Theme::changed, this, applyColors);
    connect(Theme::instance(), &Theme::fontChanged, this, [this] { m_edit->setFont(Theme::instance()->font(14.5)); });
    connect(m_edit, &QLineEdit::textChanged, this, qOverload<>(&QWidget::update));
    setCursor(Qt::IBeamCursor);
}

bool SearchField::eventFilter(QObject *o, QEvent *e)
{
    if (o == m_edit && (e->type() == QEvent::FocusIn || e->type() == QEvent::FocusOut || e->type() == QEvent::Resize))
        update();
    if (o == m_edit && e->type() == QEvent::KeyPress && static_cast<QKeyEvent *>(e)->key() == Qt::Key_Escape && !m_edit->text().isEmpty()) {
        m_edit->clear();
        return true;
    }
    return QWidget::eventFilter(o, e);
}

void SearchField::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QRectF r = QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    p.setPen(m_edit->hasFocus() ? QPen(c.accent, 1.5) : Qt::NoPen);
    p.setBrush(Theme::instance()->isDark() ? c.raised : c.raised);
    p.drawRoundedRect(r, 12, 12);
    paintIcon(&p, QRectF(14, (height() - 18) / 2.0, 18, 18), "search", c.muted);
    int right = 12;
    if (!m_hint.isEmpty() && m_edit->text().isEmpty()) {
        QFont f = Theme::instance()->font(11.5);
        p.setFont(f);
        const int w = QFontMetrics(f).horizontalAdvance(m_hint) + 12;
        const QRectF chip(width() - 10 - w, (height() - 22) / 2.0, w, 22);
        p.setPen(QPen(c.border, 1));
        p.setBrush(Qt::NoBrush);
        p.drawRoundedRect(chip, 6, 6);
        p.setPen(c.muted);
        p.drawText(chip, Qt::AlignCenter, m_hint);
        right = w + 18;
    }
    m_edit->setGeometry(42, 0, width() - 42 - right, height());
}

// ---- TextField ----

TextField::TextField(const QString &label, QWidget *parent) : QWidget(parent), m_label(label)
{
    setFixedHeight(54);
    m_edit = new QLineEdit(this);
    m_edit->setFrame(false);
    m_edit->setAccessibleName(label);
    m_edit->setFont(Theme::instance()->font(14.5));
    m_edit->installEventFilter(this);
    m_float.setStartValue(0.0);
    m_float.setEndValue(0.0);
    connect(&m_float, &QVariantAnimation::valueChanged, this, qOverload<>(&QWidget::update));
    connect(m_edit, &QLineEdit::textChanged, this, [this] { updateFloat(true); });
    auto applyColors = [this] {
        QPalette pal = m_edit->palette();
        pal.setColor(QPalette::Base, Qt::transparent);
        pal.setColor(QPalette::Text, Theme::p().text);
        m_edit->setPalette(pal);
        update();
    };
    applyColors();
    connect(Theme::instance(), &Theme::changed, this, applyColors);
    connect(Theme::instance(), &Theme::fontChanged, this, [this] { m_edit->setFont(Theme::instance()->font(14.5)); });
    setCursor(Qt::IBeamCursor);
}

void TextField::setText(const QString &t)
{
    m_edit->setText(t);
    updateFloat(false);
}

void TextField::updateFloat(bool animated)
{
    const qreal target = (m_edit->hasFocus() || !m_edit->text().isEmpty()) ? 1.0 : 0.0;
    const qreal cur = m_float.currentValue().isValid() ? m_float.currentValue().toReal() : 0.0;
    if (qFuzzyCompare(cur + 1, target + 1) && m_float.state() != QAbstractAnimation::Running)
        return;
    animate(m_float, target, animated ? Theme::ms(180) : 0);
}

bool TextField::eventFilter(QObject *o, QEvent *e)
{
    if (o == m_edit && (e->type() == QEvent::FocusIn || e->type() == QEvent::FocusOut)) {
        updateFloat(true);
        update();
    }
    return QWidget::eventFilter(o, e);
}

void TextField::mousePressEvent(QMouseEvent *)
{
    m_edit->setFocus(Qt::MouseFocusReason);
}

void TextField::resizeEvent(QResizeEvent *)
{
    m_edit->setGeometry(14, 22, width() - 28, height() - 26);
}

void TextField::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QRectF r = QRectF(rect()).adjusted(0.75, 0.75, -0.75, -0.75);
    p.setPen(QPen(m_edit->hasFocus() ? c.accent : c.border, m_edit->hasFocus() ? 1.5 : 1));
    p.setBrush(c.surface);
    p.drawRoundedRect(r, 12, 12);
    const qreal t = m_float.currentValue().isValid() ? m_float.currentValue().toReal() : 0.0;
    const qreal px = 14.5 - t * (14.5 * 0.15);
    p.setFont(Theme::instance()->font(px));
    p.setPen(m_edit->hasFocus() ? c.accent : c.muted);
    const qreal y = (height() - QFontMetricsF(p.font()).height()) / 2 * (1 - t) + 7 * t;
    p.drawText(QRectF(15, y, width() - 30, QFontMetricsF(p.font()).height()), Qt::AlignLeft | Qt::AlignVCenter, m_label);
    // The line edit stays hidden below the label until the label has moved up.
    m_edit->setVisible(t > 0.01 || m_edit->hasFocus());
}

// ---- ThemeSlider ----

static const char *const kModeIcons[] = {"theme-device", "sun", "moon"};
static const char *const kModeNames[] = {"Устройство", "День", "Ночь"};

ThemeSlider::ThemeSlider(QWidget *parent) : QWidget(parent)
{
    setFocusPolicy(Qt::StrongFocus);
    setCursor(Qt::PointingHandCursor);
    setFixedHeight(74);
    setAccessibleName(QStringLiteral("Тема"));
    m_anim.setStartValue(0.0);
    connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        m_pos = v.toReal();
        update();
    });
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
}

void ThemeSlider::setValue(int v, bool animated)
{
    v = qBound(0, v, 2);
    const bool changed = v != m_value;
    m_value = v;
    setAccessibleDescription(QString::fromUtf8(kModeNames[v]));
    if (!m_drag) {
        m_anim.stop();
        m_anim.setStartValue(m_pos);
        m_anim.setEndValue(qreal(v));
        m_anim.setEasingCurve(Theme::surfaceEasing());
        m_anim.setDuration(animated ? qMax(1, Theme::ms(220)) : 1);
        m_anim.start();
    }
    if (changed) {
        QAccessibleValueChangeEvent ev(this, v);
        QAccessible::updateAccessibility(&ev);
        emit valueChanged(v);
    }
}

QRectF ThemeSlider::track() const
{
    return QRectF(0, 0, width(), 44);
}

qreal ThemeSlider::xFor(qreal pos) const
{
    const QRectF t = track();
    return t.left() + 22 + pos * (t.width() - 44) / 2.0;
}

int ThemeSlider::valueAt(qreal x) const
{
    const QRectF t = track();
    return qBound(0, qRound((x - t.left() - 22) / ((t.width() - 44) / 2.0)), 2);
}

void ThemeSlider::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QRectF t = track();
    p.setPen(Qt::NoPen);
    p.setBrush(c.raised);
    p.drawRoundedRect(t, 22, 22);
    const qreal cx = xFor(m_pos);
    // The fill runs under the whole thumb so no gap shows between the roundings.
    p.setBrush(QColor("#367FF4"));
    p.drawRoundedRect(QRectF(t.left(), t.top(), cx + 22 - t.left(), t.height()), 22, 22);
    for (int i = 0; i < 3; ++i) {
        const qreal x = xFor(i);
        const QColor ic = x <= cx + 1 ? QColor(Qt::white) : c.muted;
        paintIcon(&p, QRectF(x - 10, t.center().y() - 10, 20, 20), kModeIcons[i], ic);
    }
    p.setBrush(Qt::white);
    p.drawEllipse(QPointF(cx, t.center().y()), 21, 21);
    paintIcon(&p, QRectF(cx - 11, t.center().y() - 11, 22, 22), kModeIcons[qBound(0, qRound(m_pos), 2)], QColor("#367FF4"));
    p.setFont(Theme::instance()->font(12.5));
    for (int i = 0; i < 3; ++i) {
        const qreal x = xFor(i);
        p.setPen(i == m_value ? c.text : c.muted);
        const Qt::Alignment al = i == 0 ? Qt::AlignLeft : i == 2 ? Qt::AlignRight : Qt::AlignHCenter;
        const QRectF lr = i == 0 ? QRectF(0, 50, 120, 20) : i == 2 ? QRectF(width() - 120, 50, 120, 20) : QRectF(x - 60, 50, 120, 20);
        p.drawText(lr, al | Qt::AlignVCenter, QString::fromUtf8(kModeNames[i]));
    }
    if (hasFocus() && m_keyboardFocus)
        paintFocusRing(&p, t.adjusted(-3, -3, 3, 3), 25);
}

void ThemeSlider::mousePressEvent(QMouseEvent *e)
{
    if (!track().adjusted(0, -4, 0, 4).contains(e->position()))
        return setValue(valueAt(e->position().x()));
    m_drag = true;
    m_anim.stop();
}

void ThemeSlider::mouseMoveEvent(QMouseEvent *e)
{
    if (!m_drag)
        return;
    const QRectF t = track();
    m_pos = qBound(0.0, (e->position().x() - t.left() - 22) / ((t.width() - 44) / 2.0), 2.0);
    update();
}

void ThemeSlider::mouseReleaseEvent(QMouseEvent *e)
{
    if (!m_drag)
        return;
    m_drag = false;
    setValue(valueAt(e->position().x()));
}

void ThemeSlider::keyPressEvent(QKeyEvent *e)
{
    switch (e->key()) {
    case Qt::Key_Left:
    case Qt::Key_Down:
        setValue(m_value - 1);
        break;
    case Qt::Key_Right:
    case Qt::Key_Up:
        setValue(m_value + 1);
        break;
    case Qt::Key_Home:
        setValue(0);
        break;
    case Qt::Key_End:
        setValue(2);
        break;
    default:
        QWidget::keyPressEvent(e);
    }
}

void ThemeSlider::focusInEvent(QFocusEvent *e)
{
    m_keyboardFocus = tabFocus(e);
    update();
    QWidget::focusInEvent(e);
}

void ThemeSlider::focusOutEvent(QFocusEvent *e)
{
    m_keyboardFocus = false;
    update();
    QWidget::focusOutEvent(e);
}

// ---- Toggle ----

Toggle::Toggle(const QString &text, QWidget *parent) : QAbstractButton(parent)
{
    setText(text);
    setCheckable(true);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setMinimumHeight(40);
    m_anim.setStartValue(0.0);
    connect(&m_anim, &QVariantAnimation::valueChanged, this, qOverload<>(&QWidget::update));
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
}

QSize Toggle::sizeHint() const
{
    return {QFontMetrics(Theme::instance()->font(14.5)).horizontalAdvance(text()) + 70, 40};
}

void Toggle::checkStateSet()
{
    animate(m_anim, isChecked() ? 1 : 0, 0);
}

void Toggle::nextCheckState()
{
    setChecked(!isChecked());
    animate(m_anim, isChecked() ? 1 : 0, Theme::ms(220), Theme::surfaceEasing());
}

void Toggle::focusInEvent(QFocusEvent *e)
{
    m_keyboardFocus = tabFocus(e);
    QAbstractButton::focusInEvent(e);
}

void Toggle::focusOutEvent(QFocusEvent *e)
{
    m_keyboardFocus = false;
    QAbstractButton::focusOutEvent(e);
}

void Toggle::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setFont(Theme::instance()->font(14.5));
    p.setPen(c.text);
    p.drawText(QRectF(0, 0, width() - 60, height()), Qt::AlignLeft | Qt::AlignVCenter, text());
    const qreal t = m_anim.currentValue().isValid() ? m_anim.currentValue().toReal() : (isChecked() ? 1 : 0);
    const QRectF track(width() - 46, (height() - 26) / 2.0, 46, 26);
    QColor off = c.border;
    QColor on("#367FF4");
    QColor bg = QColor::fromRgbF(off.redF() + (on.redF() - off.redF()) * t, off.greenF() + (on.greenF() - off.greenF()) * t,
                                 off.blueF() + (on.blueF() - off.blueF()) * t);
    p.setPen(Qt::NoPen);
    p.setBrush(bg);
    p.drawRoundedRect(track, 13, 13);
    p.setBrush(Qt::white);
    p.drawEllipse(QPointF(track.left() + 13 + t * 20, track.center().y()), 10, 10);
    if (hasFocus() && m_keyboardFocus)
        paintFocusRing(&p, track.adjusted(-3, -3, 3, 3), 16);
}

// ---- Toast ----

Toast::Toast(QWidget *host) : QWidget(host)
{
    setAttribute(Qt::WA_TransparentForMouseEvents);
    m_anim.setStartValue(0.0);
    connect(&m_anim, &QVariantAnimation::valueChanged, this, qOverload<>(&QWidget::update));
    connect(&m_anim, &QVariantAnimation::finished, this, [this] {
        if (m_anim.endValue().toReal() == 0)
            hide();
    });
    m_timer = new QTimer(this);
    m_timer->setSingleShot(true);
    connect(m_timer, &QTimer::timeout, this, [this] { animate(m_anim, 0, Theme::ms(160)); });
    host->installEventFilter(this);
    hide();
}

void Toast::show(const QString &text)
{
    m_text = text;
    place();
    QWidget::show();
    raise();
    animate(m_anim, 1, Theme::ms(180));
    m_timer->start(3200);
    QAccessibleEvent ev(this, QAccessible::Alert);
    setAccessibleName(text);
    QAccessible::updateAccessibility(&ev);
}

void Toast::place()
{
    const QFont f = Theme::instance()->font(14);
    const int w = qMin(parentWidget()->width() - 32, QFontMetrics(f).horizontalAdvance(m_text) + 40);
    setGeometry((parentWidget()->width() - w) / 2, parentWidget()->height() - 110, w, 44);
}

bool Toast::eventFilter(QObject *o, QEvent *e)
{
    if (o == parentWidget() && e->type() == QEvent::Resize && isVisible())
        place();
    return QWidget::eventFilter(o, e);
}

void Toast::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    const qreal t = m_anim.currentValue().isValid() ? m_anim.currentValue().toReal() : 0;
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setOpacity(t);
    p.translate(0, (1 - t) * 8);
    p.setPen(QPen(c.border, 1));
    p.setBrush(c.raised);
    p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 14, 14);
    p.setPen(c.text);
    p.setFont(Theme::instance()->font(14));
    p.drawText(rect().adjusted(16, 0, -16, 0), Qt::AlignCenter, QFontMetrics(p.font()).elidedText(m_text, Qt::ElideRight, width() - 32));
}

// ---- QrView ----

QrView::QrView(QWidget *parent) : QWidget(parent)
{
    setFixedSize(232, 232);
    setAccessibleName(QStringLiteral("QR-код привязки"));
}

void QrView::setRows(const QStringList &rows)
{
    m_rows = rows;
    update();
}

void QrView::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(Qt::NoPen);
    p.setBrush(Qt::white);
    p.drawRoundedRect(rect(), 16, 16);
    if (m_rows.isEmpty())
        return;
    const int n = m_rows.size();
    const qreal quiet = 4;
    const qreal cell = qreal(width() - 16) / (n + 2 * quiet);
    const qreal off = 8 + quiet * cell;
    p.setRenderHint(QPainter::Antialiasing, false);
    p.setBrush(Qt::black);
    for (int y = 0; y < n; ++y)
        for (int x = 0; x < m_rows[y].size(); ++x)
            if (m_rows[y][x] == '1')
                p.drawRect(QRectF(off + x * cell, off + y * cell, cell + 0.4, cell + 0.4));
}

} // namespace ui
