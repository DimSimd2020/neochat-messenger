#pragma once

#include <QJsonObject>
#include <QWidget>

class QStackedWidget;
class QTimer;
class QCheckBox;
namespace ui {
class TextField;
class Button;
class Label;
class QrView;
}

// First-run and sign-in flow. "Войти" signs in by password (a new computer restores
// the keys and history from the password-protected copy); next to the form a QR code
// lets a signed-in phone approve this computer. Registration and recovery are secondary.
class Onboarding : public QWidget {
    Q_OBJECT
public:
    explicit Onboarding(QWidget *parent = nullptr);
    // `status` is the core's status object; it decides which modes make sense.
    void start(const QJsonObject &status);
    bool busy() const { return m_busy; }

signals:
    void finished();

protected:
    void paintEvent(QPaintEvent *) override;

private:
    enum Mode { Login, Register, Recover };
    void setMode(int mode);
    void submit();
    void showError(const QString &text);
    void setBusy(bool busy);
    void showRecoveryCode(const QString &code);
    void updateQr();
    void pollQr();

    QStackedWidget *m_pages;
    ui::Label *m_heading;
    ui::Label *m_hint;
    QString m_serverUrl;
    bool m_hasKeys = false;
    ui::TextField *m_username;
    ui::TextField *m_password;
    ui::TextField *m_code;
    ui::TextField *m_loginCode;
    ui::Label *m_error;
    ui::Button *m_submit;
    ui::Button *m_switch;
    ui::Button *m_recover;
    // recovery code page
    ui::Label *m_recoveryCode;
    QCheckBox *m_saved;
    ui::Button *m_continue;
    // QR sign-in
    QWidget *m_qrCard;
    ui::QrView *m_qr;
    ui::Button *m_showQr;
    ui::Label *m_qrState;
    QTimer *m_poll;
    bool m_qrActive = false;
    int m_mode = Login;
    bool m_busy = false;
};
