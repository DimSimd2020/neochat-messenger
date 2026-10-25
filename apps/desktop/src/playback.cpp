#include "playback.h"

#include "core.h"

#include <QSettings>
#include <QPointer>
#include <algorithm>
#include <thread>

Playback *Playback::instance()
{
    static Playback *p = new Playback;
    return p;
}

Playback::Playback()
{
    const double saved = QSettings().value("playback/speed", 1.0).toDouble();
    m_speed = saved >= 1.9 ? 2.0 : saved >= 1.4 ? 1.5 : 1.0;
    m_timer.setInterval(25);
    connect(&m_timer, &QTimer::timeout, this, &Playback::tick);
}

qint64 Playback::currentMs() const
{
    if (m_state == State::Paused || m_state == State::Loading)
        return m_pausedMs;
    if (m_state != State::Playing)
        return 0;
    if (m_silent)
        return std::min(m_durationMs, m_clockBaseMs + m_clock.elapsed());
    return std::min<qint64>(m_durationMs, qint64(double(m_out.positionFrame()) * 1000.0 / std::max(1, m_orig.rate) * m_playingSpeed + 0.5));
}

qreal Playback::progress(const QString &id) const
{
    if (id != m_id || m_durationMs <= 0 || m_state == State::Idle)
        return 0;
    return std::clamp(double(currentMs()) / double(m_durationMs), 0.0, 1.0);
}

void Playback::toggle(const QJsonObject &msg)
{
    const QString id = msg.value("id").toString();
    if (id == m_id && m_state == State::Playing) {
        m_pausedMs = currentMs();
        m_state = State::Paused;
        if (m_silent)
            m_clockBaseMs = m_pausedMs;
        m_out.pause();
        emit changed();
        return;
    }
    if (id == m_id && m_state == State::Paused) {
        m_state = State::Playing;
        if (m_silent)
            m_clock.restart();
        m_out.resume();
        emit changed();
        return;
    }
    if (id == m_id && m_state == State::Loading)
        return;
    load(msg);
}

void Playback::stop()
{
    ++m_generation;
    m_timer.stop();
    m_out.stop();
    if (m_decoder) {
        m_decoder->cancel();
        m_decoder->wait(2000);
        delete m_decoder;
        m_decoder = nullptr;
    }
    m_state = State::Idle;
    m_id.clear();
    m_frame = {};
    m_orig = {};
    m_pausedMs = 0;
    emit changed();
}

void Playback::load(const QJsonObject &msg)
{
    stop();
    m_id = msg.value("id").toString();
    const QJsonObject file = msg.value("file").toObject();
    m_circle = file.value("circle").toBool();
    m_durationMs = qint64(file.value("duration").toDouble());
    m_state = State::Loading;
    m_pausedMs = 0;
    const quint64 generation = m_generation;
    const QString id = m_id;
    emit changed();
    Core::instance()->call("download_file", {{"message_id", id}}, this, [this, generation, id](const QJsonValue &v, const CoreError &e) {
        if (generation != m_generation)
            return;
        if (!e.ok()) {
            stop();
            emit failed(e.message);
            return;
        }
        m_path = v.toObject().value("path").toString();
        const QString path = m_path;
        const QPointer<Playback> self(this);
        std::thread([self, generation, path] {
            mf::Pcm pcm;
            const bool ok = mf::decodeAudio(path, &pcm);
            if (!self)
                return;
            QMetaObject::invokeMethod(
                self,
                [self, generation, ok, pcm] {
                    if (!self || generation != self->m_generation)
                        return;
                    self->m_orig = ok ? pcm : mf::Pcm();
                    if (self->m_orig.frames() > 0)
                        self->m_durationMs = std::max(self->m_durationMs, self->m_orig.ms());
                    self->m_silent = self->m_orig.frames() == 0;
                    if (self->m_silent && !self->m_circle) {
                        self->stop();
                        emit self->failed(QStringLiteral("Не удалось воспроизвести: в Windows нет кодека для этого звука"));
                        return;
                    }
                    if (self->m_circle) {
                        self->m_decoder = new CircleDecoder(self->m_path, self);
                        self->m_decoder->start();
                    }
                    self->begin(0);
                },
                Qt::QueuedConnection);
        }).detach();
    });
}

void Playback::begin(qint64 fromMs)
{
    m_playingSpeed = m_circle ? 1.0 : m_speed;
    if (!m_silent) {
        const mf::Pcm pcm = m_playingSpeed == 1.0 ? m_orig : mf::stretch(m_orig, m_playingSpeed);
        const qint64 frame = qint64(double(fromMs) / m_playingSpeed * pcm.rate / 1000.0);
        if (!m_out.start(pcm, frame)) {
            stop();
            emit failed(QStringLiteral("Не удалось открыть устройство вывода звука"));
            return;
        }
    } else {
        m_clockBaseMs = fromMs;
        m_clock.restart();
    }
    m_pausedMs = fromMs;
    m_state = State::Playing;
    m_timer.start();
    emit changed();
}

void Playback::seek(const QString &id, qreal fraction)
{
    if (id != m_id || m_state == State::Loading || m_state == State::Idle || m_durationMs <= 0)
        return;
    const qint64 ms = qint64(std::clamp<qreal>(fraction, 0, 0.999) * double(m_durationMs));
    if (m_decoder) {
        // The decoder runs forward only: a circle starts over from the beginning of its queue.
        m_decoder->cancel();
        m_decoder->wait(2000);
        delete m_decoder;
        m_decoder = new CircleDecoder(m_path, this);
        m_decoder->start();
    }
    begin(ms);
}

void Playback::cycleSpeed()
{
    m_speed = m_speed < 1.4 ? 1.5 : m_speed < 1.9 ? 2.0 : 1.0;
    QSettings().setValue("playback/speed", m_speed);
    // The voice that plays now goes on at the new tempo from where it is.
    if (!m_circle && (m_state == State::Playing || m_state == State::Paused) && !m_silent) {
        const bool paused = m_state == State::Paused;
        const qint64 at = currentMs();
        begin(at);
        if (paused) {
            m_out.pause();
            m_state = State::Paused;
        }
    }
    emit changed();
}

void Playback::tick()
{
    if (m_state != State::Playing)
        return;
    if (!m_silent) {
        m_out.tick();
        if (m_out.finished())
            return finish();
    } else if (currentMs() >= m_durationMs) {
        return finish();
    }
    if (m_decoder) {
        const QImage f = m_decoder->frameAt(currentMs());
        if (!f.isNull())
            m_frame = f;
    }
    emit changed();
}

void Playback::finish()
{
    m_timer.stop();
    m_out.stop();
    if (m_decoder) {
        m_decoder->cancel();
        m_decoder->wait(2000);
        delete m_decoder;
        m_decoder = nullptr;
    }
    m_state = State::Idle;
    m_id.clear();
    m_frame = {};
    m_orig = {};
    emit changed();
}
