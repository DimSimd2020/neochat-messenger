#pragma once

#include <QAbstractListModel>
#include <QDate>
#include <QElapsedTimer>
#include <QJsonObject>
#include <QListView>
#include <QSet>
#include <QStyledItemDelegate>
#include <QTimer>

namespace ui {
class IconButton;
}

struct MessageItem {
    bool isDate = false;
    QDate date;
    QJsonObject msg;
    bool groupStart = true;
    bool groupEnd = true;
    qint64 appearAt = -1; // ms on the shared clock; -1 = no entry animation
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
    QStringList merge(const QList<QJsonObject> &page, bool animateNew);
    void setFlat(const QList<QJsonObject> &list); // search results: no grouping
    qint64 oldestLocalId() const;
    int messageCount() const { return int(m_messages.size()); }
    bool group = false;
    bool flat = false;

private:
    void rebuild();
    QList<QJsonObject> m_messages; // chronological
    QHash<QString, qint64> m_appear;
    QList<MessageItem> m_items;
};

// Geometry of one message row, shared by painting and hit testing.
struct MessageLayout {
    QRectF bubble, name, avatar, text, meta, reply, file, download, retry;
    qreal height = 0;
};

class MessageDelegate : public QStyledItemDelegate {
public:
    using QStyledItemDelegate::QStyledItemDelegate;
    static MessageLayout layout(const MessageItem &it, qreal width, bool group);
    void paint(QPainter *p, const QStyleOptionViewItem &opt, const QModelIndex &index) const override;
    QSize sizeHint(const QStyleOptionViewItem &opt, const QModelIndex &index) const override;
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

signals:
    void replyRequested(const QJsonObject &msg);
    void toast(const QString &text);
    void readable(); // the newest messages are on screen

protected:
    void resizeEvent(QResizeEvent *e) override;
    void mouseReleaseEvent(QMouseEvent *e) override;
    void mouseMoveEvent(QMouseEvent *e) override;
    void contextMenuEvent(QContextMenuEvent *e) override;
    void keyPressEvent(QKeyEvent *e) override;
    void paintEvent(QPaintEvent *e) override;

private:
    void load(bool older);
    void applyPage(const QList<QJsonObject> &page, bool older);
    void restoreBottomDistance(int distance);
    void openFile(const QJsonObject &m);
    void saveFile(const QJsonObject &m);
    void retry(const QJsonObject &m);
    void placeJump();
    QJsonObject messageAt(const QPoint &pos, MessageLayout *lay = nullptr) const;

    MessageModel *m_model;
    QString m_conv;
    bool m_loading = false;
    bool m_hasMore = true;
    bool m_searching = false;
    int m_generation = 0;
    int m_newBelow = 0;
    QTimer m_anim;
    ui::IconButton *m_jump;
};
