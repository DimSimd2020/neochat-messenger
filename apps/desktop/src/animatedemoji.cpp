#include "animatedemoji.h"

#include "theme.h"

#include <QCache>
#include <QFile>
#include <QHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPainter>
#include <QSettings>
#include <QTextBoundaryFinder>

namespace animatedemoji {
namespace {

struct Entry {
    int frames = 1;
    int ms = 66;
};

struct Index {
    int size = 96;
    int cols = 8;
    QHash<QString, Entry> emoji;
};

const Index &index()
{
    static const Index idx = [] {
        Index i;
        QFile f(QStringLiteral(":/animated-emoji/index.json"));
        if (!f.open(QIODevice::ReadOnly))
            return i;
        const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
        i.size = o.value("size").toInt(96);
        i.cols = o.value("cols").toInt(8);
        const QJsonObject all = o.value("emoji").toObject();
        for (auto it = all.begin(); it != all.end(); ++it) {
            const QJsonObject e = it.value().toObject();
            i.emoji.insert(it.key(), {e.value("frames").toInt(1), qMax(16, e.value("ms").toInt(66))});
        }
        return i;
    }();
    return idx;
}

// Code points in hex joined by '-', without the variation selector: the name of the sheet.
QString keyOf(const QString &glyph)
{
    QStringList parts;
    for (const auto cp : glyph.toUcs4())
        if (cp != 0xFE0F)
            parts << QString::number(static_cast<uint>(cp), 16);
    return parts.join(QLatin1Char('-'));
}

bool &flag()
{
    static bool on = QSettings().value(QStringLiteral("chat/animatedEmoji"), true).toBool();
    return on;
}

} // namespace

bool enabled() { return flag(); }

void setEnabled(bool on)
{
    flag() = on;
    QSettings().setValue(QStringLiteral("chat/animatedEmoji"), on);
}

QStringList keysOf(const QString &text)
{
    if (!enabled())
        return {};
    QStringList keys;
    QTextBoundaryFinder finder(QTextBoundaryFinder::Grapheme, text);
    int start = 0;
    for (int end = finder.toNextBoundary(); end != -1; start = end, end = finder.toNextBoundary()) {
        const QString glyph = text.mid(start, end - start);
        if (glyph.trimmed().isEmpty())
            continue;
        const QString key = keyOf(glyph);
        if (keys.size() == 3 || !index().emoji.contains(key))
            return {};
        keys << key;
    }
    return keys;
}

qreal drawSize(int count)
{
    return count <= 1 ? 60 : count == 2 ? 48 : 40;
}

Sheet sheet(const QString &key)
{
    static QCache<QString, Sheet> cache(12 * 1024 * 1024);
    if (const Sheet *hit = cache.object(key))
        return *hit;
    const auto entry = index().emoji.constFind(key);
    if (entry == index().emoji.constEnd())
        return {};
    auto *s = new Sheet;
    s->pix.load(QStringLiteral(":/animated-emoji/") + key + QStringLiteral(".png"));
    s->frames = entry->frames;
    s->ms = entry->ms;
    s->size = index().size;
    s->cols = index().cols;
    const Sheet copy = *s;
    cache.insert(key, s, qMax(1, s->pix.width() * s->pix.height() * 4));
    return copy;
}

bool paint(QPainter *p, const Sheet &s, const QRectF &to, qint64 elapsedMs)
{
    const qint64 step = elapsedMs / s.ms;
    const bool moving = !Theme::reducedMotion() && s.frames > 1 && step < 2 * qint64(s.frames);
    const int n = moving ? int(step % s.frames) : 0;
    const QRectF from((n % s.cols) * s.size, (n / s.cols) * s.size, s.size, s.size);
    p->save();
    p->setRenderHint(QPainter::SmoothPixmapTransform);
    p->drawPixmap(to, s.pix, from);
    p->restore();
    return moving;
}

} // namespace animatedemoji
