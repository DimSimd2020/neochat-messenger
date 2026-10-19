#include "sheet.h"

#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QGraphicsOpacityEffect>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QPainter>
#include <QVBoxLayout>

namespace {

class Card : public QWidget {
public:
    using QWidget::QWidget;

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(c.border, 1));
        p.setBrush(c.surface);
        p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 20, 20);
    }
};

} // namespace

Sheet::Sheet(QWidget *host, const QString &title, int width) : QWidget(host), m_width(width)
{
    setObjectName("Sheet");
    m_opener = QApplication::focusWidget();
    setFocusPolicy(Qt::StrongFocus);
    m_card = new Card(this);
    auto *outer = new QVBoxLayout(m_card);
    outer->setContentsMargins(24, 18, 24, 24);
    outer->setSpacing(14);
    auto *head = new QHBoxLayout;
    m_title = new ui::Label(title, 18, QFont::DemiBold);
    m_title->setAccessibleName(title);
    auto *close = new ui::IconButton("close", QStringLiteral("Закрыть"), m_card, 36);
    connect(close, &QAbstractButton::clicked, this, &Sheet::close);
    head->addWidget(m_title, 1);
    head->addWidget(close);
    outer->addLayout(head);
    m_body = new QVBoxLayout;
    m_body->setSpacing(10);
    outer->addLayout(m_body, 1);
    m_fade = new QGraphicsOpacityEffect(m_card);
    m_fade->setOpacity(0);
    m_card->setGraphicsEffect(m_fade);
    m_anim.setStartValue(0.0);
    connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) { step(v.toReal()); });
    connect(&m_anim, &QVariantAnimation::finished, this, [this] {
        if (m_closing) {
            hide();
            emit closed();
            deleteLater();
        } else {
            // Drop the effect when idle: it would otherwise re-render the card on every update.
            m_card->setGraphicsEffect(nullptr);
            m_fade = nullptr;
        }
    });
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    host->installEventFilter(this);
    m_card->installEventFilter(this);
    hide();
}

void Sheet::setTitle(const QString &title)
{
    m_title->setText(title);
}

void Sheet::open()
{
    setGeometry(parentWidget()->rect());
    layoutCard();
    show();
    raise();
    setAccessibleName(m_title->text());
    // The close button is the first focus stop; fields are not focused automatically.
    QWidget *first = m_card->findChild<ui::IconButton *>();
    if (first)
        first->setFocus(Qt::OtherFocusReason);
    qApp->installEventFilter(this);
    m_anim.setDuration(qMax(1, Theme::ms(220)));
    m_anim.setStartValue(0.0);
    m_anim.setEndValue(1.0);
    m_anim.setEasingCurve(QEasingCurve::Linear);
    m_anim.start();
}

void Sheet::close()
{
    if (m_closing || m_busy)
        return;
    m_closing = true;
    qApp->removeEventFilter(this);
    // A closing surface no longer takes input; focus goes back at once.
    setAttribute(Qt::WA_TransparentForMouseEvents);
    if (m_opener && m_opener->isVisible())
        m_opener->setFocus(Qt::OtherFocusReason);
    if (!m_fade) {
        m_fade = new QGraphicsOpacityEffect(m_card);
        m_card->setGraphicsEffect(m_fade);
    }
    m_anim.stop();
    m_anim.setStartValue(m_t);
    m_anim.setEndValue(0.0);
    m_anim.setDuration(qMax(1, int(Theme::ms(220) * m_t)));
    m_anim.start();
}

void Sheet::step(qreal t)
{
    m_t = t;
    // Opacity completes at 180 of 220 ms; movement uses the surface curve.
    if (m_fade)
        m_fade->setOpacity(qMin(1.0, t * 220.0 / 180.0));
    layoutCard();
    update();
}

void Sheet::layoutCard()
{
    const int w = qMin(m_width, width() - 32);
    const int maxH = height() - 64;
    m_card->setFixedWidth(w);
    m_card->setMaximumHeight(maxH);
    // Wrapped labels need height-for-width, not the plain size hint.
    const int want = m_card->hasHeightForWidth() ? m_card->heightForWidth(w) : m_card->sizeHint().height();
    const int h = qMin(maxH, qMax(want, m_card->minimumSizeHint().height()));
    const qreal shift = (1 - Theme::surfaceEasing().valueForProgress(m_t)) * 10;
    m_card->setGeometry((width() - w) / 2, (height() - h) / 2 + int(shift), w, h);
}

void Sheet::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.fillRect(rect(), QColor(0, 0, 0, int(110 * m_t)));
}

void Sheet::mousePressEvent(QMouseEvent *e)
{
    m_pressOutside = !m_card->geometry().contains(e->position().toPoint());
}

void Sheet::mouseReleaseEvent(QMouseEvent *e)
{
    // Text selection that started inside and ended outside is not an outside click.
    if (m_pressOutside && !m_card->geometry().contains(e->position().toPoint()))
        close();
    m_pressOutside = false;
}

void Sheet::keyPressEvent(QKeyEvent *e)
{
    if (e->key() == Qt::Key_Escape) {
        close();
        return;
    }
    QWidget::keyPressEvent(e);
}

bool Sheet::eventFilter(QObject *o, QEvent *e)
{
    if (o == parentWidget() && e->type() == QEvent::Resize) {
        setGeometry(parentWidget()->rect());
        layoutCard();
    }
    if (e->type() == QEvent::LayoutRequest && o == m_card)
        layoutCard();
    // Keep Tab focus inside the topmost open sheet.
    if (e->type() == QEvent::FocusIn && !m_closing && o->isWidgetType()) {
        auto *w = static_cast<QWidget *>(o);
        const auto sheets = parentWidget()->findChildren<Sheet *>(QString(), Qt::FindDirectChildrenOnly);
        const bool topmost = !sheets.isEmpty() && sheets.last() == this;
        if (topmost && w->window() == window() && w != this && !isAncestorOf(w)) {
            if (auto *first = m_card->findChild<ui::IconButton *>())
                first->setFocus(Qt::TabFocusReason);
        }
    }
    return QWidget::eventFilter(o, e);
}

void Sheet::closeAll(QWidget *host)
{
    for (auto *s : host->findChildren<Sheet *>(QString(), Qt::FindDirectChildrenOnly))
        s->close();
}

bool Sheet::anyOpen(QWidget *host)
{
    for (auto *s : host->findChildren<Sheet *>(QString(), Qt::FindDirectChildrenOnly))
        if (!s->m_closing)
            return true;
    return false;
}
