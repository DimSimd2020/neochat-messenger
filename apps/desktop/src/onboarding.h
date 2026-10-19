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

// First-run and sign-in flow. "Войти" signs in with stored keys or, on a new
// computer, links it to the account (approved on the primary device);
// registration and recovery by code are secondary links.
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
    void showLinkCode(const QJsonObject &res);
    void pollLink();

    QStackedWidget *m_pages;
    ui::Label *m_heading;
    ui::Label *m_hint;
    QString m_serverUrl;
    bool m_hasKeys = false;
    ui::TextField *m_username;
    ui::TextField *m_password;
    ui::TextField *m_code;
    ui::Label *m_error;
    ui::Button *m_submit;
    ui::Button *m_switch;
    ui::Button *m_recover;
    // recovery code page
    ui::Label *m_recoveryCode;
    QCheckBox *m_saved;
    ui::Button *m_continue;
    // link page
    ui::QrView *m_qr;
    ui::Label *m_linkState;
    QString m_linkText;
    QTimer *m_poll;
    int m_mode = Login;
    bool m_busy = false;
};
