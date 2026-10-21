#include "popupmenu.h"

#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QPainter>
#include <QScreen>
#include <QVBoxLayout>

namespace {
class MenuButton : public QAbstractButton {
public:
    MenuButton(const QString &text, QWidget *parent) : QAbstractButton(parent) {
        setText(text);
        setAccessibleName(text);
        setFocusPolicy(Qt::StrongFocus);
        setCursor(Qt::PointingHandCursor);
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        m_hover.setStartValue(0.0);
        connect(&m_hover, &QVariantAnimation::valueChanged, this, qOverload<>(&QWidget::update));
    }
    // A choice shows a tick for the current value and no action icon.
    void setChoice(bool current) { m_choice = true; m_current = current; }
    QSize sizeHint() const override { return {qMax(240, QFontMetrics(Theme::instance()->font(14)).horizontalAdvance(text()) + 64), 42}; }
protected:
    void enterEvent(QEnterEvent *) override { ui::animate(m_hover, 1, Theme::ms(140)); }
    void leaveEvent(QEvent *) override { ui::animate(m_hover, 0, Theme::ms(140)); }
    void paintEvent(QPaintEvent *) override {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const auto &c = Theme::p();
        QColor bg = c.raised;
        bg.setAlphaF(hasFocus() || isDown() ? 1 : m_hover.currentValue().toReal());
        p.setPen(Qt::NoPen);
        p.setBrush(bg);
        p.drawRoundedRect(rect(), 11, 11);
        const bool danger = text().startsWith(QStringLiteral("Удалить")) || text().startsWith(QStringLiteral("Очистить"));
        QColor ink = danger ? c.warning : c.text;
        if (!isEnabled()) ink.setAlphaF(0.4f);
        QString icon = "chat";
        if (m_choice) icon = m_current ? "tick" : QString();
        else if (text().contains(QStringLiteral("Ответ"))) icon = "reply";
        else if (text().contains(QStringLiteral("Копир"))) icon = "copy";
        else if (text().contains(QStringLiteral("Пересл"))) icon = "forward";
        else if (text().contains(QStringLiteral("закреп"), Qt::CaseInsensitive)) icon = "pin";
        else if (text().contains(QStringLiteral("архив"), Qt::CaseInsensitive)) icon = "archive";
        else if (danger) icon = "trash";
        else if (text().contains(QStringLiteral("Выб")) || text().contains(QStringLiteral("Выдел"))) icon = "tick";
        else if (text().contains(QStringLiteral("Вырез"))) icon = "copy";
        else if (text().contains(QStringLiteral("Встав"))) icon = "file";
        if (!icon.isEmpty()) ui::paintIcon(&p, QRectF(12, 11, 20, 20), icon, m_choice ? c.accent : ink, 1.7);
        p.setFont(Theme::instance()->font(14));
        p.setPen(ink);
        p.drawText(rect().adjusted(44, 0, -12, 0), Qt::AlignLeft | Qt::AlignVCenter, text());
        if (hasFocus()) ui::paintFocusRing(&p, rect().adjusted(1, 1, -1, -1), 11);
    }
private:
    QVariantAnimation m_hover;
    bool m_choice = false;
    bool m_current = false;
};

// Cells painted by hand; the arrow keys move the focus ring, Enter and Space pick.
class GridView : public QWidget {
public:
    GridView(const QStringList &items, const QString &current, int columns, std::function<void(const QString &)> pick, QWidget *parent)
        : QWidget(parent), m_items(items), m_cols(columns), m_pick(std::move(pick))
    {
        setFocusPolicy(Qt::StrongFocus);
        setMouseTracking(true);
        setProperty("ownKeys", true);
        setAccessibleName(QStringLiteral("Выбор реакции"));
        m_current = items.indexOf(current);
        m_focus = qMax(0, m_current);
        setFixedSize(m_cols * kCell, rows() * kCell);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }

protected:
    static constexpr int kCell = 54;
    int rows() const { return (m_items.size() + m_cols - 1) / m_cols; }
    QRect cell(int i) const { return {(i % m_cols) * kCell, (i / m_cols) * kCell, kCell, kCell}; }
    int indexAt(const QPoint &p) const
    {
        const int i = (p.y() / kCell) * m_cols + p.x() / kCell;
        return p.x() >= 0 && p.y() >= 0 && p.x() < m_cols * kCell && i < m_items.size() ? i : -1;
    }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const auto &c = Theme::p();
        QFont f(QStringLiteral("Segoe UI Emoji"));
        f.setPixelSize(28);
        p.setFont(f);
        for (int i = 0; i < m_items.size(); ++i) {
            const QRectF r = QRectF(cell(i)).adjusted(3, 3, -3, -3);
            if (i == m_current) {
                QColor bg = c.accent;
                bg.setAlphaF(0.22);
                p.setPen(QPen(c.accent, 1.5));
                p.setBrush(bg);
                p.drawRoundedRect(r, 13, 13);
            } else if (i == m_hover || (hasFocus() && i == m_focus)) {
                p.setPen(Qt::NoPen);
                p.setBrush(c.raised);
                p.drawRoundedRect(r, 13, 13);
            }
            p.setPen(c.text);
            p.drawText(r, Qt::AlignCenter, m_items[i]);
        }
        if (hasFocus() && m_focus >= 0)
            ui::paintFocusRing(&p, QRectF(cell(m_focus)).adjusted(2, 2, -2, -2), 14);
    }
    void mouseMoveEvent(QMouseEvent *e) override
    {
        const int i = indexAt(e->position().toPoint());
        if (i != m_hover) {
            m_hover = i;
            setCursor(i >= 0 ? Qt::PointingHandCursor : Qt::ArrowCursor);
            update();
        }
    }
    void leaveEvent(QEvent *) override { m_hover = -1; update(); }
    void mouseReleaseEvent(QMouseEvent *e) override
    {
        const int i = indexAt(e->position().toPoint());
        if (i >= 0) m_pick(m_items[i]);
    }
    void focusInEvent(QFocusEvent *) override { update(); }
    void focusOutEvent(QFocusEvent *) override { update(); }
    void keyPressEvent(QKeyEvent *e) override
    {
        int next = m_focus;
        switch (e->key()) {
        case Qt::Key_Left: next -= 1; break;
        case Qt::Key_Right: next += 1; break;
        case Qt::Key_Up: next -= m_cols; break;
        case Qt::Key_Down: next += m_cols; break;
        case Qt::Key_Return: case Qt::Key_Enter: case Qt::Key_Space:
            if (m_focus >= 0) m_pick(m_items[m_focus]);
            return;
        default: return QWidget::keyPressEvent(e);
        }
        if (next >= 0 && next < m_items.size()) {
            m_focus = next;
            update();
        }
    }

private:
    QStringList m_items;
    int m_cols;
    std::function<void(const QString &)> m_pick;
    int m_current = -1, m_hover = -1, m_focus = 0;
};
}

PopupMenu::PopupMenu(QWidget *parent) : QWidget(parent, Qt::Popup | Qt::FramelessWindowHint | Qt::NoDropShadowWindowHint)
{
    setObjectName("PopupMenu");
    setAttribute(Qt::WA_TranslucentBackground);
    m_opener = QApplication::focusWidget();
    m_layout = new QVBoxLayout(this);
    m_layout->setContentsMargins(16, 16, 16, 16);
    m_layout->setSpacing(2);
    m_anim.setEasingCurve(Theme::surfaceEasing());
    m_anim.setStartValue(0.0);
    connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        const qreal t = v.toReal();
        setWindowOpacity(t);
        move(m_target + QPoint(0, int((1 - t) * 8)));
    });
    connect(&m_anim, &QVariantAnimation::finished, this, [this] { if (m_closing) close(); });
}

QAction *PopupMenu::addAction(const QString &text, QObject *context, std::function<void()> run)
{
    auto *a = new QAction(text, this);
    QWidget::addAction(a);
    auto *b = new MenuButton(text, this);
    m_layout->addWidget(b);
    connect(a, &QAction::changed, b, [a, b] { b->setEnabled(a->isEnabled()); });
    connect(a, &QAction::triggered, context, std::move(run));
    connect(b, &QAbstractButton::clicked, this, [this, a] { dismiss(); a->trigger(); });
    return a;
}

QAction *PopupMenu::addChoice(const QString &text, bool current, QObject *context, std::function<void()> run)
{
    QAction *a = addAction(text, context, std::move(run));
    static_cast<MenuButton *>(m_layout->itemAt(m_layout->count() - 1)->widget())->setChoice(current);
    return a;
}

void PopupMenu::addGrid(const QStringList &items, const QString &current, int columns, std::function<void(const QString &)> pick)
{
    m_layout->addWidget(new GridView(items, current, columns, [this, pick = std::move(pick)](const QString &v) {
        dismiss();
        pick(v);
    }, this));
}

void PopupMenu::addWidget(QWidget *widget) { m_layout->addWidget(widget); }

void PopupMenu::addSeparator()
{
    auto *line = new QWidget(this);
    line->setFixedHeight(1);
    line->setAutoFillBackground(true);
    QPalette pal = line->palette();
    pal.setColor(QPalette::Window, Theme::p().border);
    line->setPalette(pal);
    m_layout->addSpacing(4);
    m_layout->addWidget(line);
    m_layout->addSpacing(4);
}

void PopupMenu::popup(const QPoint &globalPos)
{
    adjustSize();
    QScreen *screen = QGuiApplication::screenAt(globalPos);
    const QRect area = (screen ? screen : QGuiApplication::primaryScreen())->availableGeometry();
    m_target = QPoint(qBound(area.left(), globalPos.x() - 8, qMax(area.left(), area.right() - width() + 1)),
                      qBound(area.top(), globalPos.y() - 8, qMax(area.top(), area.bottom() - height() + 1)));
    move(m_target);
    setWindowOpacity(Theme::reducedMotion() ? 1 : 0);
    show();
    raise();
    if (auto *b = findChild<QAbstractButton *>()) {
        b->setFocus(Qt::PopupFocusReason);
    } else {
        for (auto *w : findChildren<QWidget *>())
            if (w->property("ownKeys").toBool()) {
                w->setFocus(Qt::PopupFocusReason);
                break;
            }
    }
    qApp->installEventFilter(this);
    m_anim.setDuration(qMax(1, Theme::ms(180)));
    m_anim.setEndValue(1.0);
    m_anim.start();
}

void PopupMenu::dismiss()
{
    if (m_closing) return;
    m_closing = true;
    setEnabled(false);
    qApp->removeEventFilter(this);
    m_anim.stop();
    m_anim.setStartValue(m_anim.currentValue());
    m_anim.setEndValue(0.0);
    m_anim.setDuration(qMax(1, Theme::ms(140)));
    m_anim.start();
}

void PopupMenu::hideEvent(QHideEvent *)
{
    qApp->removeEventFilter(this);
    QWidget *focus = QApplication::focusWidget();
    if (m_opener && m_opener->isVisible() && (!focus || focus == this || isAncestorOf(focus)))
        m_opener->setFocus(Qt::PopupFocusReason);
    deleteLater();
}

bool PopupMenu::eventFilter(QObject *, QEvent *e)
{
    if (e->type() == QEvent::MouseButtonPress && !geometry().contains(static_cast<QMouseEvent *>(e)->globalPosition().toPoint())) {
        dismiss();
        return true;
    }
    if (e->type() == QEvent::KeyPress) {
        auto *key = static_cast<QKeyEvent *>(e);
        if (key->key() == Qt::Key_Escape) { dismiss(); return true; }
        if (focusWidget() && focusWidget()->property("ownKeys").toBool())
            return false;
        if (key->key() == Qt::Key_Down || key->key() == Qt::Key_Up) {
            focusNextPrevChild(key->key() == Qt::Key_Down);
            return true;
        }
        if (key->key() == Qt::Key_Return || key->key() == Qt::Key_Enter) {
            if (auto *b = qobject_cast<QAbstractButton *>(focusWidget())) b->click();
            return true;
        }
    }
    return false;
}

void PopupMenu::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(Qt::NoPen);
    for (int i = 8; i > 0; --i) {
        p.setBrush(QColor(0, 0, 0, 3));
        p.drawRoundedRect(QRectF(rect()).adjusted(8 - i, 8 - i, i - 8, i - 6), 19 + i, 19 + i);
    }
    p.setPen(QPen(Theme::p().border, 1));
    p.setBrush(Theme::p().surface);
    p.drawRoundedRect(QRectF(rect()).adjusted(8.5, 8.5, -8.5, -8.5), 19, 19);
}
