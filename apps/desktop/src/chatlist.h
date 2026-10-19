#pragma once

#include <QAbstractListModel>
#include <QJsonObject>
#include <QListView>
#include <QStyledItemDelegate>
#include <QVariantAnimation>
#include <QWidget>

class QButtonGroup;
namespace ui {
class SearchField;
class Label;
class IconButton;
class Button;
}

QString formatListTime(qint64 ms);

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

signals:
    void activated(const QString &id);
    void newChat();

protected:
    void paintEvent(QPaintEvent *) override;

private:
    void apply(const QList<QJsonObject> &all, bool animate);

    ui::SearchField *m_search;
    QButtonGroup *m_filters;
    QListView *m_view;
    ChatListModel *m_model;
    QWidget *m_empty;
    ui::Label *m_emptyText;
    ui::Label *m_status;
    QVariantAnimation m_refresh;
    QList<QJsonObject> m_all;
    QString m_selected;
    int m_generation = 0;
};
