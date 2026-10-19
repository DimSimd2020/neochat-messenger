#include "onboarding.h"

#include "core.h"
#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QCheckBox>
#include <QClipboard>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QKeyEvent>
#include <QPainter>
#include <QRegularExpression>
#include <QStackedWidget>
#include <QSysInfo>
#include <QTimer>
#include <QVBoxLayout>

namespace {

const char kDefaultServer[] = "https://r1270286.s2566.vm.mydomain.zone:8443";

class CardFrame : public QWidget {
public:
    using QWidget::QWidget;

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(c.border, 1));
        p.setBrush(c.surface);
        p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 22, 22);
    }
};

QString deviceName()
{
    const QString host = QSysInfo::machineHostName();
    return host.isEmpty() ? QStringLiteral("Windows") : host.left(40);
}

} // namespace

Onboarding::Onboarding(QWidget *parent) : QWidget(parent)
{
    auto *outer = new QVBoxLayout(this);
    outer->setContentsMargins(16, 16, 16, 16);
    auto *card = new CardFrame;
    card->setMaximumWidth(460);
    card->setMinimumWidth(320);
    outer->addStretch();
    outer->addWidget(card, 0, Qt::AlignHCenter);
    outer->addStretch();

    auto *lay = new QVBoxLayout(card);
    lay->setContentsMargins(28, 26, 28, 26);
    lay->setSpacing(12);
    auto *brand = new QHBoxLayout;
    auto *logo = new QLabel;
    const qreal dpr = qMax(1.0, devicePixelRatioF());
    QPixmap pm = QPixmap(":/logo.png").scaled(int(36 * dpr), int(36 * dpr), Qt::KeepAspectRatio, Qt::SmoothTransformation);
    pm.setDevicePixelRatio(dpr);
    logo->setPixmap(pm);
    brand->addWidget(logo);
    brand->addSpacing(6);
    brand->addWidget(new ui::Label(QStringLiteral("NeoChat"), 24, QFont::Bold), 1);
    lay->addLayout(brand);

    m_pages = new QStackedWidget;
    lay->addWidget(m_pages);
    // Size the card to the visible page, not the tallest one.
    connect(m_pages, &QStackedWidget::currentChanged, this, [this](int index) {
        for (int i = 0; i < m_pages->count(); ++i)
            m_pages->widget(i)->setSizePolicy(QSizePolicy::Preferred, i == index ? QSizePolicy::Preferred : QSizePolicy::Ignored);
        m_pages->adjustSize();
    });

    // ---- form page ----
    auto *form = new QWidget;
    auto *fl = new QVBoxLayout(form);
    fl->setContentsMargins(0, 4, 0, 0);
    fl->setSpacing(10);
    m_heading = new ui::Label({}, 21, QFont::Bold);
    m_hint = new ui::Label({}, 13.5, 400, ui::Label::Muted);
    m_hint->setWordWrap(true);
    fl->addWidget(m_heading);
    fl->addWidget(m_hint);
    // The server address is built in; NEOCHAT_SERVER overrides it for local test servers.
    m_serverUrl = qEnvironmentVariable("NEOCHAT_SERVER", QString::fromLatin1(kDefaultServer));
    m_invite = new ui::TextField(QStringLiteral("Код приглашения"));
    m_username = new ui::TextField(QStringLiteral("Имя пользователя"));
    m_password = new ui::TextField(QStringLiteral("Пароль"));
    m_password->setPassword(true);
    m_code = new ui::TextField(QStringLiteral("Код восстановления"));
    for (auto *f : {m_username, m_password, m_invite, m_code}) {
        fl->addWidget(f);
        connect(f->edit(), &QLineEdit::returnPressed, this, &Onboarding::submit);
    }
    m_error = new ui::Label({}, 13.5, 400, ui::Label::Warning);
    m_error->setWordWrap(true);
    m_error->hide();
    fl->addWidget(m_error);
    m_submit = new ui::Button({});
    fl->addSpacing(4);
    fl->addWidget(m_submit);
    connect(m_submit, &QAbstractButton::clicked, this, &Onboarding::submit);
    m_switch = new ui::Button({}, ui::Button::Flat);
    fl->addWidget(m_switch);
    connect(m_switch, &QAbstractButton::clicked, this, [this] { setMode(m_mode == Login ? Register : Login); });
    m_recover = new ui::Button(QStringLiteral("Восстановить доступ"), ui::Button::Flat);
    fl->addWidget(m_recover);
    connect(m_recover, &QAbstractButton::clicked, this, [this] { setMode(Recover); });
    m_pages->addWidget(form);

    // ---- recovery code page ----
    auto *rec = new QWidget;
    auto *rl = new QVBoxLayout(rec);
    rl->setContentsMargins(0, 4, 0, 0);
    rl->setSpacing(12);
    rl->addWidget(new ui::Label(QStringLiteral("Сохраните код восстановления"), 17, QFont::DemiBold));
    auto *recHint = new ui::Label(QStringLiteral("Код вернёт доступ к аккаунту, если все устройства потеряны. "
                                                 "История сообщений этим кодом не восстанавливается: она хранится только на ваших устройствах. "
                                                 "Запишите код и храните отдельно от компьютера."),
                                  13.5, 400, ui::Label::Muted);
    recHint->setWordWrap(true);
    rl->addWidget(recHint);
    m_recoveryCode = new ui::Label({}, 17, QFont::DemiBold, ui::Label::Accent);
    m_recoveryCode->setMono(true);
    m_recoveryCode->setWordWrap(true);
    m_recoveryCode->setTextInteractionFlags(Qt::TextSelectableByMouse | Qt::TextSelectableByKeyboard);
    m_recoveryCode->setAccessibleName(QStringLiteral("Код восстановления"));
    rl->addWidget(m_recoveryCode);
    auto *copy = new ui::Button(QStringLiteral("Скопировать код"), ui::Button::Secondary);
    connect(copy, &QAbstractButton::clicked, this, [this] {
        QApplication::clipboard()->setText(m_recoveryCode->text().remove('\n'));
    });
    rl->addWidget(copy);
    m_saved = new QCheckBox(QStringLiteral("Я сохранил код в надёжном месте"));
    m_saved->setFont(Theme::instance()->font(14));
    m_saved->setCursor(Qt::PointingHandCursor);
    rl->addWidget(m_saved);
    m_continue = new ui::Button(QStringLiteral("Продолжить"));
    m_continue->setEnabled(false);
    connect(m_saved, &QCheckBox::toggled, m_continue, &QWidget::setEnabled);
    connect(m_continue, &QAbstractButton::clicked, this, [this] {
        // Clear the code from memory-backed widgets as soon as it is confirmed.
        m_recoveryCode->clear();
        emit finished();
    });
    rl->addWidget(m_continue);
    m_pages->addWidget(rec);

    // ---- link page ----
    auto *link = new QWidget;
    auto *ll = new QVBoxLayout(link);
    ll->setContentsMargins(0, 4, 0, 0);
    ll->setSpacing(12);
    ll->addWidget(new ui::Label(QStringLiteral("Подтвердите на основном устройстве"), 17, QFont::DemiBold));
    auto *linkHint = new ui::Label(QStringLiteral("Это новый компьютер для вашего аккаунта. Откройте NeoChat на основном устройстве: Устройства → Привязать новое. "
                                                  "Отсканируйте QR-код или вставьте скопированный текст кода."),
                                   13.5, 400, ui::Label::Muted);
    linkHint->setWordWrap(true);
    ll->addWidget(linkHint);
    m_qr = new ui::QrView;
    ll->addWidget(m_qr, 0, Qt::AlignHCenter);
    auto *copyLink = new ui::Button(QStringLiteral("Скопировать код привязки"), ui::Button::Secondary);
    connect(copyLink, &QAbstractButton::clicked, this, [this] { QApplication::clipboard()->setText(m_linkText); });
    ll->addWidget(copyLink);
    m_linkState = new ui::Label(QStringLiteral("Ожидаем подтверждения…"), 13.5, 400, ui::Label::Muted);
    m_linkState->setWordWrap(true);
    ll->addWidget(m_linkState);
    auto *cancel = new ui::Button(QStringLiteral("Отменить"), ui::Button::Flat);
    connect(cancel, &QAbstractButton::clicked, this, [this] {
        m_poll->stop();
        m_pages->setCurrentIndex(0);
    });
    ll->addWidget(cancel);
    m_pages->addWidget(link);

    m_poll = new QTimer(this);
    m_poll->setInterval(2000);
    connect(m_poll, &QTimer::timeout, this, &Onboarding::pollLink);
    for (int i = 1; i < m_pages->count(); ++i)
        m_pages->widget(i)->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Ignored);
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
}

void Onboarding::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.fillRect(rect(), Theme::p().stage);
}

void Onboarding::start(const QJsonObject &status)
{
    m_pages->setCurrentIndex(0);
    m_hasKeys = status.value("has_device_keys").toBool();
    if (!status.value("server").toString().isEmpty())
        m_serverUrl = status.value("server").toString();
    if (!status.value("username").toString().isEmpty())
        m_username->setText(status.value("username").toString());
    setMode(Login);
}

void Onboarding::setMode(int mode)
{
    m_mode = mode;
    m_error->hide();
    m_invite->setVisible(mode == Register);
    m_code->setVisible(mode == Recover);
    m_password->edit()->clear();
    switch (mode) {
    case Login:
        m_heading->setText(QStringLiteral("Вход"));
        m_hint->clear();
        m_submit->setText(QStringLiteral("Войти"));
        break;
    case Register:
        m_heading->setText(QStringLiteral("Регистрация"));
        m_hint->setText(QStringLiteral("Нужен код приглашения. Пароль — не меньше 8 символов."));
        m_submit->setText(QStringLiteral("Создать аккаунт"));
        break;
    case Recover:
        m_heading->setText(QStringLiteral("Восстановление доступа"));
        m_hint->setText(QStringLiteral("Остальные устройства будут отключены. Переписка с них не переносится."));
        m_submit->setText(QStringLiteral("Восстановить"));
        break;
    }
    m_hint->setVisible(!m_hint->text().isEmpty());
    m_switch->setText(mode == Login ? QStringLiteral("Создать аккаунт") : QStringLiteral("Уже есть аккаунт? Войти"));
    m_switch->setAccessibleName(m_switch->text());
    m_recover->setVisible(mode == Login);
    m_password->setLabel(mode == Recover ? QStringLiteral("Новый пароль") : QStringLiteral("Пароль"));
}

void Onboarding::showError(const QString &text)
{
    m_error->setText(text);
    m_error->setVisible(!text.isEmpty());
}

void Onboarding::setBusy(bool busy)
{
    m_busy = busy;
    m_submit->setEnabled(!busy);
    m_switch->setEnabled(!busy);
    m_recover->setEnabled(!busy);
    if (busy)
        m_submit->setText(QStringLiteral("Подождите…"));
    else
        setMode(m_mode);
}

void Onboarding::submit()
{
    if (m_busy)
        return;
    const QString username = m_username->text().trimmed().remove(QRegularExpression("^@"));
    const QString password = m_password->text();
    if (username.isEmpty())
        return showError(QStringLiteral("Введите имя пользователя"));
    if (password.isEmpty())
        return showError(QStringLiteral("Введите пароль"));
    if (m_mode != Login && password.size() < 8)
        return showError(QStringLiteral("Пароль — не меньше 8 символов"));
    QJsonObject p{{"server", m_serverUrl}, {"username", username}, {"device_name", deviceName()}, {"platform", "windows"}};
    // Without keys on this computer signing in means linking it to the account.
    const bool linking = m_mode == Login && !m_hasKeys;
    QString method;
    switch (m_mode) {
    case Login:
        method = linking ? "link_start" : "login";
        p["password"] = password;
        break;
    case Register:
        if (m_invite->text().trimmed().isEmpty())
            return showError(QStringLiteral("Введите код приглашения"));
        method = "register";
        p["password"] = password;
        p["invite"] = m_invite->text().trimmed();
        break;
    case Recover:
        if (m_code->text().trimmed().isEmpty())
            return showError(QStringLiteral("Введите код восстановления"));
        method = "recover";
        p["new_password"] = password;
        p["recovery_code"] = m_code->text().trimmed();
        break;
    }
    showError({});
    setBusy(true);
    const int mode = m_mode;
    Core::instance()->call(method, p, this, [this, mode, linking](const QJsonValue &v, const CoreError &e) {
        setBusy(false);
        if (!e.ok())
            return showError(e.message);
        m_password->edit()->clear();
        m_code->edit()->clear();
        if (mode == Register || mode == Recover)
            showRecoveryCode(v.toObject().value("recovery_code").toString());
        else if (linking)
            showLinkCode(v.toObject());
        else
            emit finished();
    });
}

void Onboarding::showRecoveryCode(const QString &code)
{
    m_recoveryCode->setText(code);
    m_saved->setChecked(false);
    m_pages->setCurrentIndex(1);
    m_saved->setFocus();
}

void Onboarding::showLinkCode(const QJsonObject &res)
{
    m_linkText = res.value("qr").toString();
    QStringList rows;
    for (const auto &r : res.value("qr_rows").toArray())
        rows << r.toString();
    m_qr->setRows(rows);
    const int minutes = qMax(1, res.value("expires_in").toInt() / 60);
    m_linkState->setText(QStringLiteral("Ожидаем подтверждения… Код действует %1 мин.").arg(minutes));
    m_linkState->setRole(ui::Label::Muted);
    m_pages->setCurrentIndex(2);
    m_poll->start();
}

void Onboarding::pollLink()
{
    if (m_busy)
        return;
    m_busy = true;
    Core::instance()->call("link_poll", {}, this, [this](const QJsonValue &v, const CoreError &e) {
        m_busy = false;
        if (!e.ok()) {
            if (e.code == "network")
                return;
            m_poll->stop();
            m_linkState->setText(e.message);
            m_linkState->setRole(ui::Label::Warning);
            return;
        }
        const QString status = v.isObject() ? v.toObject().value("status").toString() : QStringLiteral("approved");
        if (status == "approved") {
            m_poll->stop();
            emit finished();
        } else if (status != "pending") {
            m_poll->stop();
            m_linkState->setText(QStringLiteral("Привязка отклонена или истекла. Начните заново."));
            m_linkState->setRole(ui::Label::Warning);
        }
    });
}
