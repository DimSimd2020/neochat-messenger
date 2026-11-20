#pragma once

#include "spoiler.h"

#include <QAbstractListModel>
#include <QDate>
#include <QElapsedTimer>
#include <QHash>
#include <QImage>
#include <QPixmap>
#include <QJsonArray>
#include <QJsonObject>
#include <QListView>
#include <QSet>
#include <QStyledItemDelegate>
#include <QTimer>
#include <functional>

namespace ui {
class IconButton;
}

// The reaction a double click puts on a message. An account setting: the core
// shares it with the user's other devices; QSettings only caches it.
// Opens a picture inside NeoChat. `actions` fills the right-click menu of the viewer; without it
// a right click does nothing. Returns false when the file cannot be read.
bool showPhotoFile(QWidget *host, const QString &path, std::function<void(class PopupMenu *)> actions = {});

QString quickReaction();
void loadQuickReaction(QObject *ctx);
QStringList quickReactionChoices();

// The text of a message as it may be quoted elsewhere (a pin, a preview): hidden words are blocks.
QString messageQuote(const QJsonObject &m);

// Protected top-level photo window; closing it marks the message viewed in the core.
QWidget *showViewOncePhoto(QWidget *host, const QImage &image, const QString &messageId, class MessageView *report = nullptr);

struct MessageItem {
    bool isDate = false;
    QDate date;
    QJsonObject msg;
    bool groupStart = true;
    bool groupEnd = true;
    qint64 appearAt = -1; // ms on the shared clock; -1 = no entry animation
    qint64 reactAt = -1;  // reactions changed at this time: chips pop in
    qint64 goneAt = -1;   // deleted at this time: the row dissolves and closes under itself
};

class MessageModel : public QAbstractListModel {
    Q_OBJECT
public:
    enum { ItemRole = Qt::UserRole + 1 };
    using QAbstractListModel::QAbstractListModel;
    int rowCount(const QModelIndex &parent = {}) const override { return parent.isValid() ? 0 : int(m_items.size()); }
    QVariant data(const QModelIndex &index, int role) const override;
    const MessageItem &item(int row) const { return m_items[row]; }

    void clear();
    // Merges a chronological page; returns ids that were appended after the newest known message.
    QStringList merge(const QList<QJsonObject> &page, bool animateNew, bool older = false);
    void setFlat(const QList<QJsonObject> &list); // search results: no grouping
    qint64 oldestLocalId() const;
    int messageCount() const { return int(m_messages.size()); }
    // Drops the rows whose farewell has finished; true when the model changed.
    bool finishGone();
    bool anyGone() const { return !m_gone.isEmpty(); }
    bool group = false;
    bool flat = false;

private:
    void rebuild();
    QList<QJsonObject> m_messages; // chronological
    QHash<QString, qint64> m_appear;
    QHash<QString, qint64> m_react;
    QHash<QString, qint64> m_gone;
    QList<MessageItem> m_items;
};

// Geometry of one message row, shared by painting and hit testing.
struct MessageLayout {
    QRectF bubble, name, avatar, text, meta, reply, fwd, file, download, retry, image, pollInfo, call;
    // Voice messages and circles: the row, its play button, wave, speed chip and duration; the round picture;
    // the "transcribe" action and the recognized text under them.
    QRectF voice, play, wave, speed, duration, circle, transBtn, transcript;
    QList<QRectF> options; // poll answers
    QList<QRectF> reacts;  // reaction chips
    bool big = false;      // emoji-only message without a bubble
    qreal height = 0;
};

// Photos in bubbles: the thumbnail from the message at once, the decrypted file
// once download_file has it. `changed` asks views to repaint.
class ImageCache : public QObject {
    Q_OBJECT
public:
    static ImageCache *instance();
    QPixmap thumb(const QJsonObject &m);
    QPixmap full(const QJsonObject &m); // null until loaded; starts the download
    // One step of the defocus over a hidden picture: 0 unreadable, 1 middle, 2 nearly sharp.
    QPixmap blur(const QJsonObject &m, int level);
    // The poster as it may be shown outside the chat: a closed spoiler stays unreadable.
    QPixmap poster(const QJsonObject &m);

signals:
    void changed();

private:
    using QObject::QObject;
    QHash<QString, QPixmap> m_thumbs, m_full, m_blur;
    QHash<QString, qint64> m_pending; // id -> request time (ms since epoch)
};

class MessageDelegate : public QStyledItemDelegate {
public:
    using QStyledItemDelegate::QStyledItemDelegate;
    static MessageLayout layout(const MessageItem &it, qreal width, bool group);
    void paint(QPainter *p, const QStyleOptionViewItem &opt, const QModelIndex &index) const override;
    QSize sizeHint(const QStyleOptionViewItem &opt, const QModelIndex &index) const override;
    // Mouse selection inside one message's text (document positions).
    QString selId;
    int selA = 0, selB = 0;
};

class MessageView : public QListView {
    Q_OBJECT
public:
    explicit MessageView(QWidget *parent = nullptr);
    void setConversation(const QString &id, bool group);
    QString conversation() const { return m_conv; }
    void refresh();
    void showSearch(const QString &query);
    void scrollToNewest();
    bool atBottom() const;
    void setCanReact(bool on) { m_canReact = on; }
    void setCanPin(bool on) { m_canPin = on; }
    void setCopyProtection(bool copy, bool forward);
    void closeMediaViewers();
    // Scrolls to a message (loading older pages until it is found) and flashes it.
    void jumpToMessage(const QString &id);
    // Opens a picture of this conversation full-window, with the menu of its message on right click.
    void openImage(const QJsonObject &m);
    // The photo messages loaded for this conversation, oldest first — the album "Все фотографии".
    QJsonArray conversationPhotos() const;
    // 0..1 while the message is flashing after a jump, else 0.
    qreal flashOf(const QString &id) const;
    // How far the cover of a hidden picture is open (0 closed, 1 open), and the same for hidden text.
    qreal revealOf(const QString &id, bool closed) const;
    qreal textRevealOf(const QString &id) const;
    // Where the finger asked for the picture, in 0..1 of its rect: the blur melts from there.
    QPointF revealOrigin(const QString &id) const;
    bool revealOpening(const QString &id) const;
    void setRevealOrigin(const QString &id, const QPointF &origin);
    // A picture stays revealed until this chat is left; hidden text still toggles on each tap.
    void revealHidden(const QJsonObject &m);
    void toggleText(const QJsonObject &m);
    // Something on screen still needs its dust drawn: a closed cover or a cover in motion.
    bool liveCover() const;
    // Check mode: several messages picked for a bulk action.
    bool checkMode() const { return m_checkMode; }
    bool isChecked(const QString &id) const { return m_checked.contains(id); }

signals:
    void replyRequested(const QJsonObject &msg);
    void editRequested(const QJsonObject &msg);
    void forwardRequested(const QJsonArray &msgs);
    void profileRequested(const QString &userId);
    // "Все фотографии" of an open picture: this conversation's album, starting from `photo`.
    void photosRequested(const QJsonObject &photo, const QJsonArray &photos);
    void toast(const QString &text);
    void readable(); // the newest messages are on screen

protected:
    void resizeEvent(QResizeEvent *e) override;
    void mousePressEvent(QMouseEvent *e) override;
    void mouseReleaseEvent(QMouseEvent *e) override;
    void mouseDoubleClickEvent(QMouseEvent *e) override;
    void mouseMoveEvent(QMouseEvent *e) override;
    void contextMenuEvent(QContextMenuEvent *e) override;
    void keyPressEvent(QKeyEvent *e) override;
    void paintEvent(QPaintEvent *e) override;
    bool viewportEvent(QEvent *e) override;
    // The backdrop is fixed to the window: repaint instead of moving pixels.
    void scrollContentsBy(int, int) override { viewport()->update(); }

private:
    void load(bool older);
    void applyPage(const QList<QJsonObject> &page, bool older);
    void restoreBottomDistance(int distance);
    bool isMediaRevealed(const QJsonObject &m) const;
    void openFile(const QJsonObject &m);
    void saveFile(const QJsonObject &m);
    void retry(const QJsonObject &m);
    void vote(const QJsonObject &m, int option);
    void react(const QJsonObject &m, const QString &emoji);
    void showMenu(const QJsonObject &m, const QPoint &globalPos);
    // The right-click menu of an open picture: the actions of the message it came from.
    void viewerActions(class PopupMenu *menu, const QJsonObject &m, const QImage &img);
    QString linkAt(const QJsonObject &m, const MessageLayout &L, const QPoint &pos) const;
    // A click on a hidden run of text, open or closed: it toggles instead of selecting.
    bool spoilerTextAt(const QJsonObject &m, const MessageLayout &L, const QPoint &pos) const;
    void openMention(const QString &name);
    void placeJump();
    QJsonObject messageAt(const QPoint &pos, MessageLayout *lay = nullptr) const;
    int textHit(const QPoint &pos) const;
    QString selectedText() const;
    void clearSelection();
    void enterCheckMode(const QString &id);
    void exitCheckMode();
    void toggleCheck(const QString &id);
    void placeCheckBar();

    MessageModel *m_model;
    QString m_conv;
    bool m_loading = false;
    bool m_hasMore = true;
    bool m_searching = false;
    bool m_canReact = true;
    bool m_canPin = false;
    bool m_copyBlocked = false;
    bool m_forwardBlocked = false;
    QString m_jumpId;  // a jump waiting for older pages
    QString m_flashId; // the message just jumped to
    qint64 m_flashAt = 0;
    // Covers of this chat: how far each hidden picture and each hidden text is open. They live
    // only while the chat is on screen; leaving it closes everything again.
    mutable QHash<QString, spoiler::Reveal> m_reveal;
    mutable QHash<QString, spoiler::Reveal> m_textReveal;
    mutable QHash<QString, QPointF> m_origin;
    QHash<QString, bool> m_openingWave;
    QHash<QString, int> m_revealRequests;
    QSet<QString> m_openText;
    int m_generation = 0;
    int m_newBelow = 0;
    QTimer m_anim;
    ui::IconButton *m_jump;
    MessageDelegate *m_delegate;
    QPersistentModelIndex m_selIndex;
    QPoint m_selPress;
    bool m_selDragging = false;
    bool m_selMoved = false;
    bool m_checkMode = false;
    QSet<QString> m_checked;
    QWidget *m_checkBar = nullptr;
    class QLabel *m_checkCount = nullptr;
};
