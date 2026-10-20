#pragma once

#include <QPair>
#include <QStringList>
#include <QWidget>
#include <functional>

namespace emoji {
// Quick reactions and the full catalog; the same lists as on Android and iOS.
const QStringList &quick();
const QList<QPair<QString, QStringList>> &categories();
}

// Popup with every emoji by category; `pick` gets the chosen one.
class EmojiPopup : public QWidget {
public:
    EmojiPopup(QWidget *anchor, std::function<void(const QString &)> pick);
    void popup(const QPoint &bottomRight);

protected:
    void paintEvent(QPaintEvent *) override;
};
