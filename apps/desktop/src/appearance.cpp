#include "dialogs.h"

#include "sheet.h"
#include "theme.h"
#include "widgets.h"

#include <QAbstractButton>
#include <QFocusEvent>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLineEdit>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QPointer>
#include <QRegularExpression>
#include <QVBoxLayout>
#include <QtMath>
#include <functional>
#include <memory>

// "Оформление интерфейса": built-in themes, two slots for the user's own, and the editor with
// draggable gradient points. Models and rendering live in theme.h.

namespace {

bool tabFocus(const QFocusEvent *e)
{
    return e->reason() == Qt::TabFocusReason || e->reason() == Qt::BacktabFocusReason || e->reason() == Qt::ShortcutFocusReason;
}

QColor readable(const QColor &on)
{
    return Theme::darkBase(on) ? QColor("#F2F7FF") : QColor("#182C46");
}

// ---- colour picker: saturation/value square, hue strip, hex field ----

class SvArea : public QWidget {
public:
    std::function<void(qreal s, qreal v)> picked;

    explicit SvArea(QWidget *parent = nullptr) : QWidget(parent)
    {
        setMinimumHeight(118);
        setFocusPolicy(Qt::StrongFocus);
        setCursor(Qt::CrossCursor);
        setAccessibleName(QStringLiteral("Насыщенность и яркость"));
    }
    void setValues(qreal h, qreal s, qreal v)
    {
        m_h = h;
        m_s = s;
        m_v = v;
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QPainterPath clip;
        clip.addRoundedRect(QRectF(rect()), 12, 12);
        p.setClipPath(clip);
        p.fillRect(rect(), QColor::fromHsvF(m_h, 1, 1));
        QLinearGradient white(0, 0, width(), 0);
        white.setColorAt(0, Qt::white);
        white.setColorAt(1, QColor(255, 255, 255, 0));
        p.fillRect(rect(), white);
        QLinearGradient black(0, 0, 0, height());
        black.setColorAt(0, QColor(0, 0, 0, 0));
        black.setColorAt(1, Qt::black);
        p.fillRect(rect(), black);
        p.setClipping(false);
        const QPointF at(m_s * width(), (1 - m_v) * height());
        p.setBrush(Qt::NoBrush);
        p.setPen(QPen(QColor(0, 0, 0, 90), 4));
        p.drawEllipse(at, 8, 8);
        p.setPen(QPen(Qt::white, 2.5));
        p.drawEllipse(at, 8, 8);
        if (hasFocus() && m_keyboardFocus)
            ui::paintFocusRing(&p, rect(), 12);
    }
    void mousePressEvent(QMouseEvent *e) override { pick(e->position()); }
    void mouseMoveEvent(QMouseEvent *e) override
    {
        if (e->buttons() & Qt::LeftButton)
            pick(e->position());
    }
    void keyPressEvent(QKeyEvent *e) override
    {
        const qreal step = e->modifiers() & Qt::ShiftModifier ? 0.1 : 0.02;
        qreal s = m_s, v = m_v;
        switch (e->key()) {
        case Qt::Key_Left: s -= step; break;
        case Qt::Key_Right: s += step; break;
        case Qt::Key_Up: v += step; break;
        case Qt::Key_Down: v -= step; break;
        default: return QWidget::keyPressEvent(e);
        }
        m_s = qBound(0.0, s, 1.0);
        m_v = qBound(0.0, v, 1.0);
        update();
        if (picked)
            picked(m_s, m_v);
    }
    void focusInEvent(QFocusEvent *e) override
    {
        m_keyboardFocus = tabFocus(e);
        QWidget::focusInEvent(e);
    }
    void focusOutEvent(QFocusEvent *e) override
    {
        m_keyboardFocus = false;
        QWidget::focusOutEvent(e);
    }

private:
    void pick(const QPointF &at)
    {
        m_s = qBound(0.0, at.x() / width(), 1.0);
        m_v = 1 - qBound(0.0, at.y() / height(), 1.0);
        update();
        if (picked)
            picked(m_s, m_v);
    }
    qreal m_h = 0, m_s = 0, m_v = 1;
    bool m_keyboardFocus = false;
};

class HueStrip : public QWidget {
public:
    std::function<void(qreal h)> picked;

    explicit HueStrip(QWidget *parent = nullptr) : QWidget(parent)
    {
        setFixedHeight(22);
        setFocusPolicy(Qt::StrongFocus);
        setCursor(Qt::PointingHandCursor);
        setAccessibleName(QStringLiteral("Оттенок"));
    }
    void setHue(qreal h)
    {
        m_h = h;
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const QRectF track = QRectF(rect()).adjusted(0, 5, 0, -5);
        QLinearGradient g(track.left(), 0, track.right(), 0);
        for (int i = 0; i <= 6; ++i)
            g.setColorAt(i / 6.0, QColor::fromHsvF(i == 6 ? 0.999 : i / 6.0, 1, 1));
        p.setPen(Qt::NoPen);
        p.setBrush(g);
        p.drawRoundedRect(track, 6, 6);
        const QPointF at(8 + m_h * (width() - 16), height() / 2.0);
        p.setBrush(QColor::fromHsvF(m_h, 1, 1));
        p.setPen(QPen(QColor(0, 0, 0, 90), 4));
        p.drawEllipse(at, 9, 9);
        p.setPen(QPen(Qt::white, 2.5));
        p.drawEllipse(at, 9, 9);
        if (hasFocus() && m_keyboardFocus)
            ui::paintFocusRing(&p, rect(), 11);
    }
    void mousePressEvent(QMouseEvent *e) override { pick(e->position().x()); }
    void mouseMoveEvent(QMouseEvent *e) override
    {
        if (e->buttons() & Qt::LeftButton)
            pick(e->position().x());
    }
    void keyPressEvent(QKeyEvent *e) override
    {
        const qreal step = e->modifiers() & Qt::ShiftModifier ? 0.05 : 0.01;
        if (e->key() != Qt::Key_Left && e->key() != Qt::Key_Right && e->key() != Qt::Key_Down && e->key() != Qt::Key_Up)
            return QWidget::keyPressEvent(e);
        const bool back = e->key() == Qt::Key_Left || e->key() == Qt::Key_Down;
        m_h = qBound(0.0, m_h + (back ? -step : step), 0.999);
        update();
        if (picked)
            picked(m_h);
    }
    void focusInEvent(QFocusEvent *e) override
    {
        m_keyboardFocus = tabFocus(e);
        QWidget::focusInEvent(e);
    }
    void focusOutEvent(QFocusEvent *e) override
    {
        m_keyboardFocus = false;
        QWidget::focusOutEvent(e);
    }

private:
    void pick(qreal x)
    {
        m_h = qBound(0.0, (x - 8) / qMax(1, width() - 16), 0.999);
        update();
        if (picked)
            picked(m_h);
    }
    qreal m_h = 0;
    bool m_keyboardFocus = false;
};

class ColorPicker : public QWidget {
public:
    std::function<void(const QColor &)> changed;

    explicit ColorPicker(QWidget *parent = nullptr) : QWidget(parent)
    {
        auto *lay = new QVBoxLayout(this);
        lay->setContentsMargins(0, 0, 0, 0);
        lay->setSpacing(8);
        m_sv = new SvArea;
        m_hue = new HueStrip;
        m_hex = new ui::TextField(QStringLiteral("Цвет, HEX"));
        lay->addWidget(m_sv, 1);
        lay->addWidget(m_hue);
        lay->addWidget(m_hex);
        m_sv->picked = [this](qreal s, qreal v) {
            m_s = s;
            m_v = v;
            publish();
        };
        m_hue->picked = [this](qreal h) {
            m_h = h;
            m_sv->setValues(m_h, m_s, m_v);
            publish();
        };
        connect(m_hex->edit(), &QLineEdit::textEdited, this, [this](const QString &text) {
            static const QRegularExpression hex(QStringLiteral("^#?[0-9A-Fa-f]{6}$"));
            if (hex.match(text.trimmed()).hasMatch())
                setColor(QColor(text.trimmed().startsWith('#') ? text.trimmed() : '#' + text.trimmed()), true);
        });
    }

    QColor color() const { return QColor::fromHsvF(m_h, m_s, m_v); }

    // Shows `c`; the picker only reports changes the user makes.
    void setColor(const QColor &c, bool report = false)
    {
        float h, s, v;
        c.getHsvF(&h, &s, &v);
        // Grey has no hue: keep the last one so the strip does not jump.
        if (h >= 0)
            m_h = qBound(0.0, qreal(h), 0.999);
        m_s = s;
        m_v = v;
        m_sv->setValues(m_h, m_s, m_v);
        m_hue->setHue(m_h);
        if (report) {
            if (changed)
                changed(color());
        } else {
            m_hex->setText(color().name(QColor::HexRgb).toUpper());
        }
    }

private:
    void publish()
    {
        m_hex->setText(color().name(QColor::HexRgb).toUpper());
        if (changed)
            changed(color());
    }
    SvArea *m_sv;
    HueStrip *m_hue;
    ui::TextField *m_hex;
    qreal m_h = 0, m_s = 0, m_v = 1;
};

// ---- backdrop canvas ----

// Live preview of the backdrop with one handle per point. A click on empty ground adds a
// point, a drag moves it, Delete removes it.
class BackdropCanvas : public QWidget {
public:
    std::function<void(int)> selectionChanged; // -1 = base colour

    explicit BackdropCanvas(QWidget *parent = nullptr) : QWidget(parent)
    {
        setMinimumHeight(190);
        setFocusPolicy(Qt::StrongFocus);
        setCursor(Qt::CrossCursor);
        setAccessibleName(QStringLiteral("Фон: нажмите, чтобы добавить точку, перетащите, чтобы передвинуть"));
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }

    QColor base = "#101A2E";
    QList<Spot> spots;
    int selected = -1;

    void select(int i)
    {
        selected = i;
        update();
        if (selectionChanged)
            selectionChanged(i);
    }

    bool add(const QPointF &pos, const QColor &color)
    {
        if (spots.size() >= kMaxSpots)
            return false;
        spots.append(Spot{pos, color});
        select(spots.size() - 1);
        return true;
    }

    void removeSelected()
    {
        if (selected < 0 || selected >= spots.size())
            return;
        spots.removeAt(selected);
        select(spots.isEmpty() ? -1 : qMin(selected, int(spots.size()) - 1));
    }

    // A colour no point uses yet.
    QColor freshColor() const
    {
        static const char *const colors[] = {"#6D8DFF", "#FF7A9C", "#5FD4B5", "#FFC76B", "#B48CFF"};
        for (const char *candidate : colors) {
            bool used = false;
            for (const Spot &s : spots)
                used = used || s.color == QColor(candidate);
            if (!used)
                return candidate;
        }
        return colors[0];
    }

    // The palette the preview is painted with: flat base plus the points.
    Palette look() const { return Palette::themed(Theme::darkBase(base), base, base, base, spots); }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QPainterPath clip;
        clip.addRoundedRect(QRectF(rect()), 18, 18);
        p.save();
        p.setClipPath(clip);
        Theme::paintBackdropIn(p, rect(), rect(), look());
        p.restore();
        p.setBrush(Qt::NoBrush);
        p.setPen(QPen(c.border, 1));
        p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 18, 18);
        QFont font = Theme::instance()->font(12, QFont::Bold);
        p.setFont(font);
        for (int i = 0; i < spots.size(); ++i) {
            const QPointF at = pointOf(i);
            const bool on = i == selected;
            const qreal r = on ? 15 : 12;
            p.setPen(QPen(QColor(0, 0, 0, 80), on ? 5.5 : 4));
            p.setBrush(Qt::NoBrush);
            p.drawEllipse(at, r, r);
            p.setPen(QPen(Qt::white, on ? 3.5 : 2.5));
            p.setBrush(spots[i].color);
            p.drawEllipse(at, r, r);
            p.setPen(readable(spots[i].color));
            p.drawText(QRectF(at.x() - r, at.y() - r, 2 * r, 2 * r), Qt::AlignCenter, QString::number(i + 1));
        }
        if (hasFocus() && m_keyboardFocus)
            ui::paintFocusRing(&p, rect(), 18);
    }

    void mousePressEvent(QMouseEvent *e) override
    {
        setFocus(Qt::MouseFocusReason);
        const QPointF at = e->position();
        m_drag = -1;
        for (int i = spots.size() - 1; i >= 0; --i) {
            if (QLineF(at, pointOf(i)).length() <= 18) {
                m_drag = i;
                break;
            }
        }
        if (e->button() == Qt::RightButton) {
            if (m_drag >= 0) {
                select(m_drag);
                removeSelected();
            }
            m_drag = -1;
            return;
        }
        if (m_drag >= 0) {
            select(m_drag);
            return;
        }
        // A new point follows the finger at once.
        if (add(fractionOf(at), freshColor()))
            m_drag = selected;
    }
    void mouseMoveEvent(QMouseEvent *e) override
    {
        if (m_drag < 0 || m_drag >= spots.size() || !(e->buttons() & Qt::LeftButton))
            return;
        spots[m_drag].pos = fractionOf(e->position());
        update();
    }
    void mouseReleaseEvent(QMouseEvent *) override { m_drag = -1; }
    void keyPressEvent(QKeyEvent *e) override
    {
        if (selected >= 0 && selected < spots.size()) {
            const qreal step = e->modifiers() & Qt::ShiftModifier ? 0.05 : 0.01;
            QPointF d;
            switch (e->key()) {
            case Qt::Key_Left: d = {-step, 0}; break;
            case Qt::Key_Right: d = {step, 0}; break;
            case Qt::Key_Up: d = {0, -step}; break;
            case Qt::Key_Down: d = {0, step}; break;
            case Qt::Key_Delete:
            case Qt::Key_Backspace: removeSelected(); return;
            default: return QWidget::keyPressEvent(e);
            }
            spots[selected].pos = fractionOf(QPointF((spots[selected].pos.x() + d.x()) * width(), (spots[selected].pos.y() + d.y()) * height()));
            update();
            return;
        }
        QWidget::keyPressEvent(e);
    }
    void focusInEvent(QFocusEvent *e) override
    {
        m_keyboardFocus = tabFocus(e);
        QWidget::focusInEvent(e);
    }
    void focusOutEvent(QFocusEvent *e) override
    {
        m_keyboardFocus = false;
        QWidget::focusOutEvent(e);
    }

private:
    // A point on the very edge is drawn inside the canvas, so its handle is never cut off.
    QPointF pointOf(int i) const
    {
        constexpr qreal margin = 17;
        return {qBound(margin, spots[i].pos.x() * width(), qMax(margin, width() - margin)),
                qBound(margin, spots[i].pos.y() * height(), qMax(margin, height() - margin))};
    }
    QPointF fractionOf(const QPointF &at) const
    {
        return {qBound(0.0, at.x() / qMax(1, width()), 1.0), qBound(0.0, at.y() / qMax(1, height()), 1.0)};
    }
    int m_drag = -1;
    bool m_keyboardFocus = false;
};

// ---- cards and chips ----

// A theme in the list: its backdrop, two mock bubbles and the name. `slot` < 0 is a built-in theme.
class ThemeCard : public QAbstractButton {
public:
    explicit ThemeCard(const QString &name, QWidget *parent = nullptr) : QAbstractButton(parent), m_name(name)
    {
        setCheckable(true);
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        setFixedHeight(124);
        setMinimumWidth(150);
        setAccessibleName(name);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    // Empty slot: dashed outline and a plus.
    void setEmpty(bool empty)
    {
        m_empty = empty;
        update();
    }
    void setLook(const Palette &p)
    {
        m_palette = p;
        update();
    }
    QSize sizeHint() const override { return {210, 124}; }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const QRectF r = QRectF(rect()).adjusted(2, 2, -2, -2);
        if (m_empty) {
            p.setBrush(underMouse() ? c.raised : Qt::transparent);
            p.setPen(QPen(c.border, 1.5, Qt::DashLine));
            p.drawRoundedRect(r, 16, 16);
            p.setPen(QPen(c.accent, 2.2, Qt::SolidLine, Qt::RoundCap));
            const QPointF mid = r.center() - QPointF(0, 10);
            p.drawLine(mid - QPointF(9, 0), mid + QPointF(9, 0));
            p.drawLine(mid - QPointF(0, 9), mid + QPointF(0, 9));
            p.setFont(Theme::instance()->font(14, QFont::DemiBold));
            p.setPen(c.text);
            p.drawText(QRectF(r.left(), r.center().y() + 8, r.width(), 24), Qt::AlignCenter, m_name);
            if (hasFocus())
                ui::paintFocusRing(&p, rect(), 16);
            return;
        }
        QPainterPath clip;
        clip.addRoundedRect(r, 16, 16);
        p.save();
        p.setClipPath(clip);
        Theme::paintBackdropIn(p, r, r, m_palette);
        // Two mock bubbles show how text and the accent sit on this backdrop.
        p.setPen(Qt::NoPen);
        QColor incoming = m_palette.raised;
        incoming.setAlphaF(0.85);
        p.setBrush(incoming);
        p.drawRoundedRect(QRectF(r.left() + 12, r.top() + 12, r.width() * 0.52, 18), 9, 9);
        p.setBrush(m_palette.outgoing);
        p.drawRoundedRect(QRectF(r.right() - 12 - r.width() * 0.4, r.top() + 36, r.width() * 0.4, 18), 9, 9);
        QColor strip = m_palette.surface;
        strip.setAlphaF(0.88);
        p.setBrush(strip);
        p.drawRect(QRectF(r.left(), r.bottom() - 32, r.width(), 32));
        p.restore();
        p.setFont(Theme::instance()->font(14, QFont::DemiBold));
        p.setPen(m_palette.text);
        const QRectF label(r.left() + 12, r.bottom() - 32, r.width() - 44, 32);
        p.drawText(label, Qt::AlignVCenter | Qt::AlignLeft, QFontMetrics(p.font()).elidedText(m_name, Qt::ElideRight, int(label.width())));
        p.setBrush(Qt::NoBrush);
        p.setPen(QPen(isChecked() ? c.accent : c.border, isChecked() ? 2.5 : 1));
        p.drawRoundedRect(r, 16, 16);
        if (isChecked()) {
            p.setPen(Qt::NoPen);
            p.setBrush(c.action);
            const QRectF dot(r.right() - 30, r.bottom() - 28, 22, 22);
            p.drawEllipse(dot);
            ui::paintIcon(&p, dot.adjusted(4, 4, -4, -4), "tick", c.onAccent, 2.4);
        }
        if (hasFocus())
            ui::paintFocusRing(&p, rect(), 16);
    }
    void enterEvent(QEnterEvent *) override { update(); }
    void leaveEvent(QEvent *) override { update(); }

private:
    QString m_name;
    bool m_empty = false;
    Palette m_palette = Palette::dark();
};

// A target of the colour picker: the base colour or one point.
class Chip : public QAbstractButton {
public:
    Chip(const QString &label, const QString &accessible, const QColor &color, QWidget *parent = nullptr)
        : QAbstractButton(parent), m_label(label), m_color(color)
    {
        setCheckable(true);
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        setFixedHeight(38);
        setAccessibleName(accessible);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    void setColor(const QColor &color)
    {
        m_color = color;
        update();
    }
    QSize sizeHint() const override
    {
        return {QFontMetrics(Theme::instance()->font(13.5, QFont::DemiBold)).horizontalAdvance(m_label) + 52, 38};
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const QRectF r = QRectF(rect()).adjusted(1, 1, -1, -1);
        p.setBrush(isChecked() ? c.selected : (underMouse() ? c.raised : Qt::transparent));
        p.setPen(QPen(isChecked() ? c.accent : c.border, isChecked() ? 2 : 1));
        p.drawRoundedRect(r, 12, 12);
        p.setPen(QPen(c.border, 1));
        p.setBrush(m_color);
        p.drawEllipse(QRectF(r.left() + 10, r.center().y() - 9, 18, 18));
        p.setFont(Theme::instance()->font(13.5, QFont::DemiBold));
        p.setPen(c.text);
        p.drawText(QRectF(r.left() + 36, r.top(), r.width() - 42, r.height()), Qt::AlignVCenter | Qt::AlignLeft, m_label);
        if (hasFocus())
            ui::paintFocusRing(&p, rect(), 12);
    }
    void enterEvent(QEnterEvent *) override { update(); }
    void leaveEvent(QEvent *) override { update(); }

private:
    QString m_label;
    QColor m_color;
};

// ---- editor ----

void editTheme(const DialogHost &h, int slot);

QString defaultName(int slot)
{
    return QStringLiteral("Моя тема %1").arg(slot + 1);
}

void showAppearance(const DialogHost &h);

void editTheme(const DialogHost &h, int slot)
{
    auto *theme = Theme::instance();
    const CustomTheme saved = theme->custom(slot);
    auto *sheet = new Sheet(h.host, saved.valid() ? QStringLiteral("Изменить тему") : QStringLiteral("Новая тема"), 540);
    sheet->setBack([h] { showAppearance(h); });

    auto *name = new ui::TextField(QStringLiteral("Название"));
    name->edit()->setMaxLength(24);
    name->setText(saved.valid() ? saved.name : QString());
    sheet->body()->addWidget(name);

    auto *canvas = new BackdropCanvas;
    if (saved.valid()) {
        canvas->base = saved.base;
        canvas->spots = saved.spots;
    } else {
        // A new theme starts from what is on screen now, with its points moved inside the window.
        const Palette now = Theme::p();
        canvas->base = now.bg1;
        for (const Spot &s : now.spots)
            if (s.color.alpha() > 0)
                canvas->spots.append(Spot{{qBound(0.05, s.pos.x(), 0.95), qBound(0.05, s.pos.y(), 0.95)}, QColor(s.color.rgb())});
    }
    sheet->body()->addWidget(canvas);

    // One chip for the base colour and one per point; the picker edits the chosen one.
    auto *chips = new QHBoxLayout;
    chips->setSpacing(6);
    sheet->body()->addLayout(chips);
    auto *picker = new ColorPicker;
    sheet->body()->addWidget(picker);
    auto *add = new ui::Button(QStringLiteral("Добавить точку"), ui::Button::Flat);
    auto *remove = new ui::Button(QStringLiteral("Удалить точку"), ui::Button::Flat);
    auto *tools = new QHBoxLayout;
    tools->addWidget(add);
    tools->addWidget(remove);
    tools->addStretch(1);
    sheet->body()->addLayout(tools);
    auto *hint = new ui::Label(QStringLiteral("Нажмите на фон, чтобы поставить точку, и перетащите её. Тёмный или светлый вид интерфейса выбирается по цвету фона."), 13.5, QFont::Normal, ui::Label::Muted);
    hint->setWordWrap(true);
    sheet->body()->addWidget(hint);

    auto *cancel = new ui::Button(QStringLiteral("Отмена"), ui::Button::Secondary);
    auto *save = new ui::Button(QStringLiteral("Сохранить"), ui::Button::Primary);
    auto *buttons = new QHBoxLayout;
    buttons->addWidget(cancel, 1);
    buttons->addWidget(save, 1);
    sheet->body()->addLayout(buttons);

    auto rebuild = std::make_shared<std::function<void()>>();
    *rebuild = [=] {
        while (QLayoutItem *item = chips->takeAt(0)) {
            delete item->widget();
            delete item;
        }
        auto *baseChip = new Chip(QStringLiteral("Фон"), QStringLiteral("Цвет фона"), canvas->base);
        baseChip->setChecked(canvas->selected < 0);
        QObject::connect(baseChip, &QAbstractButton::clicked, sheet, [=] { canvas->select(-1); });
        chips->addWidget(baseChip);
        for (int i = 0; i < canvas->spots.size(); ++i) {
            auto *chip = new Chip(QString::number(i + 1), QStringLiteral("Точка %1").arg(i + 1), canvas->spots[i].color);
            chip->setChecked(canvas->selected == i);
            QObject::connect(chip, &QAbstractButton::clicked, sheet, [=] { canvas->select(i); });
            chips->addWidget(chip);
        }
        chips->addStretch(1);
        add->setEnabled(canvas->spots.size() < kMaxSpots);
        remove->setEnabled(canvas->selected >= 0);
        picker->setColor(canvas->selected < 0 ? canvas->base : canvas->spots[canvas->selected].color);
    };
    // Adding, removing or selecting a point rebuilds the chips; moving and recolouring only repaint.
    canvas->selectionChanged = [=](int) { (*rebuild)(); };
    picker->changed = [=](const QColor &color) {
        if (canvas->selected < 0)
            canvas->base = color;
        else
            canvas->spots[canvas->selected].color = color;
        canvas->update();
        // Keep the chip colour in step without rebuilding the picker under the user's hand.
        const int index = canvas->selected + 1;
        if (auto *chip = chips->itemAt(index) ? qobject_cast<QAbstractButton *>(chips->itemAt(index)->widget()) : nullptr)
            static_cast<Chip *>(chip)->setColor(color);
    };
    QObject::connect(add, &QAbstractButton::clicked, sheet, [=] {
        canvas->add({0.5, 0.5}, canvas->freshColor());
    });
    QObject::connect(remove, &QAbstractButton::clicked, sheet, [=] { canvas->removeSelected(); });
    QObject::connect(cancel, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        showAppearance(h);
    });
    QObject::connect(save, &QAbstractButton::clicked, sheet, [=] {
        CustomTheme out;
        out.name = name->text().trimmed().isEmpty() ? defaultName(slot) : name->text().trimmed();
        out.base = canvas->base;
        out.spots = canvas->spots;
        Theme::instance()->saveCustom(slot, out);
        sheet->close();
        showAppearance(h);
    });
    (*rebuild)();
    sheet->open();
}

// ---- the page ----

void showAppearance(const DialogHost &h)
{
    auto *theme = Theme::instance();
    auto *sheet = new Sheet(h.host, QStringLiteral("Оформление интерфейса"), 480);
    sheet->setBack([h] { dialogs::graphics(h); });

    auto cards = std::make_shared<QList<QPair<QString, ThemeCard *>>>();
    auto sync = [=] {
        for (const auto &card : *cards)
            card.second->setChecked(card.first == theme->appearance());
    };
    auto grid = [&](const QString &title) {
        sheet->body()->addWidget(new ui::Label(title, 14, QFont::DemiBold));
        auto *row = new QHBoxLayout;
        row->setSpacing(12);
        return row;
    };

    auto *builtIn = grid(QStringLiteral("Готовые темы"));
    auto *builtInRow2 = new QHBoxLayout;
    builtInRow2->setSpacing(12);
    sheet->body()->addLayout(builtIn);
    sheet->body()->addLayout(builtInRow2);
    int n = 0;
    for (const Theme::Preset &preset : Theme::presets()) {
        const QString id = QString::fromLatin1(preset.id);
        auto *card = new ThemeCard(QString::fromUtf8(preset.name));
        // Built-in themes follow day and night, so the card shows what the window would use now.
        card->setLook(theme->paletteFor(id, theme->modeDark()));
        QObject::connect(card, &QAbstractButton::clicked, sheet, [=] {
            theme->setAppearance(id);
            sync();
        });
        (n++ < 2 ? builtIn : builtInRow2)->addWidget(card, 1);
        cards->append({id, card});
    }

    sheet->body()->addSpacing(4);
    auto *mine = grid(QStringLiteral("Мои темы"));
    sheet->body()->addLayout(mine);
    auto *manage = new QHBoxLayout;
    manage->setSpacing(12);
    sheet->body()->addLayout(manage);
    for (int slot = 0; slot < kCustomSlots; ++slot) {
        const CustomTheme custom = theme->custom(slot);
        const QString id = Theme::customId(slot);
        auto *card = new ThemeCard(custom.valid() ? custom.name : QStringLiteral("Новая тема"));
        if (custom.valid()) {
            card->setLook(theme->paletteFor(id, true));
            QObject::connect(card, &QAbstractButton::clicked, sheet, [=] {
                theme->setAppearance(id);
                sync();
            });
            cards->append({id, card});
        } else {
            card->setEmpty(true);
            QObject::connect(card, &QAbstractButton::clicked, sheet, [=] {
                sheet->close();
                editTheme(h, slot);
            });
        }
        mine->addWidget(card, 1);

        auto *column = new QHBoxLayout;
        column->setSpacing(6);
        if (custom.valid()) {
            auto *edit = new ui::Button(QStringLiteral("Изменить"), ui::Button::Flat);
            edit->setAccessibleName(QStringLiteral("Изменить тему «%1»").arg(custom.name));
            QObject::connect(edit, &QAbstractButton::clicked, sheet, [=] {
                sheet->close();
                editTheme(h, slot);
            });
            auto *del = new ui::Button(QStringLiteral("Удалить"), ui::Button::Flat);
            del->setAccessibleName(QStringLiteral("Удалить тему «%1»").arg(custom.name));
            // The first click only asks; the second removes, so a slip never costs a theme.
            QObject::connect(del, &QAbstractButton::clicked, sheet, [=] {
                if (del->text() != QStringLiteral("Точно?")) {
                    del->setText(QStringLiteral("Точно?"));
                    return;
                }
                theme->deleteCustom(slot);
                sheet->close();
                showAppearance(h);
            });
            column->addWidget(edit, 1);
            column->addWidget(del, 1);
        } else {
            column->addStretch(1);
        }
        manage->addLayout(column, 1);
    }
    auto *note = new ui::Label(QStringLiteral("Готовые темы меняются вместе с режимом «День» и «Ночь» в разделе «Графика». Своя тема сама задаёт светлый или тёмный вид по цвету фона. Можно сохранить до двух своих тем."), 13.5, QFont::Normal, ui::Label::Muted);
    note->setWordWrap(true);
    sheet->body()->addWidget(note);
    sync();
    sheet->open();
}

} // namespace

namespace dialogs {
void appearance(const DialogHost &h)
{
    showAppearance(h);
}
}
