#include "theme.h"

#include <QApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLinearGradient>
#include <QPainter>
#include <QRadialGradient>
#include <QWidget>
#include <QPalette>
#include <QSettings>
#include <QStyleHints>
#include <QtMath>

#include <windows.h>

namespace {

QColor mixColor(const QColor &a, const QColor &b, qreal t)
{
    return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                            a.blueF() + (b.blueF() - a.blueF()) * t, a.alphaF() + (b.alphaF() - a.alphaF()) * t);
}

qreal luminance(const QColor &c)
{
    auto lin = [](qreal v) { return v <= 0.03928 ? v / 12.92 : qPow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * lin(c.redF()) + 0.7152 * lin(c.greenF()) + 0.0722 * lin(c.blueF());
}

// Built-in themes other than the original "ocean". The spots sit where the ocean ones do:
// top right and bottom left, outside the window by the same fractions.
Palette presetPalette(const QString &id, bool dark)
{
    auto make = [dark](const char *c0, const char *c1, const char *c2, std::initializer_list<Spot> spots) {
        return Palette::themed(dark, c0, c1, c2, spots);
    };
    if (id == QLatin1String("sunset")) {
        return dark ? make("#2A1626", "#1D1021", "#2B1A24", {{{1.0, 0.0}, "#8A3A5E"}, {{-0.35, 0.95}, "#8A5420"}})
                    : make("#FFF3EA", "#FBE7DE", "#F8E4EA", {{{1.0, 0.0}, "#F9B9A5"}, {{-0.35, 0.95}, "#F3C3D6"}});
    }
    if (id == QLatin1String("forest")) {
        return dark ? make("#10241D", "#0C1814", "#112A21", {{{1.0, 0.0}, "#1F6048"}, {{-0.35, 0.95}, "#3F6A2C"}})
                    : make("#EAF6EE", "#DFF0E5", "#D9EDE6", {{{1.0, 0.0}, "#AEE0C4"}, {{-0.35, 0.95}, "#D3E8A6"}});
    }
    if (id == QLatin1String("lilac")) {
        return dark ? make("#201B3A", "#15112B", "#1E1B40", {{{1.0, 0.0}, "#54409A"}, {{-0.35, 0.95}, "#2F5C94"}})
                    : make("#F1EDFC", "#E7E1F7", "#E1E8F9", {{{1.0, 0.0}, "#CDBFF6"}, {{-0.35, 0.95}, "#BBD4F6"}});
    }
    return dark ? Palette::dark() : Palette::light();
}

QString colorName(const QColor &c)
{
    return c.name(QColor::HexRgb);
}

} // namespace

Palette Palette::dark()
{
    // Android's liquid-glass dark: deep navy instead of neutral grey. The spots are the Android
    // colors blended at the 55 % the window used to paint them with, so the picture is unchanged.
    Palette p{"#0F1A2E", "#12213B", "#0F1B31", "#16243D", "#1C2C46", "#F2F7FF", "#BBCBE3", "#98B9FF", "#4169CF", "#FFFFFF",
              "#3159B8", "#FFFFFF", "#D6E3FF", "#26385A", "#2B3D5C", "#F2C989", "#382F24",
              "#12213B", "#101A2E", "#132C42"};
    p.spots[0] = {{1.0, 0.0}, "#1E375E"};
    p.spots[1] = {{-0.35, 0.95}, "#193C4E"};
    return p;
}

Palette Palette::light()
{
    Palette p{"#DEE8F5", "#E9F0FC", "#E3ECF8", "#F7FAFF", "#FFFFFF", "#182C46", "#4A607C", "#2456B8", "#315DCF", "#FFFFFF",
              "#3159B8", "#FFFFFF", "#D8E4FF", "#D6E2F7", "#C8D5E8", "#805015", "#FFF3DD",
              "#E9F0FC", "#DEE8F5", "#D7E8F0"};
    p.spots[0] = {{1.0, 0.0}, "#C8D9F6"};
    p.spots[1] = {{-0.35, 0.95}, "#C8E4E8"};
    return p;
}

Palette Palette::themed(bool dark, const QColor &c0, const QColor &c1, const QColor &c2, const QList<Spot> &spots)
{
    Palette p = dark ? Palette::dark() : Palette::light();
    p.bg0 = c0;
    p.bg1 = c1;
    p.bg2 = c2;
    for (int i = 0; i < kMaxSpots; ++i)
        p.spots[i] = i < spots.size() ? spots[i] : Spot{};
    // The same relation to the backdrop as in the original themes: panels are the middle stop
    // lifted toward white (night) or washed out toward white (day).
    p.canvas = c0;
    if (dark) {
        p.stage = mixColor(c1, Qt::black, 0.12);
        p.rail = c1;
        p.surface = mixColor(c1, Qt::white, 0.06);
        p.raised = mixColor(c1, Qt::white, 0.10);
        p.selected = mixColor(c1, Qt::white, 0.18);
        p.border = mixColor(c1, Qt::white, 0.20);
    } else {
        p.stage = c1;
        p.rail = mixColor(c1, Qt::white, 0.15);
        p.surface = mixColor(c1, Qt::white, 0.75);
        p.raised = Qt::white;
        p.selected = mixColor(c1, Qt::black, 0.04);
        p.border = mixColor(c1, Qt::black, 0.10);
    }
    return p;
}

Palette Palette::mix(const Palette &a, const Palette &b, qreal t)
{
    Palette r;
    QColor Palette::*fields[] = {&Palette::stage, &Palette::canvas, &Palette::rail, &Palette::surface, &Palette::raised,
                                       &Palette::text, &Palette::muted, &Palette::accent, &Palette::action, &Palette::onAccent,
                                       &Palette::outgoing, &Palette::onOutgoing, &Palette::outgoingMuted, &Palette::selected,
                                       &Palette::border, &Palette::warning, &Palette::warningBg, &Palette::bg0, &Palette::bg1,
                                       &Palette::bg2};
    for (auto f : fields)
        r.*f = mixColor(a.*f, b.*f, t);
    for (int i = 0; i < kMaxSpots; ++i) {
        Spot from = a.spots[i], to = b.spots[i];
        // A slot that is unused on one side grows out of the other side's blob instead of
        // drifting through grey; blobs that exist on both sides glide to their new place.
        if (from.color.alpha() == 0) {
            from.pos = to.pos;
            from.color = to.color;
            from.color.setAlpha(0);
        }
        if (to.color.alpha() == 0) {
            to.pos = from.pos;
            to.color = from.color;
            to.color.setAlpha(0);
        }
        r.spots[i].pos = from.pos + (to.pos - from.pos) * t;
        r.spots[i].color = mixColor(from.color, to.color, t);
    }
    return r;
}

Theme *Theme::instance()
{
    static Theme *t = new Theme;
    return t;
}

Theme::Theme()
{
    m_anim.setStartValue(0.0);
    m_anim.setEndValue(1.0);
    m_anim.setEasingCurve(QEasingCurve::InOutQuad); // CSS "ease"
    connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        m_current = Palette::mix(m_from, m_to, v.toReal());
        applyQtPalette();
        emit changed();
    });
    connect(QGuiApplication::styleHints(), &QStyleHints::colorSchemeChanged, this, [this] {
        if (m_mode == System)
            retarget(true);
    });
}

void Theme::init()
{
    QSettings s;
    m_mode = Mode(qBound(0, s.value("theme/mode", 0).toInt(), 2));
    m_fontScale = s.value("theme/fontScale", 1.0).toReal();
    const QString saved = s.value("appearance/active", QStringLiteral("ocean")).toString();
    m_appearance = known(saved) ? saved : QStringLiteral("ocean");
    retarget(false);
}

bool Theme::systemDark() const
{
    return QGuiApplication::styleHints()->colorScheme() != Qt::ColorScheme::Light;
}

void Theme::setMode(Mode mode)
{
    m_mode = mode;
    QSettings().setValue("theme/mode", int(mode));
    retarget(true);
}

QList<Theme::Preset> Theme::presets()
{
    return {{"ocean", "Океан"}, {"sunset", "Закат"}, {"forest", "Лес"}, {"lilac", "Сирень"}};
}

bool Theme::known(const QString &id) const
{
    for (const Preset &preset : presets())
        if (id == QLatin1String(preset.id))
            return true;
    for (int slot = 0; slot < kCustomSlots; ++slot)
        if (id == customId(slot) && custom(slot).valid())
            return true;
    return false;
}

CustomTheme Theme::custom(int slot) const
{
    CustomTheme theme;
    if (slot < 0 || slot >= kCustomSlots)
        return theme;
    const QJsonObject o = QJsonDocument::fromJson(QSettings().value(QStringLiteral("appearance/") + customId(slot)).toString().toUtf8()).object();
    const QColor base(o.value("base").toString());
    if (!base.isValid())
        return theme;
    theme.name = o.value("name").toString();
    theme.base = base;
    for (const QJsonValue &value : o.value("spots").toArray()) {
        const QJsonObject s = value.toObject();
        const QColor color(s.value("color").toString());
        if (!color.isValid() || theme.spots.size() >= kMaxSpots)
            continue;
        theme.spots.append(Spot{{s.value("x").toDouble(), s.value("y").toDouble()}, color});
    }
    return theme;
}

void Theme::saveCustom(int slot, const CustomTheme &theme)
{
    if (slot < 0 || slot >= kCustomSlots || !theme.valid())
        return;
    QJsonArray spots;
    for (const Spot &s : theme.spots.first(qMin(int(kMaxSpots), int(theme.spots.size()))))
        spots.append(QJsonObject{{"x", s.pos.x()}, {"y", s.pos.y()}, {"color", colorName(s.color)}});
    const QJsonObject o{{"name", theme.name.left(24)}, {"base", colorName(theme.base)}, {"spots", spots}};
    QSettings s;
    s.setValue(QStringLiteral("appearance/") + customId(slot), QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact)));
    m_appearance = customId(slot);
    s.setValue("appearance/active", m_appearance);
    retarget(true);
}

void Theme::deleteCustom(int slot)
{
    if (slot < 0 || slot >= kCustomSlots)
        return;
    QSettings s;
    s.remove(QStringLiteral("appearance/") + customId(slot));
    if (m_appearance == customId(slot)) {
        m_appearance = QStringLiteral("ocean");
        s.setValue("appearance/active", m_appearance);
        retarget(true);
    }
}

void Theme::setAppearance(const QString &id)
{
    if (!known(id) || id == m_appearance)
        return;
    m_appearance = id;
    QSettings().setValue("appearance/active", id);
    retarget(true);
}

bool Theme::darkBase(const QColor &base)
{
    const qreal l = luminance(base);
    const qreal lightText = (luminance(QColor("#F2F7FF")) + 0.05) / (l + 0.05);
    const qreal darkText = (l + 0.05) / (luminance(QColor("#182C46")) + 0.05);
    return lightText >= darkText;
}

Palette Theme::paletteFor(const QString &id, bool dark) const
{
    for (int slot = 0; slot < kCustomSlots; ++slot) {
        const CustomTheme theme = custom(slot);
        if (id == customId(slot) && theme.valid())
            return Palette::themed(darkBase(theme.base), theme.base, theme.base, theme.base, theme.spots);
    }
    return presetPalette(id, dark);
}

Palette Theme::resolve(bool *dark) const
{
    *dark = modeDark();
    if (m_appearance.startsWith(QLatin1String("custom"))) {
        for (int slot = 0; slot < kCustomSlots; ++slot) {
            const CustomTheme theme = custom(slot);
            if (m_appearance == customId(slot) && theme.valid()) {
                *dark = darkBase(theme.base);
                break;
            }
        }
    }
    return paletteFor(m_appearance, *dark);
}

void Theme::setFontScale(qreal scale)
{
    m_fontScale = scale;
    QSettings().setValue("theme/fontScale", scale);
    emit fontChanged();
    emit changed();
}

QFont Theme::font(qreal px, int weight) const
{
    QFont f;
    f.setFamilies({QStringLiteral("Segoe UI Variable Text"), QStringLiteral("Segoe UI"), QStringLiteral("Segoe UI Emoji")});
    f.setPixelSize(qRound(px * m_fontScale));
    f.setWeight(QFont::Weight(weight));
    return f;
}

void Theme::retarget(bool animate)
{
    const Palette target = resolve(&m_dark);
    m_anim.stop();
    const int duration = animate ? ms(420) : 0;
    if (duration == 0) {
        m_current = target;
        applyQtPalette();
        emit changed();
        return;
    }
    // A quick repeat continues from the colors currently on screen.
    m_from = m_current;
    m_to = target;
    m_anim.setDuration(duration);
    m_anim.start();
}

void Theme::applyQtPalette()
{
    const Palette &c = m_current;
    QPalette pal;
    pal.setColor(QPalette::Window, c.canvas);
    pal.setColor(QPalette::WindowText, c.text);
    pal.setColor(QPalette::Base, c.canvas);
    pal.setColor(QPalette::AlternateBase, c.surface);
    pal.setColor(QPalette::Text, c.text);
    pal.setColor(QPalette::PlaceholderText, c.muted);
    pal.setColor(QPalette::Button, c.raised);
    pal.setColor(QPalette::ButtonText, c.text);
    pal.setColor(QPalette::Highlight, c.action);
    pal.setColor(QPalette::HighlightedText, c.onAccent);
    pal.setColor(QPalette::ToolTipBase, c.raised);
    pal.setColor(QPalette::ToolTipText, c.text);
    pal.setColor(QPalette::Link, c.accent);
    pal.setColor(QPalette::Mid, c.border);
    pal.setColor(QPalette::Dark, c.rail);
    pal.setColor(QPalette::Light, c.raised);
    pal.setColor(QPalette::Disabled, QPalette::Text, c.muted);
    pal.setColor(QPalette::Disabled, QPalette::WindowText, c.muted);
    pal.setColor(QPalette::Disabled, QPalette::ButtonText, c.muted);
    QApplication::setPalette(pal);
}

bool Theme::reducedMotion()
{
    BOOL animations = TRUE;
    if (SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &animations, 0))
        return !animations;
    return false;
}

QEasingCurve Theme::surfaceEasing()
{
    QEasingCurve c(QEasingCurve::BezierSpline);
    c.addCubicBezierSegment(QPointF(0.22, 0.8), QPointF(0.25, 1.0), QPointF(1.0, 1.0));
    return c;
}

void Theme::paintBackdropIn(QPainter &painter, const QRectF &clip, const QRectF &all, const Palette &c)
{
    QLinearGradient g(all.topLeft(), QPointF(all.left() + all.width() * 0.35, all.bottom()));
    g.setColorAt(0, c.bg0);
    g.setColorAt(0.56, c.bg1);
    g.setColorAt(1, c.bg2);
    painter.fillRect(clip, g);
    const qreal radius = qMax(all.width(), all.height()) * 0.7;
    for (const Spot &spot : c.spots) {
        if (spot.color.alpha() == 0)
            continue;
        QRadialGradient r(QPointF(all.left() + spot.pos.x() * all.width(), all.top() + spot.pos.y() * all.height()), radius);
        QColor edge = spot.color;
        edge.setAlpha(0);
        r.setColorAt(0, spot.color);
        r.setColorAt(1, edge);
        painter.fillRect(clip, r);
    }
}

void Theme::paintBackdrop(QPainter &painter, const QWidget *w, const QColor &glass)
{
    const QWidget *win = w->window();
    const QPoint off = w->mapTo(win, QPoint(0, 0));
    paintBackdropIn(painter, w->rect(), QRectF(-off.x(), -off.y(), win->width(), win->height()), p());
    if (glass.isValid())
        painter.fillRect(w->rect(), glass);
}
