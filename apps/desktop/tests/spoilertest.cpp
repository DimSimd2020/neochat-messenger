// Hidden media and the farewell of a deleted message, rendered and checked offscreen.
// Usage: neochat_spoilertest <out dir>
// Saves the states of a hidden photo and of hidden text (dark and light) and checks the cover
// motion, the collapse of a deleted row, the masking of hidden words and the blurred poster.
#include "messages.h"
#include "spoiler.h"
#include "style.h"
#include "theme.h"
#include "videoplayer.h"
#include "popupmenu.h"
#include <QAbstractButton>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QScreen>

#include <QApplication>
#include <cstdio>
#include <QBuffer>
#include <QDateTime>
#include <QDir>
#include <QImage>
#include <QPainter>
#include <QJsonArray>
#include <QThread>

namespace {

int failures = 0;

void check(bool ok, const char *what)
{
    fprintf(stderr, "%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++failures;
}

QString thumb()
{
    // Detail the defocus has something to eat: quadrants, rings and stripes.
    QImage img(160, 120, QImage::Format_RGB32);
    for (int y = 0; y < img.height(); ++y)
        for (int x = 0; x < img.width(); ++x) {
            const bool quad = (x / 40 + y / 30) % 2 == 0;
            QColor c = quad ? QColor(210, 60, 70) : QColor(40, 120, 210);
            if ((x - 80) * (x - 80) + (y - 60) * (y - 60) < 900)
                c = QColor(250, 210, 60);
            if (y % 12 < 3)
                c = QColor(20, 20, 24);
            img.setPixelColor(x, y, c);
        }
    QByteArray jpeg;
    QBuffer buf(&jpeg);
    buf.open(QIODevice::WriteOnly);
    img.save(&buf, "JPEG");
    return QString::fromLatin1(jpeg.toBase64());
}

QJsonObject photo(int n, bool closed)
{
    const QJsonObject file{{"name", "photo.jpg"}, {"mime", "image/jpeg"}, {"size", 90000}, {"image", true}, {"thumb", thumb()},
                           {"width", 160}, {"height", 120}, {"hidden", true}, {"spoiler", closed}, {"once", false}, {"viewed", false}};
    QJsonObject m{{"id", QString("p%1").arg(n)}, {"local_id", n}, {"kind", "file"}, {"outgoing", false}, {"status", "delivered"},
                  {"sent_at", double(QDateTime::currentMSecsSinceEpoch() - (20 - n) * 60000)}, {"sender_name", "Анна"}, {"sender_user_id", "u1"},
                  {"text", QString()}};
    m.insert("file", file);
    return m;
}

QJsonObject span(const QString &t, bool hidden = false)
{
    QJsonObject s{{"text", t}};
    if (hidden)
        s.insert("spoiler", true);
    return s;
}

QJsonObject text(int n, const QString &body, const QJsonArray &spans, bool out)
{
    return {{"id", QString("t%1").arg(n)}, {"local_id", n}, {"kind", "text"}, {"outgoing", out}, {"status", "read"},
            {"sent_at", double(QDateTime::currentMSecsSinceEpoch() - (20 - n) * 60000)}, {"sender_name", "Анна"}, {"sender_user_id", "u1"},
            {"text", body}, {"spans", spans}};
}

// "код ||1234|| дальше" as the core sends it.
QJsonObject secret(int n, bool out)
{
    QJsonArray spans;
    spans << span(QStringLiteral("код ")) << span(QStringLiteral("1234"), true) << span(QStringLiteral(" дальше"));
    return text(n, QStringLiteral("код 1234 дальше"), spans, out);
}

void wait(int ms)
{
    // Pump the loop while waiting: the palette glide and the cover tween both live on its ticks.
    QElapsedTimer t;
    t.start();
    while (t.elapsed() < ms) {
        QApplication::processEvents();
        QThread::msleep(8);
    }
    QApplication::processEvents();
}

int rowOf(const MessageModel &m, const QString &id)
{
    for (int i = 0; i < m.rowCount(); ++i)
        if (!m.item(i).isDate && m.item(i).msg.value("id").toString() == id)
            return i;
    return -1;
}

qreal rowHeight(const MessageView &view, const MessageModel &m, int row)
{
    QStyleOptionViewItem opt;
    opt.widget = &view;
    return static_cast<MessageDelegate *>(view.itemDelegate())->sizeHint(opt, m.index(row, 0)).height();
}

} // namespace

int main(int argc, char **argv)
{
    QApplication app(argc, argv);
    if (argc < 2)
        return 2;
    Q_INIT_RESOURCE(resources);
    QApplication::setStyle(createStyle());
    QApplication::setFont(Theme::instance()->font(14));
    Theme::instance()->init();
    const QDir out(argv[1]);

    // The cover tween: it leaves at once, spends the middle of its time in motion and settles.
    spoiler::Reveal r;
    r.start(1, 460);
    check(r.value(460) < 0.05, "cover starts closed");
    wait(200);
    const qreal mid = r.value(460);
    check(mid > 0.1 && mid < 0.95, "cover is in motion halfway");
    wait(320);
    check(qFuzzyCompare(r.value(460), 1.0), "cover settles open");
    r.start(0, 0);
    check(r.value(0) == 0.0, "reduced motion jumps");

    // Fixed-progress checks: the point touched changes first, the far corner stays unchanged.
    // Checking alpha catches a uniform fade even when a screenshot looks vaguely blurred.
    for (const QPointF origin : {QPointF(0.5, 0.5), QPointF(0.08, 0.12), QPointF(0.92, 0.88)}) {
        for (bool opening : {true, false}) {
            QImage layer(320, 240, QImage::Format_ARGB32_Premultiplied);
            layer.fill(Qt::white);
            QPainter painter(&layer);
            const qreal progress = opening ? 0.4 : 0.6;
            spoiler::maskCover(painter, QRectF(0, 0, 320, 240), origin, progress, opening);
            painter.end();
            const QPoint near(qRound(origin.x() * 319), qRound(origin.y() * 239));
            const QPoint far(origin.x() <= 0.5 ? 319 : 0, origin.y() <= 0.5 ? 239 : 0);
            check(qAlpha(layer.pixel(near)) == (opening ? 0 : 255), "radial wave changes the tap first");
            check(qAlpha(layer.pixel(far)) == (opening ? 255 : 0), "radial wave preserves the far corner until it arrives");
            const QPixmap original = ImageCache::instance()->thumb(photo(80, false));
            QImage picture(320, 240, QImage::Format_ARGB32_Premultiplied);
            QPainter frame(&picture);
            frame.drawPixmap(picture.rect(), original);
            QImage blur = ImageCache::instance()->blur(photo(80, true), 0).toImage().convertToFormat(QImage::Format_ARGB32_Premultiplied).scaled(320, 240, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
            QPainter maskPainter(&blur);
            maskPainter.setCompositionMode(QPainter::CompositionMode_DestinationIn);
            maskPainter.drawImage(0, 0, layer);
            maskPainter.end();
            frame.drawImage(0, 0, blur);
            frame.end();
            check(picture.save(out.filePath(QString("radial-%1-%2.png").arg(qRound(origin.x() * 100)).arg(opening ? "open" : "close"))), "radial screenshot saved");
        }
    }
    for (qreal endpoint : {0.0, 1.0}) {
        QImage layer(32, 24, QImage::Format_ARGB32_Premultiplied);
        layer.fill(Qt::white);
        QPainter painter(&layer);
        spoiler::maskCover(painter, QRectF(layer.rect()), QPointF(0, 0), endpoint, false);
        painter.end();
        check(qAlpha(layer.pixel(0, 0)) == (endpoint == 0 ? 255 : 0)
              && qAlpha(layer.pixel(31, 23)) == (endpoint == 0 ? 255 : 0), "cover endpoints have no residual blur or holes");
    }

    // Hidden words are blocks wherever the message is quoted, and never the words themselves.
    const QJsonObject s = secret(1, false);
    check(messageQuote(s) == QStringLiteral("код ███ дальше"), "quote masks hidden words");
    check(messageQuote(text(2, QStringLiteral("обычный"), QJsonArray{span(QStringLiteral("обычный"))}, false)) == QStringLiteral("обычный"),
          "plain text is quoted as it is");

    // Outside the chat a closed spoiler keeps an unreadable poster; an open one is sharp.
    const QPixmap closedPoster = ImageCache::instance()->poster(photo(3, true));
    const QPixmap openPoster = ImageCache::instance()->poster(photo(4, false));
    check(!closedPoster.isNull() && !openPoster.isNull(), "posters decode");
    check(closedPoster.toImage() != openPoster.toImage(), "closed poster is blurred");

    MessageModel model;
    model.merge({text(9, QStringLiteral("обычное сообщение"), QJsonArray{span(QStringLiteral("обычное сообщение"))}, false),
                 photo(10, true), photo(11, false), secret(12, false), secret(13, true)},
                false);

    MessageView view;
    view.setModel(&model);
    view.resize(660, 1000);
    view.show();
    QApplication::processEvents();

    check(view.revealOf(QStringLiteral("p10"), true) == 0.0, "hidden photo starts closed");
    check(view.revealOf(QStringLiteral("p11"), false) == 1.0, "opened photo stays open");
    check(view.textRevealOf(QStringLiteral("t12")) == 0.0, "hidden text starts closed");
    const int secretRow = rowOf(model, QStringLiteral("t12"));
    check(secretRow > 0, "hidden text is in the list");
    Theme::instance()->setMode(Theme::Dark);
    wait(600);
    view.toggleText(model.item(secretRow).msg);
    wait(200);
    const qreal textMid = view.textRevealOf(QStringLiteral("t12"));
    check(textMid > 0.1 && textMid < 0.95, "hidden text opens in motion");
    view.grab().save(out.filePath("spoiler-mid-dark.png"));
    wait(320);
    check(qFuzzyCompare(view.textRevealOf(QStringLiteral("t12")), 1.0), "hidden text settles open");
    view.toggleText(model.item(secretRow).msg);
    wait(520);
    check(view.textRevealOf(QStringLiteral("t12")) == 0.0, "second tap hides the text again");
    view.grab().save(out.filePath("spoiler-dark.png"));
    Theme::instance()->setMode(Theme::Light);
    wait(600);
    view.grab().save(out.filePath("spoiler-light.png"));
    view.toggleText(model.item(secretRow).msg);
    wait(200);
    view.grab().save(out.filePath("spoiler-mid-light.png"));
    view.toggleText(model.item(secretRow).msg);
    wait(520);
    Theme::instance()->setMode(Theme::Dark);
    wait(600);

    // A deleted message keeps its place, closes under itself and then leaves for good.
    const int goneRow = rowOf(model, QStringLiteral("p10"));
    const qreal full = rowHeight(view, model, goneRow);
    QList<QJsonObject> page;
    for (int i = 0; i < model.rowCount(); ++i)
        if (!model.item(i).isDate && model.item(i).msg.value("id").toString() != QStringLiteral("p10"))
            page << model.item(i).msg;
    model.merge(page, false);
    check(model.anyGone(), "deleted row is kept for its farewell");
    check(model.item(rowOf(model, QStringLiteral("p10"))).goneAt >= 0, "deleted row carries its time");
    // Measure mid-collapse: before 150 ms the row is still full, after 420 ms the live timer
    // has already taken it away, and a real window's grab costs enough to land past that.
    wait(250);
    const qreal collapsing = rowHeight(view, model, rowOf(model, QStringLiteral("p10")));
    check(collapsing > 0 && collapsing < full, "deleted row collapses");
    emit model.layoutChanged();
    wait(20);
    view.grab().save(out.filePath("spoiler-gone.png"));
    wait(400);
    model.finishGone();
    check(!model.anyGone() && rowOf(model, QStringLiteral("p10")) < 0, "deleted row is gone");

    // A view-once file carries no poster: its cover is the living dust, and it keeps the list awake.
    MessageModel onceModel;
    QJsonObject onceFile{{"name", "once.jpg"}, {"mime", "image/jpeg"}, {"size", 90000}, {"image", true}, {"once", true}, {"viewed", false}};
    QJsonObject onceMsg{{"id", "o1"}, {"local_id", 1}, {"kind", "file"}, {"outgoing", false}, {"status", "delivered"},
                        {"sent_at", double(QDateTime::currentMSecsSinceEpoch() - 60000)}, {"sender_name", "Анна"}, {"sender_user_id", "u1"}, {"text", QString()}};
    onceMsg.insert("file", onceFile);
    onceModel.merge({onceMsg}, false);
    MessageView onceView;
    onceView.setModel(&onceModel);
    onceView.resize(660, 500);
    onceView.show();
    QApplication::processEvents();
    check(onceView.liveCover(), "view-once cover keeps the dust alive");
    onceView.grab().save(out.filePath("once-cover.png"));

    // The player panel and its control row stay inside the window, never at negative coordinates.
    QWidget host;
    host.resize(800, 600);
    host.show();
    QApplication::processEvents();
    auto *player = new VideoViewer(&host, QStringLiteral("missing-video.mp4"), QStringLiteral("probe"));
    QApplication::processEvents();
    auto *panel = player->findChild<QWidget *>(QStringLiteral("videoPanel"));
    auto *bar = player->findChild<QWidget *>(QStringLiteral("videoControls"));
    check(panel && bar, "player panel and controls exist");
    if (panel && bar) {
        const QRect pr = panel->geometry(), br = bar->geometry();
        check(pr.x() >= 0 && pr.y() >= 0 && pr.right() <= host.width() && pr.bottom() <= host.height(), "panel stays inside the window");
        check(pr.width() < host.width(), "panel is not a full-window takeover by default");
        check(br.y() >= 0 && br.bottom() <= pr.height(), "control row stays inside the panel");
    }
    host.grab().save(out.filePath("player-panel.png"));
    player->close();
    QApplication::processEvents();

    // Exercise the same protected photo window used after a successful once-file download.
    host.resize(600, 440);
    host.move(70, 70);
    QImage onceImage(200, 140, QImage::Format_RGB32);
    onceImage.fill(QColor(230, 180, 35));
    QPointer<QWidget> oncePhoto = showViewOncePhoto(&host, onceImage, QStringLiteral("synthetic-once-test"));
    wait(100);
    check(oncePhoto->isWindow() && oncePhoto->isVisible(), "once photo opens in a separate visible window");
    const QPoint expected = host.mapToGlobal(host.rect().center());
    check((oncePhoto->geometry().center() - expected).manhattanLength() < 6, "once viewer centers on host in global coordinates");
    check(oncePhoto->screen()->availableGeometry().contains(oncePhoto->geometry()), "once viewer is inside its monitor");
    const QImage rendered = oncePhoto->grab().toImage();
    check(rendered.pixelColor(rendered.width() / 2, rendered.height() / 2).red() > 180, "once viewer actually paints the photo");
    QKeyEvent onceEscape(QEvent::KeyPress, Qt::Key_Escape, Qt::NoModifier);
    QApplication::sendEvent(oncePhoto, &onceEscape);
    QApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    check(oncePhoto.isNull(), "once viewer closes and destroys its image");

    if (argc > 2) {
        // Native Windows only: actual H.264/AAC, not the missing-file error path above.
        host.resize(800, 600);
        host.setAutoFillBackground(true);
        QPalette palette = host.palette();
        palette.setColor(QPalette::Window, QColor(90, 120, 150));
        host.setPalette(palette);
        host.move(140, 100);
        host.raise();
        host.activateWindow();
        auto *video = new VideoViewer(&host, QString::fromLocal8Bit(argv[2]), QStringLiteral("Тест видео"));
        QPointer<VideoViewer> alive(video);
        QElapsedTimer loading;
        loading.start();
        while (!video->ready() && video->errorString().isEmpty() && loading.elapsed() < 8000) wait(20);
        check(video->ready() && video->errorString().isEmpty(), "real MP4 becomes ready");
        if (!video->errorString().isEmpty()) fprintf(stderr, "player: %s\n", qPrintable(video->errorString()));
        wait(1000);
        check(video->playing() && video->position() > 300 && video->duration() >= 11000, "MFPlay clock advances on real video");
        auto *surface = video->findChild<QWidget *>(QStringLiteral("videoSurface"));
        auto *panel = video->findChild<QWidget *>(QStringLiteral("videoPanel"));
        auto *controls = video->findChild<QWidget *>(QStringLiteral("videoControls"));
        check(surface && panel && surface->winId() != panel->winId(), "video owns a separate native surface");
        check(surface && controls && !surface->geometry().intersects(controls->geometry()), "video cannot cover the control row");
        auto click = [&](const char *name) {
            if (auto *button = video->findChild<QAbstractButton *>(QLatin1String(name))) button->click();
            else check(false, name);
            wait(150);
        };
        click("videoMute");
        check(video->muted(), "MFPlay confirms mute");
        click("videoRate");
        check(qAbs(video->playbackRate() - 1.25f) < 0.01, "MFPlay confirms 1.25x rate");
        click("videoPlay");
        check(!video->playing(), "pause stops playback");
        video->seek(5000);
        wait(250);
        click("videoPlay");
        wait(500);
        check(video->position() >= 5000 && video->position() < 7500, "seek reaches the requested point and resumes");
        if (surface) {
            const QPointF local(surface->rect().center());
            const QPointF global(surface->mapToGlobal(local.toPoint()));
            QMouseEvent right(QEvent::MouseButtonRelease, local, global, Qt::RightButton, Qt::NoButton, Qt::NoModifier);
            QApplication::sendEvent(surface, &right);
            wait(350);
            auto *menu = QApplication::activePopupWidget();
            check(menu && menu->isVisible(), "right click on native video opens menu");
            if (menu) {
                check(menu->screen()->availableGeometry().contains(menu->geometry()), "menu stays on its screen");
                menu->close();
            }
        }
        wait(150);
        const QPoint screenOrigin = host.mapToGlobal(QPoint(0, 0));
        const QImage captured = host.screen()->grabWindow(0, screenOrigin.x(), screenOrigin.y(), host.width(), host.height()).toImage();
        check(captured.save(out.filePath("player-real.png")), "native video screenshot saved");
        int colourful = 0;
        for (int y = 0; y < captured.height(); y += 3)
            for (int x = 0; x < captured.width(); x += 3) {
                const QColor pixel = captured.pixelColor(x, y);
                if (pixel.saturation() > 180 && pixel.value() > 160) ++colourful;
            }
        check(colourful > 500, "desktop capture contains decoded colour test bars, not a black surface");
        Theme::instance()->setMode(Theme::Light);
        wait(650);
        check(host.screen()->grabWindow(0, screenOrigin.x(), screenOrigin.y(), host.width(), host.height()).save(out.filePath("player-real-light.png")), "light player screenshot saved");
        click("videoExpand");
        check(panel && panel->width() > host.width() - 60, "expand enlarges the panel");
        host.resize(620, 460);
        wait(200);
        check(panel && host.rect().contains(panel->geometry()), "resized panel remains in the window");
        QKeyEvent escape(QEvent::KeyPress, Qt::Key_Escape, Qt::NoModifier);
        QApplication::sendEvent(surface ? surface : video, &escape);
        QApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
        check(alive.isNull(), "Escape from video destroys the player");
    }

    fprintf(stderr, failures ? "SPOILERTEST FAILED: %d\n" : "SPOILERTEST PASSED\n", failures);
    return failures ? 1 : 0;
}