#include "chatlist.h"

#include "core.h"
#include "theme.h"
#include "widgets.h"

#include <QButtonGroup>
#include <QDateTime>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QLocale>
#include <QPainter>
#include <QScrollBar>
#include <QVBoxLayout>

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
        QString s = c.value("title").toString();
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
    ui::paintAvatar(p, avatar, o.value("title").toString(), o.value("id").toString());

    const qreal x = avatar.right() + 13;
    const qreal right = r.right() - 12;
    const Theme *th = Theme::instance();
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
    p->drawText(titleR, Qt::AlignLeft | Qt::AlignVCenter, QFontMetricsF(titleFont).elidedText(o.value("title").toString(), Qt::ElideRight, titleR.width()));
    p->setFont(timeFont);
    p->setPen(c.muted);
    p->drawText(QRectF(right - timeW, top, timeW, titleH), Qt::AlignRight | Qt::AlignVCenter, time);

    const int unread = o.value("unread").toInt();
    qreal badgeW = 0;
    if (unread > 0) {
        const QFont bf = th->font(11.5, QFont::Bold);
        const QString n = unread > 99 ? QStringLiteral("99+") : QString::number(unread);
        badgeW = qMax(20.0, QFontMetricsF(bf).horizontalAdvance(n) + 12);
        const QRectF b(right - badgeW, top + titleH + 4 + (prevH - 20) / 2, badgeW, 20);
        p->setPen(Qt::NoPen);
        p->setBrush(c.action);
        p->drawRoundedRect(b, 7, 7);
        p->setFont(bf);
        p->setPen(c.onAccent);
        p->drawText(b, Qt::AlignCenter, n);
        badgeW += 8;
    }
    QString preview = o.value("last_preview").toString();
    QColor prevColor = selected ? c.text : c.muted;
    const QString draft = o.value("draft").toString().simplified();
    if (!draft.isEmpty() && !selected) {
        preview = QStringLiteral("Черновик: ") + draft;
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
    auto *title = new ui::Label(QStringLiteral("Сообщения"), 26, QFont::Bold);
    title->setAccessibleName(QStringLiteral("Сообщения"));
    auto *compose = new ui::IconButton("compose", QStringLiteral("Новый чат"), this, 40);
    connect(compose, &QAbstractButton::clicked, this, &ChatPanel::newChat);
    head->addWidget(logo);
    head->addSpacing(8);
    head->addWidget(title, 1);
    head->addWidget(compose);
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
    m_view->setSelectionMode(QAbstractItemView::SingleSelection);
    m_view->setUniformItemSizes(true);
    m_view->setAccessibleName(QStringLiteral("Чаты"));
    m_view->viewport()->setAutoFillBackground(false);
    m_view->setAutoFillBackground(false);
    m_view->verticalScrollBar()->setSingleStep(24);
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

    m_refresh.setStartValue(0.0);
    m_refresh.setEndValue(1.0);
    m_refresh.setEasingCurve(Theme::surfaceEasing());
    connect(&m_refresh, &QVariantAnimation::valueChanged, m_view->viewport(), qOverload<>(&QWidget::update));

    connect(m_view->selectionModel(), &QItemSelectionModel::currentChanged, this, [this](const QModelIndex &cur) {
        if (!cur.isValid())
            return;
        const QString id = m_model->at(cur.row()).value("id").toString();
        if (id == m_selected)
            return;
        m_selected = id;
        emit activated(id);
    });
    connect(m_search->edit(), &QLineEdit::textChanged, this, [this] { reload(); });
    connect(m_filters, &QButtonGroup::idClicked, this, [this] { apply(m_all, true); });
    connect(Theme::instance(), &Theme::changed, this, [this] {
        update();
        m_view->viewport()->update();
    });
    connect(Theme::instance(), &Theme::fontChanged, this, [this] { m_view->doItemsLayout(); });
}

void ChatPanel::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.fillRect(rect(), Theme::p().surface);
    p.fillRect(QRect(width() - 1, 0, 1, height()), Theme::p().border);
}

void ChatPanel::reload()
{
    const int gen = ++m_generation;
    const QString query = m_search->edit()->text();
    Core::instance()->call("list_conversations", {{"query", query}}, this, [this, gen, query](const QJsonValue &v, const CoreError &e) {
        if (!e.ok() || gen != m_generation)
            return;
        QList<QJsonObject> rows;
        for (const auto &x : v.toArray())
            rows << x.toObject();
        const bool queryChanged = property("lastQuery").toString() != query;
        setProperty("lastQuery", query);
        apply(rows, queryChanged);
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
        if (filter == 2 && kind != "group")
            continue;
        rows << c;
    }
    m_model->setRows(rows);
    const int idx = m_model->indexOf(m_selected);
    {
        QSignalBlocker block(m_view->selectionModel());
        if (idx >= 0)
            m_view->selectionModel()->setCurrentIndex(m_model->index(idx), QItemSelectionModel::ClearAndSelect);
        else
            m_view->selectionModel()->clearSelection();
    }
    const bool none = all.isEmpty() && m_search->edit()->text().isEmpty();
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
    for (const auto &c : m_all)
        if (c.value("id").toString() == id)
            return c;
    return {};
}
