#pragma once

#include <QPoint>
#include <QString>

class QApplication;
class QWidget;

// App-wide replacements for the platform's pop-ups: the context menu of text fields and
// labels (cut, copy, paste, select all) and tooltips, both drawn in the app's own style.
namespace chrome {
void install(QApplication *app);
void showTip(const QPoint &globalPos, const QString &text, QWidget *near);
void hideTip();
}
