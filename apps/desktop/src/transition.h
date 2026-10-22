#pragma once

#include <QRect>
#include <functional>

class QWidget;

namespace ui {

// How the screen changes: Fade cross-fades, Forward pushes the new picture in from the right while the old one
// drifts left (Telegram-style open), Back is the reverse. Dissolve melts the old picture away. PullLeft and
// PullRight slide it off and uncover the live widgets. ShiftLeft and ShiftRight cross-fade the two pictures
// with a short horizontal move, for content that is already on screen (profile tabs).
enum class Motion { Fade, Forward, Back, Dissolve, PullLeft, PullRight, ShiftLeft, ShiftRight };

// Runs `change` and plays the move from what `area` of `host` looked like before to what it looks like after.
// Without animations (reduced motion, host not shown) it only runs `change`.
void transition(QWidget *host, const QRect &area, Motion motion, const std::function<void()> &change);

} // namespace ui
