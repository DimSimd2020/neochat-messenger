#pragma once

#include "widgets.h"

#include <QAbstractButton>
#include <QList>
#include <QPainter>
#include <QPixmap>
#include <QPointer>
#include <QResizeEvent>
#include <QWidget>

// One square of a media album; the picture keeps its own shape inside the tile.
class MediaTile : public QAbstractButton {
public:
    QPixmap image;
    explicit MediaTile(QPixmap pix) : image(std::move(pix))
    {
        setFixedSize(96, 96);
        setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
        setAccessibleName(QStringLiteral("Открыть фото"));
        setCursor(Qt::PointingHandCursor);
    }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        p.setRenderHint(QPainter::Antialiasing);
        if (!image.isNull()) {
            // Keep the photo's own shape. The square's empty sides stay transparent.
            const QPixmap scaled = image.scaled(size(), Qt::KeepAspectRatio, Qt::SmoothTransformation);
            p.drawPixmap((width() - scaled.width()) / 2, (height() - scaled.height()) / 2, scaled);
        }
        if (hasFocus())
            ui::paintFocusRing(&p, QRectF(rect()).adjusted(2, 2, -2, -2), 12);
    }
};

// As many tiles per row as fit (six in a profile), the rows centered as one block;
// a single short row is centered by its own tiles.
class MediaGrid : public QWidget {
public:
    void add(QWidget *tile)
    {
        tile->setParent(this);
        m_tiles.append(tile);
        updateGeometry();
    }
    bool hasHeightForWidth() const override { return true; }
    int heightForWidth(int w) const override
    {
        const int cols = columns(w);
        const int rows = (int(m_tiles.size()) + cols - 1) / cols;
        return rows > 0 ? rows * (Tile + Gap) - Gap : 0;
    }
    QSize sizeHint() const override
    {
        const int w = 6 * (Tile + Gap) - Gap;
        return {w, heightForWidth(w)};
    }
    QSize minimumSizeHint() const override { return {Tile, 0}; }

protected:
    void resizeEvent(QResizeEvent *) override
    {
        const int cols = columns(width());
        const int used = qMin(cols, int(m_tiles.size()));
        const int left = (width() - (used * (Tile + Gap) - Gap)) / 2;
        for (int i = 0; i < m_tiles.size(); ++i)
            m_tiles[i]->move(left + i % cols * (Tile + Gap), i / cols * (Tile + Gap));
    }

private:
    static constexpr int Tile = 96;
    static constexpr int Gap = 8;
    static int columns(int w) { return qMax(1, (w + Gap) / (Tile + Gap)); }
    QList<QWidget *> m_tiles;
};
