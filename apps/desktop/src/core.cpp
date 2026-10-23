#include "core.h"

#include <QJsonDocument>
#include <QMetaObject>
#include <QSysInfo>

extern "C" {
#include "neocore.h"
}

namespace {

void onEvent(void *ctx, const char *json)
{
    auto *self = static_cast<Core *>(ctx);
    const QJsonObject ev = QJsonDocument::fromJson(QByteArray(json)).object();
    QMetaObject::invokeMethod(self, [self, ev] { self->receiveEvent(ev); }, Qt::QueuedConnection);
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
    const QByteArray machine = QSysInfo::machineUniqueId();
    if (!machine.isEmpty())
        cfg.insert("installation_id", QString::fromLatin1("windows:" + machine + ":" + qgetenv("USERDOMAIN") + ":" + qgetenv("USERNAME")));
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
    const QString account = params.value("_account_id").toString(m_accountId);
    const quint64 generation = m_accountGeneration;
    QJsonObject bound = params;
    if (!account.isEmpty())
        bound.insert("_account_id", account);
    QPointer<QObject> guard(ctx);
    const bool hasCtx = ctx != nullptr;
    void *core = m_core;
    m_pool.start([=] {
        QJsonValue result;
        CoreError error;
        char *out = nc_call(core, method.toUtf8().constData(), toJson(bound).constData());
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
        QMetaObject::invokeMethod(target, [guard, hasCtx, cb, result, error, account, generation, method] {
            if (hasCtx && !guard)
                return;
            // File callbacks always remove their temporary source; callers guard UI work.
            if (method != "send_file" && !account.isEmpty() && (account != Core::instance()->accountId() || generation != Core::instance()->m_accountGeneration))
                return;
            cb(result, error);
        }, Qt::QueuedConnection);
    });
}

void Core::receiveEvent(const QJsonObject &ev)
{
    QJsonObject delivered = ev;
    const QString id = ev.value("account_id").toString();
    if (ev.value("type") == "account_selected") {
        const bool changed = !m_accountId.isEmpty() && m_accountId != id;
        delivered.insert("previous_account_id", m_accountId);
        m_accountId = id;
        if (changed) ++m_accountGeneration;
        if (!changed)
            return;
    } else if (!id.isEmpty() && id != m_accountId && ev.value("type") != "account_notify") {
        return;
    }
    emit event(delivered);
}
