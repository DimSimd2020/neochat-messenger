#include "style.h"

#include "theme.h"

#include <QPainter>
#include <QProxyStyle>
#include <QStyleFactory>
#include <QStyleOptionSlider>

namespace {

// Fusion with thin rounded scroll bars in theme colors.
class Style : public QProxyStyle {
public:
    Style() : QProxyStyle(QStyleFactory::create("Fusion")) {}

    int pixelMetric(PixelMetric m, const QStyleOption *opt, const QWidget *w) const override
    {
        if (m == PM_ScrollBarExtent)
            return 10;
        if (m == PM_ScrollBarSliderMin)
            return 36;
        return QProxyStyle::pixelMetric(m, opt, w);
    }

    int styleHint(StyleHint h, const QStyleOption *opt, const QWidget *w, QStyleHintReturn *ret) const override
    {
        if (h == SH_ScrollBar_Transient)
            return 0;
        return QProxyStyle::styleHint(h, opt, w, ret);
    }

    void drawComplexControl(ComplexControl cc, const QStyleOptionComplex *opt, QPainter *p, const QWidget *w) const override
    {
        if (cc != CC_ScrollBar)
            return QProxyStyle::drawComplexControl(cc, opt, p, w);
        const auto *sb = static_cast<const QStyleOptionSlider *>(opt);
        if (sb->minimum == sb->maximum)
            return;
        QRect handle = subControlRect(cc, sb, SC_ScrollBarSlider, w);
        p->save();
        p->setRenderHint(QPainter::Antialiasing);
        QColor c = Theme::p().muted;
        c.setAlphaF((sb->state & State_MouseOver) ? 0.55 : 0.32);
        p->setPen(Qt::NoPen);
        p->setBrush(c);
        const QRectF r = sb->orientation == Qt::Vertical ? QRectF(handle).adjusted(3, 2, -3, -2) : QRectF(handle).adjusted(2, 3, -2, -3);
        p->drawRoundedRect(r, 2, 2);
        p->restore();
    }

    QRect subControlRect(ComplexControl cc, const QStyleOptionComplex *opt, SubControl sc, const QWidget *w) const override
    {
        if (cc == CC_ScrollBar && (sc == SC_ScrollBarAddLine || sc == SC_ScrollBarSubLine))
            return {};
        if (cc == CC_ScrollBar && (sc == SC_ScrollBarGroove || sc == SC_ScrollBarSlider || sc == SC_ScrollBarAddPage || sc == SC_ScrollBarSubPage)) {
            // No arrow buttons: the groove is the whole bar.
            const auto *sb = static_cast<const QStyleOptionSlider *>(opt);
            const QRect all = sb->rect;
            if (sc == SC_ScrollBarGroove)
                return all;
            const bool vertical = sb->orientation == Qt::Vertical;
            const int length = vertical ? all.height() : all.width();
            const int range = sb->maximum - sb->minimum;
            const int len = qMax(pixelMetric(PM_ScrollBarSliderMin, opt, w), int(qint64(length) * sb->pageStep / qMax(1, range + sb->pageStep)));
            const int pos = sliderPositionFromValue(sb->minimum, sb->maximum, sb->sliderPosition, length - len, sb->upsideDown);
            QRect slider = vertical ? QRect(all.x(), all.y() + pos, all.width(), len) : QRect(all.x() + pos, all.y(), len, all.height());
            if (sc == SC_ScrollBarSlider)
                return slider;
            if (sc == SC_ScrollBarSubPage)
                return vertical ? QRect(all.x(), all.y(), all.width(), pos) : QRect(all.x(), all.y(), pos, all.height());
            return vertical ? QRect(all.x(), slider.bottom() + 1, all.width(), all.bottom() - slider.bottom())
                            : QRect(slider.right() + 1, all.y(), all.right() - slider.right(), all.height());
        }
        return QProxyStyle::subControlRect(cc, opt, sc, w);
    }
};

} // namespace

QStyle *createStyle()
{
    return new Style;
}
