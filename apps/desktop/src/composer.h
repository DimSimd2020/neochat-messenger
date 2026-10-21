#pragma once

#include <QJsonObject>
#include <QPlainTextEdit>
#include <QVariantAnimation>
#include <QWidget>

namespace ui {
class IconButton;
}
class SendButton;

class ComposerEdit : public QPlainTextEdit {
    Q_OBJECT
public:
    using QPlainTextEdit::QPlainTextEdit;
    bool enterSends = true;

signals:
    void submit();
    void filesDropped(const QStringList &paths);
    void imagePasted(const QImage &image);

protected:
    void keyPressEvent(QKeyEvent *e) override;
    void insertFromMimeData(const QMimeData *source) override;
    bool canInsertFromMimeData(const QMimeData *source) const override;
};

// Message input: attach, text, emoji; the send arrow appears with the first
// non-space character (240 ms) and is absent from Tab order while hidden.
class Composer : public QWidget {
    Q_OBJECT
public:
    explicit Composer(QWidget *parent = nullptr);
    void setDraft(const QString &text);
    QString text() const;
    void setBlocked(bool blocked);
    void setReply(const QJsonObject &msg);
    void clearReply();
    // Edit mode: the field holds the message text; send changes the message instead of sending a new one.
    void setEdit(const QJsonObject &msg);
    bool editing() const { return m_editing; }
    QString replyId() const { return m_editing ? QString() : m_reply.value("id").toString(); }
    void setEnterSends(bool on) { m_edit->enterSends = on; }
    void focusInput() { m_edit->setFocus(Qt::OtherFocusReason); }
    QSize sizeHint() const override;
    QSize minimumSizeHint() const override { return sizeHint(); }

signals:
    void send(const QString &text, const QString &replyTo);
    void edited(const QString &id, const QString &text);
    void attach();
    void filesDropped(const QStringList &paths);
    void imagePasted(const QImage &image);
    void draftChanged(const QString &text);
    void typing();

protected:
    void paintEvent(QPaintEvent *) override;
    void resizeEvent(QResizeEvent *) override;
    bool eventFilter(QObject *o, QEvent *e) override;

private:
    void onTextChanged();
    void updateSendState(bool animated);
    void relayout();
    void submit();
    void openEmoji();
    int editHeight() const;

    ComposerEdit *m_edit;
    ui::IconButton *m_attach;
    ui::IconButton *m_emoji;
    ui::IconButton *m_cancelReply;
    SendButton *m_send;
    QVariantAnimation m_progress;
    QVariantAnimation m_replyAnim;
    QJsonObject m_reply;
    bool m_editing = false;
    QString m_savedDraft;
    bool m_hasText = false;
    bool m_blocked = false;
    bool m_settingDraft = false;
    QTimer *m_draftTimer;
};
