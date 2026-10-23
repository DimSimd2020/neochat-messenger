#include "profilestyle.h"
#include "theme.h"
#include "widgets.h"
#include <QPainter>
#include <QPainterPath>
#include <QRegularExpression>
#include <QtMath>

namespace profilestyle {
const QStringList kNameColors{"#ff6b6b", "#ff9f43", "#f7c948", "#32d583", "#2ec4b6", "#4dabf7", "#845ef7", "#f06595", "#adb5bd"};
const QStringList kHeaders{"#2b5876,#4e4376", "#f2709c,#ff9472", "#134e5e,#71b280", "#8e2de2,#4a00e0",
                           "#cb2d3e,#ef473a", "#f7971e,#ffd200", "#11998e,#38ef7d", "#232526,#414345"};

QColor parse(const QString &text)
{
    static const QRegularExpression re(QStringLiteral("^#[0-9a-fA-F]{6}$"));
    return re.match(text).hasMatch() ? QColor(text) : QColor();
}

QString hex(const QColor &c)
{
    return c.name(QColor::HexRgb);
}

namespace {
qreal luminance(const QColor &c)
{
    const auto f = [](qreal v) { return v <= 0.03928 ? v / 12.92 : qPow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c.redF()) + 0.7152 * f(c.greenF()) + 0.0722 * f(c.blueF());
}

qreal contrast(const QColor &a, const QColor &b)
{
    const qreal x = luminance(a), y = luminance(b);
    return (qMax(x, y) + 0.05) / (qMin(x, y) + 0.05);
}

QColor mix(const QColor &a, const QColor &b, qreal t)
{
    return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t, a.blueF() + (b.blueF() - a.blueF()) * t);
}
}

QColor readable(const QColor &c, const QColor &ground)
{
    const QColor target = luminance(ground) < 0.18 ? QColor(Qt::white) : QColor(Qt::black);
    for (int step = 0; step <= 10; ++step) {
        const QColor m = mix(c, target, step / 10.0);
        if (contrast(m, ground) >= 4)
            return m;
    }
    return target;
}

QColor nameOn(const QString &text, const QColor &ground)
{
    const QColor c = parse(text);
    return c.isValid() ? readable(c, ground) : QColor();
}

QList<QColor> colors(const QString &header, const QString &userId)
{
    QList<QColor> own;
    for (const QString &part : header.split(',', Qt::SkipEmptyParts)) {
        const QColor c = parse(part.trimmed());
        if (c.isValid())
            own.append(c);
    }
    if (own.size() == 1)
        return {own[0], mix(own[0], Qt::black, 0.35)};
    if (!own.isEmpty())
        return own;
    bool ok = false;
    const int pick = userId.left(2).toInt(&ok, 16) % kHeaders.size();
    QList<QColor> out;
    for (const QString &part : kHeaders[ok ? pick : 0].split(','))
        out.append(QColor(part));
    return out;
}

Cover::Cover(const QString &name, const QString &seed, const QString &avatarPath, int size, QWidget *parent)
    : QWidget(parent), m_name(name), m_seed(seed), m_path(avatarPath), m_colors(colors({}, seed)), m_size(size), m_band(qRound(size * 1.25))
{
    setFixedHeight(m_band + m_size / 2 + 4);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
}

void Cover::setColors(const QList<QColor> &c)
{
    m_colors = c;
    update();
}

void Cover::setAvatarWidget(QWidget *avatar)
{
    m_avatar = avatar;
    avatar->setParent(this);
    avatar->show();
    place();
}

void Cover::place()
{
    if (m_avatar)
        m_avatar->move((width() - m_avatar->width()) / 2, m_band - m_avatar->height() / 2);
}

void Cover::resizeEvent(QResizeEvent *)
{
    place();
}

void Cover::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QRectF band(0, 0, width(), m_band);
    QPainterPath path;
    path.addRoundedRect(band, 22, 22);
    p.setClipPath(path);
    QLinearGradient g(band.topLeft(), band.bottomRight());
    for (int i = 0; i < m_colors.size(); ++i)
        g.setColorAt(m_colors.size() == 1 ? 0 : qreal(i) / (m_colors.size() - 1), m_colors[i]);
    p.fillRect(band, g);
    QRadialGradient glow(band.width() * 0.9, band.height() * 0.1, band.width() * 0.7);
    glow.setColorAt(0, QColor(255, 255, 255, 56));
    glow.setColorAt(1, QColor(255, 255, 255, 0));
    p.fillRect(band, glow);
    p.setClipping(false);
    if (!m_avatar)
        ui::paintAvatar(&p, QRectF((width() - m_size) / 2.0, m_band - m_size / 2.0, m_size, m_size), m_name, m_seed, m_path);
}
}
