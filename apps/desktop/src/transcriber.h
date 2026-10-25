#pragma once

#include <QHash>
#include <QJsonObject>
#include <QObject>

class QWidget;

// Turns voice messages and circles into text on this computer: asks once for the model, downloads
// it from our server, recognizes the sound and stores the text in the core (it never leaves the
// device). The bubble shows what is going on through status().
class Transcriber : public QObject {
    Q_OBJECT
public:
    enum class Phase { Idle, Model, Working };
    struct Status {
        Phase phase = Phase::Idle;
        int percent = 0; // of the model download
    };

    static Transcriber *instance();
    Status status(const QString &messageId) const { return m_status.value(messageId); }
    // Starts the work; `host` is the window that gets the question about downloading the model.
    void request(const QJsonObject &msg, QWidget *host);
    // Forgets the text of a message.
    void clear(const QString &messageId);

signals:
    void changed();
    void notice(const QString &text);

private:
    Transcriber();
    void downloadModel(const QString &messageId);
    void recognize(const QString &messageId, const QString &modelDir);
    void fail(const QString &messageId, const QString &reason);

    QHash<QString, Status> m_status;
    QString m_downloadFor; // the message that waits for the model
};
