#include "chatlist.h"

#include "core.h"
#include "popupmenu.h"
#include "sheet.h"
#include "theme.h"
#include "transition.h"
#include "widgets.h"
#include <memory>

#include <QButtonGroup>
#include <QDateTime>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QApplication>
#include <QLocale>
#include <QPainter>
#include <QScrollBar>
#include <QSettings>
#include <QTimer>
#include <QVBoxLayout>

namespace {

const QList<QPair<QString, QString>> &sorts()
{
    static const QList<QPair<QString, QString>> s{{"activity", QStringLiteral("По активности")},
                                                  {"unread", QStringLiteral("Сначала непрочитанные")},
                                                  {"name", QStringLiteral("По названию")}};
    return s;
}

} // namespace

// ---- typing ----

Typing *Typing::instance()
{
    static Typing *t = new Typing(qApp);
    return t;
}

void Typing::onEvent(const QJsonObject &ev)
{
    const QString conv = ev.value("conversation_id").toString();
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    Entry &e = m_entries[conv];
    if (e.until < now)
        e.names.clear();
    e.names[ev.value("user_id").toString()] = ev.value("name").toString();
    e.until = now + 6000;
    if (!m_timer) {
        m_timer = new QTimer(this);
        m_timer->setInterval(400);
        connect(m_timer, &QTimer::timeout, this, [this] {
            const qint64 now = QDateTime::currentMSecsSinceEpoch();
            const auto keys = m_entries.keys();
            for (const auto &id : keys) {
                if (m_entries[id].until <= now)
                    m_entries.remove(id);
                emit changed(id);
            }
            if (m_entries.isEmpty())
                m_timer->stop();
        });
    }
    m_timer->start();
    emit changed(conv);
}

void Typing::clear(const QString &conv)
{
    if (m_entries.remove(conv))
        emit changed(conv);
}

QString Typing::text(const QString &conv, bool group) const
{
    const auto it = m_entries.constFind(conv);
    if (it == m_entries.constEnd() || it->until < QDateTime::currentMSecsSinceEpoch() || it->names.isEmpty())
        return {};
    const QString dots = Theme::ms(1) == 0 ? QStringLiteral("…") : QString(int(QDateTime::currentMSecsSinceEpoch() / 400 % 3) + 1, '.').leftJustified(3, ' ');
    if (!group)
        return QStringLiteral("печатает") + dots;
    QStringList names;
    for (const QString &n : it->names) {
        const QString first = n.section(' ', 0, 0);
        names << (first.isEmpty() ? QStringLiteral("Кто-то") : first);
    }
    if (names.size() == 1)
        return names[0] + QStringLiteral(" печатает") + dots;
    if (names.size() == 2)
        return names[0] + QStringLiteral(" и ") + names[1] + QStringLiteral(" печатают") + dots;
    return QStringLiteral("Несколько человек печатают") + dots;
}

QString formatListTime(qint64 ms)
{
    if (ms <= 0)
        return {};
    const QDateTime t = QDateTime::fromMSecsSinceEpoch(ms);
    const QDate today = QDate::currentDate();
    if (t.date() == today)
        return t.toString("HH:mm");
    if (t.date() == today.addDays(-1))
        return QStringLiteral("Вчера");
    if (t.date() > today.addDays(-7))
        return QLocale(QLocale::Russian).toString(t.date(), "ddd");
    return t.toString("dd.MM.yy");
}

// ---- model ----

QVariant ChatListModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_rows.size())
        return {};
    const QJsonObject &c = m_rows[index.row()];
    if (role == ObjectRole)
        return c;
    if (role == Qt::DisplayRole || role == Qt::AccessibleTextRole) {
        if (c.value("_archive").toBool())
            return QStringLiteral("Архив");
        QString s = c.value("title").toString();
        if (c.value("pinned").toBool())
            s += QStringLiteral(", закреплён");
        if (c.value("presence").toObject().value("online").toBool())
            s += QStringLiteral(", в сети");
        const int unread = c.value("unread").toInt();
        if (unread > 0)
            s += QStringLiteral(", непрочитанных: %1").arg(unread);
        if (!c.value("last_preview").toString().isEmpty())
            s += ", " + c.value("last_preview").toString();
        const QString time = formatListTime(qint64(c.value("last_activity").toDouble()));
        if (!time.isEmpty())
            s += ", " + time;
        return s;
    }
    return {};
}

void ChatListModel::setRows(const QList<QJsonObject> &rows)
{
    // Same ids in the same order: update in place so selection and scroll stay put.
    bool same = rows.size() == m_rows.size();
    for (int i = 0; same && i < rows.size(); ++i)
        same = rows[i].value("id") == m_rows[i].value("id");
    if (same) {
        m_rows = rows;
        if (!rows.isEmpty())
            emit dataChanged(index(0), index(int(rows.size()) - 1));
        return;
    }
    beginResetModel();
    m_rows = rows;
    endResetModel();
}

int ChatListModel::indexOf(const QString &id) const
{
    for (int i = 0; i < m_rows.size(); ++i)
        if (m_rows[i].value("id").toString() == id)
            return i;
    return -1;
}

// ---- delegate ----

QSize ChatDelegate::sizeHint(const QStyleOptionViewItem &, const QModelIndex &) const
{
    return {300, int(76 * qMax(1.0, Theme::instance()->fontScale()))};
}

void ChatDelegate::paint(QPainter *p, const QStyleOptionViewItem &opt, const QModelIndex &index) const
{
    const Palette &c = Theme::p();
    const QJsonObject o = index.data(ChatListModel::ObjectRole).toJsonObject();
    const qreal t = m_refresh->state() == QAbstractAnimation::Running ? m_refresh->currentValue().toReal() : 1.0;
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    p->setOpacity(0.65 + 0.35 * t);
    p->translate(0, (1 - t) * 4);
    const QRectF r = QRectF(opt.rect).adjusted(0, 2, 0, -2);
    const bool selected = opt.state & QStyle::State_Selected;
    const bool hover = opt.state & QStyle::State_MouseOver;
    if (selected || hover) {
        QColor bg = selected ? c.selected : c.raised;
        if (!selected)
            bg.setAlphaF(0.55f);
        p->setPen(Qt::NoPen);
        p->setBrush(bg);
        p->drawRoundedRect(r, 14, 14);
    }
    if ((opt.state & QStyle::State_HasFocus) && (opt.state & QStyle::State_KeyboardFocusChange))
        ui::paintFocusRing(p, r, 14);

    const qreal av = 48;
    const QRectF avatar(r.left() + 12, r.center().y() - av / 2, av, av);
    const bool archiveRow = o.value("_archive").toBool();
    if (archiveRow) {
        p->setPen(Qt::NoPen);
        p->setBrush(QColor("#5B6B85"));
        p->drawRoundedRect(avatar, 18, 18);
        ui::paintIcon(p, avatar.adjusted(12, 12, -12, -12), "archive", QColor(Qt::white), 2.0);
    } else {
        const QString kind = o.value("kind").toString();
        ui::paintAvatar(p, avatar, o.value("title").toString(), kind == "direct" && !o.value("peer_user_id").toString().isEmpty() ? o.value("peer_user_id").toString() : o.value("id").toString(), o.value("avatar_path").toString());
        if (kind == "direct" && o.value("presence").toObject().value("online").toBool()) {
            const QRectF dot(avatar.right() - 12, avatar.bottom() - 12, 14, 14);
            p->setPen(QPen(selected ? c.selected : c.surface, 2.5));
            p->setBrush(QColor("#3CC46B"));
            p->drawEllipse(dot);
        }
    }

    const qreal x = avatar.right() + 13;
    const qreal right = r.right() - 12;
    const Theme *th = Theme::instance();
    if (archiveRow) {
        p->setFont(th->font(15.5, QFont::DemiBold));
        p->setPen(c.text);
        p->drawText(QRectF(x, r.top(), right - x, r.height()), Qt::AlignLeft | Qt::AlignVCenter, QStringLiteral("Архив"));
        p->restore();
        return;
    }
    const QFont timeFont = th->font(12);
    const QString time = formatListTime(qint64(o.value("last_activity").toDouble()));
    const qreal timeW = QFontMetricsF(timeFont).horizontalAdvance(time);

    const QFont titleFont = th->font(15.5, QFont::DemiBold);
    const qreal titleH = QFontMetricsF(titleFont).height();
    const QFont prevFont = th->font(13.5);
    const qreal prevH = QFontMetricsF(prevFont).height();
    const qreal top = r.center().y() - (titleH + prevH + 4) / 2;

    p->setFont(titleFont);
    p->setPen(c.text);
    const QRectF titleR(x, top, right - x - timeW - 8, titleH);
    p->drawText(titleR, Qt::AlignLeft | Qt::AlignVCenter,
                QFontMetricsF(titleFont).elidedText(archiveRow ? QStringLiteral("Архив") : o.value("title").toString(), Qt::ElideRight, titleR.width()));
    p->setFont(timeFont);
    p->setPen(c.muted);
    p->drawText(QRectF(right - timeW, top, timeW, titleH), Qt::AlignRight | Qt::AlignVCenter, time);

    const int unread = o.value("unread").toInt();
    qreal badgeW = 0;
    if (unread > 0) {
        const QFont bf = th->font(11.5, QFont::Bold);
        const QString n = (o.value("mention_count").toInt() > 0 ? QStringLiteral("@ ") : QString()) + (unread > 99 ? QStringLiteral("99+") : QString::number(unread));
        badgeW = qMax(20.0, QFontMetricsF(bf).horizontalAdvance(n) + 12);
        const QRectF b(right - badgeW, top + titleH + 4 + (prevH - 20) / 2, badgeW, 20);
        p->setPen(Qt::NoPen);
        p->setBrush(c.action);
        p->drawRoundedRect(b, 7, 7);
        p->setFont(bf);
        p->setPen(c.onAccent);
        p->drawText(b, Qt::AlignCenter, n);
        badgeW += 8;
    } else if (o.value("pinned").toBool()) {
        ui::paintIcon(p, QRectF(right - 18, top + titleH + 4 + (prevH - 18) / 2, 18, 18), "pin", c.muted, 1.8);
        badgeW = 26;
    }
    QString preview = o.value("last_preview").toString();
    QColor prevColor = selected ? c.text : c.muted;
    const QString draft = o.value("draft").toString().simplified();
    if (!draft.isEmpty() && !selected) {
        preview = QStringLiteral("Черновик: ") + draft;
        prevColor = c.accent;
    }
    const QString typing = archiveRow ? QString() : Typing::instance()->text(o.value("id").toString(), o.value("kind").toString() != "direct");
    if (!typing.isEmpty()) {
        preview = typing;
        prevColor = c.accent;
    }
    if (!o.value("key_changed_users").toArray().isEmpty()) {
        preview = QStringLiteral("Ключ собеседника изменился");
        prevColor = c.warning;
    }
    p->setFont(prevFont);
    p->setPen(prevColor);
    const QRectF prevR(x, top + titleH + 4, right - x - badgeW, prevH);
    p->drawText(prevR, Qt::AlignLeft | Qt::AlignVCenter, QFontMetricsF(prevFont).elidedText(preview.simplified(), Qt::ElideRight, prevR.width()));
    p->restore();
}

// ---- filter chip ----

namespace {

class Chip : public QAbstractButton {
public:
    Chip(const QString &text, QWidget *parent) : QAbstractButton(parent)
    {
        setText(text);
        setCheckable(true);
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        setAccessibleName(text);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    QSize sizeHint() const override { return {QFontMetrics(Theme::instance()->font(13.5, QFont::DemiBold)).horizontalAdvance(text()) + 30, 32}; }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        if (isChecked() || underMouse()) {
            p.setPen(Qt::NoPen);
            QColor bg = isChecked() ? c.raised : c.raised;
            if (!isChecked())
                bg.setAlphaF(0.5);
            p.setBrush(bg);
            p.drawRoundedRect(rect(), 10, 10);
        }
        p.setFont(Theme::instance()->font(13.5, QFont::DemiBold));
        p.setPen(isChecked() ? c.text : c.muted);
        p.drawText(rect(), Qt::AlignCenter, text());
        if (hasFocus())
            ui::paintFocusRing(&p, rect(), 10);
    }
    void enterEvent(QEnterEvent *) override { update(); }
    void leaveEvent(QEvent *) override { update(); }
};

} // namespace

// ---- panel ----

ChatPanel::ChatPanel(QWidget *parent) : QWidget(parent)
{
    auto *lay = new QVBoxLayout(this);
    lay->setContentsMargins(12, 22, 12, 10);
    lay->setSpacing(0);

    auto *head = new QHBoxLayout;
    head->setContentsMargins(12, 0, 0, 0);
    auto *logo = new QLabel;
    const qreal dpr = qMax(1.0, devicePixelRatioF());
    QPixmap pm = QPixmap(":/logo.png").scaled(int(30 * dpr), int(30 * dpr), Qt::KeepAspectRatio, Qt::SmoothTransformation);
    pm.setDevicePixelRatio(dpr);
    logo->setPixmap(pm);
    logo->setFixedSize(30, 30);
    m_title = new ui::Label(QStringLiteral("Сообщения"), 26, QFont::Bold);
    m_title->setAccessibleName(QStringLiteral("Сообщения"));
    m_back = new ui::IconButton("back", QStringLiteral("Назад к чатам"), this, 40);
    m_back->hide();
    connect(m_back, &QAbstractButton::clicked, this, [this] { setArchiveMode(false); });
    m_sort = new ui::IconButton("sort", QStringLiteral("Сортировка чатов"), this, 40);
    connect(m_sort, &QAbstractButton::clicked, this, &ChatPanel::showSortMenu);
    m_compose = new ui::IconButton("compose", QStringLiteral("Новый чат"), this, 40);
    connect(m_compose, &QAbstractButton::clicked, this, &ChatPanel::newChat);
    head->addWidget(m_back);
    head->addWidget(logo);
    head->addSpacing(8);
    head->addWidget(m_title, 1);
    head->addWidget(m_sort);
    head->addWidget(m_compose);
    lay->addLayout(head);
    lay->addSpacing(18);

    m_search = new ui::SearchField(QStringLiteral("Поиск"), QStringLiteral("Ctrl K"));
    auto *searchWrap = new QHBoxLayout;
    searchWrap->setContentsMargins(12, 0, 12, 0);
    searchWrap->addWidget(m_search);
    lay->addLayout(searchWrap);
    lay->addSpacing(14);

    auto *filters = new QHBoxLayout;
    filters->setContentsMargins(12, 0, 12, 0);
    filters->setSpacing(4);
    m_filters = new QButtonGroup(this);
    const QStringList names{QStringLiteral("Все"), QStringLiteral("Личные"), QStringLiteral("Группы")};
    for (int i = 0; i < names.size(); ++i) {
        auto *chip = new Chip(names[i], this);
        m_filters->addButton(chip, i);
        filters->addWidget(chip);
    }
    m_filters->button(0)->setChecked(true);
    filters->addStretch();
    lay->addLayout(filters);
    lay->addSpacing(12);

    m_model = new ChatListModel(this);
    m_view = new QListView;
    m_view->setModel(m_model);
    m_view->setItemDelegate(new ChatDelegate(m_view, &m_refresh));
    m_view->setFrameShape(QFrame::NoFrame);
    m_view->setMouseTracking(true);
    m_view->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    m_view->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_view->setSelectionMode(QAbstractItemView::ExtendedSelection);
    m_view->setUniformItemSizes(true);
    m_view->setAccessibleName(QStringLiteral("Чаты"));
    m_view->viewport()->setAutoFillBackground(false);
    m_view->setAutoFillBackground(false);
    m_view->verticalScrollBar()->setSingleStep(24);
    m_view->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_view, &QWidget::customContextMenuRequested, this, &ChatPanel::showRowMenu);
    m_selectBar = new QWidget(this);
    auto *actions = new QHBoxLayout(m_selectBar);
    actions->setContentsMargins(4, 4, 4, 4);
    actions->setSpacing(0);
    m_selectCount = new ui::Label({}, 13, QFont::DemiBold);
    actions->addWidget(m_selectCount, 1);
    for (const auto &item : QList<QPair<QString, QString>>{{"archive", QStringLiteral("В архив")}, {"pin", QStringLiteral("Закрепить")}, {"trash", QStringLiteral("Удалить чаты")}, {"close", QStringLiteral("Отменить выбор")}}) {
        auto *button = new ui::IconButton(item.first, item.second, m_selectBar, 36);
        actions->addWidget(button);
        connect(button, &QAbstractButton::clicked, this, [this, icon = item.first] {
            if (icon == "archive") runAction("set_archived", "archived", !m_archive);
            else if (icon == "pin") runAction("set_pinned", "pinned", true);
            else if (icon == "trash") runAction("clear_conversation", "hide", true);
            else { m_selecting = false; m_view->clearSelection(); updateSelection(); }
        });
    }
    lay->addWidget(m_selectBar);
    m_selectBar->hide();
    lay->addWidget(m_view, 1);

    m_empty = new QWidget;
    auto *el = new QVBoxLayout(m_empty);
    el->setContentsMargins(24, 24, 24, 24);
    m_emptyText = new ui::Label(QStringLiteral("Чатов пока нет. Начните переписку с человеком по его имени пользователя."), 14, 400, ui::Label::Muted);
    m_emptyText->setWordWrap(true);
    m_emptyText->setAlignment(Qt::AlignCenter);
    auto *start = new ui::Button(QStringLiteral("Новый чат"), ui::Button::Secondary);
    connect(start, &QAbstractButton::clicked, this, &ChatPanel::newChat);
    el->addStretch();
    el->addWidget(m_emptyText);
    el->addSpacing(8);
    el->addWidget(start, 0, Qt::AlignHCenter);
    el->addStretch(2);
    lay->addWidget(m_empty, 1);
    m_empty->hide();

    m_status = new ui::Label(QStringLiteral("NeoChat"), 12.5, QFont::DemiBold, ui::Label::Muted);
    auto *statusWrap = new QHBoxLayout;
    statusWrap->setContentsMargins(12, 8, 12, 0);
    statusWrap->addWidget(m_status);
    lay->addLayout(statusWrap);

    m_selectAnim.setStartValue(0.0);
    connect(&m_selectAnim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        const qreal t = v.toReal();
        m_selectBar->setMaximumHeight(t >= 1.0 ? QWIDGETSIZE_MAX : int(t * m_selectBar->sizeHint().height()));
        if (t <= 0.001 && m_selectAnim.endValue().toReal() == 0)
            m_selectBar->hide();
    });
    m_refresh.setStartValue(0.0);
    m_refresh.setEndValue(1.0);
    m_refresh.setEasingCurve(Theme::surfaceEasing());
    connect(&m_refresh, &QVariantAnimation::valueChanged, m_view->viewport(), qOverload<>(&QWidget::update));

    connect(m_view->selectionModel(), &QItemSelectionModel::currentChanged, this, [this](const QModelIndex &cur) {
        if (QApplication::keyboardModifiers() & (Qt::ControlModifier | Qt::ShiftModifier)) m_selecting = true;
        if (m_selecting) return;
        if (!cur.isValid())
            return;
        const QJsonObject row = m_model->at(cur.row());
        if (row.value("_archive").toBool())
            return;
        const QString id = row.value("id").toString();
        if (id == m_selected)
            return;
        m_selected = id;
        emit activated(id);
    });
    connect(m_view->selectionModel(), &QItemSelectionModel::selectionChanged, this, [this] { updateSelection(); });
    connect(m_search->edit(), &QLineEdit::textChanged, this, [this] { reload(); });
    connect(m_filters, &QButtonGroup::idClicked, this, [this] { apply(m_all, true); });
    connect(Theme::instance(), &Theme::changed, this, [this] {
        update();
        m_view->viewport()->update();
    });
    connect(Theme::instance(), &Theme::fontChanged, this, [this] { m_view->doItemsLayout(); });
    connect(Typing::instance(), &Typing::changed, m_view->viewport(), qOverload<>(&QWidget::update));
    auto openArchive = [this](const QModelIndex &i) {
        if (i.isValid() && m_model->at(i.row()).value("_archive").toBool())
            QTimer::singleShot(0, this, [this] { setArchiveMode(true); });
    };
    connect(m_view, &QAbstractItemView::clicked, this, openArchive);
    connect(m_view, &QAbstractItemView::activated, this, openArchive);
}

QString ChatPanel::sortKey() const
{
    return QSettings().value(m_archive ? "chats/archiveSort" : "chats/sort", "activity").toString();
}

void ChatPanel::showSortMenu()
{
    auto *menu = new PopupMenu(this);
    const QString cur = sortKey();
    for (const auto &s : sorts()) {
        menu->addChoice(s.second, s.first == cur, this, [this, key = s.first] {
            QSettings().setValue(m_archive ? "chats/archiveSort" : "chats/sort", key);
            reload();
        });
    }
    menu->popup(m_sort->mapToGlobal(QPoint(0, m_sort->height())));
}

// Right click: pin (up to 3) and archive, like the swipes on phones.
void ChatPanel::showRowMenu(const QPoint &pos)
{
    const QModelIndex idx = m_view->indexAt(pos);
    if (!idx.isValid())
        return;
    const QJsonObject c = m_model->at(idx.row());
    if (c.value("_archive").toBool())
        return;
    if (!m_view->selectionModel()->isSelected(idx)) {
        QSignalBlocker block(m_view->selectionModel());
        m_view->selectionModel()->select(idx, QItemSelectionModel::ClearAndSelect);
    }
    updateSelection();
    auto *menu = new PopupMenu(this);
    const bool pinned = c.value("pinned").toBool();
    menu->addAction(pinned ? QStringLiteral("Открепить") : QStringLiteral("Закрепить"), this, [this, pinned] {
        runAction("set_pinned", "pinned", !pinned);
    });
    const bool archived = c.value("in_archive").toBool();
    menu->addAction(archived ? QStringLiteral("Вернуть из архива") : QStringLiteral("В архив"), this, [this, archived] {
        runAction("set_archived", "archived", !archived);
    });
    menu->addAction(QStringLiteral("Выбрать чаты"), this, [this] { m_selecting = true; m_view->setSelectionMode(QAbstractItemView::MultiSelection); updateSelection(); });
    menu->addSeparator();
    menu->addAction(QStringLiteral("Очистить…"), this, [this] { runAction("clear_conversation", "hide", false); });
    menu->addAction(QStringLiteral("Удалить чаты…"), this, [this] { runAction("clear_conversation", "hide", true); });
    menu->popup(m_view->viewport()->mapToGlobal(pos));
}

void ChatPanel::updateSelection()
{
    m_checked.clear();
    for (const auto &idx : m_view->selectionModel()->selectedRows()) {
        const auto c = m_model->at(idx.row());
        if (c.value("_archive").toBool()) continue;
        const QString id = c.value("id").toString();
        if (!id.isEmpty()) m_checked.insert(id);
    }
    if (m_checked.size() > 1) m_selecting = true;
    if ((m_selectAnim.endValue().toReal() > 0.5) != m_selecting) {
        if (m_selecting)
            m_selectBar->show();
        ui::animate(m_selectAnim, m_selecting ? 1 : 0, Theme::ms(180), Theme::surfaceEasing());
    }
    m_selectCount->setText(QStringLiteral("Выбрано: %1").arg(m_checked.size()));
    if (!m_selecting) m_view->setSelectionMode(QAbstractItemView::ExtendedSelection);
}

void ChatPanel::runAction(const QString &method, const QString &key, bool value)
{
    const auto ids = m_checked;
    if (ids.isEmpty()) return;
    auto run = [this, ids, method, key, value] {
        auto completed = std::make_shared<QStringList>();
        auto pending = std::make_shared<int>(ids.size());
        for (const auto &id : ids) {
            const bool canUndo = method == "set_archived" && value && !conversation(id).value("in_archive").toBool();
            Core::instance()->call(method, {{"conversation_id", id}, {key, value}}, this, [this, id, completed, pending, canUndo](const QJsonValue &, const CoreError &e) {
                if (!e.ok()) m_status->setText(e.message);
                else if (canUndo) completed->append(id);
                if (--*pending == 0 && !completed->isEmpty()) emit archived(*completed);
            });
        }
        m_selecting = false;
        m_view->clearSelection();
        updateSelection();
    };
    if (method != "clear_conversation") return run();
    auto *sheet = new Sheet(window(), value ? QStringLiteral("Удалить выбранные чаты?") : QStringLiteral("Очистить выбранные чаты?"), 420);
    auto *text = new ui::Label(QStringLiteral("Выбрано: %1. История удалится только на этом устройстве. У собеседников сообщения останутся. Отправки из очереди продолжатся.").arg(ids.size()), 14);
    text->setWordWrap(true);
    sheet->body()->addWidget(text);
    auto *confirm = new ui::Button(value ? QStringLiteral("Удалить") : QStringLiteral("Очистить"), ui::Button::Danger);
    sheet->body()->addWidget(confirm);
    connect(confirm, &QAbstractButton::clicked, sheet, [sheet, run] { run(); sheet->close(); });
    sheet->open();
}

void ChatPanel::setArchiveMode(bool on)
{
    if (m_archive == on)
        return;
    // The panel pushes to the archive and back like a phone screen; both pictures are built from what is cached.
    ui::transition(this, rect(), on ? ui::Motion::Forward : ui::Motion::Back, [this, on] {
        if (on)
            m_mainRows = m_all;
        m_archive = on;
        m_title->setText(on ? QStringLiteral("Архив") : QStringLiteral("Сообщения"));
        m_title->setAccessibleName(m_title->text());
        m_back->setVisible(on);
        m_compose->setVisible(!on);
        m_search->edit()->clear();
        apply(on ? m_archived : m_mainRows, false);
        m_view->doItemsLayout();
        m_view->verticalScrollBar()->setValue(0);
    });
    reload();
}

void ChatPanel::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    QColor glass = Theme::p().surface;
    glass.setAlphaF(0.62);
    Theme::paintBackdrop(p, this, glass);
    p.fillRect(QRect(width() - 1, 0, 1, height()), Theme::p().border);
}

void ChatPanel::resetAccount()
{
    ++m_generation;
    m_archived.clear();
    m_mainRows.clear();
    m_checked.clear();
    m_selected.clear();
    m_selecting = false;
    m_selectBar->hide();
    m_search->edit()->clear();
    m_archive = false;
    m_title->setText(QStringLiteral("Сообщения"));
    m_title->setAccessibleName(m_title->text());
    m_back->hide();
    m_compose->show();
    apply({}, false);
}

void ChatPanel::reload()
{
    const int gen = ++m_generation;
    const QString query = m_search->edit()->text();
    const bool archive = m_archive;
    QJsonObject p{{"query", query}, {"sort", sortKey()}};
    if (archive)
        p["archived"] = true;
    Core::instance()->call("list_conversations", p, this, [this, gen, query, archive](const QJsonValue &v, const CoreError &e) {
        if (!e.ok() || gen != m_generation)
            return;
        QList<QJsonObject> rows;
        for (const auto &x : v.toArray())
            if (!archive || query.isEmpty() || x.toObject().value("in_archive").toBool())
                rows << x.toObject();
        const bool queryChanged = property("lastQuery").toString() != query;
        setProperty("lastQuery", query);
        if (archive || !query.isEmpty()) {
            if (!archive)
                m_archived.clear();
            else if (query.isEmpty())
                m_archived = rows;
            apply(rows, queryChanged);
            return;
        }
        // The archive row on top of the main list shows how many chats are there.
        Core::instance()->call("list_conversations", {{"archived", true}}, this, [this, gen, rows, queryChanged](const QJsonValue &av, const CoreError &ae) {
            if (gen != m_generation)
                return;
            m_archived.clear();
            if (ae.ok())
                for (const auto &x : av.toArray())
                    m_archived << x.toObject();
            apply(rows, queryChanged);
        });
    });
}

void ChatPanel::apply(const QList<QJsonObject> &all, bool animate)
{
    m_all = all;
    const int filter = m_filters->checkedId();
    QList<QJsonObject> rows;
    for (const auto &c : all) {
        const QString kind = c.value("kind").toString();
        if (filter == 1 && kind != "direct")
            continue;
        if (filter == 2 && kind != "group" && kind != "channel")
            continue;
        rows << c;
    }
    if (!m_archive && filter == 0 && !m_archived.isEmpty() && m_search->edit()->text().isEmpty()) {
        int unread = 0;
        for (const auto &c : m_archived)
            unread += c.value("unread").toInt();
        rows.prepend(QJsonObject{{"_archive", true}, {"id", "_archive"}, {"count", int(m_archived.size())}, {"unread", unread}});
    }
    const auto checked = m_checked;
    QSignalBlocker selectionBlock(m_view->selectionModel());
    m_model->setRows(rows);
    const int idx = m_model->indexOf(m_selected);
    {
        QSignalBlocker block(m_view->selectionModel());
        if (m_selecting) {
            for (int i = 0; i < rows.size(); ++i)
                if (checked.contains(rows[i].value("id").toString())) m_view->selectionModel()->select(m_model->index(i), QItemSelectionModel::Select);
        } else if (idx >= 0)
            m_view->selectionModel()->setCurrentIndex(m_model->index(idx), QItemSelectionModel::ClearAndSelect);
        else
            m_view->selectionModel()->clearSelection();
    }
    updateSelection();
    const bool none = rows.isEmpty() && m_search->edit()->text().isEmpty() && !m_archive;
    m_view->setVisible(!none);
    m_empty->setVisible(none);
    if (animate && Theme::ms(140) > 0) {
        m_refresh.stop();
        m_refresh.setDuration(140);
        m_refresh.start();
    }
}

void ChatPanel::select(const QString &id)
{
    m_selected = id;
    const int idx = m_model->indexOf(id);
    QSignalBlocker block(m_view->selectionModel());
    if (idx >= 0) {
        m_view->selectionModel()->setCurrentIndex(m_model->index(idx), QItemSelectionModel::ClearAndSelect);
        m_view->scrollTo(m_model->index(idx));
    }
}

void ChatPanel::focusSearch()
{
    m_search->edit()->setFocus(Qt::ShortcutFocusReason);
    m_search->edit()->selectAll();
}

void ChatPanel::setConnection(const QString &state)
{
    if (state == "online") {
        m_status->setText(QStringLiteral("NeoChat"));
        m_status->setRole(ui::Label::Muted);
    } else if (state == "connecting") {
        m_status->setText(QStringLiteral("Подключение…"));
        m_status->setRole(ui::Label::Muted);
    } else {
        m_status->setText(QStringLiteral("Нет сети. Сообщения отправятся после подключения"));
        m_status->setRole(ui::Label::Warning);
    }
}

QJsonObject ChatPanel::conversation(const QString &id) const
{
    for (const auto &c : m_all + m_archived)
        if (c.value("id").toString() == id)
            return c;
    return {};
}
