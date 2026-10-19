#include "messages.h"

#include "core.h"
#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QClipboard>
#include <QContextMenuEvent>
#include <QDateTime>
#include <QDesktopServices>
#include <QFileDialog>
#include <QJsonArray>
#include <QLocale>
#include <QMenu>
#include <QPainter>
#include <QScrollBar>
#include <QStandardPaths>
#include <QUrl>

namespace {

qint64 clockMs()
{
    static QElapsedTimer t = [] {
        QElapsedTimer e;
        e.start();
        return e;
    }();
    return t.elapsed();
}

constexpr qreal kAppearMs = 220;
constexpr qreal kPadH = 15;
constexpr qreal kPadTop = 9;
constexpr qreal kPadBottom = 7;

QString dateTitle(const QDate &d)
{
    const QLocale ru(QLocale::Russian);
    const QDate today = QDate::currentDate();
    const QString day = ru.toString(d, d.year() == today.year() ? "d MMMM" : "d MMMM yyyy");
    if (d == today)
        return QStringLiteral("Сегодня, ") + day;
    if (d == today.addDays(-1))
        return QStringLiteral("Вчера, ") + day;
    return day;
}

QString sizeText(qint64 bytes)
{
    const QLocale ru(QLocale::Russian);
    if (bytes < 1024)
        return QStringLiteral("%1 Б").arg(bytes);
    if (bytes < 1024 * 1024)
        return QStringLiteral("%1 КБ").arg(ru.toString(bytes / 1024.0, 'f', 0));
    return QStringLiteral("%1 МБ").arg(ru.toString(bytes / 1048576.0, 'f', 1));
}

QString kindText(const QString &mime, const QString &name)
{
    const QString ext = name.section('.', -1).toUpper();
    if (mime == "application/pdf")
        return QStringLiteral("PDF-документ");
    if (mime.startsWith("image/"))
        return QStringLiteral("Изображение");
    if (mime.startsWith("video/"))
        return QStringLiteral("Видео");
    if (mime.startsWith("audio/"))
        return QStringLiteral("Аудио");
    if (!ext.isEmpty() && ext.size() <= 5 && name.contains('.'))
        return QStringLiteral("Файл ") + ext;
    return QStringLiteral("Файл");
}

QString statusText(const QJsonObject &m)
{
    const QString s = m.value("status").toString();
    if (s == "queued")
        return QStringLiteral("В очереди");
    if (s == "sent")
        return QStringLiteral("На сервере");
    if (s == "delivered")
        return QStringLiteral("Доставлено");
    if (s == "read")
        return QStringLiteral("Прочитано");
    if (s == "failed")
        return QStringLiteral("Не отправлено");
    return {};
}

QString messageText(const QJsonObject &m)
{
    return m.value("text").toString();
}

QFont textFont() { return Theme::instance()->font(15.5); }
QFont metaFont() { return Theme::instance()->font(11.5); }

} // namespace

// ---- model ----

QVariant MessageModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_items.size())
        return {};
    const MessageItem &it = m_items[index.row()];
    if (role == ItemRole)
        return QVariant::fromValue(static_cast<const void *>(&it));
    if (role == Qt::DisplayRole || role == Qt::AccessibleTextRole) {
        if (it.isDate)
            return dateTitle(it.date);
        const QJsonObject &m = it.msg;
        QString s = m.value("sender_name").toString() + ", " + QDateTime::fromMSecsSinceEpoch(qint64(m.value("sent_at").toDouble())).toString("HH:mm") + ": ";
        if (m.value("kind").toString() == "file")
            s += QStringLiteral("файл ") + m.value("file").toObject().value("name").toString() + ". ";
        s += messageText(m);
        const QString st = statusText(m);
        if (m.value("outgoing").toBool() && !st.isEmpty())
            s += ". " + st;
        return s;
    }
    return {};
}

void MessageModel::clear()
{
    beginResetModel();
    m_messages.clear();
    m_items.clear();
    m_appear.clear();
    endResetModel();
}

qint64 MessageModel::oldestLocalId() const
{
    return m_messages.isEmpty() ? std::numeric_limits<qint64>::max() : qint64(m_messages.first().value("local_id").toDouble());
}

QStringList MessageModel::merge(const QList<QJsonObject> &page, bool animateNew)
{
    const qint64 newest = m_messages.isEmpty() ? -1 : qint64(m_messages.last().value("local_id").toDouble());
    QHash<QString, int> pos;
    for (int i = 0; i < m_messages.size(); ++i)
        pos.insert(m_messages[i].value("id").toString(), i);
    QStringList appended;
    for (const auto &m : page) {
        const QString id = m.value("id").toString();
        auto it = pos.find(id);
        if (it != pos.end()) {
            m_messages[*it] = m;
            continue;
        }
        m_messages << m;
        if (qint64(m.value("local_id").toDouble()) > newest) {
            appended << id;
            if (animateNew && newest >= 0 && Theme::ms(220) > 0)
                m_appear.insert(id, clockMs());
        }
    }
    std::sort(m_messages.begin(), m_messages.end(),
              [](const QJsonObject &a, const QJsonObject &b) { return a.value("local_id").toDouble() < b.value("local_id").toDouble(); });
    rebuild();
    return appended;
}

void MessageModel::setFlat(const QList<QJsonObject> &list)
{
    m_messages = list;
    std::sort(m_messages.begin(), m_messages.end(),
              [](const QJsonObject &a, const QJsonObject &b) { return a.value("local_id").toDouble() < b.value("local_id").toDouble(); });
    m_appear.clear();
    rebuild();
}

void MessageModel::rebuild()
{
    beginResetModel();
    m_items.clear();
    QDate last;
    for (int i = 0; i < m_messages.size(); ++i) {
        const QJsonObject &m = m_messages[i];
        const QDateTime t = QDateTime::fromMSecsSinceEpoch(qint64(m.value("sent_at").toDouble()));
        if (t.date() != last) {
            MessageItem d;
            d.isDate = true;
            d.date = t.date();
            m_items << d;
            last = t.date();
        }
        MessageItem it;
        it.msg = m;
        it.appearAt = m_appear.value(m.value("id").toString(), -1);
        if (!flat && !m_items.isEmpty() && !m_items.last().isDate) {
            const QJsonObject &prev = m_items.last().msg;
            const bool same = prev.value("sender_user_id") == m.value("sender_user_id") && prev.value("outgoing") == m.value("outgoing") &&
                              qAbs(m.value("sent_at").toDouble() - prev.value("sent_at").toDouble()) < 5 * 60 * 1000;
            if (same) {
                it.groupStart = false;
                m_items.last().groupEnd = false;
            }
        }
        m_items << it;
    }
    endResetModel();
}

// ---- delegate ----

MessageLayout MessageDelegate::layout(const MessageItem &it, qreal width, bool group)
{
    MessageLayout L;
    if (it.isDate) {
        L.height = 52;
        return L;
    }
    const QJsonObject &m = it.msg;
    const bool out = m.value("outgoing").toBool();
    const bool showAvatar = group && !out;
    const qreal pad = width < 560 ? 12 : 24;
    const qreal colW = qMin(width - 2 * pad, 820.0);
    const qreal colX = (width - colW) / 2;
    const qreal avatarCol = showAvatar ? 40 : 0;
    const qreal maxBubble = qMin((colW - avatarCol) * 0.78, 560.0);
    const qreal inner = maxBubble - 2 * kPadH;

    qreal y = it.groupStart ? 10 : 3;
    if (group && !out && it.groupStart) {
        L.name = QRectF(colX + avatarCol + 12, y, maxBubble, 20);
        y += 20;
    }
    qreal w = 0, h = kPadTop;
    const QJsonValue reply = m.value("reply_to");
    if (reply.isObject()) {
        L.reply = QRectF(0, h, qMin(inner, 320.0), 40);
        w = qMax(w, L.reply.width());
        h += 46;
    }
    if (m.value("kind").toString() == "file") {
        L.file = QRectF(0, h + 3, qMin(inner, 300.0), 62);
        w = qMax(w, L.file.width());
        h += 70;
    }
    const QString text = messageText(m);
    if (!text.isEmpty()) {
        const QRectF br = QFontMetricsF(textFont()).boundingRect(QRectF(0, 0, inner, 1e6), Qt::TextWordWrap, text);
        L.text = QRectF(0, h, qMin(inner, br.width() + 1), br.height());
        w = qMax(w, L.text.width());
        h += br.height() + 2;
    }
    const QFontMetricsF mf(metaFont());
    const QString time = QDateTime::fromMSecsSinceEpoch(qint64(m.value("sent_at").toDouble())).toString("HH:mm");
    qreal metaW = mf.horizontalAdvance(time) + (out ? 24 : 0);
    if (out && group && m.value("delivered_count").toInt() > 0)
        metaW += mf.horizontalAdvance(QString::number(m.value("delivered_count").toInt())) + 4;
    L.meta = QRectF(0, h, metaW, 16);
    w = qMax(w, metaW);
    h += 16 + kPadBottom;

    const qreal bw = w + 2 * kPadH;
    const qreal bx = out ? colX + colW - bw : colX + avatarCol;
    L.bubble = QRectF(bx, y, bw, h);
    auto place = [&](QRectF &r, bool alignRight) {
        if (r.isNull())
            return;
        const qreal x = alignRight ? L.bubble.right() - kPadH - r.width() : L.bubble.left() + kPadH;
        r.moveTo(x, y + r.top());
    };
    place(L.reply, false);
    place(L.file, false);
    place(L.text, false);
    place(L.meta, true);
    if (!L.file.isNull())
        L.download = QRectF(L.file.right() - 36, L.file.center().y() - 18, 36, 36);
    if (showAvatar && it.groupEnd)
        L.avatar = QRectF(colX, L.bubble.bottom() - 30, 30, 30);
    y += h;
    if (m.value("status").toString() == "failed") {
        L.retry = QRectF(out ? L.bubble.right() - 260 : L.bubble.left(), y + 4, 260, 20);
        y += 26;
    }
    L.height = y + (it.groupEnd ? 6 : 0);
    return L;
}

QSize MessageDelegate::sizeHint(const QStyleOptionViewItem &opt, const QModelIndex &index) const
{
    const auto *it = static_cast<const MessageItem *>(index.data(MessageModel::ItemRole).value<const void *>());
    const auto *model = static_cast<const MessageModel *>(index.model());
    const qreal width = opt.widget ? static_cast<const QListView *>(opt.widget)->viewport()->width() : opt.rect.width();
    return {int(width), int(std::ceil(layout(*it, width, model->group && !model->flat).height))};
}

void MessageDelegate::paint(QPainter *p, const QStyleOptionViewItem &opt, const QModelIndex &index) const
{
    const Palette &c = Theme::p();
    const auto *it = static_cast<const MessageItem *>(index.data(MessageModel::ItemRole).value<const void *>());
    const auto *model = static_cast<const MessageModel *>(index.model());
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    p->translate(opt.rect.topLeft());
    const qreal width = opt.rect.width();

    if (it->isDate) {
        p->setFont(Theme::instance()->font(12));
        const QString t = dateTitle(it->date);
        const qreal tw = QFontMetricsF(p->font()).horizontalAdvance(t);
        const qreal cy = 30;
        p->setPen(QPen(c.border, 1));
        p->drawLine(QPointF(width / 2 - tw / 2 - 52, cy), QPointF(width / 2 - tw / 2 - 12, cy));
        p->drawLine(QPointF(width / 2 + tw / 2 + 12, cy), QPointF(width / 2 + tw / 2 + 52, cy));
        p->setPen(c.muted);
        p->drawText(QRectF(0, cy - 10, width, 20), Qt::AlignCenter, t);
        p->restore();
        return;
    }

    const QJsonObject &m = it->msg;
    const MessageLayout L = layout(*it, width, model->group && !model->flat);
    const bool out = m.value("outgoing").toBool();

    if (it->appearAt >= 0) {
        const qreal t = qBound(0.0, (clockMs() - it->appearAt) / kAppearMs, 1.0);
        if (t < 1) {
            const qreal e = Theme::surfaceEasing().valueForProgress(t);
            p->setOpacity(t);
            const QPointF anchor = out ? L.bubble.bottomRight() : L.bubble.bottomLeft();
            p->translate(0, (1 - e) * 10);
            p->translate(anchor);
            p->scale(0.985 + 0.015 * e, 0.985 + 0.015 * e);
            p->translate(-anchor);
        }
    }

    if (!L.name.isNull()) {
        p->setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p->setPen(c.accent);
        p->drawText(L.name, Qt::AlignLeft | Qt::AlignVCenter, m.value("sender_name").toString());
    }
    if (!L.avatar.isNull())
        ui::paintAvatar(p, L.avatar, m.value("sender_name").toString(), m.value("sender_user_id").toString());

    const qreal R = 18, r = 6;
    const QPainterPath bubble = out ? ui::roundedPath(L.bubble, R, it->groupStart ? R : r, r, R)
                                    : ui::roundedPath(L.bubble, it->groupStart ? R : r, R, R, r);
    const bool undecryptable = m.value("kind").toString() == "undecryptable";
    p->setPen(undecryptable ? QPen(c.warning, 1, Qt::DashLine) : Qt::NoPen);
    p->setBrush(out ? c.outgoing : c.raised);
    p->drawPath(bubble);
    if (opt.state & QStyle::State_Selected) {
        p->setPen(QPen(c.accent, 2));
        p->setBrush(Qt::NoBrush);
        p->drawPath(bubble);
    }
    const QColor fg = out ? c.onOutgoing : (undecryptable ? c.warning : c.text);
    const QColor meta = out ? c.outgoingMuted : c.muted;

    if (!L.reply.isNull()) {
        const QJsonObject rep = m.value("reply_to").toObject();
        p->setPen(Qt::NoPen);
        p->setBrush(out ? QColor(255, 255, 255, 40) : c.surface);
        p->drawRoundedRect(L.reply, 8, 8);
        p->setBrush(out ? c.onOutgoing : c.accent);
        p->drawRoundedRect(QRectF(L.reply.left(), L.reply.top(), 3, L.reply.height()), 1.5, 1.5);
        p->setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p->setPen(out ? c.onOutgoing : c.accent);
        p->drawText(L.reply.adjusted(10, 3, -8, -20), Qt::AlignLeft | Qt::AlignVCenter, rep.value("sender_name").toString());
        p->setFont(Theme::instance()->font(13));
        p->setPen(meta);
        const QString rt = rep.value("kind").toString() == "file" ? QStringLiteral("Файл") : rep.value("text").toString().simplified();
        p->drawText(L.reply.adjusted(10, 19, -8, -3), Qt::AlignLeft | Qt::AlignVCenter,
                    QFontMetricsF(p->font()).elidedText(rt, Qt::ElideRight, L.reply.width() - 18));
    }

    if (!L.file.isNull()) {
        const QJsonObject f = m.value("file").toObject();
        const QRectF tile(L.file.left(), L.file.top() + 2, 46, 56);
        p->setPen(Qt::NoPen);
        p->setBrush(out ? QColor(255, 255, 255, 46) : (Theme::instance()->isDark() ? c.selected : c.selected));
        p->drawRoundedRect(tile, 10, 10);
        ui::paintIcon(p, QRectF(tile.center().x() - 10, tile.top() + 9, 20, 20), "file", out ? c.onOutgoing : c.accent);
        const QString ext = f.value("name").toString().contains('.') ? f.value("name").toString().section('.', -1).toUpper().left(4) : QString();
        p->setFont(Theme::instance()->font(9.5, QFont::Bold));
        p->setPen(out ? c.onOutgoing : c.accent);
        p->drawText(QRectF(tile.left(), tile.bottom() - 20, tile.width(), 14), Qt::AlignCenter, ext);
        const qreal tx = tile.right() + 12;
        const qreal tw = L.download.left() - 8 - tx;
        p->setFont(Theme::instance()->font(14, QFont::DemiBold));
        p->setPen(fg);
        const QString name = QFontMetricsF(p->font()).elidedText(f.value("name").toString(), Qt::ElideMiddle, tw);
        p->drawText(QRectF(tx, L.file.top() + 8, tw, 22), Qt::AlignLeft | Qt::AlignVCenter, name);
        p->setFont(Theme::instance()->font(12.5));
        p->setPen(meta);
        QString sub = sizeText(qint64(f.value("size").toDouble())) + QStringLiteral(" · ") + kindText(f.value("mime").toString(), f.value("name").toString());
        p->drawText(QRectF(tx, L.file.top() + 32, tw, 20), Qt::AlignLeft | Qt::AlignVCenter, QFontMetricsF(p->font()).elidedText(sub, Qt::ElideRight, tw));
        p->setPen(QPen(out ? QColor(255, 255, 255, 110) : c.border, 1));
        p->setBrush(Qt::NoBrush);
        p->drawEllipse(L.download.adjusted(2, 2, -2, -2));
        ui::paintIcon(p, L.download.adjusted(9, 9, -9, -9), f.value("downloaded").toBool() ? "forward" : "download", fg);
    }

    if (!L.text.isNull()) {
        p->setFont(textFont());
        p->setPen(fg);
        p->drawText(L.text, Qt::TextWordWrap, messageText(m));
    }

    p->setFont(metaFont());
    p->setPen(meta);
    const QString time = QDateTime::fromMSecsSinceEpoch(qint64(m.value("sent_at").toDouble())).toString("HH:mm");
    p->drawText(L.meta, Qt::AlignLeft | Qt::AlignVCenter, time);
    if (out) {
        const QString st = m.value("status").toString();
        const QRectF ir(L.meta.left() + QFontMetricsF(p->font()).horizontalAdvance(time) + 4, L.meta.center().y() - 9, 18, 18);
        if (st == "queued")
            ui::paintIcon(p, ir, "clock", meta, 1.8);
        else if (st == "sent")
            ui::paintIcon(p, ir, "tick", meta, 1.8);
        else if (st == "delivered")
            ui::paintIcon(p, ir, "check", meta, 1.8);
        else if (st == "read")
            ui::paintIcon(p, ir, "check", c.onOutgoing, 2.6);
        else if (st == "failed")
            ui::paintIcon(p, ir, "alert", c.warning, 2.0);
        const int n = m.value("delivered_count").toInt();
        if (model->group && n > 0) {
            p->setPen(meta);
            p->drawText(QRectF(ir.right() + 3, L.meta.top(), 30, L.meta.height()), Qt::AlignLeft | Qt::AlignVCenter, QString::number(n));
        }
    }
    if (!L.retry.isNull()) {
        p->setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p->setPen(c.warning);
        p->drawText(L.retry, (out ? Qt::AlignRight : Qt::AlignLeft) | Qt::AlignVCenter, QStringLiteral("Не отправлено · Повторить"));
    }
    p->restore();
}

// ---- view ----

MessageView::MessageView(QWidget *parent) : QListView(parent)
{
    m_model = new MessageModel(this);
    setModel(m_model);
    setItemDelegate(new MessageDelegate(this));
    setFrameShape(QFrame::NoFrame);
    setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setSelectionMode(QAbstractItemView::SingleSelection);
    setMouseTracking(true);
    setResizeMode(QListView::Adjust);
    setAccessibleName(QStringLiteral("Сообщения"));
    viewport()->setAutoFillBackground(false);
    verticalScrollBar()->setSingleStep(28);
    m_anim.setInterval(16);
    connect(&m_anim, &QTimer::timeout, this, [this] {
        viewport()->update();
        bool running = false;
        for (int i = m_model->rowCount() - 1; i >= 0 && i >= m_model->rowCount() - 12; --i) {
            const auto &it = m_model->item(i);
            if (it.appearAt >= 0 && clockMs() - it.appearAt < kAppearMs)
                running = true;
        }
        if (!running)
            m_anim.stop();
    });
    m_jump = new ui::IconButton("down", QStringLiteral("К новым сообщениям"), this, 44);
    m_jump->hide();
    connect(m_jump, &QAbstractButton::clicked, this, &MessageView::scrollToNewest);
    connect(verticalScrollBar(), &QScrollBar::valueChanged, this, [this](int v) {
        if (v < 300 && !m_loading && m_hasMore && !m_searching && !m_conv.isEmpty())
            load(true);
        if (atBottom()) {
            m_newBelow = 0;
            m_jump->hide();
            emit readable();
        }
    });
    connect(Theme::instance(), &Theme::changed, viewport(), qOverload<>(&QWidget::update));
    connect(Theme::instance(), &Theme::fontChanged, this, [this] {
        const int d = verticalScrollBar()->maximum() - verticalScrollBar()->value();
        doItemsLayout();
        restoreBottomDistance(d);
    });
}

void MessageView::paintEvent(QPaintEvent *e)
{
    QListView::paintEvent(e);
    if (m_jump->isVisible() && m_newBelow > 0) {
        QPainter p(viewport());
        p.setRenderHint(QPainter::Antialiasing);
        const QString n = QString::number(m_newBelow);
        const QRectF b(m_jump->geometry().right() - 14, m_jump->geometry().top() - 8, 22, 20);
        p.setPen(Qt::NoPen);
        p.setBrush(Theme::p().action);
        p.drawRoundedRect(b, 8, 8);
        p.setPen(Theme::p().onAccent);
        p.setFont(Theme::instance()->font(11, QFont::Bold));
        p.drawText(b, Qt::AlignCenter, n);
    }
}

bool MessageView::atBottom() const
{
    return verticalScrollBar()->value() >= verticalScrollBar()->maximum() - 40;
}

void MessageView::setConversation(const QString &id, bool group)
{
    m_conv = id;
    m_hasMore = true;
    m_searching = false;
    m_newBelow = 0;
    m_jump->hide();
    ++m_generation;
    m_loading = false;
    m_model->clear();
    m_model->group = group;
    m_model->flat = false;
    if (!id.isEmpty())
        load(false);
}

void MessageView::refresh()
{
    if (m_searching || m_conv.isEmpty())
        return;
    load(false);
}

void MessageView::load(bool older)
{
    if (older && (m_loading || !m_hasMore))
        return;
    m_loading = true;
    const int gen = m_generation;
    QJsonObject params{{"conversation_id", m_conv}};
    if (older) {
        params["before"] = double(m_model->oldestLocalId());
        params["limit"] = 50;
    } else {
        params["limit"] = qBound(50, m_model->messageCount() + 20, 200);
    }
    Core::instance()->call("list_messages", params, this, [this, gen, older](const QJsonValue &v, const CoreError &e) {
        if (gen != m_generation)
            return;
        m_loading = false;
        if (!e.ok())
            return emit toast(e.message);
        QList<QJsonObject> page;
        for (const auto &x : v.toArray())
            page << x.toObject();
        if (older && page.size() < 50)
            m_hasMore = false;
        applyPage(page, older);
    });
}

void MessageView::applyPage(const QList<QJsonObject> &page, bool older)
{
    const bool first = m_model->messageCount() == 0;
    const bool wasBottom = first || atBottom();
    const int distance = verticalScrollBar()->maximum() - verticalScrollBar()->value();
    const QStringList appended = m_model->merge(page, !older && !first);
    doItemsLayout();
    if (wasBottom && !older) {
        scrollToBottom();
    } else {
        restoreBottomDistance(distance);
        int incoming = 0;
        for (const auto &m : page)
            if (appended.contains(m.value("id").toString()) && !m.value("outgoing").toBool())
                ++incoming;
        // Our own message always brings the view down; incoming ones never move it.
        bool own = false;
        for (const auto &m : page)
            if (appended.contains(m.value("id").toString()) && m.value("outgoing").toBool())
                own = true;
        if (own) {
            scrollToBottom();
        } else if (incoming > 0) {
            m_newBelow += incoming;
            placeJump();
            m_jump->show();
            viewport()->update();
        }
    }
    if (!appended.isEmpty() && !older && !first)
        m_anim.start();
    if (atBottom())
        emit readable();
}

void MessageView::restoreBottomDistance(int distance)
{
    verticalScrollBar()->setValue(verticalScrollBar()->maximum() - distance);
}

void MessageView::scrollToNewest()
{
    if (m_searching)
        return;
    scrollToBottom();
    m_newBelow = 0;
    m_jump->hide();
}

void MessageView::showSearch(const QString &query)
{
    ++m_generation;
    if (query.trimmed().isEmpty()) {
        if (m_searching) {
            m_searching = false;
            m_model->clear();
            m_model->flat = false;
            load(false);
        }
        return;
    }
    m_searching = true;
    const int gen = m_generation;
    Core::instance()->call("search_messages", {{"conversation_id", m_conv}, {"query", query}}, this, [this, gen](const QJsonValue &v, const CoreError &e) {
        if (gen != m_generation)
            return;
        if (!e.ok())
            return emit toast(e.message);
        QList<QJsonObject> list;
        for (const auto &x : v.toArray())
            list << x.toObject();
        m_model->flat = true;
        m_model->setFlat(list);
        doItemsLayout();
        scrollToBottom();
    });
}

void MessageView::resizeEvent(QResizeEvent *e)
{
    const int d = verticalScrollBar()->maximum() - verticalScrollBar()->value();
    QListView::resizeEvent(e);
    doItemsLayout();
    restoreBottomDistance(d);
    placeJump();
}

void MessageView::placeJump()
{
    m_jump->move(viewport()->width() - 64, viewport()->height() - 60);
    m_jump->raise();
}

QJsonObject MessageView::messageAt(const QPoint &pos, MessageLayout *lay) const
{
    const QModelIndex idx = indexAt(pos);
    if (!idx.isValid())
        return {};
    const MessageItem &it = m_model->item(idx.row());
    if (it.isDate)
        return {};
    const QRect vr = visualRect(idx);
    MessageLayout L = MessageDelegate::layout(it, vr.width(), m_model->group && !m_model->flat);
    L.bubble.translate(vr.topLeft());
    L.download.translate(vr.topLeft());
    L.retry.translate(vr.topLeft());
    if (lay)
        *lay = L;
    return it.msg;
}

void MessageView::mouseMoveEvent(QMouseEvent *e)
{
    MessageLayout L;
    const QJsonObject m = messageAt(e->pos(), &L);
    const bool hand = !m.isEmpty() && (L.download.contains(e->pos()) || L.retry.contains(e->pos()));
    viewport()->setCursor(hand ? Qt::PointingHandCursor : Qt::ArrowCursor);
    QListView::mouseMoveEvent(e);
}

void MessageView::mouseReleaseEvent(QMouseEvent *e)
{
    MessageLayout L;
    const QJsonObject m = messageAt(e->pos(), &L);
    if (e->button() == Qt::LeftButton && !m.isEmpty()) {
        if (L.download.contains(e->pos()))
            openFile(m);
        else if (L.retry.contains(e->pos()))
            retry(m);
    }
    QListView::mouseReleaseEvent(e);
}

void MessageView::keyPressEvent(QKeyEvent *e)
{
    const QModelIndex idx = currentIndex();
    if (idx.isValid() && !m_model->item(idx.row()).isDate) {
        const QJsonObject m = m_model->item(idx.row()).msg;
        if (e->matches(QKeySequence::Copy)) {
            QApplication::clipboard()->setText(messageText(m));
            return;
        }
        if ((e->key() == Qt::Key_Return || e->key() == Qt::Key_Enter) && m.value("kind").toString() == "file") {
            openFile(m);
            return;
        }
    }
    QListView::keyPressEvent(e);
}

void MessageView::contextMenuEvent(QContextMenuEvent *e)
{
    const QPoint pos = e->reason() == QContextMenuEvent::Mouse ? e->pos() : visualRect(currentIndex()).center();
    const QJsonObject m = messageAt(pos);
    if (m.isEmpty())
        return;
    setCurrentIndex(indexAt(pos));
    QMenu menu(this);
    const QString kind = m.value("kind").toString();
    if (kind != "undecryptable" && !m_searching)
        menu.addAction(QStringLiteral("Ответить"), this, [this, m] { emit replyRequested(m); });
    if (!messageText(m).isEmpty())
        menu.addAction(QStringLiteral("Копировать текст"), this, [m] { QApplication::clipboard()->setText(messageText(m)); });
    if (kind == "file") {
        menu.addAction(QStringLiteral("Открыть файл"), this, [this, m] { openFile(m); });
        menu.addAction(QStringLiteral("Сохранить как…"), this, [this, m] { saveFile(m); });
    }
    if (m.value("status").toString() == "failed")
        menu.addAction(QStringLiteral("Повторить отправку"), this, [this, m] { retry(m); });
    menu.addSeparator();
    menu.addAction(QStringLiteral("Удалить у себя"), this, [this, m] {
        Core::instance()->call("delete_local_message", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                emit toast(e.message);
        });
    });
    menu.exec(mapToGlobal(pos) + QPoint(0, 4));
}

void MessageView::openFile(const QJsonObject &m)
{
    emit toast(QStringLiteral("Расшифровываем файл…"));
    Core::instance()->call("download_file", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return emit toast(e.message);
        QDesktopServices::openUrl(QUrl::fromLocalFile(v.toObject().value("path").toString()));
    });
}

void MessageView::saveFile(const QJsonObject &m)
{
    const QString name = m.value("file").toObject().value("name").toString();
    const QString dest = QFileDialog::getSaveFileName(this, QStringLiteral("Сохранить файл"),
                                                      QStandardPaths::writableLocation(QStandardPaths::DownloadLocation) + "/" + name);
    if (dest.isEmpty())
        return;
    Core::instance()->call("export_file", {{"message_id", m.value("id")}, {"dest", dest}}, this, [this](const QJsonValue &, const CoreError &e) {
        emit toast(e.ok() ? QStringLiteral("Файл сохранён") : e.message);
    });
}

void MessageView::retry(const QJsonObject &m)
{
    Core::instance()->call("retry_message", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
        if (!e.ok())
            emit toast(e.message);
    });
}
