#include "theme.h"

#include <QApplication>
#include <QLinearGradient>
#include <QPainter>
#include <QRadialGradient>
#include <QWidget>
#include <QPalette>
#include <QSettings>
#include <QStyleHints>

#include <windows.h>

namespace {

QColor mixColor(const QColor &a, const QColor &b, qreal t)
{
    return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                            a.blueF() + (b.blueF() - a.blueF()) * t, a.alphaF() + (b.alphaF() - a.alphaF()) * t);
}

} // namespace

Palette Palette::dark()
{
    // Android's liquid-glass dark: deep navy instead of neutral grey.
    return {"#0F1A2E", "#12213B", "#0F1B31", "#16243D", "#1C2C46", "#F2F7FF", "#BBCBE3", "#98B9FF", "#4169CF", "#FFFFFF",
            "#3159B8", "#FFFFFF", "#D6E3FF", "#26385A", "#2B3D5C", "#F2C989", "#382F24",
            "#12213B", "#101A2E", "#132C42", "#294E86", "#215868"};
}

Palette Palette::light()
{
    return {"#DEE8F5", "#E9F0FC", "#E3ECF8", "#F7FAFF", "#FFFFFF", "#182C46", "#4A607C", "#2456B8", "#315DCF", "#FFFFFF",
            "#3159B8", "#FFFFFF", "#D8E4FF", "#D6E2F7", "#C8D5E8", "#805015", "#FFF3DD",
            "#E9F0FC", "#DEE8F5", "#D7E8F0", "#B6CDF7", "#B6E1DD"};
}

Palette Palette::mix(const Palette &a, const Palette &b, qreal t)
{
    Palette r;
    QColor Palette::*fields[] = {&Palette::stage, &Palette::canvas, &Palette::rail, &Palette::surface, &Palette::raised,
                                       &Palette::text, &Palette::muted, &Palette::accent, &Palette::action, &Palette::onAccent,
                                       &Palette::outgoing, &Palette::onOutgoing, &Palette::outgoingMuted, &Palette::selected,
                                       &Palette::border, &Palette::warning, &Palette::warningBg, &Palette::bg0, &Palette::bg1,
                                       &Palette::bg2, &Palette::spotTop, &Palette::spotBottom};
    for (auto f : fields)
        r.*f = mixColor(a.*f, b.*f, t);
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
    m_dark = m_mode == Dark || (m_mode == System && systemDark());
    const Palette target = m_dark ? Palette::dark() : Palette::light();
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

void Theme::paintBackdrop(QPainter &painter, const QWidget *w, const QColor &glass)
{
    const Palette &c = p();
    const QWidget *win = w->window();
    const QPoint off = w->mapTo(win, QPoint(0, 0));
    const QRectF all(-off.x(), -off.y(), win->width(), win->height());
    QLinearGradient g(all.topLeft(), QPointF(all.left() + all.width() * 0.35, all.bottom()));
    g.setColorAt(0, c.bg0);
    g.setColorAt(0.56, c.bg1);
    g.setColorAt(1, c.bg2);
    painter.fillRect(w->rect(), g);
    const qreal s = qMax(all.width(), all.height()) * 0.7;
    auto spot = [&](QPointF center, QColor color) {
        QRadialGradient r(center, s);
        color.setAlphaF(0.55);
        r.setColorAt(0, color);
        color.setAlphaF(0);
        r.setColorAt(1, color);
        painter.fillRect(w->rect(), r);
    };
    spot(QPointF(all.right(), all.top()), c.spotTop);
    spot(QPointF(all.left() - all.width() * 0.35, all.top() + all.height() * 0.95), c.spotBottom);
    if (glass.isValid())
        painter.fillRect(w->rect(), glass);
}
