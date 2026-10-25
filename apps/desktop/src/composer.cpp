#include "composer.h"

#include "audioio.h"
#include "emoji.h"
#include "theme.h"
#include "widgets.h"
#include "core.h"
#include <QCompleter>
#include <QStringListModel>
#include <QRegularExpression>
#include <QAbstractItemView>
#include <QJsonArray>

#include <QAbstractTextDocumentLayout>
#include <QGridLayout>
#include <QKeyEvent>
#include <QMimeData>
#include <QPushButton>
#include <QPainter>
#include <QPointer>
#include <cmath>
#include <QScrollBar>
#include <QTimer>
#include <QUrl>

namespace {

constexpr int kShift = 51; // emoji moves left by this when the send button appears
constexpr int kRecordSlots = 80; // the circle and microphone buttons take this much room in front of the arrow
constexpr qint64 kMaxVoiceMs = 10 * 60 * 1000;

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
    m_mentions = new QCompleter(new QStringListModel(this), this);
    m_mentions->setWidget(m_edit);
    m_mentions->setCaseSensitivity(Qt::CaseInsensitive);
    connect(m_mentions, qOverload<const QString &>(&QCompleter::activated), this, [this](const QString &name) {
        auto cursor = m_edit->textCursor();
        const QString prefix = m_edit->toPlainText().left(cursor.position());
        const int at = prefix.lastIndexOf('@');
        if (at < 0) return;
        cursor.setPosition(at, QTextCursor::KeepAnchor);
        cursor.insertText(name + " ");
        m_edit->setTextCursor(cursor);
    });
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
    m_mic = new ui::IconButton("mic", QStringLiteral("Записать голосовое сообщение"), this, 40);
    m_circle = new ui::IconButton("record", QStringLiteral("Записать кружок"), this, 40);
    m_recTrash = new ui::IconButton("trash", QStringLiteral("Удалить запись"), this, 40);
    m_recTrash->hide();
    m_recTick.setInterval(60);
    connect(&m_recTick, &QTimer::timeout, this, [this] {
        if (m_rec && m_rec->elapsedMs() >= kMaxVoiceMs)
            finishVoice();
        update();
    });
    connect(m_mic, &QAbstractButton::clicked, this, &Composer::startVoice);
    connect(m_circle, &QAbstractButton::clicked, this, [this] {
        if (!m_blocked)
            emit circleRequested();
    });
    connect(m_recTrash, &QAbstractButton::clicked, this, &Composer::cancelRecording);

    setTabOrder(m_attach, m_edit);
    setTabOrder(m_edit, m_emoji);
    setTabOrder(m_emoji, m_circle);
    setTabOrder(m_circle, m_mic);
    setTabOrder(m_mic, m_send);

    m_progress.setStartValue(0.0);
    connect(&m_progress, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        m_send->progress = v.toReal();
        relayout();
        m_send->update();
    });
    connect(&m_progress, &QVariantAnimation::finished, this, [this] {
        if (!m_hasText && !m_blocked)
            m_send->hide();
        updateRecordButtons();
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
    const QString prefix = m_edit->toPlainText().left(m_edit->textCursor().position());
    const auto mention = QRegularExpression("(?:^|\\s)(@[A-Za-z0-9_]*)$").match(prefix);
    if (!m_settingDraft && !m_blocked && mention.hasMatch()) {
        m_mentions->setCompletionPrefix(mention.captured(1));
        if (m_mentions->completionCount() > 0) {
            auto rect = m_edit->cursorRect();
            rect.setWidth(220);
            m_mentions->complete(rect);
        } else m_mentions->popup()->hide();
    } else m_mentions->popup()->hide();
    const bool has = !m_edit->toPlainText().trimmed().isEmpty();
    if (has != m_hasText) {
        m_hasText = has;
        updateSendState(!m_settingDraft);
    }
    if (!m_settingDraft && !m_editing) {
        m_draftTimer->start();
        if (has)
            emit typing();
    }
}

void Composer::setConversation(const QString &id)
{
    m_conversationId = id;
    m_mentions->popup()->hide();
    static_cast<QStringListModel *>(m_mentions->model())->setStringList({});
    if (id.isEmpty()) return;
    Core::instance()->call("mention_candidates", {{"conversation_id",id}}, this, [this,id](const QJsonValue &v,const CoreError &e) {
        if (!e.ok() || m_conversationId != id) return;
        QStringList names;
        for (const auto &person : v.toArray()) names << "@" + person.toObject().value("username").toString();
        static_cast<QStringListModel *>(m_mentions->model())->setStringList(names);
    });
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
    updateRecordButtons();
}

// The circle and microphone stand where the arrow will be as soon as there is text to send.
void Composer::updateRecordButtons()
{
    const bool idle = !m_hasText && !m_editing && !m_rec && m_progress.currentValue().toReal() < 0.01;
    m_mic->setVisible(idle);
    m_circle->setVisible(idle);
    m_mic->setEnabled(!m_blocked);
    m_circle->setEnabled(!m_blocked);
    relayout();
}

void Composer::startVoice()
{
    if (m_rec || m_blocked || m_editing)
        return;
    auto *rec = new VoiceRecorder(this);
    QString why;
    if (!rec->start(&why)) {
        delete rec;
        emit notice(why);
        return;
    }
    m_rec = rec;
    m_levels.clear();
    connect(rec, &VoiceRecorder::level, this, [this](qreal v) {
        m_levels.push_back(v);
        if (m_levels.size() > 200)
            m_levels.erase(m_levels.begin(), m_levels.begin() + 100);
    });
    connect(rec, &VoiceRecorder::failed, this, [this](const QString &why) {
        cancelRecording();
        emit notice(why);
    });
    connect(rec, &VoiceRecorder::finished, this, [this](const QString &path, qint64 ms, const QByteArray &wave) {
        emit voiceRecorded(path, ms, wave, replyId());
    });
    m_edit->hide();
    m_attach->hide();
    m_emoji->hide();
    m_recTrash->show();
    m_send->progress = 1;
    m_send->show();
    m_send->setEnabled(true);
    m_send->setFocusPolicy(Qt::NoFocus);
    setFocus(Qt::OtherFocusReason);
    updateRecordButtons();
    m_recTick.start();
}

void Composer::finishVoice()
{
    if (!m_rec)
        return;
    VoiceRecorder *rec = m_rec;
    m_rec = nullptr;
    m_recTick.stop();
    rec->stop(); // emits finished()
    rec->deleteLater();
    m_edit->show();
    m_attach->show();
    m_emoji->show();
    m_recTrash->hide();
    m_send->progress = m_hasText ? 1.0 : 0.0;
    m_send->setVisible(m_hasText);
    updateSendState(false);
    m_edit->setFocus(Qt::OtherFocusReason);
    update();
}

void Composer::cancelRecording()
{
    if (!m_rec)
        return;
    VoiceRecorder *rec = m_rec;
    m_rec = nullptr;
    m_recTick.stop();
    rec->cancel();
    rec->deleteLater();
    m_edit->show();
    m_attach->show();
    m_emoji->show();
    m_recTrash->hide();
    m_send->progress = m_hasText ? 1.0 : 0.0;
    m_send->setVisible(m_hasText);
    updateSendState(false);
    update();
}

void Composer::keyPressEvent(QKeyEvent *e)
{
    if (m_rec && (e->key() == Qt::Key_Return || e->key() == Qt::Key_Enter))
        return finishVoice();
    if (m_rec && e->key() == Qt::Key_Escape)
        return cancelRecording();
    QWidget::keyPressEvent(e);
}

void Composer::submit()
{
    if (m_rec)
        return finishVoice();
    const QString t = m_edit->toPlainText().trimmed();
    if (m_blocked)
        return;
    if (m_editing) {
        // A photo or file may lose its caption; a text message may not become empty.
        if (t.isEmpty() && m_reply.value("kind").toString() != "file")
            return;
        emit edited(m_reply.value("id").toString(), t);
        return;
    }
    if (t.isEmpty())
        return;
    emit send(t, replyId());
}

void Composer::setReply(const QJsonObject &msg)
{
    clearReply();
    m_reply = msg;
    m_cancelReply->show();
    ui::animate(m_replyAnim, 1, Theme::ms(180));
    focusInput();
}

void Composer::setEdit(const QJsonObject &msg)
{
    const QString saved = m_editing ? m_savedDraft : m_edit->toPlainText();
    clearReply();
    m_savedDraft = saved;
    m_editing = true;
    m_reply = msg;
    m_cancelReply->show();
    setDraft(msg.value("text").toString());
    ui::animate(m_replyAnim, 1, Theme::ms(180));
    focusInput();
}

void Composer::clearReply()
{
    if (m_editing) {
        // Leaving edit mode puts back what was typed before.
        m_editing = false;
        setDraft(m_savedDraft);
        m_savedDraft.clear();
    }
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
    m_mic->move(sendX, cy - 20 + 1);
    m_circle->move(sendX - 40, cy - 20 + 1);
    m_recTrash->move(box.left() + 10, cy - 20 + 1);
    const int emojiX = int(sendX - (1 - p) * kRecordSlots - p * kShift);
    m_emoji->move(emojiX, cy - 20 + 1);
    const int left = box.left() + 58;
    const int right = emojiX - 6;
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
    if (m_rec) {
        const int cy = int(box.bottom()) - 28;
        const qint64 ms = m_rec->elapsedMs();
        const qreal pulse = 0.55 + 0.45 * std::sin(double(ms) / 1000.0 * 2 * M_PI * 0.9);
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(224, 64, 64));
        p.setOpacity(pulse);
        p.drawEllipse(QPointF(box.left() + 68, cy + 1), 5, 5);
        p.setOpacity(1);
        p.setFont(Theme::instance()->font(15, QFont::DemiBold));
        p.setPen(c.text);
        const qint64 sec = ms / 1000;
        const QString time = QStringLiteral("%1:%2").arg(sec / 60).arg(sec % 60, 2, 10, QChar('0'));
        const QRectF tr(box.left() + 82, cy - 12, 52, 26);
        p.drawText(tr, Qt::AlignLeft | Qt::AlignVCenter, time);
        const qreal x0 = tr.right() + 10, x1 = box.right() - 10 - 40 - 12;
        p.setPen(Qt::NoPen);
        p.setBrush(c.accent);
        const int n = int((x1 - x0) / 6);
        for (int i = 0; i < n && i < int(m_levels.size()); ++i) {
            const qreal v = m_levels[m_levels.size() - 1 - size_t(i)];
            const qreal h = 3 + std::min(1.0, std::pow(v, 0.6)) * 22;
            p.drawRoundedRect(QRectF(x1 - 3 - i * 6, cy + 1 - h / 2, 3, h), 1.5, 1.5);
        }
        return;
    }
    if (replyH > 0) {
        p.setOpacity(reply);
        const QRectF r(side + 16, 8 + 8, width() - 2 * side - 32, 42);
        p.setPen(Qt::NoPen);
        p.setBrush(c.accent);
        p.drawRoundedRect(QRectF(r.left(), r.top() + 4, 3, r.height() - 8), 1.5, 1.5);
        p.setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p.setPen(c.accent);
        p.drawText(r.adjusted(14, 2, -44, -20), Qt::AlignLeft | Qt::AlignVCenter, m_editing ? QStringLiteral("Изменение сообщения") : QStringLiteral("В ответ ") + m_reply.value("sender_name").toString());
        p.setFont(Theme::instance()->font(13));
        p.setPen(c.muted);
        const QJsonObject rf = m_reply.value("file").toObject();
        const QString t = m_reply.value("kind").toString() == "file" && !m_editing
            ? (rf.value("voice").toBool() ? QStringLiteral("Голосовое сообщение") : rf.value("circle").toBool() ? QStringLiteral("Видеосообщение") : rf.value("name").toString())
            : m_reply.value("text").toString().simplified();
        p.drawText(r.adjusted(14, 20, -44, -2), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(t, Qt::ElideRight, int(r.width()) - 60));
        p.setOpacity(1);
    }
}

bool Composer::eventFilter(QObject *o, QEvent *e)
{
    if (o == m_edit && (e->type() == QEvent::FocusIn || e->type() == QEvent::FocusOut))
        update();
    if (o == m_edit && m_editing && e->type() == QEvent::KeyPress && static_cast<QKeyEvent *>(e)->key() == Qt::Key_Escape) {
        clearReply();
        return true;
    }
    return QWidget::eventFilter(o, e);
}
