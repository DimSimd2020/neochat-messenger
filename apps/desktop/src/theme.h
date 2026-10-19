#pragma once

#include <QColor>
#include <QEasingCurve>
#include <QFont>
#include <QObject>
#include <QVariantAnimation>

// Colors from docs/design/tokens.json ("themes").
class QPainter;
class QWidget;

struct Palette {
    QColor stage, canvas, rail, surface, raised, text, muted, accent, action, onAccent;
    QColor outgoing, onOutgoing, outgoingMuted, selected, border, warning, warningBg;
    // Window backdrop shared with Android and iOS: gradient plus two soft spots.
    QColor bg0, bg1, bg2, spotTop, spotBottom;

    static Palette dark();
    static Palette light();
    static Palette mix(const Palette &a, const Palette &b, qreal t);
};

class Theme : public QObject {
    Q_OBJECT
public:
    enum Mode { System = 0, Light = 1, Dark = 2 };

    static Theme *instance();
    static const Palette &p() { return instance()->m_current; }

    Mode mode() const { return m_mode; }
    void setMode(Mode mode);
    bool isDark() const { return m_dark; }

    // Text size multiplier chosen in settings (0.9, 1.0, 1.15).
    qreal fontScale() const { return m_fontScale; }
    void setFontScale(qreal scale);
    QFont font(qreal px, int weight = QFont::Normal) const;

    // Windows "Show animations" off means reduced motion; read live so a change applies at once.
    static bool reducedMotion();
    static int ms(int duration) { return reducedMotion() ? 0 : duration; }
    // cubic-bezier(.22,.8,.25,1) from the motion spec.
    static QEasingCurve surfaceEasing();

    // Paints the window backdrop into `w` in window coordinates, so neighbouring widgets
    // continue one gradient; `glass` (translucent) is laid over it when given.
    static void paintBackdrop(QPainter &painter, const QWidget *w, const QColor &glass = {});

    // Applies the saved mode without animation (first window paint).
    void init();

signals:
    void changed();
    void fontChanged();

private:
    Theme();
    bool systemDark() const;
    void retarget(bool animate);
    void applyQtPalette();

    Mode m_mode = System;
    bool m_dark = true;
    qreal m_fontScale = 1.0;
    Palette m_from, m_to, m_current;
    QVariantAnimation m_anim;
};
