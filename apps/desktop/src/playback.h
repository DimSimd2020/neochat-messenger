#pragma once

#include "audioio.h"
#include "circle.h"

#include <QElapsedTimer>
#include <QImage>
#include <QJsonObject>
#include <QObject>
#include <QTimer>

// Plays one voice message or circle at a time. A voice runs at 1×, 1.5× or 2× with the pitch
// kept; a circle shows its frames in the bubble and plays its sound.
class Playback : public QObject {
    Q_OBJECT
public:
    enum class State { Idle, Loading, Playing, Paused };

    static Playback *instance();

    // Starts, pauses or resumes the message; another one that is playing stops.
    void toggle(const QJsonObject &msg);
    // Jumps to a share (0..1) of the message and plays from there.
    void seek(const QString &id, qreal fraction);
    // 1× → 1.5× → 2× → 1×, remembered for the next voice messages.
    void cycleSpeed();
    void stop();

    State state(const QString &id) const { return id == m_id ? m_state : State::Idle; }
    qreal progress(const QString &id) const;
    qint64 positionMs(const QString &id) const { return id == m_id ? currentMs() : 0; }
    double speed() const { return m_speed; }
    // The frame of a playing circle, null when the message is not playing.
    QImage circleFrame(const QString &id) const { return id == m_id ? m_frame : QImage(); }

signals:
    void changed();
    void failed(const QString &reason);

private:
    Playback();
    void load(const QJsonObject &msg);
    void begin(qint64 fromMs);
    void tick();
    void finish();
    qint64 currentMs() const;

    QString m_id;
    bool m_circle = false;
    State m_state = State::Idle;
    mf::Pcm m_orig;
    qint64 m_durationMs = 0;
    double m_speed = 1.0;
    double m_playingSpeed = 1.0;
    qint64 m_pausedMs = 0;
    PcmOutput m_out;
    QTimer m_timer;
    CircleDecoder *m_decoder = nullptr;
    QImage m_frame;
    QString m_path;
    // A circle without a sound track runs on this clock.
    QElapsedTimer m_clock;
    qint64 m_clockBaseMs = 0;
    bool m_silent = false;
    quint64 m_generation = 0;
};
