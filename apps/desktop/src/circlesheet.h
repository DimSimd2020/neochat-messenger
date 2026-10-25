#pragma once

#include <QImage>
#include <QString>
#include <functional>

class QWidget;

namespace circlesheet {

// Opens the circle recorder over `host`. `send` runs with the finished file, its length and the
// first picture; a recording the person cancels never reaches it.
void open(QWidget *host, std::function<void(const QString &path, qint64 durationMs, const QImage &poster)> send);

}
