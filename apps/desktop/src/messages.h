#pragma once

#include <QAbstractListModel>
#include <QDate>
#include <QElapsedTimer>
#include <QHash>
#include <QPixmap>
#include <QJsonArray>
#include <QJsonObject>
#include <QListView>
#include <QSet>
#include <QStyledItemDelegate>
#include <QTimer>

namespace ui {
class IconButton;
}

// The reaction a double click puts on a message. An account setting: the core
// shares it with the user's other devices; QSettings only caches it.
QString quickReaction();
void loadQuickReaction(QObject *ctx);
QStringList quickReactionChoices();

struct MessageItem {
    bool isDate = false;
    QDate date;
    QJsonObject msg;
    bool groupStart = true;
    bool groupEnd = true;
    qint64 appearAt = -1; // ms on the shared clock; -1 = no entry animation
    qint64 reactAt = -1;  // reactions changed at this time: chips pop in
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
    bool group = false;
    bool flat = false;

private:
    void rebuild();
    QList<QJsonObject> m_messages; // chronological
    QHash<QString, qint64> m_appear;
    QHash<QString, qint64> m_react;
    QList<MessageItem> m_items;
};

// Geometry of one message row, shared by painting and hit testing.
struct MessageLayout {
    QRectF bubble, name, avatar, text, meta, reply, fwd, file, download, retry, image, pollInfo, call;
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

signals:
    void changed();

private:
    using QObject::QObject;
    QHash<QString, QPixmap> m_thumbs, m_full;
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
    // Check mode: several messages picked for a bulk action.
    bool checkMode() const { return m_checkMode; }
    bool isChecked(const QString &id) const { return m_checked.contains(id); }

signals:
    void replyRequested(const QJsonObject &msg);
    void editRequested(const QJsonObject &msg);
    void forwardRequested(const QJsonArray &msgs);
    void profileRequested(const QString &userId);
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
    void openFile(const QJsonObject &m);
    void saveFile(const QJsonObject &m);
    void retry(const QJsonObject &m);
    void openImage(const QJsonObject &m);
    void vote(const QJsonObject &m, int option);
    void react(const QJsonObject &m, const QString &emoji);
    void showMenu(const QJsonObject &m, const QPoint &globalPos);
    QString linkAt(const QJsonObject &m, const MessageLayout &L, const QPoint &pos) const;
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
