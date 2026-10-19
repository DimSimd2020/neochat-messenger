#pragma once

#include <QJsonObject>
#include <QWidget>

class QStackedWidget;
class QButtonGroup;
class QTimer;
class QCheckBox;
namespace ui {
class TextField;
class Button;
class Label;
class QrView;
}

// First-run and sign-in flow: new account by invite, linking this computer to
// an existing account (QR approved on the primary device), recovery by code,
// and password sign-in when the stored session expired.
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
    enum Mode { Login, Register, Link, Recover };
    void setMode(int mode);
    void submit();
    void showError(const QString &text);
    void setBusy(bool busy);
    void showRecoveryCode(const QString &code);
    void showLinkCode(const QJsonObject &res);
    void pollLink();

    QStackedWidget *m_pages;
    QButtonGroup *m_modes;
    QWidget *m_modeBar;
    QWidget *m_loginChip;
    ui::Label *m_heading;
    ui::Label *m_hint;
    ui::TextField *m_server;
    ui::TextField *m_invite;
    ui::TextField *m_username;
    ui::TextField *m_password;
    ui::TextField *m_code;
    ui::Label *m_error;
    ui::Button *m_submit;
    // recovery code page
    ui::Label *m_recoveryCode;
    QCheckBox *m_saved;
    ui::Button *m_continue;
    // link page
    ui::QrView *m_qr;
    ui::Label *m_linkState;
    QString m_linkText;
    QTimer *m_poll;
    int m_mode = Register;
    bool m_busy = false;
};
