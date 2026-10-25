#include "circlesheet.h"

#include "circle.h"
#include "sheet.h"
#include "theme.h"
#include "widgets.h"

#include <QBoxLayout>
#include <QPainter>
#include <QPainterPath>
#include <QPointer>
#include <cmath>

namespace {

// The round picture of the camera; while recording, a red ring runs around it.
class Preview : public QWidget {
public:
    static constexpr int kSide = 280;
    explicit Preview(QWidget *parent) : QWidget(parent)
    {
        setFixedSize(kSide + 20, kSide + 20);
        setAccessibleName(QStringLiteral("Изображение с камеры"));
    }
    void setFrame(const QImage &f)
    {
        m_frame = f;
        update();
    }
    void setProgress(qreal p)
    {
        m_progress = p;
        update();
    }
    void setStatus(const QString &s)
    {
        m_status = s;
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        const QRectF r((width() - kSide) / 2.0, (height() - kSide) / 2.0, kSide, kSide);
        QPainterPath clip;
        clip.addEllipse(r);
        p.save();
        p.setClipPath(clip);
        p.fillRect(r, c.selected);
        if (!m_frame.isNull())
            p.drawImage(r, m_frame);
        p.restore();
        if (m_frame.isNull() && !m_status.isEmpty()) {
            p.setPen(c.muted);
            p.setFont(Theme::instance()->font(14));
            p.drawText(r.adjusted(24, 0, -24, 0), Qt::AlignCenter | Qt::TextWordWrap, m_status);
        }
        p.setBrush(Qt::NoBrush);
        p.setPen(QPen(c.border, 2));
        p.drawEllipse(r.adjusted(-5, -5, 5, 5));
        if (m_progress > 0) {
            p.setPen(QPen(QColor(224, 64, 64), 4, Qt::SolidLine, Qt::RoundCap));
            p.drawArc(r.adjusted(-5, -5, 5, 5), 90 * 16, int(-m_progress * 360 * 16));
        }
    }

private:
    QImage m_frame;
    QString m_status = QStringLiteral("Включаем камеру…");
    qreal m_progress = 0;
};

QString clock(qint64 ms)
{
    const qint64 s = ms / 1000;
    return QStringLiteral("%1:%2").arg(s / 60).arg(s % 60, 2, 10, QChar('0'));
}

}

namespace circlesheet {

void open(QWidget *host, std::function<void(const QString &, qint64, const QImage &)> send)
{
    auto *sheet = new Sheet(host->window(), QStringLiteral("Кружок"), 360);
    auto *preview = new Preview(sheet);
    auto *camera = new CameraRecorder(sheet);
    auto *time = new ui::Label(QStringLiteral("0:00 / 1:00"), 14, 400, ui::Label::Muted);
    time->setAlignment(Qt::AlignCenter);
    auto *cancel = new ui::Button(QStringLiteral("Отмена"), ui::Button::Secondary);
    auto *record = new ui::Button(QStringLiteral("Записать"));
    auto *row = new QHBoxLayout;
    row->setSpacing(10);
    row->addWidget(cancel);
    row->addWidget(record, 1);
    sheet->body()->addWidget(preview, 0, Qt::AlignHCenter);
    sheet->body()->addWidget(time);
    sheet->body()->addLayout(row);

    QString why;
    const bool opened = camera->open(&why);
    if (!opened) {
        preview->setStatus(why);
        record->setEnabled(false);
    }
    QObject::connect(camera, &CameraRecorder::frame, preview, &Preview::setFrame);
    QObject::connect(camera, &CameraRecorder::failed, sheet, [=](const QString &reason) {
        record->setEnabled(false);
        preview->setFrame({});
        preview->setStatus(reason);
    });
    auto *tick = new QTimer(sheet);
    tick->setInterval(60);
    QObject::connect(tick, &QTimer::timeout, sheet, [=] {
        const qint64 ms = camera->elapsedMs();
        preview->setProgress(qreal(ms) / CameraRecorder::kMaxMs);
        time->setText(clock(ms) + QStringLiteral(" / 1:00"));
    });
    QObject::connect(record, &QAbstractButton::clicked, sheet, [=] {
        if (!camera->recording()) {
            QString err;
            if (!camera->startRecording(&err)) {
                preview->setStatus(err);
                return;
            }
            record->setText(QStringLiteral("Отправить"));
            tick->start();
            return;
        }
        // finished() sends the file and closes the sheet
        camera->stopRecording();
    });
    QObject::connect(camera, &CameraRecorder::finished, sheet, [=](const QString &path, qint64 ms, const QImage &poster) {
        tick->stop();
        send(path, ms, poster);
        sheet->close();
    });
    QObject::connect(cancel, &QAbstractButton::clicked, sheet, [=] {
        camera->cancel();
        sheet->close();
    });
    // Closing in any way before the file is finished throws the recording away.
    QObject::connect(sheet, &Sheet::closed, camera, &CameraRecorder::cancel);
    sheet->open();
    record->setFocus(Qt::OtherFocusReason);
}

}
