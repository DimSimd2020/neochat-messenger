#include "messages.h"

#include "chrome.h"
#include "filepicker.h"
#include "popupmenu.h"

#include "core.h"
#include "emoji.h"
#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QClipboard>
#include <QContextMenuEvent>
#include <QDateTime>
#include <QDesktopServices>
#include <QAbstractTextDocumentLayout>
#include <QFontDatabase>
#include <QGraphicsOpacityEffect>
#include <QHelpEvent>
#include <QHBoxLayout>
#include <QImageReader>
#include <QJsonArray>
#include <QKeyEvent>
#include <QLocale>
#include <QPainter>
#include <QPropertyAnimation>
#include <QScrollBar>
#include <QSettings>
#include <QToolButton>
#include <QWidgetAction>
#include <QStandardPaths>
#include <QTextBlock>
#include <QTextCursor>
#include <QTextDocument>
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
QFont pollFont() { return Theme::instance()->font(15.5, QFont::DemiBold); }
QFont reactFont() { return Theme::instance()->font(13.5, QFont::DemiBold); }

// One to three emoji without text are drawn large, without a bubble.
QFont bigEmojiFont(int n)
{
    QFont f(QStringLiteral("Segoe UI Emoji"));
    f.setPixelSize(n <= 1 ? 52 : n == 2 ? 42 : 34);
    return f;
}

int bigEmojiCount(const QJsonObject &m)
{
    if (m.value("kind").toString() != "text" || m.value("reply_to").isObject())
        return 0;
    return m.value("emoji_only").toInt();
}

bool isImage(const QJsonObject &m)
{
    return m.value("kind").toString() == "file" && m.value("file").toObject().value("image").toBool();
}

QString votesText(int n)
{
    const int m10 = n % 10, m100 = n % 100;
    const char *word = (m10 == 1 && m100 != 11) ? "голос" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "голоса" : "голосов";
    return QString::number(n) + " " + QString::fromUtf8(word);
}

// Rich text of a message built from the core's markdown spans; cached per text and font.
QTextDocument *textDoc(const QJsonObject &m)
{
    static QHash<QString, QTextDocument *> cache;
    const QFont font = textFont();
    const QString key = m.value("id").toString() + '|' + font.family() + '|' + QString::number(font.pointSizeF()) + '|' + messageText(m);
    if (QTextDocument *d = cache.value(key))
        return d;
    if (cache.size() > 3000) {
        qDeleteAll(cache);
        cache.clear();
    }
    auto *d = new QTextDocument;
    d->setDocumentMargin(0);
    d->setDefaultFont(font);
    QTextOption o;
    o.setWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
    d->setDefaultTextOption(o);
    QTextCursor cur(d);
    const QJsonArray spans = m.value("spans").toArray();
    if (spans.isEmpty())
        cur.insertText(messageText(m));
    QFont mono = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    if (font.pixelSize() > 0)
        mono.setPixelSize(qRound(font.pixelSize() * 0.9));
    else
        mono.setPointSizeF(font.pointSizeF() * 0.9);
    for (const auto &v : spans) {
        const QJsonObject s = v.toObject();
        QTextCharFormat f;
        if (s.value("code").toBool() || s.value("pre").toBool()) {
            f.setFont(mono);
            f.setBackground(QColor(127, 127, 127, 48));
        }
        if (s.value("bold").toBool())
            f.setFontWeight(QFont::Bold);
        if (s.value("italic").toBool())
            f.setFontItalic(true);
        if (s.value("strike").toBool())
            f.setFontStrikeOut(true);
        if (s.contains("mention")) { f.setForeground(Theme::p().accent); f.setFontWeight(QFont::DemiBold); }
        if (s.contains("link")) {
            f.setAnchor(true);
            f.setAnchorHref(s.value("link").toString());
            f.setFontUnderline(true);
        }
        QString t = s.value("text").toString();
        if (s.value("pre").toBool()) {
            // A code block always stands on its own lines.
            if (!cur.atBlockStart())
                cur.insertBlock();
            cur.insertText(t, f);
            cur.insertBlock();
            cur.setCharFormat({});
            continue;
        }
        cur.insertText(t, f);
    }
    // Drop the empty line a trailing code block leaves behind.
    if (cur.block().text().isEmpty() && cur.block().previous().isValid()) {
        cur.deletePreviousChar();
    }
    cache.insert(key, d);
    return d;
}

// Floating pill with bulk actions while several messages are checked.
class CheckBar : public QWidget {
public:
    using QWidget::QWidget;

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const Palette &c = Theme::p();
        p.setPen(QPen(c.border, 1));
        p.setBrush(c.raised);
        p.drawRoundedRect(rect().adjusted(0.5, 0.5, -0.5, -0.5), 22, 22);
    }
};

// Full-window photo viewer: click, Esc or Space closes; fades unless motion is reduced.
class PhotoViewer : public QWidget {
public:
    PhotoViewer(QWidget *win, const QImage &img) : QWidget(win), m_img(img)
    {
        setAttribute(Qt::WA_DeleteOnClose);
        setFocusPolicy(Qt::StrongFocus);
        setAccessibleName(QStringLiteral("Просмотр фото. Esc — закрыть"));
        setGeometry(win->rect());
        win->installEventFilter(this);
        auto *fx = new QGraphicsOpacityEffect(this);
        fx->setOpacity(0);
        setGraphicsEffect(fx);
        m_fade = new QPropertyAnimation(fx, "opacity", this);
        show();
        raise();
        setFocus(Qt::OtherFocusReason);
        fade(1);
    }

protected:
    bool eventFilter(QObject *o, QEvent *e) override
    {
        if (o == parent() && e->type() == QEvent::Resize)
            setGeometry(parentWidget()->rect());
        return false;
    }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        p.fillRect(rect(), QColor(4, 9, 20, 236));
        const QRectF avail = QRectF(rect()).adjusted(32, 32, -32, -32);
        const qreal s = qMin(qMin(avail.width() / m_img.width(), avail.height() / m_img.height()), 1.0 / devicePixelRatioF());
        QSizeF sz = QSizeF(m_img.size()) * s;
        p.drawImage(QRectF(avail.center() - QPointF(sz.width() / 2, sz.height() / 2), sz), m_img);
    }
    void mousePressEvent(QMouseEvent *) override { fade(0); }
    void keyPressEvent(QKeyEvent *e) override
    {
        if (e->key() == Qt::Key_Escape || e->key() == Qt::Key_Space || e->key() == Qt::Key_Return)
            fade(0);
    }

private:
    void fade(qreal to)
    {
        m_fade->stop();
        m_fade->setDuration(Theme::ms(180));
        m_fade->setEndValue(to);
        if (to == 0)
            connect(m_fade, &QPropertyAnimation::finished, this, &QWidget::close);
        m_fade->start();
    }
    QImage m_img;
    QPropertyAnimation *m_fade;
};

} // namespace

// ---- images ----

ImageCache *ImageCache::instance()
{
    static ImageCache *c = new ImageCache(qApp);
    return c;
}

QPixmap ImageCache::thumb(const QJsonObject &m)
{
    const QString id = m.value("id").toString();
    auto it = m_thumbs.find(id);
    if (it != m_thumbs.end())
        return *it;
    QPixmap px;
    px.loadFromData(QByteArray::fromBase64(m.value("file").toObject().value("thumb").toString().toLatin1()));
    m_thumbs.insert(id, px);
    return px;
}

QPixmap ImageCache::full(const QJsonObject &m)
{
    const QString id = m.value("id").toString();
    auto it = m_full.find(id);
    if (it != m_full.end())
        return *it;
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    // Large originals are fetched only on demand; failures retry after 30 s.
    if (m.value("file").toObject().value("size").toDouble() > 25e6 || now - m_pending.value(id, 0) < 30000)
        return {};
    m_pending.insert(id, now);
    Core::instance()->call("download_file", {{"message_id", id}}, this, [this, id](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return;
        QImageReader reader(v.toObject().value("path").toString());
        reader.setAutoTransform(true);
        const QSize sz = reader.size();
        if (sz.isValid() && qMax(sz.width(), sz.height()) > 1100)
            reader.setScaledSize(sz.scaled(1100, 1100, Qt::KeepAspectRatio));
        const QImage img = reader.read();
        if (img.isNull())
            return;
        m_full.insert(id, QPixmap::fromImage(img));
        m_pending.remove(id);
        emit changed();
    });
    return {};
}

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
        if (isImage(m))
            s += QStringLiteral("фото. ");
        else if (m.value("kind").toString() == "file")
            s += QStringLiteral("файл ") + m.value("file").toObject().value("name").toString() + ". ";
        else if (m.value("kind").toString() == "poll")
            s += QStringLiteral("опрос. ");
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
    m_react.clear();
    endResetModel();
}

qint64 MessageModel::oldestLocalId() const
{
    return m_messages.isEmpty() ? std::numeric_limits<qint64>::max() : qint64(m_messages.first().value("local_id").toDouble());
}

QStringList MessageModel::merge(const QList<QJsonObject> &page, bool animateNew, bool older)
{
    const qint64 newest = m_messages.isEmpty() ? -1 : qint64(m_messages.last().value("local_id").toDouble());
    if (!older) {
        QSet<QString> ids;
        for (const auto &m : page)
            ids.insert(m.value("id").toString());
        const qint64 oldest = page.isEmpty() ? 0 : qint64(page.first().value("local_id").toDouble());
        m_messages.removeIf([&](const QJsonObject &m) {
            return qint64(m.value("local_id").toDouble()) >= oldest && !ids.contains(m.value("id").toString());
        });
    }
    QHash<QString, int> pos;
    for (int i = 0; i < m_messages.size(); ++i)
        pos.insert(m_messages[i].value("id").toString(), i);
    QStringList appended;
    for (const auto &m : page) {
        const QString id = m.value("id").toString();
        auto it = pos.find(id);
        if (it != pos.end()) {
            if (m_messages[*it].value("reactions") != m.value("reactions"))
                m_react.insert(id, clockMs());
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
        it.reactAt = m_react.value(m.value("id").toString(), -1);
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
    const QJsonValue fwd = m.value("fwd");
    if (fwd.isObject()) {
        L.fwd = QRectF(0, h, qMin(inner, 320.0), 20);
        w = qMax(w, L.fwd.width());
        h += 24;
    }
    const QJsonValue reply = m.value("reply_to");
    if (reply.isObject()) {
        L.reply = QRectF(0, h, qMin(inner, 320.0), 40);
        w = qMax(w, L.reply.width());
        h += 46;
    }
    const QString kind = m.value("kind").toString();
    const QString text = messageText(m);
    if (isImage(m)) {
        const QJsonObject f = m.value("file").toObject();
        qreal iw = f.value("width").toDouble(), ih = f.value("height").toDouble();
        if (iw <= 0 || ih <= 0)
            iw = 4, ih = 3;
        const qreal s = qMin(qMin(maxBubble - 8, 340.0) / iw, 360.0 / ih);
        const qreal top = L.reply.isNull() && L.fwd.isNull() ? 4 : h;
        L.image = QRectF(0, top, qMax(140.0, iw * s), qMax(90.0, ih * s));
        w = qMax(w, L.image.width() + 8 - 2 * kPadH);
        h = top + L.image.height() + 6;
    } else if (kind == "file") {
        L.file = QRectF(0, h + 3, qMin(inner, 300.0), 62);
        w = qMax(w, L.file.width());
        h += 70;
    }
    if (kind == "poll") {
        const qreal pw = qMin(inner, 320.0);
        const QRectF br = QFontMetricsF(pollFont()).boundingRect(QRectF(0, 0, pw, 1e6), Qt::TextWordWrap, text);
        L.text = QRectF(0, h, pw, br.height());
        h += br.height() + 2;
        L.pollInfo = QRectF(0, h, pw, 18);
        h += 24;
        for (int i = 0; i < m.value("poll").toObject().value("options").toArray().size(); ++i) {
            L.options << QRectF(0, h, pw, 40);
            h += 42;
        }
        w = qMax(w, pw);
    } else if (kind == "call") {
        const qreal cw = qMin(inner, 48 + QFontMetricsF(Theme::instance()->font(14.5, QFont::DemiBold)).horizontalAdvance(text) + 2);
        L.call = QRectF(0, h + 2, cw, 38);
        w = qMax(w, cw);
        h += 44;
    } else if (const int n = bigEmojiCount(m); n > 0) {
        const QFontMetricsF bf(bigEmojiFont(n));
        L.big = true;
        L.text = QRectF(0, h, bf.horizontalAdvance(text) + 4, bf.height());
        w = qMax(w, L.text.width());
        h += L.text.height() + 2;
    } else if (!text.isEmpty()) {
        QTextDocument *d = textDoc(m);
        if (d->textWidth() != inner)
            d->setTextWidth(inner);
        L.text = QRectF(0, h, qMin(inner, std::ceil(d->idealWidth()) + 1), d->size().height());
        w = qMax(w, L.text.width());
        h += L.text.height() + 2;
    }
    const QJsonArray reacts = m.value("reactions").toArray();
    if (!reacts.isEmpty()) {
        const QFontMetricsF rf(reactFont());
        const qreal rowW = qMax(inner, 160.0);
        qreal rx = 0;
        h += 4;
        for (const auto &v : reacts) {
            const QJsonObject r = v.toObject();
            const qreal cw = rf.horizontalAdvance(r.value("emoji").toString() + " " + QString::number(r.value("count").toInt())) + 20;
            if (rx > 0 && rx + cw > rowW) {
                rx = 0;
                h += 30;
            }
            L.reacts << QRectF(rx, h, cw, 26);
            rx += cw + 6;
            w = qMax(w, rx - 6);
        }
        h += 30;
    }
    const QFontMetricsF mf(metaFont());
    const QString time = QDateTime::fromMSecsSinceEpoch(qint64(m.value("sent_at").toDouble())).toString("HH:mm");
    const QString stamp = (m.value("edited").toBool() ? QStringLiteral("изменено ") : QString()) + time;
    qreal metaW = mf.horizontalAdvance(stamp) + (out ? 24 : 0);
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
    place(L.fwd, false);
    place(L.reply, false);
    place(L.file, false);
    place(L.text, false);
    place(L.call, false);
    place(L.pollInfo, false);
    for (QRectF &o : L.options)
        place(o, false);
    place(L.meta, true);
    for (QRectF &r : L.reacts)
        r.moveTo(L.bubble.left() + kPadH + r.left(), y + r.top());
    if (!L.image.isNull())
        L.image.moveTo(L.bubble.left() + 4, y + L.image.top());
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
    const auto *view = qobject_cast<const MessageView *>(parent());
    const bool checking = view && view->checkMode();
    const bool checked = checking && view->isChecked(m.value("id").toString());
    if (checked)
        p->fillRect(QRectF(0, 0, width, opt.rect.height()), QColor(c.accent.red(), c.accent.green(), c.accent.blue(), 22));

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
        const QString name = m.value("sender_name").toString();
        p->drawText(L.name, Qt::AlignLeft | Qt::AlignVCenter, name);
        const QString tag = m.value("sender_tag").toString();
        if (!tag.isEmpty()) {
            p->setFont(Theme::instance()->font(12));
            p->setPen(c.muted);
            const qreal nx = QFontMetricsF(Theme::instance()->font(12.5, QFont::DemiBold)).horizontalAdvance(name) + 8;
            p->drawText(L.name.adjusted(nx, 0, 0, 0), Qt::AlignLeft | Qt::AlignVCenter, tag);
        }
    }
    if (!L.avatar.isNull())
        ui::paintAvatar(p, L.avatar, m.value("sender_name").toString(), m.value("sender_user_id").toString(), m.value("sender_avatar_path").toString());

    const qreal R = 18, r = 6;
    const QPainterPath bubble = out ? ui::roundedPath(L.bubble, R, it->groupStart ? R : r, r, R)
                                    : ui::roundedPath(L.bubble, it->groupStart ? R : r, R, R, r);
    const bool undecryptable = m.value("kind").toString() == "undecryptable";
    if (!L.big) {
        p->setPen(undecryptable ? QPen(c.warning, 1, Qt::DashLine) : Qt::NoPen);
        p->setBrush(out ? c.outgoing : c.raised);
        p->drawPath(bubble);
    }
    if (opt.state & QStyle::State_Selected) {
        p->setPen(QPen(c.accent, 2));
        p->setBrush(Qt::NoBrush);
        p->drawPath(bubble);
    }
    const bool tinted = out && !L.big;
    const QColor fg = tinted ? c.onOutgoing : (undecryptable ? c.warning : c.text);
    const QColor meta = tinted ? c.outgoingMuted : c.muted;

    if (!L.fwd.isNull()) {
        const QColor fc = out ? c.onOutgoing : c.accent;
        p->setPen(Qt::NoPen);
        p->setBrush(fc);
        p->drawRoundedRect(QRectF(L.fwd.left(), L.fwd.top() + 2, 3, L.fwd.height() - 4), 1.5, 1.5);
        p->setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p->setPen(fc);
        p->drawText(L.fwd.adjusted(10, 0, -6, 0), Qt::AlignLeft | Qt::AlignVCenter,
                    QFontMetricsF(p->font()).elidedText(QStringLiteral("Переслано: ") + m.value("fwd").toObject().value("name").toString(), Qt::ElideRight, L.fwd.width() - 16));
    }
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

    if (!L.image.isNull()) {
        QPixmap px = ImageCache::instance()->full(m);
        if (px.isNull())
            px = ImageCache::instance()->thumb(m);
        p->save();
        QPainterPath clip;
        clip.addRoundedRect(L.image, 14, 14);
        p->setClipPath(clip);
        p->fillRect(L.image, c.selected);
        if (!px.isNull()) {
            // Cover the frame, cropping the overflow evenly.
            p->setRenderHint(QPainter::SmoothPixmapTransform);
            const qreal k = qMax(L.image.width() / px.width(), L.image.height() / px.height());
            const QSizeF src = L.image.size() / k;
            p->drawPixmap(L.image, px, QRectF(QPointF((px.width() - src.width()) / 2, (px.height() - src.height()) / 2), src));
        }
        p->restore();
    }

    if (!L.options.isEmpty()) {
        const QJsonObject poll = m.value("poll").toObject();
        const QJsonArray opts = poll.value("options").toArray();
        const QJsonArray my = poll.value("my_choices").toArray();
        const bool closed = poll.value("closed").toBool(), multiple = poll.value("multiple").toBool();
        const int total = poll.value("voters").toInt();
        const bool results = closed || !my.isEmpty();
        p->setFont(pollFont());
        p->setPen(fg);
        p->drawText(L.text, Qt::TextWordWrap, messageText(m));
        QString info = closed ? QStringLiteral("Опрос завершён") : poll.value("anonymous").toBool() ? QStringLiteral("Анонимный опрос") : QStringLiteral("Открытый опрос");
        if (multiple)
            info += QStringLiteral(" · несколько ответов");
        info += QStringLiteral(" · ") + votesText(total);
        p->setFont(Theme::instance()->font(12.5));
        p->setPen(meta);
        p->drawText(L.pollInfo, Qt::AlignLeft | Qt::AlignVCenter, QFontMetricsF(p->font()).elidedText(info, Qt::ElideRight, L.pollInfo.width()));
        const QColor strong = out ? c.onOutgoing : c.accent;
        for (int i = 0; i < L.options.size() && i < opts.size(); ++i) {
            const QRectF r = L.options[i];
            const QJsonObject o = opts[i].toObject();
            const bool chosen = my.contains(i);
            const int votes = o.value("votes").toInt();
            const qreal textX = r.left() + (results ? 48 : 30);
            p->setFont(Theme::instance()->font(14.5, chosen ? QFont::DemiBold : QFont::Normal));
            p->setPen(fg);
            p->drawText(QRectF(textX, r.top(), r.right() - textX, results ? 26 : r.height()), Qt::AlignLeft | Qt::AlignVCenter,
                        QFontMetricsF(p->font()).elidedText(o.value("text").toString(), Qt::ElideRight, r.right() - textX));
            if (results) {
                const int pct = total ? qRound(100.0 * votes / total) : 0;
                p->setFont(Theme::instance()->font(13, QFont::DemiBold));
                p->setPen(chosen ? strong : meta);
                p->drawText(QRectF(r.left(), r.top(), 44, 26), Qt::AlignLeft | Qt::AlignVCenter, QString::number(pct) + "%");
                const QRectF track(textX, r.bottom() - 10, r.right() - textX, 4);
                QColor tc = meta;
                tc.setAlpha(60);
                p->setPen(Qt::NoPen);
                p->setBrush(tc);
                p->drawRoundedRect(track, 2, 2);
                p->setBrush(strong);
                if (votes > 0)
                    p->drawRoundedRect(QRectF(track.topLeft(), QSizeF(qMax(4.0, track.width() * votes / qMax(total, 1)), 4)), 2, 2);
            } else {
                const QRectF box(r.left() + 2, r.center().y() - 9, 18, 18);
                p->setPen(QPen(meta, 1.6));
                p->setBrush(Qt::NoBrush);
                if (multiple)
                    p->drawRoundedRect(box, 5, 5);
                else
                    p->drawEllipse(box);
            }
        }
    } else if (!L.call.isNull()) {
        const QJsonObject call = m.value("call").toObject();
        const bool missed = !out && call.value("status").toString() == "missed";
        const QRectF dot(L.call.left(), L.call.top(), 38, 38);
        p->setPen(Qt::NoPen);
        p->setBrush(out ? QColor(255, 255, 255, 46) : c.selected);
        p->drawEllipse(dot);
        ui::paintIcon(p, dot.adjusted(10, 10, -10, -10), call.value("video").toBool() ? "video" : "phone", missed ? c.warning : (out ? c.onOutgoing : c.accent));
        p->setFont(Theme::instance()->font(14.5, QFont::DemiBold));
        p->setPen(missed ? c.warning : fg);
        const QRectF tr = L.call.adjusted(48, 0, 0, 0);
        p->drawText(tr, Qt::AlignLeft | Qt::AlignVCenter, QFontMetricsF(p->font()).elidedText(messageText(m), Qt::ElideRight, tr.width()));
    } else if (L.big) {
        p->setFont(bigEmojiFont(bigEmojiCount(m)));
        p->setPen(fg);
        p->drawText(L.text, Qt::AlignLeft | Qt::AlignVCenter, messageText(m));
    } else if (!L.text.isNull()) {
        QTextDocument *d = textDoc(m);
        QAbstractTextDocumentLayout::PaintContext ctx;
        ctx.palette.setColor(QPalette::Text, fg);
        if (selA != selB && selId == m.value("id").toString()) {
            QAbstractTextDocumentLayout::Selection sel;
            sel.cursor = QTextCursor(d);
            sel.cursor.setPosition(qMin(selA, selB));
            sel.cursor.setPosition(qMax(selA, selB), QTextCursor::KeepAnchor);
            QColor hl = c.accent;
            hl.setAlphaF(0.4);
            sel.format.setBackground(hl);
            sel.format.setForeground(fg);
            ctx.selections << sel;
        }
        p->save();
        p->translate(L.text.topLeft());
        d->documentLayout()->draw(p, ctx);
        p->restore();
    }

    const QJsonArray reacts = m.value("reactions").toArray();
    // Chips pop in together when the reactions set changed just now.
    const qreal rt = it->reactAt >= 0 && Theme::ms(180) > 0 ? qBound(0.0, (clockMs() - it->reactAt) / qreal(Theme::ms(180)), 1.0) : 1.0;
    if (rt < 1 && !L.reacts.isEmpty()) {
        p->save();
        p->setOpacity(rt);
        const qreal e = Theme::surfaceEasing().valueForProgress(rt);
        const qreal s = 0.6 + 0.4 * e;
        p->translate(L.reacts[0].center());
        p->scale(s, s);
        p->translate(-L.reacts[0].center());
    }
    p->setFont(reactFont());
    for (int i = 0; i < L.reacts.size() && i < reacts.size(); ++i) {
        const QJsonObject r = reacts[i].toObject();
        const bool mine = r.value("mine").toBool();
        QColor bg = tinted ? QColor(255, 255, 255, mine ? 80 : 36) : (mine ? c.action : c.muted);
        if (!tinted)
            bg.setAlphaF(mine ? 0.28f : 0.16f);
        p->setPen(mine ? QPen(tinted ? c.onOutgoing : c.accent, 1) : Qt::NoPen);
        p->setBrush(bg);
        p->drawRoundedRect(L.reacts[i].adjusted(0.5, 0.5, -0.5, -0.5), 13, 13);
        p->setPen(fg);
        p->drawText(L.reacts[i], Qt::AlignCenter, r.value("emoji").toString() + " " + QString::number(r.value("count").toInt()));
    }
    if (rt < 1 && !L.reacts.isEmpty())
        p->restore();

    p->setFont(metaFont());
    p->setPen(meta);
    const QString time = QDateTime::fromMSecsSinceEpoch(qint64(m.value("sent_at").toDouble())).toString("HH:mm");
    const QString stamp = (m.value("edited").toBool() ? QStringLiteral("изменено ") : QString()) + time;
    p->drawText(L.meta, Qt::AlignLeft | Qt::AlignVCenter, stamp);
    if (out) {
        const QString st = m.value("status").toString();
        const QRectF ir(L.meta.left() + QFontMetricsF(p->font()).horizontalAdvance(stamp) + 4, L.meta.center().y() - 9, 18, 18);
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
    if (checking) {
        const QRectF cr(12, L.height / 2 - 11, 22, 22);
        p->setPen(QPen(checked ? c.accent : c.muted, 1.8));
        p->setBrush(checked ? c.accent : Qt::NoBrush);
        p->drawEllipse(cr);
        if (checked)
            ui::paintIcon(p, cr.adjusted(5, 5, -5, -5), "tick", c.onAccent, 2.2);
    }
    p->restore();
}

// ---- view ----

MessageView::MessageView(QWidget *parent) : QListView(parent)
{
    m_model = new MessageModel(this);
    setModel(m_model);
    m_delegate = new MessageDelegate(this);
    setItemDelegate(m_delegate);
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
        const qint64 now = clockMs();
        for (int i = 0; i < m_model->rowCount(); ++i) {
            const auto &it = m_model->item(i);
            if ((it.appearAt >= 0 && now - it.appearAt < kAppearMs) || (it.reactAt >= 0 && now - it.reactAt < 180))
                running = true;
        }
        if (!running)
            m_anim.stop();
    });
    m_jump = new ui::IconButton("down", QStringLiteral("В самый низ"), this, 44);
    m_jump->setFloating(true);
    m_jump->hide();
    connect(m_jump, &QAbstractButton::clicked, this, &MessageView::scrollToNewest);
    connect(verticalScrollBar(), &QScrollBar::valueChanged, this, [this](int v) {
        if (v < 300 && !m_loading && m_hasMore && !m_searching && !m_conv.isEmpty())
            load(true);
        if (atBottom()) {
            m_newBelow = 0;
            m_jump->hide();
            emit readable();
        } else if (!m_searching && verticalScrollBar()->maximum() - v > viewport()->height() && !m_jump->isVisible()) {
            // More than a screen of history below: offer the way back down.
            placeJump();
            m_jump->show();
        }
    });
    connect(Theme::instance(), &Theme::changed, viewport(), qOverload<>(&QWidget::update));
    connect(ImageCache::instance(), &ImageCache::changed, viewport(), qOverload<>(&QWidget::update));
    connect(Theme::instance(), &Theme::fontChanged, this, [this] {
        const int d = verticalScrollBar()->maximum() - verticalScrollBar()->value();
        doItemsLayout();
        restoreBottomDistance(d);
    });
}

void MessageView::paintEvent(QPaintEvent *e)
{
    {
        QPainter bg(viewport());
        Theme::paintBackdrop(bg, viewport());
    }
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
    exitCheckMode();
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
    const QStringList appended = m_model->merge(page, !older && !first, older);
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
    bool fresh = !appended.isEmpty() && !older && !first;
    if (!fresh)
        for (int i = 0; i < m_model->rowCount(); ++i)
            if (const auto &it = m_model->item(i); it.reactAt >= 0 && clockMs() - it.reactAt < 2000) {
                fresh = true;
                break;
            }
    if (fresh)
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
    placeCheckBar();
}

void MessageView::placeJump()
{
    m_jump->move(viewport()->width() - 64, viewport()->height() - 60);
    m_jump->raise();
}

void MessageView::placeCheckBar()
{
    if (!m_checkBar)
        return;
    m_checkBar->adjustSize();
    m_checkBar->move((viewport()->width() - m_checkBar->width()) / 2, 10);
    m_checkBar->raise();
}

// Checked messages in chat order for bulk actions.
static QList<QJsonObject> checkedMessages(const MessageModel *model, const QSet<QString> &ids)
{
    QList<QJsonObject> out;
    for (int i = 0; i < model->rowCount(); ++i) {
        const MessageItem &it = model->item(i);
        if (!it.isDate && ids.contains(it.msg.value("id").toString()))
            out << it.msg;
    }
    return out;
}

void MessageView::enterCheckMode(const QString &id)
{
    if (id.isEmpty())
        return;
    if (!m_checkMode) {
        m_checkMode = true;
        clearSelection();
        auto *bar = new CheckBar(this);
        auto *l = new QHBoxLayout(bar);
        l->setContentsMargins(18, 4, 8, 4);
        l->setSpacing(8);
        m_checkCount = new QLabel(bar);
        l->addWidget(m_checkCount);
        auto *fwd = new ui::Button(QStringLiteral("Переслать"), ui::Button::Flat, bar);
        auto *copy = new ui::Button(QStringLiteral("Копировать"), ui::Button::Flat, bar);
        auto *del = new ui::Button(QStringLiteral("Удалить…"), ui::Button::Danger, bar);
        auto *x = new ui::IconButton("close", QStringLiteral("Отмена"), bar, 32);
        l->addWidget(fwd);
        l->addWidget(copy);
        l->addWidget(del);
        l->addWidget(x);
        connect(fwd, &QAbstractButton::clicked, bar, [this] {
            const QList<QJsonObject> sel = checkedMessages(m_model, m_checked);
            QJsonArray arr;
            for (const auto &m : sel)
                if (const QString k = m.value("kind").toString(); (k == "text" || k == "file" || k == "poll") && m.value("status").toString() != "failed")
                    arr << m;
            exitCheckMode();
            if (!arr.isEmpty())
                emit forwardRequested(arr);
        });
        connect(copy, &QAbstractButton::clicked, bar, [this] {
            QStringList text;
            for (const auto &m : checkedMessages(m_model, m_checked)) {
                const QString t = messageText(m);
                if (!t.isEmpty())
                    text << t;
            }
            if (!text.isEmpty())
                QApplication::clipboard()->setText(text.join('\n'));
            exitCheckMode();
        });
        connect(del, &QAbstractButton::clicked, bar, [this, del] {
            auto *menu = new PopupMenu(this);
            bool allMine = true;
            for (const auto &m : checkedMessages(m_model, m_checked))
                allMine = allMine && m.value("outgoing").toBool();
            menu->addAction(QStringLiteral("Удалить у себя"), this, [this] {
                const QList<QJsonObject> sel = checkedMessages(m_model, m_checked);
                exitCheckMode();
                for (const auto &m : sel)
                    Core::instance()->call("delete_local_message", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
                        if (!e.ok())
                            emit toast(e.message);
                    });
            });
            QAction *all = menu->addAction(QStringLiteral("Удалить у всех"), this, [this] {
                const QList<QJsonObject> sel = checkedMessages(m_model, m_checked);
                exitCheckMode();
                for (const auto &m : sel)
                    Core::instance()->call("delete_everywhere", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
                        if (!e.ok())
                            emit toast(e.message);
                    });
            });
            all->setEnabled(allMine);
            menu->popup(del->mapToGlobal(del->rect().bottomLeft()));
        });
        connect(x, &QAbstractButton::clicked, bar, [this] { exitCheckMode(); });
        m_checkBar = bar;
        m_checkBar->show();
        placeCheckBar();
    }
    toggleCheck(id);
}

void MessageView::exitCheckMode()
{
    if (!m_checkMode)
        return;
    m_checkMode = false;
    m_checked.clear();
    if (m_checkBar) {
        m_checkBar->deleteLater();
        m_checkBar = nullptr;
        m_checkCount = nullptr;
    }
    viewport()->update();
}

void MessageView::toggleCheck(const QString &id)
{
    if (!m_checked.remove(id))
        m_checked.insert(id);
    if (m_checked.isEmpty())
        return exitCheckMode();
    if (m_checkCount)
        m_checkCount->setText(QStringLiteral("Выбрано: %1").arg(m_checked.size()));
    viewport()->update();
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
    L.text.translate(vr.topLeft());
    L.image.translate(vr.topLeft());
    for (QRectF &o : L.options)
        o.translate(vr.topLeft());
    for (QRectF &r : L.reacts)
        r.translate(vr.topLeft());
    if (lay)
        *lay = L;
    return it.msg;
}

int MessageView::textHit(const QPoint &pos) const
{
    if (!m_selIndex.isValid())
        return 0;
    const MessageItem &it = m_model->item(m_selIndex.row());
    const QRect vr = visualRect(m_selIndex);
    MessageLayout L = MessageDelegate::layout(it, vr.width(), m_model->group && !m_model->flat);
    L.text.translate(vr.topLeft());
    QTextDocument *d = textDoc(it.msg);
    const QPointF local = QPointF(pos) - L.text.topLeft();
    if (local.y() < 0)
        return 0;
    if (local.y() > L.text.height())
        return d->characterCount() - 1;
    return qMax(0, d->documentLayout()->hitTest(local, Qt::FuzzyHit));
}

QString MessageView::selectedText() const
{
    if (m_delegate->selA == m_delegate->selB || !m_selIndex.isValid())
        return {};
    QTextCursor cur(textDoc(m_model->item(m_selIndex.row()).msg));
    cur.setPosition(qMin(m_delegate->selA, m_delegate->selB));
    cur.setPosition(qMax(m_delegate->selA, m_delegate->selB), QTextCursor::KeepAnchor);
    return cur.selectedText().replace(QChar(0x2029), QChar(u'\n'));
}

void MessageView::clearSelection()
{
    if (m_delegate->selA == m_delegate->selB)
        return;
    m_delegate->selA = m_delegate->selB = 0;
    viewport()->update();
}

void MessageView::mousePressEvent(QMouseEvent *e)
{
    if (m_checkMode) {
        if (e->button() == Qt::LeftButton) {
            const QJsonObject m = messageAt(e->pos());
            if (!m.isEmpty())
                toggleCheck(m.value("id").toString());
        }
        return;
    }
    clearSelection();
    if (e->button() == Qt::LeftButton) {
        MessageLayout L;
        const QJsonObject m = messageAt(e->pos(), &L);
        if (!m.isEmpty() && !L.big && L.options.isEmpty() && !L.text.isNull() && L.text.contains(e->pos()) && linkAt(m, L, e->pos()).isEmpty()) {
            m_selIndex = QPersistentModelIndex(indexAt(e->pos()));
            m_selPress = e->pos();
            m_selDragging = true;
            m_selMoved = false;
            m_delegate->selId = m.value("id").toString();
            m_delegate->selA = m_delegate->selB = textHit(e->pos());
        }
    }
    QListView::mousePressEvent(e);
}

void MessageView::mouseMoveEvent(QMouseEvent *e)
{
    if (m_selDragging && (e->buttons() & Qt::LeftButton)) {
        if ((e->pos() - m_selPress).manhattanLength() > 4)
            m_selMoved = true;
        if (m_selMoved) {
            m_delegate->selB = textHit(e->pos());
            viewport()->setCursor(Qt::IBeamCursor);
            viewport()->update();
            return;
        }
    }
    MessageLayout L;
    const QJsonObject m = messageAt(e->pos(), &L);
    bool hand = !m.isEmpty() && (L.download.contains(e->pos()) || L.retry.contains(e->pos()) || L.image.contains(e->pos()) ||
                                 L.bubble.contains(e->pos()) || L.avatar.contains(e->pos()) || L.name.contains(e->pos()) || !linkAt(m, L, e->pos()).isEmpty());
    if (!m.isEmpty() && !m.value("poll").toObject().value("closed").toBool())
        for (const QRectF &o : L.options)
            hand = hand || o.contains(e->pos());
    viewport()->setCursor(hand ? Qt::PointingHandCursor : Qt::ArrowCursor);
    QListView::mouseMoveEvent(e);
}

void MessageView::mouseReleaseEvent(QMouseEvent *e)
{
    if (m_checkMode)
        return;
    if (m_selDragging) {
        m_selDragging = false;
        // A drag selected text: keep it for Ctrl+C instead of opening the menu.
        if (m_selMoved && m_delegate->selA != m_delegate->selB) {
            m_selMoved = false;
            QListView::mouseReleaseEvent(e);
            return;
        }
        m_selMoved = false;
        clearSelection();
    }
    MessageLayout L;
    const QJsonObject m = messageAt(e->pos(), &L);
    if (e->button() == Qt::LeftButton && !m.isEmpty()) {
        const QString link = linkAt(m, L, e->pos());
        const QJsonArray reacts = m.value("reactions").toArray();
        int option = -1, chip = -1;
        for (int i = 0; i < L.options.size(); ++i)
            if (L.options[i].contains(e->pos()))
                option = i;
        for (int i = 0; i < L.reacts.size(); ++i)
            if (L.reacts[i].contains(e->pos()))
                chip = i;
        if (L.avatar.contains(e->pos()) || L.name.contains(e->pos()))
            emit profileRequested(m.value("sender_user_id").toString());
        else if (L.download.contains(e->pos()))
            openFile(m);
        else if (L.retry.contains(e->pos()))
            retry(m);
        else if (L.image.contains(e->pos()))
            openImage(m);
        else if (!link.isEmpty())
            QDesktopServices::openUrl(QUrl(link));
        else if (option >= 0)
            vote(m, option);
        else if (chip >= 0 && chip < reacts.size())
            react(m, reacts[chip].toObject().value("emoji").toString());

    }
    QListView::mouseReleaseEvent(e);
}

void MessageView::mouseDoubleClickEvent(QMouseEvent *e)
{
    if (m_checkMode || e->button() != Qt::LeftButton)
        return;
    MessageLayout L;
    const QJsonObject m = messageAt(e->pos(), &L);
    if (m.isEmpty())
        return;
    // Buttons, links, photos and poll answers keep their own clicks.
    bool control = L.download.contains(e->pos()) || L.retry.contains(e->pos()) || L.image.contains(e->pos()) || !linkAt(m, L, e->pos()).isEmpty();
    for (const QRectF &r : L.options)
        control = control || r.contains(e->pos());
    for (const QRectF &r : L.reacts)
        control = control || r.contains(e->pos());
    const QString kind = m.value("kind").toString();
    if (control || !m_canReact || m_searching || !(kind == "text" || kind == "file" || kind == "poll") || m.value("status").toString() == "failed")
        return;
    clearSelection();
    react(m, quickReaction());
}

QString MessageView::linkAt(const QJsonObject &m, const MessageLayout &L, const QPoint &pos) const
{
    if (L.text.isNull() || !L.options.isEmpty() || !L.text.contains(pos))
        return {};
    const QString href = textDoc(m)->documentLayout()->anchorAt(pos - L.text.topLeft());
    return href.startsWith("https://") || href.startsWith("http://") ? href : QString();
}

bool MessageView::viewportEvent(QEvent *e)
{
    if (e->type() == QEvent::ToolTip) {
        auto *he = static_cast<QHelpEvent *>(e);
        MessageLayout L;
        const QJsonObject m = messageAt(he->pos(), &L);
        const QJsonArray opts = m.value("poll").toObject().value("options").toArray();
        for (int i = 0; i < L.options.size() && i < opts.size(); ++i) {
            if (!L.options[i].contains(he->pos()))
                continue;
            QStringList who;
            for (const auto &v : opts[i].toObject().value("voters").toArray())
                who << v.toString();
            if (!who.isEmpty()) {
                chrome::showTip(he->globalPos(), who.join(", "), viewport());
                return true;
            }
        }
        const QString link = linkAt(m, L, he->pos());
        if (!link.isEmpty()) {
            chrome::showTip(he->globalPos(), link, viewport());
            return true;
        }
        chrome::hideTip();
        return true;
    }
    return QListView::viewportEvent(e);
}

void MessageView::vote(const QJsonObject &m, int option)
{
    const QJsonObject poll = m.value("poll").toObject();
    if (poll.value("closed").toBool())
        return;
    QJsonArray choices;
    if (poll.value("multiple").toBool()) {
        // Multiple choice: a click toggles the answer.
        const QJsonArray my = poll.value("my_choices").toArray();
        for (const auto &v : my)
            if (v.toInt() != option)
                choices << v;
        if (!my.contains(option))
            choices << option;
    } else {
        choices << option;
    }
    Core::instance()->call("vote_poll", {{"message_id", m.value("id")}, {"choices", choices}}, this, [this](const QJsonValue &, const CoreError &e) {
        if (!e.ok())
            emit toast(e.message);
    });
}

void MessageView::openImage(const QJsonObject &m)
{
    Core::instance()->call("download_file", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return emit toast(e.message);
        QImageReader reader(v.toObject().value("path").toString());
        reader.setAutoTransform(true);
        const QImage img = reader.read();
        if (img.isNull())
            return emit toast(QStringLiteral("Не удалось открыть фото"));
        new PhotoViewer(window(), img);
    });
}

void MessageView::keyPressEvent(QKeyEvent *e)
{
    if (m_checkMode) {
        if (e->key() == Qt::Key_Escape) {
            exitCheckMode();
            return;
        }
        if (e->matches(QKeySequence::Copy)) {
            QStringList text;
            for (const auto &m : checkedMessages(m_model, m_checked)) {
                const QString t = messageText(m);
                if (!t.isEmpty())
                    text << t;
            }
            if (!text.isEmpty())
                QApplication::clipboard()->setText(text.join('\n'));
            return;
        }
    }
    const QModelIndex idx = currentIndex();
    if (idx.isValid() && !m_model->item(idx.row()).isDate) {
        const QJsonObject m = m_model->item(idx.row()).msg;
        if (e->matches(QKeySequence::Copy)) {
            const QString sel = selectedText();
            QApplication::clipboard()->setText(sel.isEmpty() ? messageText(m) : sel);
            return;
        }
        if ((e->key() == Qt::Key_Return || e->key() == Qt::Key_Enter) && m.value("kind").toString() == "file") {
            isImage(m) ? openImage(m) : openFile(m);
            return;
        }
    }
    QListView::keyPressEvent(e);
}

void MessageView::contextMenuEvent(QContextMenuEvent *e)
{
    if (m_checkMode)
        return;
    const QPoint pos = e->reason() == QContextMenuEvent::Mouse ? e->pos() : visualRect(currentIndex()).center();
    const QJsonObject m = messageAt(pos);
    if (m.isEmpty())
        return;
    setCurrentIndex(indexAt(pos));
    showMenu(m, viewport()->mapToGlobal(pos) + QPoint(0, 4));
}

void MessageView::react(const QJsonObject &m, const QString &emoji)
{
    QString mine;
    for (const auto &v : m.value("reactions").toArray())
        if (v.toObject().value("mine").toBool())
            mine = v.toObject().value("emoji").toString();
    // One reaction per person: the same emoji again removes it.
    Core::instance()->call("react", {{"message_id", m.value("id")}, {"emoji", emoji == mine ? QString() : emoji}}, this,
                           [this](const QJsonValue &, const CoreError &e) {
                               if (!e.ok())
                                   emit toast(e.message);
                           });
}

void MessageView::showMenu(const QJsonObject &m, const QPoint &globalPos)
{
    auto *menu = new PopupMenu(this);
    const QString kind = m.value("kind").toString();
    const bool reactable = m_canReact && !m_searching && (kind == "text" || kind == "file" || kind == "poll") && m.value("status").toString() != "failed";
    if (reactable) {
        QString mine;
        for (const auto &v : m.value("reactions").toArray())
            if (v.toObject().value("mine").toBool())
                mine = v.toObject().value("emoji").toString();
        auto *row = new QWidget(menu);
        auto *rl = new QHBoxLayout(row);
        rl->setContentsMargins(8, 4, 8, 4);
        rl->setSpacing(2);
        QFont ef(QStringLiteral("Segoe UI Emoji"));
        ef.setPixelSize(22);
        for (const QString &e : emoji::quick()) {
            auto *b = new QToolButton(row);
            b->setText(e);
            b->setFont(ef);
            b->setAutoRaise(true);
            b->setFixedSize(40, 40);
            b->setCursor(Qt::PointingHandCursor);
            b->setCheckable(true);
            b->setChecked(e == mine);
            b->setAccessibleName(e == mine ? QStringLiteral("Убрать реакцию ") + e : QStringLiteral("Реакция ") + e);
            connect(b, &QToolButton::clicked, menu, [this, menu, m, e] {
                menu->dismiss();
                react(m, e);
            });
            rl->addWidget(b);
        }
        auto *more = new QToolButton(row);
        more->setText(QStringLiteral("+"));
        more->setAutoRaise(true);
        more->setFixedSize(40, 40);
        more->setFont(Theme::instance()->font(20, QFont::DemiBold));
        more->setCursor(Qt::PointingHandCursor);
        more->setAccessibleName(QStringLiteral("Все эмодзи"));
        connect(more, &QToolButton::clicked, menu, [this, menu, m, globalPos] {
            menu->dismiss();
            auto *popup = new EmojiPopup(this, [this, m](const QString &e) { react(m, e); });
            popup->popup(globalPos + QPoint(196, 408));
        });
        rl->addWidget(more);
        menu->addWidget(row);
        menu->addSeparator();
    }
    if (kind != "undecryptable" && kind != "call" && !m_searching)
        menu->addAction(QStringLiteral("Ответить"), this, [this, m] { emit replyRequested(m); });
    if (m.value("outgoing").toBool() && (kind == "text" || kind == "file") && !m_searching &&
        QStringList{"sent", "delivered", "read"}.contains(m.value("status").toString()))
        menu->addAction(QStringLiteral("Изменить"), this, [this, m] { emit editRequested(m); });
    if (!messageText(m).isEmpty())
        menu->addAction(QStringLiteral("Копировать текст"), this, [m] { QApplication::clipboard()->setText(messageText(m)); });
    if (isImage(m))
        menu->addAction(QStringLiteral("Открыть фото"), this, [this, m] { openImage(m); });
    if (kind == "poll" && m.value("outgoing").toBool() && !m.value("poll").toObject().value("closed").toBool())
        menu->addAction(QStringLiteral("Завершить опрос"), this, [this, m] {
            Core::instance()->call("close_poll", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
                if (!e.ok())
                    emit toast(e.message);
            });
        });
    if (kind == "file") {
        menu->addAction(QStringLiteral("Открыть файл"), this, [this, m] { openFile(m); });
        menu->addAction(QStringLiteral("Сохранить как…"), this, [this, m] { saveFile(m); });
    }
    if (m.value("status").toString() == "failed")
        menu->addAction(QStringLiteral("Повторить отправку"), this, [this, m] { retry(m); });
    if ((kind == "text" || kind == "file" || kind == "poll") && m.value("status").toString() != "failed")
        menu->addAction(QStringLiteral("Переслать"), this, [this, m] { emit forwardRequested(QJsonArray{m}); });
    menu->addAction(QStringLiteral("Выбрать"), this, [this, m] { enterCheckMode(m.value("id").toString()); });
    menu->addSeparator();
    // A call entry is a local log line: there is nothing to delete at the peer.
    if (m.value("outgoing").toBool() && m.value("status").toString() != "failed" && kind != "call")
        menu->addAction(QStringLiteral("Удалить у всех"), this, [this, m] {
            Core::instance()->call("delete_everywhere", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
                if (!e.ok())
                    emit toast(e.message);
            });
        });
    menu->addAction(QStringLiteral("Удалить у себя"), this, [this, m] {
        Core::instance()->call("delete_local_message", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                emit toast(e.message);
        });
    });
    menu->popup(globalPos);
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
    const QString id = m.value("id").toString();
    filepicker::save(this, QStringLiteral("Сохранить файл"), name, [this, id](const QString &dest) {
        Core::instance()->call("export_file", {{"message_id", id}, {"dest", dest}}, this, [this](const QJsonValue &, const CoreError &e) {
            emit toast(e.ok() ? QStringLiteral("Файл сохранён") : e.message);
        });
    });
}

void MessageView::retry(const QJsonObject &m)
{
    Core::instance()->call("retry_message", {{"message_id", m.value("id")}}, this, [this](const QJsonValue &, const CoreError &e) {
        if (!e.ok())
            emit toast(e.message);
    });
}

// ---- quick reaction ----

QString quickReaction()
{
    return QSettings().value("chat/quickReaction", QString::fromUtf8("❤️")).toString();
}

QStringList quickReactionChoices()
{
    // 5 columns by 7 rows; the phones offer the same set.
    return QString::fromUtf8("❤️ 👍 🔥 😂 😮 😢 🙏 👏 🎉 😍 🤝 👀 👎 😡 🤔 😎 🥰 😘 🤩 😁 😭 🤯 😱 🥳 🤗 💯 ✨ ⚡ 💔 💪 🙌 👌 🤮 🥺 😴").split(' ', Qt::SkipEmptyParts);
}

void loadQuickReaction(QObject *ctx)
{
    Core::instance()->call("get_settings", {}, ctx, [](const QJsonValue &v, const CoreError &e) {
        const QString r = v.toObject().value("quick_reaction").toString();
        if (e.ok() && !r.isEmpty())
            QSettings().setValue("chat/quickReaction", r);
    });
}
