#include "spoiler.h"

#include "theme.h"

#include <QElapsedTimer>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QTextBlock>
#include <QTextLayout>
#include <QtMath>

namespace spoiler {

namespace {

qreal rand(quint32 seed, int i, int salt)
{
    quint32 h = seed * 0x9E3779B1u + quint32(i) * 0x85EBCA6Bu + quint32(salt) * 0xC2B2AE35u;
    h ^= h >> 15;
    h *= 0x2545F491u;
    h ^= h >> 13;
    return qreal(h & 0xFFFFFFu) / qreal(0xFFFFFFu);
}

// Dust of one place: same seed, same grains, so a repaint does not reshuffle them.
struct Field {
    QVector<qreal> x, y, r, phase, speed, bright;
    Field(quint32 seed, int count)
    {
        x.resize(count); y.resize(count); r.resize(count);
        phase.resize(count); speed.resize(count); bright.resize(count);
        for (int i = 0; i < count; ++i) {
            x[i] = rand(seed, i, 1);
            y[i] = rand(seed, i, 2);
            r[i] = 0.35 + 1.05 * rand(seed, i, 3);
            phase[i] = rand(seed, i, 4) * 6.283;
            speed[i] = 0.35 + 1.1 * rand(seed, i, 5);
            bright[i] = 0.35 + 0.65 * rand(seed, i, 6);
        }
    }
};

const Field &field(quint32 seed, int count)
{
    static QHash<quint64, Field> cache;
    if (cache.size() > 96)
        cache.clear();
    const quint64 key = quint64(seed) * 1024 + quint64(count);
    auto it = cache.find(key);
    if (it == cache.end())
        it = cache.insert(key, Field(seed, count));
    return *it;
}

} // namespace

qint64 now()
{
    static QElapsedTimer t = [] {
        QElapsedTimer e;
        e.start();
        return e;
    }();
    return t.elapsed();
}

qreal Reveal::value(int ms) const
{
    if (at < 0 || ms <= 0)
        return to;
    const qreal t = qBound(0.0, (now() - at) / qreal(ms), 1.0);
    return from + (to - from) * Theme::surfaceEasing().valueForProgress(t);
}

void Reveal::start(qreal target, int ms)
{
    from = value(ms);
    to = target;
    at = now();
}

bool Reveal::moving(int ms) const
{
    return at >= 0 && ms > 0 && now() - at < ms;
}

void dust(QPainter &p, const QRectF &r, quint32 seed, qreal closed, qreal spread, qreal density, qreal gain)
{
    if (closed <= 0.002 || r.width() <= 0 || r.height() <= 0)
        return;
    const int count = qBound(12, int(r.width() * r.height() / 260.0 * density), 150);
    const Field &f = field(seed, count);
    const qreal t = Theme::reducedMotion() ? 0.0 : now() / 1000.0;
    p.save();
    p.setClipRect(r);
    p.setPen(Qt::NoPen);
    const QColor accent = Theme::p().accent;
    for (int i = 0; i < count; ++i) {
        const qreal flow = t * (0.008 + 0.022 * f.speed[i]);
        qreal ux = f.x[i] + 0.018 * qSin(t * (0.5 + f.speed[i]) + f.phase[i]);
        qreal uy = std::fmod(f.y[i] - flow, 1.0);
        if (uy < 0)
            uy += 1;
        // On the way out every grain leaves the middle, the bright ones faster.
        const qreal push = spread * (0.5 + 1.3 * f.bright[i]);
        ux += (ux - 0.5) * push;
        uy += (uy - 0.5) * push;
        const QPointF at(r.left() + ux * r.width(), r.top() + uy * r.height());
        const qreal twinkle = 0.55 + 0.45 * qSin(t * 1.6 * f.speed[i] + f.phase[i] * 2);
        const qreal a = qBound(0.0, closed * f.bright[i] * twinkle * gain, 1.0);
        const qreal rad = f.r[i] * (1 + spread * 0.8);
        QColor halo = accent;
        halo.setAlphaF(qBound(0.0, a * 0.16, 1.0));
        p.setBrush(halo);
        p.drawEllipse(at, rad * 2.1, rad * 2.1);
        p.setBrush(QColor(255, 255, 255, qBound(0, int(a * 210), 255)));
        p.drawEllipse(at, rad, rad);
    }
    p.restore();
}

void sweep(QPainter &p, const QRectF &r, qreal t)
{
    if (t <= 0.02 || t >= 0.98)
        return;
    const qreal band = r.width() * 0.55;
    QLinearGradient g(0, 0, band, 0);
    g.setColorAt(0, QColor(255, 255, 255, 0));
    g.setColorAt(0.5, QColor(255, 255, 255, 48));
    g.setColorAt(1, QColor(255, 255, 255, 0));
    p.save();
    p.setClipRect(r);
    p.translate(r.left() - band + t * (r.width() + band * 2), r.top());
    p.rotate(-12);
    p.setPen(Qt::NoPen);
    p.setBrush(g);
    p.drawRect(QRectF(0, -r.height(), band, r.height() * 3));
    p.restore();
}

void textCover(QPainter &p, const QRectF &r, qreal radius, const QColor &plate, qreal closed, qreal spread, quint32 seed)
{
    if (closed <= 0.002)
        return;
    QPainterPath block;
    block.addRoundedRect(r, radius, radius);
    p.save();
    p.setClipPath(block);
    QColor fill = plate;
    fill.setAlphaF(qBound(0.0, closed, 1.0));
    p.setPen(Qt::NoPen);
    p.setBrush(fill);
    p.drawPath(block);
    dust(p, r, seed, closed, spread, 1.5, 1.3);
    p.setBrush(Qt::NoBrush);
    p.setPen(QPen(QColor(255, 255, 255, int(46 * closed)), 1));
    p.drawPath(block);
    p.restore();
}

QColor plate(const QColor &bubble, bool dark, bool outgoing)
{
    const QColor toward = (dark || outgoing) ? QColor(255, 255, 255) : QColor(22, 40, 63);
    const qreal t = outgoing ? 0.34 : (dark ? 0.30 : 0.28);
    auto mix = [t](int a, int b) { return qBound(0, int(a + (b - a) * t), 255); };
    return QColor(mix(bubble.red(), toward.red()), mix(bubble.green(), toward.green()), mix(bubble.blue(), toward.blue()));
}

QPixmap defocus(const QPixmap &src, int side)
{
    if (src.isNull())
        return {};
    QPixmap cur = src;
    // Halving on the way down keeps the blur smooth instead of speckled.
    while (qMax(cur.width(), cur.height()) / 2 >= side)
        cur = cur.scaled(qMax(1, cur.width() / 2), qMax(1, cur.height() / 2), Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    const qreal k = qreal(side) / qMax(1, qMax(cur.width(), cur.height()));
    if (k < 1)
        cur = cur.scaled(qMax(1, int(cur.width() * k)), qMax(1, int(cur.height() * k)), Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    return cur.scaled(src.size(), Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
}

QList<QRectF> rects(QTextDocument *doc, const QList<QPair<int, int>> &runs)
{
    QList<QRectF> out;
    if (!doc)
        return out;
    for (const auto &run : runs) {
        const int a = run.first, b = run.second;
        for (QTextBlock bl = doc->begin(); bl.isValid(); bl = bl.next()) {
            const int bs = bl.position();
            const int be = bs + bl.length() - 1; // the block separator is not part of the text
            if (be < a || bs >= b)
                continue;
            const int from = qMax(a, bs) - bs, to = qMin(b, be) - bs;
            QTextLayout *lay = bl.layout();
            for (int i = 0; i < lay->lineCount(); ++i) {
                const QTextLine line = lay->lineAt(i);
                const int ls = line.textStart(), le = ls + line.textLength();
                if (le <= from || ls >= to)
                    continue;
                const qreal x1 = line.cursorToX(qMax(ls, from));
                const qreal x2 = line.cursorToX(qMin(le, to));
                QRectF r(line.x() + qMin(x1, x2), line.y(), qAbs(x2 - x1), line.height());
                r.translate(lay->position());
                out << r;
            }
        }
    }
    return out;
}

} // namespace spoiler