#pragma once
#include <QPixmap>
#include <QRectF>
#include <QString>
#include <QStringList>

class QPainter;

// Animated versions of the common emoji (Animated Noto Emoji, Google, CC BY 4.0): one sprite sheet per emoji,
// shared with the phone apps from assets/animated-emoji and embedded in the program. A message made only of
// such emoji plays them; any other emoji keeps the font.
namespace animatedemoji {
struct Sheet {
    QPixmap pix;
    int frames = 1;
    int ms = 66; // length of one frame
    int size = 96;
    int cols = 8;
    bool valid() const { return !pix.isNull(); }
};

// The settings switch; on by default.
bool enabled();
void setEnabled(bool on);

// Names of the sheets for the emoji of `text` (up to three); empty unless every one of them has an animation
// and the switch is on. Cheap: it looks names up in the index and decodes nothing.
QStringList keysOf(const QString &text);

// Side of one emoji on screen for a message of `count` emoji.
qreal drawSize(int count);

// Decoded sheet, kept in a small cache; invalid when there is no such emoji.
Sheet sheet(const QString &key);

// Draws the frame for `elapsedMs` since the first paint: twice through, then the first frame again (and only
// that when motion is reduced). Returns whether it is still moving, that is whether to paint again soon.
bool paint(QPainter *p, const Sheet &s, const QRectF &to, qint64 elapsedMs);
}
