#include "composer.h"

#include "emoji.h"
#include "theme.h"
#include "widgets.h"

#include <QAbstractTextDocumentLayout>
#include <QGridLayout>
#include <QKeyEvent>
#include <QMimeData>
#include <QPushButton>
#include <QPainter>
#include <QScrollBar>
#include <QTimer>
#include <QUrl>

namespace {

constexpr int kShift = 51; // emoji moves left by this when the send button appears

} // namespace

class SendButton : public QAbstractButton {
public:
    explicit SendButton(QWidget *parent) : QAbstractButton(parent)
    {
        setCursor(Qt::PointingHandCursor);
        setFixedSize(40, 40);
        setAccessibleName(QStringLiteral("Отправить"));
        setToolTip(QStringLiteral("Отправить"));
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    qreal progress = 0;

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setOpacity(progress * (isEnabled() ? 1.0 : 0.45));
        const qreal s = 0.72 + 0.28 * progress;
        p.translate(width() / 2.0 + (1 - progress) * 10, height() / 2.0);
        p.scale(s, s);
        p.setPen(Qt::NoPen);
        p.setBrush(isDown() ? c.action.darker(110) : c.action);
        p.drawEllipse(QPointF(0, 0), 19, 19);
        ui::paintIcon(&p, QRectF(-11, -11, 22, 22), "send", c.onAccent, 2.1);
        if (hasFocus()) {
            p.setBrush(Qt::NoBrush);
            p.setPen(QPen(c.accent, 2));
            p.drawEllipse(QPointF(0, 0), 21, 21);
        }
    }
};

// ---- edit ----

void ComposerEdit::keyPressEvent(QKeyEvent *e)
{
    const bool enter = e->key() == Qt::Key_Return || e->key() == Qt::Key_Enter;
    if (enter) {
        const bool shift = e->modifiers() & Qt::ShiftModifier;
        const bool ctrl = e->modifiers() & Qt::ControlModifier;
        if ((enterSends && !shift && !ctrl) || (!enterSends && ctrl)) {
            emit submit();
            return;
        }
        insertPlainText("\n");
        return;
    }
    QPlainTextEdit::keyPressEvent(e);
}

bool ComposerEdit::canInsertFromMimeData(const QMimeData *source) const
{
    return source->hasUrls() || source->hasImage() || QPlainTextEdit::canInsertFromMimeData(source);
}

void ComposerEdit::insertFromMimeData(const QMimeData *source)
{
    if (source->hasUrls()) {
        QStringList files;
        for (const QUrl &u : source->urls())
            if (u.isLocalFile())
                files << u.toLocalFile();
        if (!files.isEmpty()) {
            emit filesDropped(files);
            return;
        }
    }
    if (source->hasImage()) {
        const QImage image = qvariant_cast<QImage>(source->imageData());
        if (!image.isNull()) {
            emit imagePasted(image);
            return;
        }
    }
    // Plain text only: no rich formatting is ever sent.
    if (source->hasText())
        insertPlainText(source->text());
}

// ---- composer ----

Composer::Composer(QWidget *parent) : QWidget(parent)
{
    m_edit = new ComposerEdit(this);
    m_edit->setFrameShape(QFrame::NoFrame);
    m_edit->setPlaceholderText(QStringLiteral("Написать сообщение…"));
    m_edit->setAccessibleName(QStringLiteral("Текст сообщения"));
    m_edit->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_edit->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    m_edit->setTabChangesFocus(true);
    m_edit->document()->setDocumentMargin(2);
    m_edit->setFont(Theme::instance()->font(15.5));
    m_attach = new ui::IconButton("attach", QStringLiteral("Прикрепить файл"), this, 40);
    m_emoji = new ui::IconButton("smile", QStringLiteral("Выбрать эмодзи"), this, 40);
    m_cancelReply = new ui::IconButton("close", QStringLiteral("Отменить ответ"), this, 30);
    m_cancelReply->hide();
    m_send = new SendButton(this);
    m_send->hide();
    m_send->setFocusPolicy(Qt::NoFocus);

    setTabOrder(m_attach, m_edit);
    setTabOrder(m_edit, m_emoji);
    setTabOrder(m_emoji, m_send);

    m_progress.setStartValue(0.0);
    connect(&m_progress, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        m_send->progress = v.toReal();
        relayout();
        m_send->update();
    });
    connect(&m_progress, &QVariantAnimation::finished, this, [this] {
        if (!m_hasText && !m_blocked)
            m_send->hide();
    });
    m_replyAnim.setStartValue(0.0);
    connect(&m_replyAnim, &QVariantAnimation::valueChanged, this, [this] {
        updateGeometry();
        relayout();
        update();
    });

    m_draftTimer = new QTimer(this);
    m_draftTimer->setSingleShot(true);
    m_draftTimer->setInterval(600);
    connect(m_draftTimer, &QTimer::timeout, this, [this] { emit draftChanged(text()); });

    m_edit->installEventFilter(this);
    connect(m_edit, &QPlainTextEdit::textChanged, this, &Composer::onTextChanged);
    connect(m_edit, &ComposerEdit::submit, this, &Composer::submit);
    connect(m_edit, &ComposerEdit::filesDropped, this, &Composer::filesDropped);
    connect(m_edit, &ComposerEdit::imagePasted, this, &Composer::imagePasted);
    connect(m_send, &QAbstractButton::clicked, this, &Composer::submit);
    connect(m_attach, &QAbstractButton::clicked, this, &Composer::attach);
    connect(m_emoji, &QAbstractButton::clicked, this, &Composer::openEmoji);
    connect(m_cancelReply, &QAbstractButton::clicked, this, &Composer::clearReply);
    auto applyColors = [this] {
        QPalette pal = m_edit->palette();
        pal.setColor(QPalette::Base, Qt::transparent);
        pal.setColor(QPalette::Text, Theme::p().text);
        pal.setColor(QPalette::PlaceholderText, Theme::p().muted);
        m_edit->setPalette(pal);
        update();
    };
    applyColors();
    connect(Theme::instance(), &Theme::changed, this, applyColors);
    connect(Theme::instance(), &Theme::fontChanged, this, [this] {
        m_edit->setFont(Theme::instance()->font(15.5));
        updateGeometry();
        relayout();
    });
    connect(m_edit->document()->documentLayout(), &QAbstractTextDocumentLayout::documentSizeChanged, this, [this] {
        updateGeometry();
        relayout();
    });
}

int Composer::editHeight() const
{
    const QFontMetrics fm(m_edit->font());
    const int lines = qBound(1, int(m_edit->document()->documentLayout()->documentSize().height() / fm.lineSpacing() + 0.5), 5);
    return lines * fm.lineSpacing() + 8;
}

QSize Composer::sizeHint() const
{
    const qreal reply = m_replyAnim.currentValue().isValid() ? m_replyAnim.currentValue().toReal() : 0;
    const int box = qMax(56, editHeight() + 22);
    return {400, box + 12 + 8 + int(reply * 50)};
}

QString Composer::text() const
{
    return m_edit->toPlainText();
}

void Composer::setDraft(const QString &text)
{
    m_settingDraft = true;
    m_edit->setPlainText(text);
    m_edit->moveCursor(QTextCursor::End);
    m_settingDraft = false;
    m_hasText = !text.trimmed().isEmpty();
    updateSendState(false);
}

void Composer::onTextChanged()
{
    const bool has = !m_edit->toPlainText().trimmed().isEmpty();
    if (has != m_hasText) {
        m_hasText = has;
        updateSendState(!m_settingDraft);
    }
    if (!m_settingDraft) {
        m_draftTimer->start();
        if (has)
            emit typing();
    }
}

void Composer::setBlocked(bool blocked)
{
    m_blocked = blocked;
    updateSendState(false);
}

void Composer::updateSendState(bool animated)
{
    const bool show = m_hasText;
    m_send->setEnabled(!m_blocked);
    m_send->setFocusPolicy(show && !m_blocked ? Qt::StrongFocus : Qt::NoFocus);
    m_send->setAccessibleDescription(m_blocked ? QStringLiteral("Отправка приостановлена: ключ собеседника изменился") : QString());
    if (show)
        m_send->show();
    ui::animate(m_progress, show ? 1.0 : 0.0, animated ? Theme::ms(240) : 0, Theme::surfaceEasing());
    if (!show && !animated)
        m_send->hide();
}

void Composer::submit()
{
    const QString t = m_edit->toPlainText().trimmed();
    if (t.isEmpty() || m_blocked)
        return;
    emit send(t, replyId());
}

void Composer::setReply(const QJsonObject &msg)
{
    m_reply = msg;
    m_cancelReply->show();
    ui::animate(m_replyAnim, 1, Theme::ms(180));
    focusInput();
}

void Composer::clearReply()
{
    if (m_reply.isEmpty())
        return;
    m_reply = {};
    m_cancelReply->hide();
    ui::animate(m_replyAnim, 0, Theme::ms(180));
}

void Composer::openEmoji()
{
    auto *popup = new EmojiPopup(this, [this](const QString &e) {
        m_edit->insertPlainText(e);
        m_edit->setFocus(Qt::OtherFocusReason);
    });
    popup->popup(m_emoji->mapToGlobal(QPoint(m_emoji->width(), 0)));
}

void Composer::resizeEvent(QResizeEvent *)
{
    relayout();
}

void Composer::relayout()
{
    const qreal reply = m_replyAnim.currentValue().isValid() ? m_replyAnim.currentValue().toReal() : 0;
    const int side = width() < 560 ? 12 : 24;
    const int replyH = int(reply * 50);
    const QRect box(side, 8, width() - 2 * side, height() - 8 - 12);
    const QRect inner(box.left(), box.top() + replyH, box.width(), box.height() - replyH);
    const int cy = box.bottom() - 28;
    m_attach->move(box.left() + 10, cy - 20 + 1);
    const qreal p = m_send->progress;
    const int sendX = box.right() - 10 - 40;
    m_send->move(sendX, cy - 20 + 1);
    m_emoji->move(int(sendX - p * kShift), cy - 20 + 1);
    const int left = box.left() + 58;
    const int right = int(sendX - p * kShift) - 6;
    const int eh = editHeight();
    m_edit->setGeometry(left, inner.top() + (inner.height() - eh) / 2, right - left, eh);
    m_cancelReply->move(box.right() - 40, box.top() + 12);
    m_cancelReply->setVisible(replyH > 30);
}

void Composer::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const qreal reply = m_replyAnim.currentValue().isValid() ? m_replyAnim.currentValue().toReal() : 0;
    const int side = width() < 560 ? 12 : 24;
    const int replyH = int(reply * 50);
    const QRectF box(side + 0.5, 8.5, width() - 2 * side - 1, height() - 8 - 12 - 1);
    p.setPen(QPen(m_edit->hasFocus() ? c.accent : c.border, 1));
    p.setBrush(c.surface);
    p.drawRoundedRect(box, 20, 20);
    if (replyH > 0) {
        p.setOpacity(reply);
        const QRectF r(side + 16, 8 + 8, width() - 2 * side - 32, 42);
        p.setPen(Qt::NoPen);
        p.setBrush(c.accent);
        p.drawRoundedRect(QRectF(r.left(), r.top() + 4, 3, r.height() - 8), 1.5, 1.5);
        p.setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p.setPen(c.accent);
        p.drawText(r.adjusted(14, 2, -44, -20), Qt::AlignLeft | Qt::AlignVCenter, QStringLiteral("В ответ ") + m_reply.value("sender_name").toString());
        p.setFont(Theme::instance()->font(13));
        p.setPen(c.muted);
        const QString t = m_reply.value("kind").toString() == "file" ? m_reply.value("file").toObject().value("name").toString() : m_reply.value("text").toString().simplified();
        p.drawText(r.adjusted(14, 20, -44, -2), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(t, Qt::ElideRight, int(r.width()) - 60));
        p.setOpacity(1);
    }
}

bool Composer::eventFilter(QObject *o, QEvent *e)
{
    if (o == m_edit && (e->type() == QEvent::FocusIn || e->type() == QEvent::FocusOut))
        update();
    return QWidget::eventFilter(o, e);
}
