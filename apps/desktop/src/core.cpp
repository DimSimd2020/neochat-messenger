#include "core.h"

#include <QJsonDocument>
#include <QMetaObject>

extern "C" {
#include "neocore.h"
}

namespace {

void onEvent(void *ctx, const char *json)
{
    auto *self = static_cast<Core *>(ctx);
    const QJsonObject ev = QJsonDocument::fromJson(QByteArray(json)).object();
    QMetaObject::invokeMethod(self, [self, ev] { emit self->event(ev); }, Qt::QueuedConnection);
}

QByteArray toJson(const QJsonObject &o)
{
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

} // namespace

Core *Core::instance()
{
    static Core *core = new Core;
    return core;
}

Core::Core()
{
    m_pool.setMaxThreadCount(4);
}

Core::~Core()
{
    close();
}

bool Core::open(const QString &dataDir, const QString &dbKeyHex, QString *error)
{
    QJsonObject cfg{{"data_dir", dataDir}, {"db_key", dbKeyHex}, {"foreground", true}};
    char *err = nullptr;
    m_core = nc_open(toJson(cfg).constData(), &onEvent, this, &err);
    if (!m_core) {
        if (error)
            *error = err ? QJsonDocument::fromJson(err).object().value("message").toString() : QStringLiteral("Не удалось открыть хранилище");
        if (err)
            nc_free(err);
        return false;
    }
    return true;
}

void Core::close()
{
    if (!m_core)
        return;
    m_pool.waitForDone();
    nc_close(m_core);
    m_core = nullptr;
}

void Core::call(const QString &method, const QJsonObject &params, QObject *ctx, Callback cb)
{
    QPointer<QObject> guard(ctx);
    const bool hasCtx = ctx != nullptr;
    void *core = m_core;
    m_pool.start([=] {
        QJsonValue result;
        CoreError error;
        char *out = nc_call(core, method.toUtf8().constData(), toJson(params).constData());
        const QJsonObject res = QJsonDocument::fromJson(QByteArray(out)).object();
        nc_free(out);
        if (res.contains("error")) {
            const QJsonObject e = res.value("error").toObject();
            error = {e.value("code").toString("unknown"), e.value("message").toString()};
        } else {
            result = res.value("ok");
        }
        if (!cb)
            return;
        QObject *target = hasCtx ? guard.data() : Core::instance();
        if (!target)
            return;
        QMetaObject::invokeMethod(target, [guard, hasCtx, cb, result, error] {
            if (hasCtx && !guard)
                return;
            cb(result, error);
        }, Qt::QueuedConnection);
    });
}
