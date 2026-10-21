#include "transition.h"

#include "theme.h"

#include <QCoreApplication>
#include <QEvent>
#include <QLayout>
#include <QLinearGradient>
#include <QPainter>
#include <QPixmap>
#include <QVariantAnimation>
#include <QWidget>

namespace ui {
namespace {

// Pictures of the area before and after a change, laid over the live widgets while they move.
class Overlay : public QWidget {
public:
    Overlay(QWidget *host, const QRect &area, const QPixmap &before, const QPixmap &after, Motion motion)
        : QWidget(host), m_before(before), m_after(after), m_motion(motion)
    {
        setObjectName(QStringLiteral("ui-transition"));
        setGeometry(area);
        setAttribute(Qt::WA_TransparentForMouseEvents);
        m_anim.setStartValue(0.0);
        m_anim.setEndValue(1.0);
        m_anim.setDuration(Theme::ms(motion == Motion::Fade || motion == Motion::Dissolve ? 200 : 320));
        m_anim.setEasingCurve(Theme::surfaceEasing());
        connect(&m_anim, &QVariantAnimation::valueChanged, this, qOverload<>(&QWidget::update));
        connect(&m_anim, &QVariantAnimation::finished, this, &QObject::deleteLater);
        raise();
        show();
        m_anim.start();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const qreal t = m_anim.currentValue().toReal();
        const int w = width();
        QPainter p(this);
        switch (m_motion) {
        case Motion::Dissolve:
            p.setOpacity(1 - t);
            p.drawPixmap(0, 0, m_before);
            break;
        case Motion::Fade:
            p.drawPixmap(0, 0, m_before);
            p.setOpacity(t);
            p.drawPixmap(0, int((1 - t) * 8), m_after);
            break;
        case Motion::PullLeft: {
            const int x = int(-t * w);
            p.drawPixmap(x, 0, m_before);
            edge(p, x + w, false);
            break;
        }
        case Motion::PullRight: {
            const int x = int(t * w);
            p.drawPixmap(x, 0, m_before);
            edge(p, x);
            break;
        }
        case Motion::Forward: {
            p.drawPixmap(int(-t * w * 0.3), 0, m_before);
            p.fillRect(rect(), QColor(0, 0, 0, int(46 * t)));
            const int x = int((1 - t) * w);
            p.drawPixmap(x, 0, m_after);
            edge(p, x);
            break;
        }
        case Motion::Back: {
            p.drawPixmap(int(-(1 - t) * w * 0.3), 0, m_after);
            p.fillRect(rect(), QColor(0, 0, 0, int(46 * (1 - t))));
            const int x = int(t * w);
            p.drawPixmap(x, 0, m_before);
            edge(p, x);
            break;
        }
        }
    }

private:
    // A soft shadow beside the edge `x` of the picture that slides over the other: on its left, or on its right.
    void edge(QPainter &p, int x, bool left = true) const
    {
        const int from = left ? x - 18 : x;
        QLinearGradient g(from, 0, from + 18, 0);
        g.setColorAt(0, QColor(0, 0, 0, left ? 0 : 60));
        g.setColorAt(1, QColor(0, 0, 0, left ? 60 : 0));
        p.fillRect(QRect(from, 0, 18, height()), g);
    }

    QPixmap m_before, m_after;
    Motion m_motion;
    QVariantAnimation m_anim;
};

} // namespace

void transition(QWidget *host, const QRect &area, Motion motion, const std::function<void()> &change)
{
    if (Theme::ms(1) == 0 || !host->isVisible() || area.isEmpty())
        return change();
    // A picture taken over a running overlay would capture the overlay itself.
    for (auto *o : host->findChildren<QWidget *>(QStringLiteral("ui-transition"), Qt::FindDirectChildrenOnly))
        delete o;
    const QPixmap before = host->grab(area);
    change();
    QPixmap after;
    if (motion == Motion::Fade || motion == Motion::Forward || motion == Motion::Back) {
        if (auto *l = host->layout())
            l->activate();
        QCoreApplication::sendPostedEvents(nullptr, QEvent::LayoutRequest);
        after = host->grab(area);
    }
    new Overlay(host, area, before, after, motion);
}

} // namespace ui
