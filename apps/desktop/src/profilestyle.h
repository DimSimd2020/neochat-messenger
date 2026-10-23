#pragma once
#include <QColor>
#include <QList>
#include <QString>
#include <QStringList>
#include <QWidget>

// How a profile looks to others: the colour of the name and the gradient of the header.
namespace profilestyle {
// Name colours on offer; an empty string is the default colour of the theme.
extern const QStringList kNameColors;
// Header gradients on offer, two colours each, joined by a comma. A profile without its own gets one by its ID.
extern const QStringList kHeaders;

// Invalid unless the text is '#rrggbb'.
QColor parse(const QString &hex);
QString hex(const QColor &c);
// `c` moved toward white or black until it can be read on `ground`.
QColor readable(const QColor &c, const QColor &ground);
// The colour a person chose for their name as it reads on `ground`; invalid when none was chosen.
QColor nameOn(const QString &hex, const QColor &ground);
// The colours of a header: the chosen ones, or a pair picked by the user's ID.
QList<QColor> colors(const QString &header, const QString &userId);

// The cover of a profile: a gradient with rounded corners, the avatar on its lower edge.
class Cover : public QWidget {
public:
    // Without `avatar` the cover paints the avatar itself, `size` pixels wide.
    Cover(const QString &name, const QString &seed, const QString &avatarPath, int size = 112, QWidget *parent = nullptr);
    void setColors(const QList<QColor> &colors);
    // Places a widget (a button that opens the photos) on the lower edge instead of the painted avatar.
    void setAvatarWidget(QWidget *avatar);

protected:
    void paintEvent(QPaintEvent *) override;
    void resizeEvent(QResizeEvent *) override;

private:
    void place();
    QString m_name, m_seed, m_path;
    QList<QColor> m_colors;
    QWidget *m_avatar = nullptr;
    int m_size;
    int m_band;
};
}
