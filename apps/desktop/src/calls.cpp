#include "calls.h"

#include "core.h"
#include "neocall.h"
#include "theme.h"
#include "widgets.h"

#include <QAbstractButton>
#include <QApplication>
#include <QCloseEvent>
#include <QDateTime>
#include <QJsonDocument>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QSettings>
#include <QTimer>
#include <QVariantAnimation>
#include <QWidget>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <vector>

#define NOMINMAX
#include <windows.h>
#include <mmsystem.h>

// ---- tones ----

namespace {

// Ringing sounds are generated, not shipped: a short two-note ring for
// incoming calls and the 425 Hz ringback for outgoing ones.
QByteArray makeWav(const std::vector<std::pair<double, double>> &parts)
{
    const int rate = 16000;
    QByteArray pcm;
    for (const auto &[freq, seconds] : parts) {
        const int n = int(rate * seconds);
        const double fade = rate * 0.012;
        for (int i = 0; i < n; ++i) {
            const double env = std::min({1.0, i / fade, (n - i) / fade});
            const double v = freq > 0 ? std::sin(2 * 3.14159265358979 * freq * i / rate) * 0.3 * env : 0;
            const qint16 s = qint16(v * 32767);
            pcm.append(reinterpret_cast<const char *>(&s), 2);
        }
    }
    QByteArray wav;
    auto u32 = [&](quint32 v) { wav.append(reinterpret_cast<const char *>(&v), 4); };
    auto u16 = [&](quint16 v) { wav.append(reinterpret_cast<const char *>(&v), 2); };
    wav.append("RIFF");
    u32(36 + pcm.size());
    wav.append("WAVEfmt ");
    u32(16);
    u16(1);
    u16(1);
    u32(rate);
    u32(rate * 2);
    u16(2);
    u16(16);
    wav.append("data");
    u32(pcm.size());
    return wav + pcm;
}

enum class Tone { Ring, Ringback };

void playTone(Tone t)
{
    static const QByteArray ring = makeWav({{659, 0.16}, {0, 0.05}, {880, 0.16}, {0, 0.05}, {659, 0.16}, {0, 0.05}, {880, 0.22}, {0, 1.8}});
    static const QByteArray ringback = makeWav({{425, 0.9}, {0, 3.1}});
    const QByteArray &data = t == Tone::Ring ? ring : ringback;
    PlaySoundW(reinterpret_cast<LPCWSTR>(data.constData()), nullptr, SND_MEMORY | SND_ASYNC | SND_LOOP | SND_NODEFAULT);
}

void stopTone()
{
    PlaySoundW(nullptr, nullptr, 0);
}

QString clock(qint64 seconds)
{
    const qint64 h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
    return h ? QStringLiteral("%1:%2:%3").arg(h).arg(m, 2, 10, QChar('0')).arg(s, 2, 10, QChar('0'))
             : QStringLiteral("%1:%2").arg(m, 2, 10, QChar('0')).arg(s, 2, 10, QChar('0'));
}

} // namespace

// ---- media ----

// State shared with neocall's threads. `nc` is touched only by tasks of the
// controller's serial pool; callbacks keep the object alive while queued.
struct CallMedia : std::enable_shared_from_this<CallMedia> {
    quint64 gen = 0;
    neocall *nc = nullptr;
    std::atomic<bool> pending[2] = {false, false};
};

namespace {

void postMediaEvent(quint64 gen, const QJsonObject &ev)
{
    QMetaObject::invokeMethod(CallController::instance(), [gen, ev] { CallController::instance()->onMediaEvent(gen, ev); }, Qt::QueuedConnection);
}

void mediaEvent(void *ctx, const char *json)
{
    postMediaEvent(static_cast<CallMedia *>(ctx)->gen, QJsonDocument::fromJson(QByteArray(json)).object());
}

void mediaFrame(void *ctx, int remote, const uint8_t *bgra, int w, int h, int stride)
{
    auto *m = static_cast<CallMedia *>(ctx);
    const int i = remote ? 1 : 0;
    // One frame in flight per stream: a busy UI skips frames instead of queueing them.
    if (m->pending[i].exchange(true))
        return;
    const QImage img = QImage(bgra, w, h, stride, QImage::Format_RGB32).copy();
    std::shared_ptr<CallMedia> keep = m->shared_from_this();
    QMetaObject::invokeMethod(CallController::instance(), [keep, i, img] {
        keep->pending[i] = false;
        CallController::instance()->onFrame(keep->gen, i, img);
    }, Qt::QueuedConnection);
}

} // namespace

// ---- window ----

namespace {

class CallButton : public QAbstractButton {
public:
    enum Kind { Plain, Danger, Accept };
    CallButton(const QString &icon, const QString &caption, Kind kind, QWidget *parent)
        : QAbstractButton(parent), m_icon(icon), m_kind(kind)
    {
        setText(caption);
        setAccessibleName(caption);
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        resize(sizeHint());
    }
    void set(const QString &icon, const QString &caption, const QString &accessible)
    {
        m_icon = icon;
        setText(caption);
        setAccessibleName(accessible);
        update();
    }
    QSize sizeHint() const override { return {84, 86}; }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const QRectF circle((width() - 56) / 2.0, 2, 56, 56);
        QColor bg = m_kind == Danger ? QColor("#E5484D") : m_kind == Accept ? QColor("#2FA86A") : isChecked() ? QColor("#F2F7FF") : QColor(255, 255, 255, 40);
        if (isDown())
            bg = bg.darker(115);
        else if (underMouse())
            bg = m_kind == Plain && !isChecked() ? QColor(255, 255, 255, 64) : bg.lighter(110);
        p.setPen(Qt::NoPen);
        p.setBrush(bg);
        p.drawEllipse(circle);
        const QColor fg = m_kind == Plain && isChecked() ? QColor("#0F1A2E") : QColor(Qt::white);
        ui::paintIcon(&p, circle.adjusted(16, 16, -16, -16), m_icon, fg, 1.9);
        p.setFont(Theme::instance()->font(12));
        p.setPen(QColor("#D3DEF0"));
        p.drawText(QRectF(0, 62, width(), 20), Qt::AlignHCenter | Qt::AlignTop, text());
        if (hasFocus()) {
            p.setBrush(Qt::NoBrush);
            p.setPen(QPen(QColor("#98B9FF"), 2));
            p.drawEllipse(circle.adjusted(-3, -3, 3, 3));
        }
    }
    void enterEvent(QEnterEvent *) override { update(); }
    void leaveEvent(QEvent *) override { update(); }

private:
    QString m_icon;
    Kind m_kind;
};

} // namespace

// Separate window, so the chat stays usable during a call. Always dark, like
// the video stage; the peer's video covers it when their camera is on.
class CallWindow : public QWidget {
public:
    CallWindow()
    {
        setAttribute(Qt::WA_DeleteOnClose);
        setAttribute(Qt::WA_QuitOnClose, false);
        setMinimumSize(340, 480);
        resize(440, 640);
        auto *c = CallController::instance();
        m_mic = new CallButton("mic", QStringLiteral("Микрофон"), CallButton::Plain, this);
        m_mic->setCheckable(true);
        m_camera = new CallButton("video-off", QStringLiteral("Камера"), CallButton::Plain, this);
        m_camera->setCheckable(true);
        m_end = new CallButton("hangup", QStringLiteral("Завершить"), CallButton::Danger, this);
        m_decline = new CallButton("hangup", QStringLiteral("Отклонить"), CallButton::Danger, this);
        m_accept = new CallButton("phone", QStringLiteral("Ответить"), CallButton::Accept, this);
        m_acceptVideo = new CallButton("video", QStringLiteral("С видео"), CallButton::Accept, this);
        connect(m_mic, &QAbstractButton::clicked, this, [c](bool muted) { c->setMic(!muted); });
        connect(m_camera, &QAbstractButton::clicked, this, [c](bool on) { c->setCamera(on); });
        connect(m_end, &QAbstractButton::clicked, c, &CallController::hangup);
        connect(m_decline, &QAbstractButton::clicked, c, &CallController::hangup);
        connect(m_accept, &QAbstractButton::clicked, this, [c] { c->accept(false); });
        connect(m_acceptVideo, &QAbstractButton::clicked, this, [c] { c->accept(true); });
        connect(c, &CallController::changed, this, &CallWindow::sync);
        connect(c, &CallController::frame, this, qOverload<>(&QWidget::update));
        auto *tick = new QTimer(this);
        tick->setInterval(500);
        connect(tick, &QTimer::timeout, this, qOverload<>(&QWidget::update));
        tick->start();
        m_ripple.setStartValue(0.0);
        m_ripple.setEndValue(1.0);
        m_ripple.setDuration(1600);
        m_ripple.setLoopCount(-1);
        connect(&m_ripple, &QVariantAnimation::valueChanged, this, qOverload<>(&QWidget::update));
        m_fade.setStartValue(0.0);
        m_fade.setEndValue(1.0);
        connect(&m_fade, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) { setWindowOpacity(v.toReal()); });
        sync();
    }

    void appear()
    {
        const bool shown = isVisible();
        if (!shown && Theme::ms(180) > 0)
            setWindowOpacity(0);
        show();
        raise();
        activateWindow();
        if (!shown && Theme::ms(180) > 0) {
            m_fade.setDuration(Theme::ms(180));
            m_fade.start();
        }
    }

    void sync()
    {
        auto *c = CallController::instance();
        const auto ph = c->phase();
        const QJsonObject call = c->call();
        setWindowTitle(QStringLiteral("Звонок · ") + call.value("peer_name").toString());
        const bool ringing = ph == CallController::Incoming;
        m_decline->setVisible(ringing);
        m_accept->setVisible(ringing);
        m_acceptVideo->setVisible(ringing && call.value("video").toBool());
        const bool live = ph == CallController::Starting || ph == CallController::Outgoing || ph == CallController::Connecting || ph == CallController::Active;
        m_mic->setVisible(live);
        m_camera->setVisible(live);
        m_end->setVisible(live);
        m_mic->setChecked(!c->micOn());
        m_mic->set(c->micOn() ? "mic" : "mic-off", c->micOn() ? QStringLiteral("Микрофон") : QStringLiteral("Без звука"),
                   c->micOn() ? QStringLiteral("Выключить микрофон") : QStringLiteral("Включить микрофон"));
        m_camera->setChecked(c->cameraOn());
        m_camera->set(c->cameraOn() ? "video" : "video-off", QStringLiteral("Камера"),
                      c->cameraOn() ? QStringLiteral("Выключить камеру") : QStringLiteral("Включить камеру"));
        const bool pulse = (ph == CallController::Incoming || ph == CallController::Outgoing) && !Theme::reducedMotion();
        if (pulse && m_ripple.state() != QAbstractAnimation::Running)
            m_ripple.start();
        else if (!pulse)
            m_ripple.stop();
        setTopmost(ringing);
        layoutButtons();
        update();
    }

protected:
    void resizeEvent(QResizeEvent *) override { layoutButtons(); }

    void closeEvent(QCloseEvent *e) override
    {
        auto *c = CallController::instance();
        if (c->phase() != CallController::Idle && c->phase() != CallController::Ended)
            c->hangup();
        e->accept();
    }

    void paintEvent(QPaintEvent *) override
    {
        auto *c = CallController::instance();
        const Palette d = Palette::dark();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        QLinearGradient g(0, 0, 0, height());
        g.setColorAt(0, d.bg1);
        g.setColorAt(1, d.bg0);
        p.fillRect(rect(), g);
        const QJsonObject call = c->call();
        const QString name = call.value("peer_name").toString();
        const QString status = statusText();
        if (c->remoteVideo()) {
            const QImage &img = c->remoteFrame();
            // Cover the window, cropping the overflow evenly.
            const qreal k = qMax(width() / qreal(img.width()), height() / qreal(img.height()));
            const QSizeF src(width() / k, height() / k);
            p.drawImage(QRectF(rect()), img, QRectF(QPointF((img.width() - src.width()) / 2, (img.height() - src.height()) / 2), src));
            QLinearGradient shade(0, 0, 0, 110);
            shade.setColorAt(0, QColor(0, 0, 0, 140));
            shade.setColorAt(1, QColor(0, 0, 0, 0));
            p.fillRect(QRectF(0, 0, width(), 110), shade);
            p.setFont(Theme::instance()->font(17, QFont::DemiBold));
            p.setPen(Qt::white);
            p.drawText(QRectF(20, 16, width() - 40, 24), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(name, Qt::ElideRight, width() - 40));
            p.setFont(Theme::instance()->font(13));
            p.setPen(QColor("#D3DEF0"));
            p.drawText(QRectF(20, 40, width() - 40, 20), Qt::AlignLeft | Qt::AlignVCenter, status);
        } else {
            const qreal side = qMin(120.0, width() * 0.32);
            const QPointF center(width() / 2.0, height() * 0.34);
            if (m_ripple.state() == QAbstractAnimation::Running) {
                for (qreal off : {0.0, 0.5}) {
                    const qreal t = std::fmod(m_ripple.currentValue().toReal() + off, 1.0);
                    const qreal r = side / 2 + t * side * 0.45;
                    QColor ring = d.accent;
                    ring.setAlphaF(0.35 * (1 - t));
                    p.setPen(QPen(ring, 2));
                    p.setBrush(Qt::NoBrush);
                    p.drawEllipse(center, r, r);
                }
            }
            p.save();
            QPainterPath clip;
            clip.addEllipse(center, side / 2, side / 2);
            p.setClipPath(clip);
            ui::paintAvatar(&p, QRectF(center.x() - side / 2, center.y() - side / 2, side, side), name,
                            call.value("peer_user_id").toString(), call.value("avatar_path").toString());
            p.restore();
            p.setFont(Theme::instance()->font(22, QFont::DemiBold));
            p.setPen(d.text);
            const qreal ty = center.y() + side / 2 + 22;
            p.drawText(QRectF(20, ty, width() - 40, 32), Qt::AlignHCenter | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(name, Qt::ElideRight, width() - 40));
            p.setFont(Theme::instance()->font(14));
            p.setPen(d.muted);
            p.drawText(QRectF(20, ty + 34, width() - 40, 22), Qt::AlignHCenter | Qt::AlignVCenter, status);
        }
        const QImage &local = c->localFrame();
        if (c->cameraOn() && !local.isNull()) {
            const qreal w = qMin(200.0, width() * 0.3), h = w * local.height() / qMax(1, local.width());
            const QRectF r(width() - w - 16, height() - 104 - h - 12, w, h);
            p.save();
            QPainterPath clip;
            clip.addRoundedRect(r, 14, 14);
            p.setClipPath(clip);
            // Mirrored, like a mirror: the peer sees the real orientation.
            p.translate(r.center());
            p.scale(-1, 1);
            p.translate(-r.center());
            p.drawImage(r, local);
            p.restore();
            p.setPen(QPen(QColor(255, 255, 255, 60), 1));
            p.setBrush(Qt::NoBrush);
            p.drawRoundedRect(r, 14, 14);
        }
    }

private:
    QString statusText() const
    {
        auto *c = CallController::instance();
        const bool video = c->call().value("video").toBool();
        switch (c->phase()) {
        case CallController::Starting: return QStringLiteral("Подготовка звонка…");
        case CallController::Outgoing: return QStringLiteral("Вызов…");
        case CallController::Incoming: return video ? QStringLiteral("Входящий видеозвонок") : QStringLiteral("Входящий звонок");
        case CallController::Connecting: return QStringLiteral("Соединение…");
        case CallController::Active:
            return c->reconnecting() ? QStringLiteral("Восстанавливаем связь…") : clock((QDateTime::currentMSecsSinceEpoch() - c->activeSince()) / 1000);
        case CallController::Ended: return c->endText();
        case CallController::Idle: break;
        }
        return {};
    }

    void layoutButtons()
    {
        QList<QWidget *> row;
        for (QWidget *b : {static_cast<QWidget *>(m_mic), static_cast<QWidget *>(m_camera), static_cast<QWidget *>(m_end), static_cast<QWidget *>(m_decline),
                           static_cast<QWidget *>(m_acceptVideo), static_cast<QWidget *>(m_accept)})
            if (!b->isHidden())
                row << b;
        const int gap = row.size() > 2 ? 12 : 48;
        int total = -gap;
        for (QWidget *b : row)
            total += b->width() + gap;
        int x = (width() - total) / 2;
        for (QWidget *b : row) {
            b->move(x, height() - 104);
            x += b->width() + gap;
        }
    }

    void setTopmost(bool on)
    {
        if (on == m_topmost || !isVisible())
            return;
        m_topmost = on;
        SetWindowPos(reinterpret_cast<HWND>(winId()), on ? HWND_TOPMOST : HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }

    CallButton *m_mic, *m_camera, *m_end, *m_decline, *m_accept, *m_acceptVideo;
    QVariantAnimation m_ripple, m_fade;
    bool m_topmost = false;
};

// ---- controller ----

CallController *CallController::instance()
{
    static CallController *c = new CallController;
    return c;
}

CallController::CallController()
{
    m_pool.setMaxThreadCount(1);
    m_pool.setExpiryTimeout(-1);
    m_dropTimer = new QTimer(this);
    m_dropTimer->setSingleShot(true);
    m_dropTimer->setInterval(12000);
    // ICE stayed disconnected (network switch, sleep): give up as a failed call.
    connect(m_dropTimer, &QTimer::timeout, this, [this] {
        if (m_phase == Active || m_phase == Connecting)
            Core::instance()->call("call_hangup", {{"call_id", m_call.value("call_id")}, {"reason", "failed"}});
    });
    connect(Core::instance(), &Core::event, this, &CallController::onCoreEvent);
}

bool CallController::remoteVideo() const
{
    return m_peerCamera && !m_remote.isNull() && m_remoteAt.isValid() && m_remoteAt.elapsed() < 1500;
}

void CallController::setPhase(Phase p)
{
    m_phase = p;
    emit changed();
}

void CallController::showWindow()
{
    if (!m_window)
        m_window = new CallWindow;
    m_window->sync();
    m_window->appear();
}

void CallController::showCurrentCall()
{
    if (m_phase != Idle && m_phase != Ended)
        showWindow();
}

void CallController::closeWindow()
{
    // Cleared first: the window deletes itself later and must not be reused.
    if (CallWindow *w = m_window.data()) {
        m_window.clear();
        w->close();
    }
}

void CallController::start(const QJsonObject &conversation, bool video)
{
    if (m_phase == Ended)
        closeWindow();
    if (m_phase != Idle && m_phase != Ended) {
        if (toast)
            toast(QStringLiteral("Уже идёт звонок"));
        if (m_window)
            m_window->appear();
        return;
    }
    m_call = {{"conversation_id", conversation.value("id")},
              {"peer_name", conversation.value("title")},
              {"peer_user_id", conversation.value("peer_user_id")},
              {"avatar_path", conversation.value("avatar_path")},
              {"video", video},
              {"outgoing", true}};
    m_mic = true;
    m_camera = false;
    m_peerCamera = video;
    m_hangupPending = false;
    m_reconnecting = false;
    m_cameraSent = false;
    m_remote = m_local = QImage();
    setPhase(Starting);
    showWindow();
    Core::instance()->call("call_start", {{"conversation_id", conversation.value("id")}, {"video", video}}, this, [this, video](const QJsonValue &v, const CoreError &e) {
        if (!e.ok()) {
            if (m_phase == Starting) {
                if (toast)
                    toast(e.message);
                m_endText = e.message;
                finish({});
            }
            return;
        }
        const QJsonObject res = v.toObject();
        const QJsonObject call = res.value("call").toObject();
        if (m_hangupPending || m_phase != Starting) {
            // Cancelled while the core was reserving the call.
            Core::instance()->call("call_hangup", {{"call_id", call.value("call_id")}});
            return;
        }
        m_call = call;
        createMedia(res.value("ice_servers").toArray(), video, {});
        emit changed();
    });
}

void CallController::accept(bool video)
{
    if (m_phase != Incoming)
        return;
    stopTone();
    setPhase(Connecting);
    const QString id = m_call.value("call_id").toString();
    Core::instance()->call("call_accept", {{"call_id", id}}, this, [this, id, video](const QJsonValue &v, const CoreError &e) {
        if (m_call.value("call_id").toString() != id || m_phase != Connecting)
            return;
        if (!e.ok()) {
            m_endText = e.message;
            finish({});
            return;
        }
        const QJsonObject res = v.toObject();
        createMedia(res.value("ice_servers").toArray(), video, res.value("sdp").toString());
    });
}

void CallController::hangup()
{
    switch (m_phase) {
    case Idle:
    case Ended:
        return;
    case Starting:
        m_hangupPending = true;
        finish("cancelled");
        return;
    default:
        break;
    }
    const QString id = m_call.value("call_id").toString();
    Core::instance()->call("call_hangup", {{"call_id", id}}, this, [this, id](const QJsonValue &, const CoreError &) {
        // The core reports the end as an event; this covers a call it no longer had.
        if (m_call.value("call_id").toString() == id && m_phase != Ended && m_phase != Idle)
            finish("hangup");
    });
}

void CallController::setMic(bool on)
{
    m_mic = on;
    emit changed();
    if (auto media = m_media)
        m_pool.start([media, on] {
            if (media->nc)
                neocall_set_mic(media->nc, on);
        });
}

void CallController::setCamera(bool on)
{
    auto media = m_media;
    if (!media) {
        // Before the media exists (call still being prepared) only remember the wish.
        emit changed();
        return;
    }
    m_pool.start([media, on] {
        const int now = media->nc ? neocall_set_camera(media->nc, on) : 0;
        postMediaEvent(media->gen, {{"type", "camera"}, {"on", now != 0}, {"wanted", on}});
    });
}

void CallController::sendCameraState()
{
    if (m_phase != Active && m_phase != Connecting)
        return;
    Core::instance()->call("call_media", {{"call_id", m_call.value("call_id")}, {"video", m_camera}});
}

void CallController::createMedia(const QJsonArray &ice, bool video, const QString &offer)
{
    destroyMedia();
    auto media = std::make_shared<CallMedia>();
    media->gen = ++m_gen;
    m_media = media;
    struct Ice {
        QByteArray urls, user, pass;
    };
    std::vector<Ice> servers;
    for (const auto &x : ice) {
        const QJsonObject s = x.toObject();
        QStringList urls;
        if (s.value("urls").isArray())
            for (const auto &u : s.value("urls").toArray())
                urls << u.toString();
        else
            urls << s.value("urls").toString();
        servers.push_back({urls.join(' ').toUtf8(), s.value("username").toString().toUtf8(), s.value("credential").toString().toUtf8()});
    }
    QSettings settings;
    const QByteArray input = settings.value("calls/input").toString().toUtf8();
    const QByteArray output = settings.value("calls/output").toString().toUtf8();
    const QByteArray camera = settings.value("calls/camera").toString().toUtf8();
    const QByteArray sdp = offer.toUtf8();
    const bool mic = m_mic;
    m_pool.start([media, servers, input, output, camera, sdp, video, mic] {
        std::vector<neocall_ice> list;
        for (const auto &s : servers)
            list.push_back({s.urls.constData(), s.user.constData(), s.pass.constData()});
        neocall_config cfg{list.data(), int(list.size()), 0, input.constData(), output.constData(), camera.constData()};
        media->nc = neocall_create(&cfg, &mediaEvent, &mediaFrame, media.get());
        if (!media->nc) {
            postMediaEvent(media->gen, {{"type", "state"}, {"state", "failed"}});
            return;
        }
        if (!mic)
            neocall_set_mic(media->nc, 0);
        if (sdp.isEmpty())
            neocall_offer(media->nc);
        else
            neocall_answer(media->nc, sdp.constData());
        if (video) {
            const int on = neocall_set_camera(media->nc, 1);
            postMediaEvent(media->gen, {{"type", "camera"}, {"on", on != 0}, {"wanted", true}});
        }
    });
}

void CallController::destroyMedia()
{
    auto media = std::move(m_media);
    m_media.reset();
    if (!media)
        return;
    m_pool.start([media] {
        if (media->nc)
            neocall_destroy(media->nc);
        media->nc = nullptr;
    });
}

void CallController::onMediaEvent(quint64 gen, const QJsonObject &ev)
{
    if (!m_media || m_media->gen != gen)
        return;
    const QString type = ev.value("type").toString();
    const QString id = m_call.value("call_id").toString();
    if (type == "local_sdp") {
        const bool outgoing = m_call.value("outgoing").toBool();
        Core::instance()->call(outgoing ? "call_offer" : "call_answer", {{"call_id", id}, {"sdp", ev.value("sdp")}}, this,
                               [this, id, outgoing](const QJsonValue &, const CoreError &e) {
                                   if (e.ok() || m_call.value("call_id").toString() != id || m_phase == Ended || m_phase == Idle)
                                       return;
                                   if (toast)
                                       toast(e.message);
                                   if (outgoing)
                                       finish("failed"); // the core already dropped the call
                                   else
                                       hangup();
                               });
    } else if (type == "state") {
        const QString state = ev.value("state").toString();
        if (state == "connected") {
            m_dropTimer->stop();
            m_reconnecting = false;
            if (m_phase == Connecting)
                Core::instance()->call("call_connected", {{"call_id", id}});
            emit changed();
        } else if (state == "disconnected") {
            if (m_phase == Active) {
                m_reconnecting = true;
                m_dropTimer->start();
                emit changed();
            }
        } else if (state == "failed") {
            Core::instance()->call("call_hangup", {{"call_id", id}, {"reason", "failed"}}, this, [this, id](const QJsonValue &, const CoreError &) {
                if (m_call.value("call_id").toString() == id && m_phase != Ended && m_phase != Idle)
                    finish("failed");
            });
        }
    } else if (type == "camera") {
        m_camera = ev.value("on").toBool();
        if (!m_camera)
            m_local = QImage();
        if (ev.value("wanted").toBool() && !m_camera && toast)
            toast(QStringLiteral("Не удалось включить камеру"));
        sendCameraState();
        emit changed();
    }
}

void CallController::onFrame(quint64 gen, int remote, const QImage &img)
{
    if (!m_media || m_media->gen != gen)
        return;
    if (remote) {
        m_remote = img;
        m_remoteAt.restart();
    } else {
        m_local = img;
    }
    emit frame();
}

void CallController::onCoreEvent(const QJsonObject &ev)
{
    const QString type = ev.value("type").toString();
    if (type == "call_media") {
        if (ev.value("call_id") == m_call.value("call_id")) {
            m_peerCamera = ev.value("video").toBool();
            emit changed();
        }
        return;
    }
    if (type != "call")
        return;
    const QString state = ev.value("state").toString();
    if (state == "incoming") {
        if (m_phase != Idle && m_phase != Ended)
            return;
        closeWindow();
        m_call = ev;
        m_mic = true;
        m_camera = false;
        m_peerCamera = ev.value("video").toBool();
        m_reconnecting = false;
        m_remote = m_local = QImage();
        setPhase(Incoming);
        showWindow();
        playTone(Tone::Ring);
        QApplication::alert(m_window);
        return;
    }
    if (ev.value("call_id") != m_call.value("call_id") || m_phase == Idle || m_phase == Ended)
        return;
    if (state == "outgoing") {
        setPhase(Outgoing);
        playTone(Tone::Ringback);
    } else if (state == "answered") {
        stopTone();
        setPhase(Connecting);
        const QByteArray sdp = ev.value("sdp").toString().toUtf8();
        if (auto media = m_media)
            m_pool.start([media, sdp] {
                if (media->nc)
                    neocall_set_answer(media->nc, sdp.constData());
            });
    } else if (state == "connecting") {
        setPhase(Connecting);
    } else if (state == "active") {
        m_since = qint64(ev.value("since").toDouble());
        if (m_since <= 0)
            m_since = QDateTime::currentMSecsSinceEpoch();
        setPhase(Active);
        sendCameraState();
    } else if (state == "ended") {
        finish(ev.value("reason").toString());
    }
}

void CallController::finish(const QString &reason)
{
    stopTone();
    m_dropTimer->stop();
    destroyMedia();
    const bool out = m_call.value("outgoing").toBool();
    static const QHash<QString, QString> texts{
        {"hangup", QStringLiteral("Звонок завершён")},
        {"busy", QStringLiteral("Абонент занят")},
        {"cancelled", QStringLiteral("Звонок отменён")},
        {"no_answer", QStringLiteral("Нет ответа")},
        {"missed", QStringLiteral("Пропущенный звонок")},
        {"failed", QStringLiteral("Не удалось соединиться")},
        {"answered_elsewhere", QStringLiteral("Принят на другом устройстве")},
        {"declined_elsewhere", QStringLiteral("Отклонён на другом устройстве")},
    };
    if (reason == "declined")
        m_endText = out ? QStringLiteral("Собеседник отклонил звонок") : QStringLiteral("Звонок отклонён");
    else if (!reason.isEmpty())
        m_endText = texts.value(reason, QStringLiteral("Звонок завершён"));
    m_remote = m_local = QImage();
    m_camera = false;
    setPhase(Ended);
    // The result stays on screen briefly, then the window closes.
    const QString id = m_call.value("call_id").toString();
    QTimer::singleShot(1600, this, [this, id] {
        if (m_phase != Ended || m_call.value("call_id").toString() != id)
            return;
        closeWindow();
        m_phase = Idle;
    });
}

void CallController::shutdown()
{
    if (m_phase != Idle && m_phase != Ended && !m_call.value("call_id").toString().isEmpty())
        Core::instance()->call("call_hangup", {{"call_id", m_call.value("call_id")}});
    stopTone();
    destroyMedia();
    m_pool.waitForDone(5000);
    m_phase = Idle;
    closeWindow();
}

void CallController::devices(QObject *ctx, std::function<void(const QJsonObject &)> cb)
{
    QPointer<QObject> guard(ctx);
    m_pool.start([guard, cb] {
        char *s = neocall_devices();
        const QJsonObject o = QJsonDocument::fromJson(QByteArray(s)).object();
        neocall_free(s);
        QMetaObject::invokeMethod(CallController::instance(), [guard, cb, o] {
            if (guard)
                cb(o);
        }, Qt::QueuedConnection);
    });
}
