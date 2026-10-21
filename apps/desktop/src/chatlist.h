#pragma once

#include <QAbstractListModel>
#include <QHash>
#include <QMap>
#include <QJsonObject>
#include <QListView>
#include <QSet>
#include <QStyledItemDelegate>
#include <QVariantAnimation>
#include <QWidget>

class QButtonGroup;
class QTimer;
namespace ui {
class SearchField;
class Label;
class IconButton;
class Button;
}

QString formatListTime(qint64 ms);

// "печатает…" hints by conversation; each lasts 6 s after the last one.
class Typing : public QObject {
    Q_OBJECT
public:
    static Typing *instance();
    void onEvent(const QJsonObject &ev);
    void clear(const QString &conv);
    QString text(const QString &conv, bool group) const;

signals:
    void changed(const QString &conv);

private:
    using QObject::QObject;
    struct Entry {
        qint64 until = 0;
        QMap<QString, QString> names;
    };
    QHash<QString, Entry> m_entries;
    QTimer *m_timer = nullptr;
};

class ChatListModel : public QAbstractListModel {
    Q_OBJECT
public:
    enum { ObjectRole = Qt::UserRole + 1 };
    using QAbstractListModel::QAbstractListModel;
    int rowCount(const QModelIndex &parent = {}) const override { return parent.isValid() ? 0 : int(m_rows.size()); }
    QVariant data(const QModelIndex &index, int role) const override;
    void setRows(const QList<QJsonObject> &rows);
    int indexOf(const QString &id) const;
    QJsonObject at(int row) const { return m_rows.value(row); }

private:
    QList<QJsonObject> m_rows;
};

class ChatDelegate : public QStyledItemDelegate {
public:
    ChatDelegate(QObject *parent, const QVariantAnimation *refresh) : QStyledItemDelegate(parent), m_refresh(refresh) {}
    void paint(QPainter *p, const QStyleOptionViewItem &opt, const QModelIndex &index) const override;
    QSize sizeHint(const QStyleOptionViewItem &opt, const QModelIndex &index) const override;

private:
    const QVariantAnimation *m_refresh;
};

// The list column: title, new chat, search (Ctrl+K), filters and the chats.
class ChatPanel : public QWidget {
    Q_OBJECT
public:
    explicit ChatPanel(QWidget *parent = nullptr);
    void reload();
    void select(const QString &id);
    QString selected() const { return m_selected; }
    void focusSearch();
    void setConnection(const QString &state);
    QJsonObject conversation(const QString &id) const;
    QList<QJsonObject> all() const { return m_all + m_archived; }
    void setArchiveMode(bool on);

signals:
    void activated(const QString &id);
    void newChat();

protected:
    void paintEvent(QPaintEvent *) override;

private:
    void apply(const QList<QJsonObject> &all, bool animate);
    void showSortMenu();
    void showRowMenu(const QPoint &pos);
    void updateSelection();
    void runAction(const QString &method, const QString &key, bool value);
    QString sortKey() const;

    ui::SearchField *m_search;
    QButtonGroup *m_filters;
    QListView *m_view;
    ChatListModel *m_model;
    QWidget *m_empty;
    ui::Label *m_emptyText;
    ui::Label *m_status;
    ui::Label *m_title;
    ui::IconButton *m_back;
    ui::IconButton *m_compose;
    ui::IconButton *m_sort;
    QList<QJsonObject> m_archived;
    QList<QJsonObject> m_mainRows; // the main list while the archive is open
    QVariantAnimation m_selectAnim;
    bool m_archive = false;
    QVariantAnimation m_refresh;
    QList<QJsonObject> m_all;
    QString m_selected;
    int m_generation = 0;
    bool m_selecting = false;
    QSet<QString> m_checked;
    QWidget *m_selectBar;
    ui::Label *m_selectCount;
};
