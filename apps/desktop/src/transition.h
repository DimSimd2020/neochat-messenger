#pragma once

#include <QRect>
#include <functional>

class QWidget;

namespace ui {

// How the screen changes: Fade cross-fades, Forward pushes the new picture in from the right while the old one
// drifts left (Telegram-style open), Back is the reverse. The next three need no picture of the result, so they
// suit content that is still loading: Dissolve melts the old picture away, PullLeft and PullRight slide it off
// to that side and uncover the live widgets.
enum class Motion { Fade, Forward, Back, Dissolve, PullLeft, PullRight };

// Runs `change` and plays the move from what `area` of `host` looked like before to what it looks like after.
// Without animations (reduced motion, host not shown) it only runs `change`.
void transition(QWidget *host, const QRect &area, Motion motion, const std::function<void()> &change);

} // namespace ui
