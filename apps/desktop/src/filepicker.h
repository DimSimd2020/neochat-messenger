#pragma once

#include <QString>
#include <QStringList>
#include <functional>

class QWidget;

// Own file chooser in the app's style (a sheet over the window), instead of the platform dialog.
namespace filepicker {
// `extensions`: lower case, without the dot; empty shows every file.
void open(QWidget *host, const QString &title, const QStringList &extensions, bool multiple, std::function<void(const QStringList &)> done);
// An existing file is never overwritten: the picked name gets " (1)", " (2)" ... then.
void save(QWidget *host, const QString &title, const QString &suggestedName, std::function<void(const QString &)> done);
}
