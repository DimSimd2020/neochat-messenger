#pragma once

#include <QFont>
#include <QJsonObject>
#include <QString>
#include <QTextCursor>

class QTextDocument;

// LaTeX formulas of the core's markdown spans ("math" key): typeset by the core, drawn here as
// inline objects of a QTextDocument in the color of the text around them.
namespace mathtext {

// Appends the formula of `span` ({text, math, display}) to `cur`; a display formula gets its own centered line.
void insert(QTextCursor &cur, const QJsonObject &span, const QFont &font);

// Lays `d` out for a column `inner` wide and returns the width its text takes (the bubble width).
qreal fit(QTextDocument *d, qreal inner);

// The text between two positions of `d` with every formula as typed ($x^2$), for copying.
QString plainText(QTextDocument *d, int from, int to);

} // namespace mathtext
