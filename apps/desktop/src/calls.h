#pragma once

#include <QElapsedTimer>
#include <QImage>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QThreadPool>
#include <functional>
#include <memory>

class CallWindow;
struct CallMedia;

// One-to-one calls on the desktop. Signaling (offer/answer/hangup) goes through
// the core as E2EE messages; media runs in neocall.dll (libwebrtc) on a serial
// worker thread so the UI never waits for devices or the network.
class CallController : public QObject {
    Q_OBJECT
public:
    enum Phase { Idle, Starting, Outgoing, Incoming, Connecting, Active, Ended };

    static CallController *instance();
    // `conversation` is the direct chat as returned by get_conversation.
    void start(const QJsonObject &conversation, bool video);
    void accept(bool video);
    void hangup();
    void setMic(bool on);
    void setCamera(bool on);
    // Ends a running call before the app quits.
    void shutdown();

    Phase phase() const { return m_phase; }
    bool busy() const { return m_phase != Idle; }
    const QJsonObject &call() const { return m_call; }
    bool micOn() const { return m_mic; }
    bool cameraOn() const { return m_camera; }
    // The peer's camera is on and frames are arriving.
    bool remoteVideo() const;
    const QImage &remoteFrame() const { return m_remote; }
    const QImage &localFrame() const { return m_local; }
    qint64 activeSince() const { return m_since; }
    bool reconnecting() const { return m_reconnecting; }
    QString endText() const { return m_endText; }

    // {"inputs","outputs","cameras": [{"id","name"}]}, from a worker thread.
    void devices(QObject *ctx, std::function<void(const QJsonObject &)> cb);

    std::function<void(const QString &)> toast;

    // Called on the UI thread for events and frames from neocall.dll.
    void onMediaEvent(quint64 gen, const QJsonObject &ev);
    void onFrame(quint64 gen, int remote, const QImage &img);

signals:
    void changed();
    void frame();

private:
    CallController();
    void onCoreEvent(const QJsonObject &ev);
    void createMedia(const QJsonArray &ice, bool video, const QString &offer);
    void destroyMedia();
    void finish(const QString &reason);
    void showWindow();
    void closeWindow();
    void setPhase(Phase p);
    void sendCameraState();

    Phase m_phase = Idle;
    QJsonObject m_call;
    bool m_mic = true;
    bool m_camera = false;
    bool m_peerCamera = false;
    bool m_hangupPending = false;
    bool m_reconnecting = false;
    bool m_cameraSent = false;
    qint64 m_since = 0;
    QString m_endText;
    QImage m_remote, m_local;
    QElapsedTimer m_remoteAt;
    quint64 m_gen = 0;
    std::shared_ptr<CallMedia> m_media;
    QThreadPool m_pool;
    QPointer<CallWindow> m_window;
    class QTimer *m_dropTimer;
};
