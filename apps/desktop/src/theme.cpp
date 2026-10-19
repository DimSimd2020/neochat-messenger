#include "theme.h"

#include <QApplication>
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
    return {"#111318", "#1A1D24", "#14171D", "#21252E", "#2A2F3B", "#EEF1F7", "#A3AEBF", "#98B9FF", "#4169CF", "#FFFFFF",
            "#3257AF", "#FFFFFF", "#D6E3FF", "#2C374D", "#343C4B", "#F2C989", "#382F24"};
}

Palette Palette::light()
{
    return {"#E5E9F1", "#FFFFFF", "#E9EDF4", "#F3F5F9", "#E7ECF3", "#1C2638", "#53617A", "#2456B8", "#315DCF", "#FFFFFF",
            "#315DCF", "#FFFFFF", "#D8E4FF", "#E1E9FA", "#CFD7E5", "#805015", "#FFF3DD"};
}

Palette Palette::mix(const Palette &a, const Palette &b, qreal t)
{
    Palette r;
    QColor Palette::*fields[] = {&Palette::stage, &Palette::canvas, &Palette::rail, &Palette::surface, &Palette::raised,
                                       &Palette::text, &Palette::muted, &Palette::accent, &Palette::action, &Palette::onAccent,
                                       &Palette::outgoing, &Palette::onOutgoing, &Palette::outgoingMuted, &Palette::selected,
                                       &Palette::border, &Palette::warning, &Palette::warningBg};
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
