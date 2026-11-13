#pragma once

#include <QPointer>
#include <QString>
#include <QWidget>
#include <memory>

class QCloseEvent;
class QKeyEvent;
class QMouseEvent;
class QSlider;
class QTimer;
class VideoPlayerCallback;
struct VideoPlayback;
struct IMFPMediaItem;
struct IMFPMediaPlayer;
namespace ui {
class IconButton;
class Label;
}

// The video of a message plays inside NeoChat through Windows MFPlay, which streams both the
// picture and the sound; nothing is decoded into memory here. MFPlay delivers its events on its
// own thread: they are relayed to the UI thread, and the relay is closed before Shutdown.
// Times are milliseconds.
class VideoViewer : public QWidget {
    Q_OBJECT
public:
    explicit VideoViewer(QWidget *host, const QString &path, const QString &name);
    ~VideoViewer() override;

    bool ready() const { return m_ready; }
    bool playing() const { return m_playing; }
    qint64 position() const { return m_positionMs; }
    qint64 duration() const { return m_durationMs; }
    QString errorString() const { return m_error; }
    // The line that explains an error in the middle of the picture.
    ui::Label *note() const { return m_note; }

public slots:
    void toggle();
    void seek(qint64 ms);

protected:
    void paintEvent(QPaintEvent *) override;
    void mousePressEvent(QMouseEvent *e) override;
    void closeEvent(QCloseEvent *event) override;
    void keyPressEvent(QKeyEvent *event) override;
    bool eventFilter(QObject *object, QEvent *event) override;
    bool focusNextPrevChild(bool next) override;

private:
    friend class VideoPlayerCallback;
    void mediaEvent(int type, long result, IMFPMediaItem *item);
    void applyItemInfo();
    void applyCommands();
    void tick();
    void refreshControls();
    void restyle();
    void place();
    void updateVideoWindow();
    QRect videoRect() const;
    void shutdown();
    void fail(const QString &message, long result = 0);
    bool handleKey(QKeyEvent *event);

    QString m_path, m_name;
    std::unique_ptr<VideoPlayback> m_backend;
    QPointer<QWidget> m_previousFocus;
    void *m_videoWindow = nullptr; // the video window MFPlay creates inside this overlay (HWND)
    void *m_overlay = nullptr;     // this widget's own HWND (HWND)
    QWidget *m_bar = nullptr;
    QTimer *m_clock = nullptr;
    QSlider *m_seekBar = nullptr;
    ui::IconButton *m_play = nullptr;
    ui::IconButton *m_close = nullptr;
    ui::Label *m_elapsed = nullptr;
    ui::Label *m_total = nullptr;
    ui::Label *m_note = nullptr;
    QString m_error;
    qint64 m_positionMs = 0;
    qint64 m_durationMs = 0;
    qint64 m_pendingSeekMs = -1;
    bool m_ready = false;
    bool m_playing = false;
    bool m_wantPlaying = true;
    bool m_seekPending = false;
    bool m_canSeek = true;
    bool m_ended = false;
};

// True means the viewer was opened, not that asynchronous decoding has succeeded.
// Playback errors remain visible inside it; false means there is no host widget.
bool showVideoFile(QWidget *host, const QString &path, const QString &name);
