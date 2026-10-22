#include "sheet.h"

#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QGraphicsOpacityEffect>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QPainter>
#include <QScrollArea>
#include <QVBoxLayout>

namespace {

// Fills `core` with `color`; around it the color thins out over `soft` px into what is below.
void paintSoftPanel(QPainter &p, const QRect &core, const QColor &color)
{
    constexpr int soft = 12;
    constexpr int radius = 12;
    p.setPen(Qt::NoPen);
    p.save();
    // Inside the core every layer is covered by the opaque fill below; only the rim needs them.
    p.setClipRegion(QRegion(core.adjusted(-soft - 1, -soft - 1, soft + 1, soft + 1))
                        .subtracted(QRegion(core.adjusted(radius, radius, -radius, -radius))));
    qreal reached = 0;
    for (int i = soft; i > 0; --i) {
        const qreal t = qreal(soft + 1 - i) / (soft + 1);
        const qreal want = t * t * (3 - 2 * t);
        QColor layer = color;
        layer.setAlphaF((want - reached) / (1 - reached));
        p.setBrush(layer);
        p.drawRoundedRect(QRectF(core).adjusted(-i, -i, i, i), radius + i, radius + i);
        reached = want;
    }
    p.restore();
    p.setBrush(color);
    p.drawRoundedRect(QRectF(core), radius, radius);
}

class Card : public QWidget {
public:
    using QWidget::QWidget;
    QWidget *body = nullptr;

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(c.border, 1));
        p.setBrush(c.surface);
        p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 20, 20);
        if (body)
            paintSoftPanel(p, body->geometry(), c.canvas);
    }
};

// The body scrolls when the dialog is taller than the window, so rows are never squeezed
// into each other; below that height the card simply fits its content.
class BodyScroll : public QScrollArea {
public:
    explicit BodyScroll(QWidget *parent) : QScrollArea(parent)
    {
        setWidgetResizable(true);
        setFrameShape(QFrame::NoFrame);
        setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        viewport()->setAutoFillBackground(false);
    }
    bool hasHeightForWidth() const override { return true; }
    int heightForWidth(int w) const override
    {
        const QWidget *c = widget();
        // A layout without wrapped rows has no height for width (-1): its size hint is the height then.
        const int h = c && c->layout() ? c->layout()->totalHeightForWidth(w) : -1;
        return h >= 0 ? h : (c ? c->sizeHint().height() : 0);
    }
    QSize sizeHint() const override { return widget() ? widget()->sizeHint() : QSize(); }
    QSize minimumSizeHint() const override { return {0, 96}; }

protected:
    // Rows added or removed inside the body resize the dialog.
    bool eventFilter(QObject *o, QEvent *e) override
    {
        if (o == widget() && e->type() == QEvent::LayoutRequest)
            updateGeometry();
        return QScrollArea::eventFilter(o, e);
    }
};

} // namespace

Sheet::Sheet(QWidget *host, const QString &title, int width) : QWidget(host), m_width(width)
{
    setObjectName("Sheet");
    m_opener = QApplication::focusWidget();
    setFocusPolicy(Qt::StrongFocus);
    auto *card = new Card(this);
    m_card = card;
    auto *outer = new QVBoxLayout(m_card);
    outer->setContentsMargins(24, 18, 24, 24);
    outer->setSpacing(14);
    auto *head = new QHBoxLayout;
    m_head = head;
    m_title = new ui::Label(title, 18, QFont::DemiBold);
    m_title->setAccessibleName(title);
    auto *close = new ui::IconButton("close", QStringLiteral("Закрыть"), m_card, 36);
    connect(close, &QAbstractButton::clicked, this, &Sheet::close);
    head->addWidget(m_title, 1);
    head->addWidget(close);
    outer->addLayout(head);
    auto *scroll = new BodyScroll(m_card);
    auto *content = new QWidget;
    m_body = new QVBoxLayout(content);
    m_body->setContentsMargins(0, 0, 0, 0);
    m_body->setSpacing(10);
    scroll->setWidget(content);
    // setWidget turns the fill back on; the card paints the body's backdrop.
    content->setAutoFillBackground(false);
    content->installEventFilter(scroll);
    outer->addWidget(scroll, 1);
    card->body = scroll;
    m_anim.setStartValue(0.0);
    m_anim.setEasingCurve(Theme::surfaceEasing());
    connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) { step(v.toReal()); });
    connect(&m_anim, &QVariantAnimation::finished, this, [this] {
        if (m_closing)
            finish();
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

void Sheet::setBack(std::function<void()> to)
{
    if (!m_back) {
        m_back = new ui::IconButton("back", QStringLiteral("Назад"), m_card, 36);
        m_head->insertWidget(0, m_back);
        m_head->insertSpacing(1, 6);
    }
    m_back->disconnect(this);
    connect(m_back, &QAbstractButton::clicked, this, [this, to = std::move(to)] {
        if (m_closing || m_busy)
            return;
        close();
        to();
    });
}

void Sheet::open()
{
    setGeometry(parentWidget()->rect());
    layoutCard();
    show();
    raise();
    setAccessibleName(m_title->text());
    // The back or close button is the first focus stop; fields are not focused automatically.
    QWidget *first = m_card->findChild<ui::IconButton *>();
    if (first)
        first->setFocus(Qt::OtherFocusReason);
    qApp->installEventFilter(this);
    // A sheet replacing a closing one keeps its dim and removes it at once, so the backdrop
    // never brightens in between and the old card never shows around the new one.
    for (auto *s : parentWidget()->findChildren<Sheet *>(QString(), Qt::FindDirectChildrenOnly)) {
        if (s != this && s->m_closing) {
            m_dimFloor = qMax(m_dimFloor, qMax(s->m_t, s->m_dimFloor));
            s->finish();
        }
    }
    // The card is opaque from the first frame. Only the dim and the 10 px rise animate,
    // so the screen underneath never shows through the new window.
    step(0);
    m_anim.setDuration(qMax(1, Theme::ms(220)));
    m_anim.setStartValue(0.0);
    m_anim.setEndValue(1.0);
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
    m_card->setGraphicsEffect(new QGraphicsOpacityEffect(m_card));
    const qreal from = qMax(m_t, m_dimFloor);
    m_dimFloor = 0;
    m_anim.stop();
    m_anim.setStartValue(from);
    m_anim.setEndValue(0.0);
    m_anim.setDuration(qMax(1, int(Theme::ms(220) * from)));
    m_anim.start();
}

void Sheet::finish()
{
    m_anim.stop();
    hide();
    emit closed();
    deleteLater();
}

void Sheet::step(qreal t)
{
    m_t = t;
    if (auto *fade = qobject_cast<QGraphicsOpacityEffect *>(m_card->graphicsEffect()))
        fade->setOpacity(t);
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
    const qreal shift = (1 - m_t) * 10;
    m_card->setGeometry((width() - w) / 2, (height() - h) / 2 + int(shift), w, h);
}

void Sheet::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.fillRect(rect(), QColor(0, 0, 0, int(110 * qMax(m_t, m_dimFloor))));
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
