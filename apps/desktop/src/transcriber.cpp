#include "transcriber.h"

#include "core.h"
#include "sheet.h"
#include "speech.h"
#include "widgets.h"

#include <QBoxLayout>
#include <QPointer>
#include <thread>

Transcriber *Transcriber::instance()
{
    static Transcriber *t = new Transcriber;
    return t;
}

Transcriber::Transcriber()
{
    connect(Core::instance(), &Core::event, this, [this](const QJsonObject &ev) {
        if (ev.value("type").toString() != "stt_progress" || m_downloadFor.isEmpty())
            return;
        const double total = ev.value("total").toDouble();
        if (total <= 0)
            return;
        Status &s = m_status[m_downloadFor];
        s.percent = int(ev.value("done").toDouble() * 100 / total);
        emit changed();
    });
}

void Transcriber::fail(const QString &messageId, const QString &reason)
{
    m_status.remove(messageId);
    if (m_downloadFor == messageId)
        m_downloadFor.clear();
    emit changed();
    emit notice(reason);
}

void Transcriber::request(const QJsonObject &msg, QWidget *host)
{
    const QString id = msg.value("id").toString();
    if (id.isEmpty() || m_status.contains(id))
        return;
    if (!speech::available()) {
        emit notice(QStringLiteral("В этой сборке нет распознавания речи"));
        return;
    }
    Core::instance()->call("stt_model", {}, this, [this, id, host = QPointer<QWidget>(host)](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return emit notice(e.message);
        const QJsonObject model = v.toObject();
        if (model.value("ready").toBool()) {
            m_status[id] = {Phase::Working, 0};
            emit changed();
            return recognize(id, model.value("dir").toString());
        }
        if (model.value("downloading").toBool())
            return emit notice(QStringLiteral("Модель уже скачивается. Попробуйте чуть позже"));
        if (!host)
            return;
        const qint64 size = qint64(model.value("size").toDouble());
        auto *sheet = new Sheet(host->window(), QStringLiteral("Расшифровка голоса"), 440);
        auto *text = new ui::Label(QStringLiteral("Речь распознаётся на вашем компьютере: звук никуда не отправляется. Один раз нужно скачать модель — %1 МБ.").arg(qMax<qint64>(1, size >> 20)), 14);
        text->setWordWrap(true);
        sheet->body()->addWidget(text);
        auto *go = new ui::Button(QStringLiteral("Скачать и расшифровать"));
        sheet->body()->addWidget(go);
        connect(go, &QAbstractButton::clicked, sheet, [this, sheet, id] {
            sheet->close();
            downloadModel(id);
        });
        sheet->open();
    });
}

void Transcriber::downloadModel(const QString &messageId)
{
    if (!m_downloadFor.isEmpty())
        return emit notice(QStringLiteral("Модель уже скачивается. Попробуйте чуть позже"));
    m_downloadFor = messageId;
    m_status[messageId] = {Phase::Model, 0};
    emit changed();
    Core::instance()->call("stt_model_download", {}, this, [this, messageId](const QJsonValue &v, const CoreError &e) {
        m_downloadFor.clear();
        if (!e.ok())
            return fail(messageId, e.message);
        m_status[messageId] = {Phase::Working, 0};
        emit changed();
        recognize(messageId, v.toObject().value("dir").toString());
    });
}

void Transcriber::recognize(const QString &messageId, const QString &modelDir)
{
    Core::instance()->call("download_file", {{"message_id", messageId}}, this, [this, messageId, modelDir](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return fail(messageId, e.message);
        const QString path = v.toObject().value("path").toString();
        const QPointer<Transcriber> self(this);
        std::thread([self, messageId, modelDir, path] {
            const speech::Result r = speech::transcribe(path, modelDir);
            if (!self)
                return;
            QMetaObject::invokeMethod(
                self,
                [self, messageId, r] {
                    if (!self)
                        return;
                    if (!r.ok)
                        return self->fail(messageId, r.error);
                    Core::instance()->call("set_transcript", {{"message_id", messageId}, {"text", r.text}}, self, [self, messageId](const QJsonValue &, const CoreError &e) {
                        if (!e.ok())
                            return self->fail(messageId, e.message);
                        self->m_status.remove(messageId);
                        emit self->changed();
                    });
                },
                Qt::QueuedConnection);
        }).detach();
    });
}

void Transcriber::clear(const QString &messageId)
{
    Core::instance()->call("clear_transcript", {{"message_id", messageId}});
}
