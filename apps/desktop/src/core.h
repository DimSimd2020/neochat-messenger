#pragma once

#include <QJsonObject>
#include <QJsonValue>
#include <QObject>
#include <QPointer>
#include <QThreadPool>
#include <functional>

// Error returned by the core: {"code": ..., "message": ...}. Messages are user-facing.
struct CoreError {
    QString code;
    QString message;
    bool ok() const { return code.isEmpty(); }
};

// Thin asynchronous wrapper over the neocore C ABI. Every call runs on a worker
// thread so the UI never waits for the database, crypto or network.
class Core : public QObject {
    Q_OBJECT
public:
    using Callback = std::function<void(const QJsonValue &result, const CoreError &error)>;

    static Core *instance();
    bool open(const QString &dataDir, const QString &dbKeyHex, QString *error);
    void close();

    // `cb` runs on the thread of `ctx`, and only if `ctx` is still alive.
    void call(const QString &method, const QJsonObject &params, QObject *ctx = nullptr, Callback cb = {});

signals:
    void event(const QJsonObject &event);

private:
    Core();
    ~Core() override;
    void *m_core = nullptr;
    QThreadPool m_pool;
};
