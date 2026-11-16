#include "videoplayer.h"

#include "playback.h"
#include "popupmenu.h"
#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QCloseEvent>
#include <QDir>
#include <QGuiApplication>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QScreen>
#include <QSignalBlocker>
#include <QSlider>
#include <QTimer>
#include <algorithm>
#include <atomic>
#include <iterator>
#include <limits>
#include <mutex>
#include <windows.h>
#include <mferror.h>
#include <mfplay.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace {

constexpr int kBarHeight = 52; // the control row under the picture
constexpr float kRates[] = {1.0f, 1.25f, 1.5f, 2.0f};

// MFPlay's aspect-ratio modes; the SDK header takes them as a plain DWORD and does not declare
// the enumeration, so the values come from the API documentation.
enum MFPlayAspect : DWORD {
    MFPlayAspect_MaxScale = 0,
    MFPlayAspect_LetterBox = 1,
    MFPlayAspect_NonUniform = 2,
    MFPlayPanel_Pan = 3,
};

QString clockText(qint64 ms)
{
    const qint64 s = ms / 1000;
    return QStringLiteral("%1:%2").arg(s / 60).arg(s % 60, 2, 10, QLatin1Char('0'));
}

QString rateText(float rate)
{
    return rate == 1.0f ? QStringLiteral("1×") : rate == 1.25f ? QStringLiteral("1,25×") : rate == 1.5f ? QStringLiteral("1,5×") : QStringLiteral("2×");
}

// The player answers times as 64-bit 100 ns values; both variants appear in practice.
HRESULT readTime(IMFPMediaPlayer *player, bool duration, qint64 *ms)
{
    PROPVARIANT value{};
    HRESULT hr = duration ? player->GetDuration(MFP_POSITIONTYPE_100NS, &value) : player->GetPosition(MFP_POSITIONTYPE_100NS, &value);
    if (SUCCEEDED(hr)) {
        if (value.vt == VT_UI8 && value.uhVal.QuadPart <= ULONGLONG(std::numeric_limits<qint64>::max()))
            *ms = qint64(value.uhVal.QuadPart / 10000);
        else if (value.vt == VT_I8)
            *ms = qMax(qint64(0), qint64(value.hVal.QuadPart / 10000));
        else
            hr = E_UNEXPECTED;
    }
    PropVariantClear(&value);
    return hr;
}

QString failureDetail(long hr)
{
    switch (HRESULT(hr)) {
    case MF_E_TOPO_CODEC_NOT_FOUND:
    case MF_E_UNSUPPORTED_BYTESTREAM_TYPE:
    case MF_E_UNSUPPORTED_FORMAT:
        return QStringLiteral("Windows не поддерживает формат этого видео.");
    case MF_E_INVALID_FILE_FORMAT:
        return QStringLiteral("Файл повреждён или имеет неверный формат.");
    case HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND):
    case HRESULT_FROM_WIN32(ERROR_PATH_NOT_FOUND):
        return QStringLiteral("Файл не найден. Скачайте видео заново.");
    case E_ACCESSDENIED:
        return QStringLiteral("Нет доступа к видеофайлу.");
    case MF_E_NO_AUDIO_PLAYBACK_DEVICE:
    case MF_E_AUDIO_PLAYBACK_DEVICE_INVALIDATED:
        return QStringLiteral("Устройство звука недоступно. Проверьте наушники или динамики.");
    default:
        return QStringLiteral("Попробуйте скачать видео заново.");
    }
}

// The panel is a native window of its own: MFPlay parents the picture to it, and a native
// window paints its own plate instead of inheriting the scrim's translucency.
class Panel : public QWidget {
public:
    using QWidget::QWidget;

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QPainterPath path;
        path.addRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 18, 18);
        p.fillPath(path, QColor(11, 18, 32));
        p.setPen(QPen(QColor(255, 255, 255, 26), 1));
        p.drawPath(path);
    }
};

} // namespace

// The UI target is cleared under this mutex before Shutdown, so a late event posts nothing.
struct VideoPlayerRelay {
    std::mutex mutex;
    VideoViewer *target = nullptr;
};

class VideoPlayerCallback final : public IMFPMediaPlayerCallback {
public:
    explicit VideoPlayerCallback(const std::shared_ptr<VideoPlayerRelay> &relay) : m_relay(relay) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override
    {
        if (!object)
            return E_POINTER;
        *object = nullptr;
        if (iid != __uuidof(IUnknown) && iid != __uuidof(IMFPMediaPlayerCallback))
            return E_NOINTERFACE;
        *object = static_cast<IMFPMediaPlayerCallback *>(this);
        AddRef();
        return S_OK;
    }

    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_refs; }
    ULONG STDMETHODCALLTYPE Release() override
    {
        const ULONG refs = --m_refs;
        if (!refs)
            delete this;
        return refs;
    }

    void STDMETHODCALLTYPE OnMediaPlayerEvent(MFP_EVENT_HEADER *event) override
    {
        if (!event)
            return;
        const auto type = event->eEventType;
        const HRESULT result = event->hrEvent;
        if (SUCCEEDED(result) && type != MFP_EVENT_TYPE_MEDIAITEM_CREATED && type != MFP_EVENT_TYPE_MEDIAITEM_SET
            && type != MFP_EVENT_TYPE_PLAY && type != MFP_EVENT_TYPE_PAUSE && type != MFP_EVENT_TYPE_POSITION_SET
            && type != MFP_EVENT_TYPE_PLAYBACK_ENDED && type != MFP_EVENT_TYPE_ERROR)
            return;
        // The event structure only lives for the call; the item reference we take outlives it.
        ComPtr<IMFPMediaItem> item;
        if (type == MFP_EVENT_TYPE_MEDIAITEM_CREATED && SUCCEEDED(result))
            item = MFP_GET_MEDIAITEM_CREATED_EVENT(event)->pMediaItem;
        post(int(type), long(result), std::move(item));
    }

private:
    void post(int type, long result, ComPtr<IMFPMediaItem> item)
    {
        std::lock_guard<std::mutex> lock(m_relay->mutex);
        if (!m_relay->target)
            return;
        auto relay = m_relay;
        QMetaObject::invokeMethod(
            m_relay->target,
            [relay, type, result, item] {
                VideoViewer *viewer = nullptr;
                {
                    std::lock_guard<std::mutex> lock(relay->mutex);
                    viewer = relay->target;
                }
                if (viewer)
                    viewer->mediaEvent(type, result, item.Get());
            },
            Qt::QueuedConnection);
    }

    std::atomic<ULONG> m_refs{1};
    std::shared_ptr<VideoPlayerRelay> m_relay;
};

struct VideoPlayback {
    ComPtr<IMFPMediaPlayer> player;
    ComPtr<IMFPMediaPlayerCallback> callback;
    std::shared_ptr<VideoPlayerRelay> relay = std::make_shared<VideoPlayerRelay>();
};

// MFPlay's picture window is not a Qt window: it takes the keyboard and the mouse for itself.
// Its procedure is hooked so Esc, space, arrows, mute and the clicks still reach the viewer.
static LRESULT CALLBACK videoWindowProc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam)
{
    auto *viewer = reinterpret_cast<VideoViewer *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (viewer) {
        const QPoint local(int(short(LOWORD(lparam))), int(short(HIWORD(lparam))));
        POINT screen{local.x(), local.y()};
        ClientToScreen(hwnd, &screen);
        if (viewer->nativeInput(message, unsigned(wparam), QPoint(screen.x, screen.y)))
            return 0;
        if (viewer->originalWindowProc())
            return CallWindowProcW(reinterpret_cast<WNDPROC>(viewer->originalWindowProc()), hwnd, message, wparam, lparam);
    }
    return DefWindowProcW(hwnd, message, wparam, lparam);
}

bool showVideoFile(QWidget *host, const QString &path, const QString &name)
{
    if (!host)
        return false;
    QWidget *window = host->window() ? host->window() : host;
    // One player per window: a second video raises the running one instead of stealing the sound.
    for (auto *viewer : window->findChildren<VideoViewer *>(QStringLiteral("videoViewer")))
        if (viewer->isVisible()) {
            viewer->raise();
            viewer->setFocus(Qt::OtherFocusReason);
            return true;
        }
    Playback::instance()->stop();
    new VideoViewer(window, path, name);
    return true;
}

VideoViewer::VideoViewer(QWidget *host, const QString &path, const QString &name)
    : QWidget(host), m_path(path), m_name(name)
{
    setObjectName(QStringLiteral("videoViewer"));
    setAttribute(Qt::WA_DeleteOnClose);
    setFocusPolicy(Qt::StrongFocus);
    setAccessibleName(QStringLiteral("Просмотр видео. ") + name + QStringLiteral(". Пробел — пауза, стрелки — перемотка, Esc — закрыть"));
    setGeometry(host->rect());
    host->installEventFilter(this);
    m_previousFocus = host->focusWidget();

    m_panel = new Panel(this);
    m_panel->setObjectName(QStringLiteral("videoPanel"));
    m_panel->setFocusPolicy(Qt::NoFocus);

    m_bar = new QWidget(m_panel);
    m_bar->setObjectName(QStringLiteral("videoControls"));
    auto *row = new QHBoxLayout(m_bar);
    row->setContentsMargins(10, 6, 10, 6);
    row->setSpacing(6);
    m_play = new ui::IconButton(QStringLiteral("play"), QStringLiteral("Воспроизвести"), m_bar, 36);
    m_play->setObjectName(QStringLiteral("videoPlay"));
    row->addWidget(m_play);
    m_elapsed = new ui::Label(QStringLiteral("0:00"), 12.5, 500, ui::Label::Muted, m_bar);
    row->addWidget(m_elapsed);
    m_seekBar = new QSlider(Qt::Horizontal, m_bar);
    m_seekBar->setObjectName(QStringLiteral("videoSeek"));
    m_seekBar->setFocusPolicy(Qt::TabFocus);
    m_seekBar->setAccessibleName(QStringLiteral("Место в видео"));
    m_seekBar->setRange(0, 0);
    row->addWidget(m_seekBar, 1);
    m_total = new ui::Label({}, 12.5, 500, ui::Label::Muted, m_bar);
    row->addWidget(m_total);
    m_rate = new ui::Button(QStringLiteral("1×"), ui::Button::Flat, m_bar);
    m_rate->setObjectName(QStringLiteral("videoRate"));
    m_rate->setAccessibleName(QStringLiteral("Скорость воспроизведения"));
    m_rate->setFixedHeight(32);
    row->addWidget(m_rate);
    m_mute = new ui::IconButton(QStringLiteral("speaker"), QStringLiteral("Выключить звук"), m_bar, 36);
    m_mute->setObjectName(QStringLiteral("videoMute"));
    row->addWidget(m_mute);
    m_expand = new ui::IconButton(QStringLiteral("expand"), QStringLiteral("Развернуть на окно"), m_bar, 36);
    m_expand->setObjectName(QStringLiteral("videoExpand"));
    row->addWidget(m_expand);
    m_close = new ui::IconButton(QStringLiteral("close"), QStringLiteral("Закрыть"), m_bar, 36);
    m_close->setObjectName(QStringLiteral("videoClose"));
    row->addWidget(m_close);

    m_note = new ui::Label({}, 14, 400, ui::Label::Muted, m_panel);
    m_note->setObjectName(QStringLiteral("videoNote"));
    m_note->setAlignment(Qt::AlignCenter);
    m_note->setWordWrap(true);
    m_note->hide();

    m_clock = new QTimer(this);
    m_clock->setInterval(30);
    connect(m_clock, &QTimer::timeout, this, &VideoViewer::tick);
    connect(m_play, &QAbstractButton::clicked, this, [this] { toggle(); });
    connect(m_close, &QAbstractButton::clicked, this, &QWidget::close);
    connect(m_rate, &QAbstractButton::clicked, this, [this] { cycleRate(); });
    connect(m_mute, &QAbstractButton::clicked, this, [this] { toggleMute(); });
    connect(m_expand, &QAbstractButton::clicked, this, [this] { toggleExpanded(); });
    connect(m_seekBar, &QSlider::sliderMoved, this, [this](int sec) { seek(qint64(sec) * 1000); });
    connect(m_seekBar, &QSlider::actionTriggered, this, [this](int action) {
        // The keyboard moves the bar without dragging it; that is a seek too.
        if (action != QAbstractSlider::SliderMove && !m_seekBar->isSliderDown())
            seek(qint64(m_seekBar->value()) * 1000);
    });
    connect(Theme::instance(), &Theme::changed, this, &VideoViewer::restyle);

    m_backend = std::make_unique<VideoPlayback>();
    m_backend->callback = new VideoPlayerCallback(m_backend->relay);
    {
        std::lock_guard<std::mutex> lock(m_backend->relay->mutex);
        m_backend->relay->target = this;
    }
    // MFPlay parents its video window to the panel, so the panel is the player window.
    m_overlay = reinterpret_cast<HWND>(m_panel->winId());
    const HWND overlay = static_cast<HWND>(m_overlay);
    const HRESULT created = MFPCreateMediaPlayer(nullptr, FALSE, MFP_OPTION_NONE, m_backend->callback.Get(), overlay, &m_backend->player);
    if (SUCCEEDED(created) && m_backend->player && overlay) {
        m_backend->player->SetBorderColor(RGB(11, 18, 32));
        m_backend->player->SetAspectRatioMode(MFPlayAspect_LetterBox);
        HWND video = nullptr;
        if (SUCCEEDED(m_backend->player->GetVideoWindow(&video)))
            m_videoWindow = video;
        const std::wstring file = QDir::toNativeSeparators(path).toStdWString();
        ComPtr<IMFPMediaItem> item;
        const HRESULT made = m_backend->player->CreateMediaItemFromURL(file.c_str(), TRUE, 0, &item);
        if (SUCCEEDED(made) && item) {
            const HRESULT set = m_backend->player->SetMediaItem(item.Get());
            if (FAILED(set))
                fail(QStringLiteral("Не удалось подготовить видео к воспроизведению."), set);
        } else {
            fail(QStringLiteral("Не удалось открыть видео."), made);
        }
    } else if (!overlay) {
        fail(QStringLiteral("Видео недоступно в этом режиме."));
    } else {
        fail(QStringLiteral("Проигрыватель Windows недоступен."), created);
    }

    restyle();
    show();
    raise();
    setFocus(Qt::OtherFocusReason);
    if (m_backend->player && m_error.isEmpty()) {
        applyItemInfo();
        m_ready = true;
        applyCommands();
    }
}

VideoViewer::~VideoViewer()
{
    shutdown();
}

void VideoViewer::mediaEvent(int type, long result, IMFPMediaItem *item)
{
    switch (MFP_EVENT_TYPE(type)) {
    case MFP_EVENT_TYPE_MEDIAITEM_CREATED:
        if (SUCCEEDED(result) && item && m_backend->player && !m_ready) {
            if (SUCCEEDED(m_backend->player->SetMediaItem(item))) {
                applyItemInfo();
                m_ready = true;
                applyCommands();
            } else
                fail(QStringLiteral("Не удалось подготовить видео к воспроизведению."));
        }
        return;
    case MFP_EVENT_TYPE_PLAY:
    case MFP_EVENT_TYPE_PAUSE:
        m_playing = m_backend->player && [&] {
            MFP_MEDIAPLAYER_STATE state = MFP_MEDIAPLAYER_STATE_EMPTY;
            return SUCCEEDED(m_backend->player->GetState(&state)) && state == MFP_MEDIAPLAYER_STATE_PLAYING;
        }();
        m_seekPending = false;
        m_clock->stop();
        if (m_playing)
            m_clock->start();
        refreshControls();
        updateVideoWindow();
        return;
    case MFP_EVENT_TYPE_POSITION_SET:
        m_seekPending = false;
        return;
    case MFP_EVENT_TYPE_PLAYBACK_ENDED:
        m_ended = true;
        m_playing = false;
        m_wantPlaying = false;
        m_clock->stop();
        if (m_durationMs > 0)
            m_positionMs = m_durationMs;
        m_elapsed->setText(clockText(m_positionMs));
        {
            const QSignalBlocker blocker(m_seekBar);
            m_seekBar->setValue(int(m_positionMs / 1000));
        }
        refreshControls();
        if (m_backend->player)
            m_backend->player->UpdateVideo();
        return;
    case MFP_EVENT_TYPE_ERROR:
        if (!m_error.isEmpty())
            return;
        fail(QStringLiteral("Во время воспроизведения произошла ошибка."), result);
        return;
    default:
        return;
    }
}

void VideoViewer::applyItemInfo()
{
    IMFPMediaPlayer *player = m_backend->player.Get();
    if (!player)
        return;
    qint64 ms = 0;
    if (SUCCEEDED(readTime(player, true, &ms)) && ms > 0) {
        m_durationMs = ms;
        m_seekBar->setRange(0, int(ms / 1000));
        m_total->setText(clockText(ms));
    }
    ComPtr<IMFPMediaItem> item;
    if (SUCCEEDED(player->GetMediaItem(&item)) && item) {
        MFP_MEDIAITEM_CHARACTERISTICS flags = 0;
        if (SUCCEEDED(item->GetCharacteristics(&flags)))
            m_canSeek = (flags & MFP_MEDIAITEM_CAN_SEEK) != 0;
    }
    // The video window exists only once an item is set: this is where it first gets its place.
    HWND video = nullptr;
    if (!m_videoWindow && SUCCEEDED(player->GetVideoWindow(&video)))
        m_videoWindow = video;
    hookVideoWindow();
    updateVideoWindow();
}

void VideoViewer::applyCommands()
{
    if (!m_ready || !m_backend->player)
        return;
    if (m_pendingSeekMs >= 0) {
        const qint64 ms = m_pendingSeekMs;
        m_pendingSeekMs = -1;
        seek(ms);
        return;
    }
    if (m_wantPlaying != m_playing && !m_seekPending)
        (m_wantPlaying ? m_backend->player->Play() : m_backend->player->Pause());
}

void VideoViewer::tick()
{
    if (!m_playing || !m_backend->player || m_seekPending)
        return;
    qint64 ms = 0;
    if (FAILED(readTime(m_backend->player.Get(), false, &ms)))
        return;
    m_positionMs = ms;
    m_elapsed->setText(clockText(ms));
    if (!m_seekBar->isSliderDown() && m_durationMs > 0) {
        const QSignalBlocker blocker(m_seekBar);
        m_seekBar->setValue(int(ms / 1000));
    }
}

void VideoViewer::toggle()
{
    if (!m_error.isEmpty()) {
        close();
        return;
    }
    if (m_ended) {
        m_ended = false;
        m_positionMs = 0;
        m_wantPlaying = true;
        if (m_ready)
            seek(0);
        else
            m_pendingSeekMs = 0;
        applyCommands();
        return;
    }
    m_wantPlaying = !m_wantPlaying;
    applyCommands();
    refreshControls();
}

void VideoViewer::seek(qint64 ms)
{
    const qint64 limit = m_durationMs > 0 ? m_durationMs : ms;
    m_positionMs = qBound(qint64(0), ms, limit);
    m_ended = false;
    m_elapsed->setText(clockText(m_positionMs));
    {
        const QSignalBlocker blocker(m_seekBar);
        m_seekBar->setValue(int(m_positionMs / 1000));
    }
    if (!m_ready || !m_backend->player) {
        m_pendingSeekMs = m_positionMs;
        return;
    }
    if (!m_canSeek)
        return;
    PROPVARIANT value{};
    value.vt = VT_I8;
    value.hVal.QuadPart = LONGLONG(m_positionMs * 10000);
    if (SUCCEEDED(m_backend->player->SetPosition(MFP_POSITIONTYPE_100NS, &value)))
        m_seekPending = true;
    PropVariantClear(&value);
    if (!m_playing && m_backend->player)
        m_backend->player->UpdateVideo();
}

void VideoViewer::cycleRate()
{
    m_rateIndex = (m_rateIndex + 1) % int(std::size(kRates));
    if (m_backend->player)
        m_backend->player->SetRate(kRates[m_rateIndex]);
    refreshControls();
}

void VideoViewer::toggleMute()
{
    m_muted = !m_muted;
    if (m_backend->player)
        m_backend->player->SetMute(m_muted ? TRUE : FALSE);
    refreshControls();
}

void VideoViewer::toggleExpanded()
{
    m_expanded = !m_expanded;
    place();
    refreshControls();
}

void VideoViewer::fail(const QString &message, long result)
{
    m_error = result ? QStringLiteral("%1 %2").arg(message, failureDetail(result)) : message;
    m_ready = false;
    m_playing = false;
    m_wantPlaying = false;
    m_clock->stop();
    m_note->setText(m_error);
    m_note->show();
    refreshControls();
    updateVideoWindow();
    update();
}

void VideoViewer::refreshControls()
{
    m_play->setIcon(m_playing ? QStringLiteral("pause") : QStringLiteral("play"));
    m_play->setLabel(m_playing ? QStringLiteral("Пауза") : QStringLiteral("Воспроизвести"));
    m_play->setAccessibleName(m_playing ? QStringLiteral("Пауза") : QStringLiteral("Воспроизвести"));
    m_mute->setIcon(m_muted ? QStringLiteral("speaker-off") : QStringLiteral("speaker"));
    m_mute->setLabel(m_muted ? QStringLiteral("Включить звук") : QStringLiteral("Выключить звук"));
    m_mute->setAccessibleName(m_muted ? QStringLiteral("Включить звук") : QStringLiteral("Выключить звук"));
    m_expand->setIcon(m_expanded ? QStringLiteral("collapse") : QStringLiteral("expand"));
    m_expand->setLabel(m_expanded ? QStringLiteral("Свернуть панель") : QStringLiteral("Развернуть на окно"));
    m_expand->setAccessibleName(m_expanded ? QStringLiteral("Свернуть панель") : QStringLiteral("Развернуть на окно"));
    m_rate->setText(rateText(kRates[m_rateIndex]));
    m_close->setAccessibleName(QStringLiteral("Закрыть видео"));
}

void VideoViewer::restyle()
{
    m_note->setAutoFillBackground(true);
    QPalette note = m_note->palette();
    note.setColor(QPalette::Window, QColor(11, 18, 32));
    note.setColor(QPalette::WindowText, Theme::p().text);
    m_note->setPalette(note);
    place();
}

QRect VideoViewer::panelRect() const
{
    const QRect avail = parentWidget() ? parentWidget()->rect() : rect();
    if (m_expanded)
        return avail.adjusted(24, 24, -24, -24);
    // A panel, not a takeover: the chat stays visible around the picture.
    int w = qMin(avail.width() - 96, 920);
    int h = qMin(avail.height() - 96, w * 9 / 16 + kBarHeight + 40);
    w = qMax(w, qMin(480, avail.width() - 32));
    h = qMax(h, qMin(320, avail.height() - 32));
    w = qMin(w, avail.width() - 32);
    h = qMin(h, avail.height() - 32);
    return QRect(avail.x() + (avail.width() - w) / 2, avail.y() + (avail.height() - h) / 2, qMax(1, w), qMax(1, h));
}

void VideoViewer::place()
{
    const QRect panel = panelRect();
    m_panel->setGeometry(panel);
    // Everything inside the panel is clamped to it: a control row can never land off-window.
    m_bar->setGeometry(12, qMax(0, panel.height() - kBarHeight - 12), qMax(1, panel.width() - 24), kBarHeight);
    m_note->setGeometry(videoRect().adjusted(24, 0, -24, 0));
    updateVideoWindow();
}

QRect VideoViewer::videoRect() const
{
    const QSize panel = m_panel->size();
    return QRect(16, 16, qMax(1, panel.width() - 32), qMax(1, panel.height() - kBarHeight - 44));
}

// MFPlay owns the video window: a native child of the panel, moved only in panel coordinates.
void VideoViewer::updateVideoWindow()
{
    auto *window = reinterpret_cast<HWND>(m_videoWindow);
    if (!window || !m_backend || !m_backend->player)
        return;
    if (window != reinterpret_cast<HWND>(m_overlay)) {
        const QRect area = videoRect();
        // With an error there is no picture: the window steps aside for the explanation.
        const bool visible = m_error.isEmpty();
        SetWindowPos(window, nullptr, area.x(), area.y(), area.width(), area.height(),
                     visible ? SWP_SHOWWINDOW : SWP_HIDEWINDOW);
    }
    m_backend->player->UpdateVideo();
}

void VideoViewer::hookVideoWindow()
{
    auto *hwnd = reinterpret_cast<HWND>(m_videoWindow);
    if (!hwnd || m_originalProc || !IsWindow(hwnd))
        return;
    SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));
    m_originalProc = reinterpret_cast<void *>(SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(videoWindowProc)));
}

void VideoViewer::unhookVideoWindow()
{
    auto *hwnd = reinterpret_cast<HWND>(m_videoWindow);
    if (hwnd && m_originalProc && IsWindow(hwnd)) {
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(m_originalProc));
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
    }
    m_originalProc = nullptr;
}

bool VideoViewer::nativeInput(unsigned message, unsigned key, const QPoint &global)
{
    switch (message) {
    case WM_KEYDOWN: {
        // Windows virtual keys are not Qt keys; only the ones the player answers are mapped.
        const int qt = key == VK_ESCAPE ? Qt::Key_Escape : key == VK_SPACE ? Qt::Key_Space
            : key == VK_LEFT ? Qt::Key_Left : key == VK_RIGHT ? Qt::Key_Right : key == 'M' ? Qt::Key_M : 0;
        if (!qt)
            return false;
        QKeyEvent event(QEvent::KeyPress, qt, Qt::NoModifier);
        return handleKey(&event);
    }
    case WM_LBUTTONUP:
        toggle();
        return true;
    case WM_RBUTTONUP:
        pictureMenu(global);
        return true;
    default:
        return false;
    }
}

void VideoViewer::pictureMenu(const QPoint &global)
{
    auto *menu = new PopupMenu(this);
    menu->addAction(m_playing ? QStringLiteral("Пауза") : QStringLiteral("Воспроизвести"), this, [this] { toggle(); });
    for (float rate : kRates)
        menu->addChoice(QStringLiteral("Скорость ") + rateText(rate), qFuzzyCompare(rate, kRates[m_rateIndex]), this, [this, rate] {
            m_rateIndex = int(std::find(std::begin(kRates), std::end(kRates), rate) - std::begin(kRates));
            if (m_backend->player)
                m_backend->player->SetRate(rate);
            refreshControls();
        });
    menu->addChoice(QStringLiteral("Без звука"), m_muted, this, [this] { toggleMute(); });
    menu->addChoice(QStringLiteral("На всё окно"), m_expanded, this, [this] { toggleExpanded(); });
    menu->addSeparator();
    menu->addAction(QStringLiteral("Закрыть"), this, [this] { close(); });
    menu->adjustSize();
    const QRect screen = QGuiApplication::screenAt(global)->availableGeometry();
    const QPoint at(qBound(screen.left(), global.x(), screen.right() - menu->width()),
                    qBound(screen.top(), global.y(), screen.bottom() - menu->height()));
    menu->popup(at);
}

bool VideoViewer::eventFilter(QObject *object, QEvent *event)
{
    if (object == parent() && event->type() == QEvent::Resize) {
        setGeometry(parentWidget()->rect());
        place();
    }
    return false;
}

bool VideoViewer::focusNextPrevChild(bool next)
{
    // The overlay keeps its own Tab cycle: the chat under it is unreachable while a video plays.
    if (next && focusWidget() == m_close) {
        m_play->setFocus(Qt::TabFocusReason);
        return true;
    }
    if (!next && focusWidget() == m_play) {
        m_close->setFocus(Qt::BacktabFocusReason);
        return true;
    }
    return QWidget::focusNextPrevChild(next);
}

void VideoViewer::paintEvent(QPaintEvent *)
{
    // A scrim, not a blackout: the messenger stays visible under the picture.
    QPainter p(this);
    p.fillRect(rect(), QColor(4, 9, 20, 150));
}

void VideoViewer::mousePressEvent(QMouseEvent *e)
{
    // A press on the scrim outside the panel closes; the panel surround toggles playback.
    if (e->button() != Qt::LeftButton)
        return;
    if (!m_panel->geometry().contains(e->pos()))
        close();
    else if (!m_bar->geometry().contains(m_panel->mapFrom(this, e->pos())) && !m_note->isVisible())
        toggle();
}

void VideoViewer::keyPressEvent(QKeyEvent *event)
{
    if (handleKey(event))
        return;
    QWidget::keyPressEvent(event);
}

bool VideoViewer::handleKey(QKeyEvent *event)
{
    switch (event->key()) {
    case Qt::Key_Escape:
        close();
        return true;
    case Qt::Key_Space:
        toggle();
        return true;
    case Qt::Key_M:
        toggleMute();
        return true;
    case Qt::Key_Left:
        if (focusWidget() == m_seekBar)
            return false;
        seek(m_positionMs - 5000);
        return true;
    case Qt::Key_Right:
        if (focusWidget() == m_seekBar)
            return false;
        seek(m_positionMs + 5000);
        return true;
    default:
        return false;
    }
}

void VideoViewer::closeEvent(QCloseEvent *event)
{
    const QPointer<QWidget> opener = m_previousFocus;
    shutdown();
    QWidget::closeEvent(event);
    if (opener && opener->isVisible())
        opener->setFocus(Qt::ActiveWindowFocusReason);
}

void VideoViewer::shutdown()
{
    if (!m_backend || !m_backend->player)
        return;
    m_clock->stop();
    m_playing = false;
    m_ready = false;
    unhookVideoWindow();
    {
        std::lock_guard<std::mutex> lock(m_backend->relay->mutex);
        m_backend->relay->target = nullptr;
    }
    m_backend->player->Shutdown();
    m_videoWindow = nullptr;
    m_backend->player.Reset();
    m_backend->callback.Reset();
}