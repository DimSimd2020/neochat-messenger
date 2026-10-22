#pragma once
#include <QWidget>
#include <QElapsedTimer>
#include <functional>

namespace ui { class TextField; class Label; class Button; }
struct DialogHost;

class EmailVerification : public QWidget {
    Q_OBJECT
public:
    EmailVerification(bool registration, const QString &server = {}, QWidget *parent = nullptr);
    QString verifiedTicket() const { return m_verified ? m_ticket : QString(); }
signals:
    void verified();
private:
    void send();
    void refresh();
    void setBusy(bool busy);
    bool m_registration, m_busy = false, m_verified = false;
    QString m_server, m_ticket;
    QElapsedTimer m_sent;
    ui::TextField *m_address;
    ui::Label *m_info;
    ui::Button *m_send, *m_check, *m_change;
};

void showEmailBinding(const DialogHost &host, std::function<void()> done = {});
