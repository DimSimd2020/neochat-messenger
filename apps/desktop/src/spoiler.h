#pragma once

#include <QColor>
#include <QList>
#include <QPair>
#include <QPixmap>
#include <QRectF>
#include <QTextDocument>

class QPainter;

// The cover of something hidden, shared by a blurred photo and a hidden run of text: a field of
// dust that drifts while the content is closed and scatters outward as it opens. Presentation
// only — the picture and the words are already on this device.
namespace spoiler {

// Milliseconds on a clock of this module, for the tweens below.
qint64 now();

// One cover on its way open or closed. `ms` is 0 when motion is reduced: the value jumps.
struct Reveal {
    qreal from = 0;
    qreal to = 0;
    qint64 at = -1;

    qreal value(int ms) const;
    void start(qreal target, int ms);
    bool moving(int ms) const;
};

// Dust over `r`: `closed` is how hidden the content still is, `spread` how far the grains have
// flown from the middle. `seed` keeps the same grains on the same place across repaints.
// `gain` lifts the brightness of the grains where the cover lies on plain text.
void dust(QPainter &p, const QRectF &r, quint32 seed, qreal closed, qreal spread, qreal density = 1.0, qreal gain = 1.0);

// A soft band of light crossing the picture once while it opens.
void sweep(QPainter &p, const QRectF &r, qreal t);

// The block a hidden run of text lies under: frosted, dusted, with a light edge.
void textCover(QPainter &p, const QRectF &r, qreal radius, const QColor &plate, qreal closed, qreal spread, quint32 seed);

// The colour of that block: a step away from the bubble it lies on.
QColor plate(const QColor &bubble, bool dark, bool outgoing);

// One step of defocus: the picture shrunk to `side` pixels on its long side and stretched back.
QPixmap defocus(const QPixmap &src, int side);

// Where a run of characters sits in a laid-out document, one rectangle per line it covers.
QList<QRectF> rects(QTextDocument *doc, const QList<QPair<int, int>> &runs);

} // namespace spoiler