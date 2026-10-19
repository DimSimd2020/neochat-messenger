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
    // Sign-in by QR: a signed-in phone scans it and lets this computer in.
    m_qrCard = new CardFrame;
    m_qrCard->setFixedWidth(300);
    auto *row = new QHBoxLayout;
    row->setSpacing(16);
    row->addStretch();
    row->addWidget(card, 0, Qt::AlignVCenter);
    row->addWidget(m_qrCard, 0, Qt::AlignVCenter);
    row->addStretch();
    outer->addStretch();
    outer->addLayout(row);
    outer->addStretch();

    auto *ql = new QVBoxLayout(m_qrCard);
    ql->setContentsMargins(24, 26, 24, 26);
    ql->setSpacing(12);
    auto *qrTitle = new ui::Label(QStringLiteral("Вход по QR-коду"), 17, QFont::DemiBold);
    qrTitle->setAlignment(Qt::AlignHCenter);
    ql->addWidget(qrTitle);
    m_qr = new ui::QrView;
    m_qr->setAccessibleName(QStringLiteral("QR-код для входа"));
    ql->addWidget(m_qr, 0, Qt::AlignHCenter);
    // A saved sign-in on this computer is replaced only when the person asks for the QR code.
    m_showQr = new ui::Button(QStringLiteral("Показать QR-код"), ui::Button::Secondary);
    connect(m_showQr, &QAbstractButton::clicked, this, [this] {
        m_hasKeys = false;
        updateQr();
    });
    ql->addWidget(m_showQr);
    m_qrState = new ui::Label(QStringLiteral("Откройте NeoChat на телефоне: Устройства → «Сканировать QR-код»."), 13.5, 400, ui::Label::Muted);
    m_qrState->setWordWrap(true);
    m_qrState->setAlignment(Qt::AlignHCenter);
    ql->addWidget(m_qrState);
    m_qrCard->hide();

    auto *lay = new QVBoxLayout(card);
    lay->setContentsMargins(28, 26, 28, 26);
    lay->setSpacing(12);
    auto *brand = new QHBoxLayout;
    auto *logo = new QLabel;
    const qreal dpr = qMax(1.0, devicePixelRatioF());
    QPixmap pm = QPixmap(":/logo.png").scaled(int(36 * dpr), int(36 * dpr), Qt::KeepAspectRatio, Qt::SmoothTransformation);
    pm.setDevicePixelRatio(dpr);
    logo->setPixmap(pm);
    brand->addStretch(1);
    brand->addWidget(logo);
    brand->addSpacing(6);
    brand->addWidget(new ui::Label(QStringLiteral("NeoChat"), 24, QFont::Bold));
    brand->addStretch(1);
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
    m_heading->setAlignment(Qt::AlignHCenter);
    m_hint->setAlignment(Qt::AlignHCenter);
    fl->addWidget(m_heading);
    fl->addWidget(m_hint);
    // The core has the server built in; NEOCHAT_SERVER points it at a test server.
    m_serverUrl = qEnvironmentVariable("NEOCHAT_SERVER");
    m_name = new ui::TextField(QStringLiteral("Имя"));
    fl->addWidget(m_name);
    connect(m_name->edit(), &QLineEdit::returnPressed, this, &Onboarding::submit);
    m_username = new ui::TextField(QStringLiteral("Никнейм"));
    m_password = new ui::TextField(QStringLiteral("Пароль"));
    m_password->setPassword(true);
    m_code = new ui::TextField(QStringLiteral("Код восстановления"));
    m_loginCode = new ui::TextField(QStringLiteral("Код из чата «NeoChat уведомления»"));
    m_loginCode->hide();
    for (auto *f : {m_username, m_password, m_code, m_loginCode}) {
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
                                                 "Переписка этим кодом не восстанавливается: её копия зашифрована паролем, а пароль меняется. "
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

    m_poll = new QTimer(this);
    m_poll->setInterval(2000);
    connect(m_poll, &QTimer::timeout, this, &Onboarding::pollQr);
    for (int i = 1; i < m_pages->count(); ++i)
        m_pages->widget(i)->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Ignored);
    connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
}

void Onboarding::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    Theme::paintBackdrop(p, this);
}

void Onboarding::start(const QJsonObject &status)
{
    m_pages->setCurrentIndex(0);
    m_hasKeys = status.value("has_device_keys").toBool();
    if (!status.value("username").toString().isEmpty())
        m_username->setText(status.value("username").toString());
    m_loginCode->hide();
    m_loginCode->edit()->clear();
    setMode(Login);
}

// The QR code starts by itself on a new computer; with a saved sign-in it waits for a click.
void Onboarding::updateQr()
{
    const bool shown = m_mode == Login && m_pages->currentIndex() == 0;
    m_qrCard->setVisible(shown);
    m_qr->setVisible(!m_hasKeys);
    m_showQr->setVisible(m_hasKeys);
    if (m_hasKeys)
        m_qrState->setText(QStringLiteral("Войти с телефона: покажите QR-код и отсканируйте его в NeoChat (Устройства → «Сканировать QR-код»)."));
    const bool want = shown && !m_hasKeys && !m_busy;
    if (!want) {
        m_poll->stop();
        m_qrActive = false;
        return;
    }
    if (m_qrActive)
        return;
    m_qrActive = true;
    QJsonObject p{{"device_name", deviceName()}, {"platform", "windows"}};
    if (!m_serverUrl.isEmpty())
        p["server"] = m_serverUrl;
    Core::instance()->call("qr_login_start", p, this, [this](const QJsonValue &v, const CoreError &e) {
        if (!m_qrActive)
            return;
        if (!e.ok()) {
            m_qrActive = false;
            m_qr->setRows({});
            m_qrState->setText(e.message);
            m_qrState->setRole(ui::Label::Warning);
            QTimer::singleShot(10000, this, &Onboarding::updateQr);
            return;
        }
        QStringList rows;
        for (const auto &r : v.toObject().value("qr_rows").toArray())
            rows << r.toString();
        m_qr->setRows(rows);
        m_qrState->setText(QStringLiteral("Откройте NeoChat на телефоне: Устройства → «Сканировать QR-код»."));
        m_qrState->setRole(ui::Label::Muted);
        m_poll->start();
    });
}

void Onboarding::setMode(int mode)
{
    m_mode = mode;
    m_error->hide();
    m_code->setVisible(mode == Recover);
    m_name->setVisible(mode == Register);
    m_password->edit()->clear();
    switch (mode) {
    case Login:
        m_heading->setText(QStringLiteral("Вход"));
        m_hint->clear();
        m_submit->setText(QStringLiteral("Войти"));
        break;
    case Register:
        m_heading->setText(QStringLiteral("Регистрация"));
        m_hint->setText(QStringLiteral("Пароль — не меньше 8 символов."));
        m_submit->setText(QStringLiteral("Создать аккаунт"));
        break;
    case Recover:
        m_heading->setText(QStringLiteral("Восстановление доступа"));
        m_hint->setText(QStringLiteral("Остальные устройства будут отключены, переписка не перенесётся."));
        m_submit->setText(QStringLiteral("Восстановить"));
        break;
    }
    m_hint->setVisible(!m_hint->text().isEmpty());
    m_switch->setText(mode == Login ? QStringLiteral("Создать аккаунт") : QStringLiteral("Уже есть аккаунт? Войти"));
    m_switch->setAccessibleName(m_switch->text());
    m_recover->setVisible(mode == Login);
    m_password->setLabel(mode == Recover ? QStringLiteral("Новый пароль") : QStringLiteral("Пароль"));
    if (mode != Login)
        m_loginCode->hide();
    updateQr();
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
        return showError(QStringLiteral("Введите никнейм"));
    if (password.isEmpty())
        return showError(QStringLiteral("Введите пароль"));
    if (m_mode != Login && password.size() < 8)
        return showError(QStringLiteral("Пароль — не меньше 8 символов"));
    QJsonObject p{{"username", username}, {"device_name", deviceName()}, {"platform", "windows"}};
    if (!m_serverUrl.isEmpty())
        p["server"] = m_serverUrl;
    QString method;
    switch (m_mode) {
    case Login:
        method = "login";
        p["password"] = password;
        if (m_loginCode->isVisible())
            p["code"] = m_loginCode->text().trimmed();
        break;
    case Register:
        if (m_name->text().trimmed().isEmpty())
            return showError(QStringLiteral("Введите имя"));
        method = "register";
        p["password"] = password;
        p["display_name"] = m_name->text().trimmed();
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
    Core::instance()->call(method, p, this, [this, mode, password](const QJsonValue &v, const CoreError &e) {
        // After success this computer has keys: no new QR code may replace them.
        if (e.ok())
            m_hasKeys = true;
        if (e.code == "code_required") {
            setBusy(false);
            m_password->setText(password);
            m_loginCode->show();
            m_loginCode->edit()->setFocus();
            return showError(e.message);
        }
        setBusy(false);
        if (!e.ok())
            return showError(e.message);
        m_password->edit()->clear();
        m_code->edit()->clear();
        m_loginCode->edit()->clear();
        m_loginCode->hide();
        if (mode == Register || mode == Recover)
            showRecoveryCode(v.toObject().value("recovery_code").toString());
        else
            emit finished();
    });
}

void Onboarding::showRecoveryCode(const QString &code)
{
    m_recoveryCode->setText(code);
    m_saved->setChecked(false);
    m_pages->setCurrentIndex(1);
    updateQr();
    m_saved->setFocus();
}


void Onboarding::pollQr()
{
    if (m_busy || !m_qrActive)
        return;
    Core::instance()->call("qr_login_poll", {}, this, [this](const QJsonValue &v, const CoreError &e) {
        if (!m_qrActive || m_busy)
            return;
        if (!e.ok()) {
            if (e.code == "network")
                return;
            m_poll->stop();
            m_qrActive = false;
            m_qrState->setText(e.message);
            m_qrState->setRole(ui::Label::Warning);
            return;
        }
        const QString status = v.toObject().value("status").toString();
        if (status == "approved") {
            m_poll->stop();
            m_qrActive = false;
            emit finished();
        } else if (status != "pending") {
            // Expired: show a fresh code.
            m_poll->stop();
            m_qrActive = false;
            updateQr();
        }
    });
}
