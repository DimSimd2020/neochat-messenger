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
        if (text().contains(QStringLiteral("Ответ"))) icon = "reply";
        else if (text().contains(QStringLiteral("Копир"))) icon = "copy";
        else if (text().contains(QStringLiteral("Пересл"))) icon = "forward";
        else if (text().contains(QStringLiteral("закреп"), Qt::CaseInsensitive)) icon = "pin";
        else if (text().contains(QStringLiteral("архив"), Qt::CaseInsensitive)) icon = "archive";
        else if (danger) icon = "trash";
        else if (text().contains(QStringLiteral("Выб"))) icon = "tick";
        ui::paintIcon(&p, QRectF(12, 11, 20, 20), icon, ink, 1.7);
        p.setFont(Theme::instance()->font(14));
        p.setPen(ink);
        p.drawText(rect().adjusted(44, 0, -12, 0), Qt::AlignLeft | Qt::AlignVCenter, text());
        if (hasFocus()) ui::paintFocusRing(&p, rect().adjusted(1, 1, -1, -1), 11);
    }
private:
    QVariantAnimation m_hover;
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
    if (auto *b = findChild<QAbstractButton *>()) b->setFocus(Qt::PopupFocusReason);
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
