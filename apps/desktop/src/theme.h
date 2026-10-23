#pragma once

#include <QColor>
#include <QEasingCurve>
#include <QFont>
#include <QList>
#include <QObject>
#include <QPointF>
#include <QVariantAnimation>

// Colors from docs/design/tokens.json ("themes", "appearance").
class QPainter;
class QWidget;

// A soft blob of the window backdrop. `pos` is a fraction of the window and may lie outside
// it; alpha 0 marks an unused slot.
struct Spot {
    QPointF pos;
    QColor color{0, 0, 0, 0};
};

constexpr int kMaxSpots = 5;
constexpr int kCustomSlots = 2;

struct Palette {
    QColor stage, canvas, rail, surface, raised, text, muted, accent, action, onAccent;
    QColor outgoing, onOutgoing, outgoingMuted, selected, border, warning, warningBg;
    // Window backdrop shared with Android and iOS: three-stop gradient plus color spots.
    QColor bg0, bg1, bg2;
    Spot spots[kMaxSpots];

    static Palette dark();
    static Palette light();
    // The dark or light template with its own backdrop; panels follow the backdrop colors.
    static Palette themed(bool dark, const QColor &c0, const QColor &c1, const QColor &c2, const QList<Spot> &spots);
    static Palette mix(const Palette &a, const Palette &b, qreal t);
};

// A theme made in "Оформление интерфейса": one base color and up to kMaxSpots colored blobs.
struct CustomTheme {
    QString name;
    QColor base;
    QList<Spot> spots;
    bool valid() const { return base.isValid(); }
};

class Theme : public QObject {
    Q_OBJECT
public:
    enum Mode { System = 0, Light = 1, Dark = 2 };
    struct Preset {
        const char *id, *name;
    };

    static Theme *instance();
    static const Palette &p() { return instance()->m_current; }

    Mode mode() const { return m_mode; }
    void setMode(Mode mode);
    bool isDark() const { return m_dark; }
    // What day/night mode alone asks for, whatever the chosen theme does with it.
    bool modeDark() const { return m_mode == Dark || (m_mode == System && systemDark()); }

    // Built-in themes follow the day/night mode; a custom theme sets light or dark by its base color.
    static QList<Preset> presets();
    static QString customId(int slot) { return QStringLiteral("custom%1").arg(slot); }
    QString appearance() const { return m_appearance; }
    void setAppearance(const QString &id);
    CustomTheme custom(int slot) const;
    // Stores the theme in the slot and switches to it.
    void saveCustom(int slot, const CustomTheme &theme);
    void deleteCustom(int slot);
    // Palette of an appearance as it would look now, for previews; `dark` matters for built-in themes.
    Palette paletteFor(const QString &id, bool dark) const;
    // Whether light text reads better on `base`.
    static bool darkBase(const QColor &base);

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
    // The same backdrop stretched over `all` and painted into `clip`: previews and the editor.
    static void paintBackdropIn(QPainter &painter, const QRectF &clip, const QRectF &all, const Palette &c);

    // Applies the saved mode without animation (first window paint).
    void init();

signals:
    void changed();
    void fontChanged();

private:
    Theme();
    bool systemDark() const;
    Palette resolve(bool *dark) const;
    bool known(const QString &id) const;
    void retarget(bool animate);
    void applyQtPalette();

    Mode m_mode = System;
    bool m_dark = true;
    QString m_appearance = QStringLiteral("ocean");
    qreal m_fontScale = 1.0;
    Palette m_from, m_to, m_current;
    QVariantAnimation m_anim;
};
