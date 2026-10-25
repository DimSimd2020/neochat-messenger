// Renders voice and circle bubbles (with and without a transcript) into PNG files.
// Usage: neochat_bubbletest <out dir>
#include "messages.h"
#include "style.h"
#include "theme.h"

#include <QApplication>
#include <QBuffer>
#include <QDateTime>
#include <QDir>
#include <QImage>
#include <QJsonArray>
#include <QListView>

static QString thumb()
{
    QImage img(120, 120, QImage::Format_RGB32);
    img.fill(QColor(70, 120, 180));
    QByteArray jpeg;
    QBuffer buf(&jpeg);
    buf.open(QIODevice::WriteOnly);
    img.save(&buf, "JPEG");
    return QString::fromLatin1(jpeg.toBase64());
}

static QJsonObject message(int n, bool out, QJsonObject file)
{
    return {{"id", QString("m%1").arg(n)}, {"local_id", n}, {"kind", "file"}, {"outgoing", out}, {"status", out ? "read" : "delivered"},
            {"sent_at", double(QDateTime::currentMSecsSinceEpoch() - (10 - n) * 60000)}, {"sender_name", "Анна"}, {"sender_user_id", "u1"}, {"text", ""}, {"file", file}};
}

int main(int argc, char **argv)
{
    QApplication app(argc, argv);
    if (argc < 2)
        return 2;
    Q_INIT_RESOURCE(resources);
    QApplication::setStyle(createStyle());
    QApplication::setFont(Theme::instance()->font(14));
    Theme::instance()->init();
    QByteArray wave;
    for (int i = 0; i < 64; ++i)
        wave.append(char(30 + (i * 37) % 200));
    const QString w = QString::fromLatin1(wave.toBase64());
    MessageModel model;
    model.merge({message(1, false, {{"name", "voice.m4a"}, {"mime", "audio/mp4"}, {"size", 90000}, {"voice", true}, {"wave", w}, {"duration", 7300}}),
                 message(2, true, {{"name", "voice.m4a"}, {"mime", "audio/mp4"}, {"size", 90000}, {"voice", true}, {"wave", w}, {"duration", 41000},
                                   {"transcript", "Привет! Я задержусь минут на десять, начинайте без меня, я скоро подключусь к созвону."}}),
                 message(3, false, {{"name", "circle.mp4"}, {"mime", "video/mp4"}, {"size", 900000}, {"circle", true}, {"duration", 12000}, {"thumb", thumb()}, {"width", 480}, {"height", 480}}),
                 message(4, true, {{"name", "circle.mp4"}, {"mime", "video/mp4"}, {"size", 900000}, {"circle", true}, {"duration", 5000}, {"thumb", thumb()}, {"transcript", ""}}),
                 message(5, false, {{"name", "voice.m4a"}, {"mime", "audio/mp4"}, {"size", 90000}, {"voice", true}, {"wave", w}, {"duration", 125000}, {"transcript", ""}})},
                false);
    for (bool dark : {true, false}) {
        Theme::instance()->setMode(dark ? Theme::Dark : Theme::Light);
        QListView view;
        view.setModel(&model);
        view.setItemDelegate(new MessageDelegate(&view));
        view.setFrameShape(QFrame::NoFrame);
        view.resize(640, 820);
        view.setStyleSheet(QString("QListView { background: %1; }").arg(Theme::p().canvas.name()));
        view.show();
        QApplication::processEvents();
        view.grab().save(QDir(argv[1]).filePath(dark ? "bubbles-dark.png" : "bubbles-light.png"));
    }
    return 0;
}
