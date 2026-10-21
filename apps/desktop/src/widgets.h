#pragma once

#include <QAbstractButton>
#include <QLabel>
#include <QLineEdit>
#include <QPainterPath>
#include <QPlainTextEdit>
#include <QVariantAnimation>
#include <QWidget>

class QPainter;
class QTimer;

namespace ui {

// Draws an icon from the design sketch (docs/design/preview.html symbols).
void paintIcon(QPainter *p, const QRectF &r, const QString &name, const QColor &color, qreal strokeWidth = 1.8);
// Initials on a rounded tile; the hue comes from `seed` so the same person keeps one color.
void paintAvatar(QPainter *p, const QRectF &r, const QString &name, const QString &seed, const QString &avatar = {});
QString initials(const QString &name);
// Rounded path with an individual radius per corner (tl, tr, br, bl).
QPainterPath roundedPath(const QRectF &r, qreal tl, qreal tr, qreal br, qreal bl);
void paintFocusRing(QPainter *p, const QRectF &r, qreal radius);
// Shortens a short animation helper: runs `step(t)` from the current value to `to`.
void animate(QVariantAnimation &a, qreal to, int ms, const QEasingCurve &curve = QEasingCurve::InOutQuad);

// Label whose color follows a palette role through theme changes.
class Label : public QLabel {
    Q_OBJECT
public:
    enum Role { Text, Muted, Accent, Warning };
    Label(const QString &text, qreal px, int weight = 400, Role role = Text, QWidget *parent = nullptr);
    void setRole(Role role);
    // Monospace digits for codes people compare (recovery code, safety number).
    void setMono(bool mono)
    {
        m_mono = mono;
        restyle();
    }

protected:
    void paintEvent(QPaintEvent *e) override;

private:
    void restyle();
    bool m_mono = false;
    Role m_role;
    qreal m_px;
    int m_weight;
};

class IconButton : public QAbstractButton {
    Q_OBJECT
public:
    IconButton(const QString &icon, const QString &label, QWidget *parent = nullptr, int size = 40);
    void setIcon(const QString &icon) { m_icon = icon; update(); }
    void setLabel(const QString &label);
    // Rail style: a filled tile marks the checked section.
    void setRail(bool rail) { m_rail = rail; }
    // Floating style: raised circle with a border and an accent icon.
    void setFloating(bool floating) { m_floating = floating; }
    QSize sizeHint() const override { return {m_size, m_size}; }

protected:
    void paintEvent(QPaintEvent *) override;
    void enterEvent(QEnterEvent *) override;
    void leaveEvent(QEvent *) override;
    void focusInEvent(QFocusEvent *e) override;
    void focusOutEvent(QFocusEvent *e) override;

private:
    QString m_icon;
    int m_size;
    bool m_rail = false;
    bool m_floating = false;
    bool m_keyboardFocus = false;
    QVariantAnimation m_hover;
};

class Button : public QAbstractButton {
    Q_OBJECT
public:
    enum Kind { Primary, Secondary, Danger, Flat };
    Button(const QString &text, Kind kind = Primary, QWidget *parent = nullptr);
    void setKind(Kind k) { m_kind = k; update(); }
    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent *) override;
    void enterEvent(QEnterEvent *) override;
    void leaveEvent(QEvent *) override;
    void focusInEvent(QFocusEvent *e) override;
    void focusOutEvent(QFocusEvent *e) override;

private:
    Kind m_kind;
    bool m_keyboardFocus = false;
    QVariantAnimation m_hover;
    QVariantAnimation m_press;
};

// Search box: icon, borderless line edit and an optional shortcut hint.
class SearchField : public QWidget {
    Q_OBJECT
public:
    SearchField(const QString &placeholder, const QString &hint = {}, QWidget *parent = nullptr);
    QLineEdit *edit() const { return m_edit; }

protected:
    void paintEvent(QPaintEvent *) override;
    bool eventFilter(QObject *o, QEvent *e) override;

private:
    QLineEdit *m_edit;
    QString m_hint;
};

// Text field with a floating label: inside when empty, above when focused or filled (180 ms).
class TextField : public QWidget {
    Q_OBJECT
public:
    TextField(const QString &label, QWidget *parent = nullptr);
    QLineEdit *edit() const { return m_edit; }
    QString text() const { return m_edit->text(); }
    void setText(const QString &t);
    void setPassword(bool on) { m_edit->setEchoMode(on ? QLineEdit::Password : QLineEdit::Normal); }
    void setLabel(const QString &label)
    {
        m_label = label;
        m_edit->setAccessibleName(label);
        update();
    }
    QSize sizeHint() const override { return {280, 54}; }

protected:
    void paintEvent(QPaintEvent *) override;
    void resizeEvent(QResizeEvent *) override;
    void mousePressEvent(QMouseEvent *) override;
    bool eventFilter(QObject *o, QEvent *e) override;

private:
    void updateFloat(bool animated);
    QLineEdit *m_edit;
    QString m_label;
    QVariantAnimation m_float;
};

// Several-line field with the same floating label; text longer than the limit is cut.
class TextArea : public QWidget {
    Q_OBJECT
public:
    TextArea(const QString &label, int lines = 3, QWidget *parent = nullptr);
    QPlainTextEdit *edit() const { return m_edit; }
    QString text() const { return m_edit->toPlainText(); }
    void setText(const QString &t);
    void setMaxLength(int n) { m_max = n; }
    QSize sizeHint() const override { return {280, height()}; }

signals:
    void textChanged(const QString &text);

protected:
    void paintEvent(QPaintEvent *) override;
    void resizeEvent(QResizeEvent *) override;
    void mousePressEvent(QMouseEvent *) override;
    bool eventFilter(QObject *o, QEvent *e) override;

private:
    void updateFloat(bool animated);
    QPlainTextEdit *m_edit;
    QString m_label;
    QVariantAnimation m_float;
    int m_max = 0;
};

// Settings slider: device -> day -> night. Drag, click, arrows, Home/End.
class ThemeSlider : public QWidget {
    Q_OBJECT
public:
    explicit ThemeSlider(QWidget *parent = nullptr);
    int value() const { return m_value; }
    void setValue(int v, bool animated = true);
    QSize sizeHint() const override { return {360, 74}; }

signals:
    void valueChanged(int value);

protected:
    void paintEvent(QPaintEvent *) override;
    void mousePressEvent(QMouseEvent *) override;
    void mouseMoveEvent(QMouseEvent *) override;
    void mouseReleaseEvent(QMouseEvent *) override;
    void keyPressEvent(QKeyEvent *) override;
    void focusInEvent(QFocusEvent *e) override;
    void focusOutEvent(QFocusEvent *e) override;

private:
    QRectF track() const;
    qreal xFor(qreal pos) const;
    int valueAt(qreal x) const;
    int m_value = 0;
    qreal m_pos = 0;
    bool m_drag = false;
    bool m_keyboardFocus = false;
    QVariantAnimation m_anim;
};

// On/off switch with a text label.
class Toggle : public QAbstractButton {
    Q_OBJECT
public:
    Toggle(const QString &text, QWidget *parent = nullptr);
    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent *) override;
    void checkStateSet() override;
    void nextCheckState() override;
    void focusInEvent(QFocusEvent *e) override;
    void focusOutEvent(QFocusEvent *e) override;

private:
    bool m_keyboardFocus = false;
    QVariantAnimation m_anim;
};

// Short message at the bottom of the window: 180 ms in, 3.2 s, 160 ms out.
class Toast : public QWidget {
    Q_OBJECT
public:
    explicit Toast(QWidget *host);
    void show(const QString &text);

protected:
    void paintEvent(QPaintEvent *) override;
    bool eventFilter(QObject *o, QEvent *e) override;

private:
    void place();
    QString m_text;
    QVariantAnimation m_anim;
    QTimer *m_timer;
};

// QR code drawn from the core's module rows ('1' = dark).
class QrView : public QWidget {
    Q_OBJECT
public:
    explicit QrView(QWidget *parent = nullptr);
    void setRows(const QStringList &rows);
    QSize sizeHint() const override { return {232, 232}; }

protected:
    void paintEvent(QPaintEvent *) override;

private:
    QStringList m_rows;
};

} // namespace ui
