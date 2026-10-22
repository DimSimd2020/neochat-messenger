#include "email.h"
#include "core.h"
#include "dialogs.h"
#include "sheet.h"
#include "widgets.h"
#include <QLineEdit>
#include <QVBoxLayout>

EmailVerification::EmailVerification(bool registration, const QString &server, QWidget *parent)
    : QWidget(parent), m_registration(registration), m_server(server)
{
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(0,0,0,0); layout->setSpacing(10);
    m_address = new ui::TextField(QStringLiteral("Электронная почта"));
    m_info = new ui::Label(QStringLiteral("Почта видна только вам. Подтвердите адрес по ссылке из письма."),13.5,400,ui::Label::Muted);
    m_info->setWordWrap(true);
    m_send = new ui::Button(QStringLiteral("Отправить ссылку"),ui::Button::Secondary);
    m_check = new ui::Button(QStringLiteral("Я подтвердил почту"),ui::Button::Secondary);
    m_change = new ui::Button(QStringLiteral("Изменить адрес"),ui::Button::Flat);
    layout->addWidget(m_address); layout->addWidget(m_info);
    for (auto *button : {m_send,m_check,m_change}) layout->addWidget(button);
    m_check->hide(); m_change->hide();
    connect(m_send,&QAbstractButton::clicked,this,&EmailVerification::send);
    connect(m_check,&QAbstractButton::clicked,this,&EmailVerification::refresh);
    connect(m_address->edit(),&QLineEdit::returnPressed,this,&EmailVerification::send);
    connect(m_change,&QAbstractButton::clicked,this,[this] {
        m_ticket.clear(); m_verified=false; m_address->setEnabled(true);
        m_send->show(); m_check->hide(); m_change->hide();
        m_info->setText(QStringLiteral("Введите адрес и отправьте новую ссылку."));
    });
}

void EmailVerification::setBusy(bool busy)
{
    m_busy=busy;
    for (auto *button : {m_send,m_check,m_change}) button->setEnabled(!busy);
}

void EmailVerification::send()
{
    if (m_busy) return;
    if (m_address->text().trimmed().isEmpty()) { m_info->setText(QStringLiteral("Введите электронную почту")); m_address->edit()->setFocus(); return; }
    if (m_sent.isValid() && m_sent.elapsed()<60000) { m_info->setText(QStringLiteral("Повторная отправка доступна через минуту после предыдущего письма.")); return; }
    setBusy(true);
    QJsonObject params{{"email",m_address->text().trimmed()}};
    if (!m_server.isEmpty()) params["server"]=m_server;
    Core::instance()->call(m_registration ? "registration_email_start" : "email_start",params,this,[this](const QJsonValue &value,const CoreError &error) {
        setBusy(false);
        if (!error.ok()) { m_info->setText(error.message); return; }
        m_ticket=value.toObject().value("ticket").toString();
        m_sent.start(); m_address->setEnabled(false);
        m_send->setText(QStringLiteral("Отправить повторно"));
        m_check->show(); m_change->setVisible(m_registration);
        m_info->setText(QStringLiteral("Письмо отправлено. Откройте ссылку, нажмите «Продолжить», подтвердите привязку и вернитесь в NeoChat."));
    });
}

void EmailVerification::refresh()
{
    if (m_busy || m_ticket.isEmpty()) return;
    setBusy(true);
    QJsonObject params{{"ticket",m_ticket}};
    if (!m_server.isEmpty()) params["server"]=m_server;
    Core::instance()->call(m_registration ? "registration_email_status" : "email_status",params,this,[this](const QJsonValue &value,const CoreError &error) {
        setBusy(false);
        if (!error.ok()) { m_info->setText(error.message); return; }
        if (!value.toObject().value("verified").toBool()) { m_info->setText(QStringLiteral("Адрес ещё не подтверждён. Откройте письмо и завершите подтверждение.")); return; }
        m_verified=true;
        m_info->setText(QStringLiteral("Почта подтверждена"));
        m_send->hide(); m_check->hide();
        emit verified();
    });
}

void showEmailBinding(const DialogHost &host,std::function<void()> done)
{
    auto *sheet = new Sheet(host.host,QStringLiteral("Привяжите электронную почту"));
    auto *message = new ui::Label(QStringLiteral("Подтвердите почту, чтобы у аккаунта был ваш контактный адрес. Пароль, ключи и переписка не изменятся."),14,400,ui::Label::Muted);
    message->setWordWrap(true);
    auto *form = new EmailVerification(false);
    auto *later = new ui::Button(QStringLiteral("Позже"),ui::Button::Secondary);
    sheet->body()->addWidget(message); sheet->body()->addWidget(form); sheet->body()->addWidget(later);
    QObject::connect(later,&QAbstractButton::clicked,sheet,&Sheet::close);
    QObject::connect(form,&EmailVerification::verified,sheet,[sheet,done] { sheet->close(); if (done) done(); });
    sheet->open();
}
