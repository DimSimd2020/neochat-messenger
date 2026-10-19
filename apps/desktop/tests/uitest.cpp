// UI scenario test. Drives the real widgets (offscreen) against a local server and
// a second, headless neocore client acting as the peer. Run via scripts/uitest.sh,
// which passes NEOCHAT_E2E_SERVER and NEOCHAT_SHOTS.

#include "chatlist.h"
#include "composer.h"
#include "core.h"
#include "mainwindow.h"
#include "messages.h"
#include "sheet.h"
#include "style.h"
#include "theme.h"
#include "widgets.h"

#include <QAbstractButton>
#include <QApplication>
#include <QCheckBox>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSettings>
#include <QTemporaryDir>
#include <QTest>
#include <QTranslator>
#include <QUuid>

extern "C" {
#include "neocore.h"
}

namespace {

// Headless peer on its own database.
class Peer {
public:
    explicit Peer(const QString &dir)
    {
        const QJsonObject cfg{{"data_dir", dir}, {"db_key", QString(64, 'a')}, {"foreground", false}};
        char *err = nullptr;
        m_core = nc_open(QJsonDocument(cfg).toJson().constData(), [](void *, const char *) {}, nullptr, &err);
        if (err)
            nc_free(err);
    }
    ~Peer()
    {
        if (m_core)
            nc_close(m_core);
    }
    QJsonValue call(const QString &method, const QJsonObject &params = {}, QString *errorCode = nullptr)
    {
        char *out = nc_call(m_core, method.toUtf8().constData(), QJsonDocument(params).toJson().constData());
        const QJsonObject res = QJsonDocument::fromJson(out).object();
        nc_free(out);
        if (res.contains("error")) {
            if (errorCode)
                *errorCode = res.value("error").toObject().value("code").toString();
            else
                qWarning() << "peer" << method << res.value("error").toObject().value("message").toString();
            return {};
        }
        return res.value("ok");
    }

private:
    void *m_core = nullptr;
};

// QTest::keyClicks only handles ASCII; send real key events with Unicode text.
void typeText(QWidget *w, const QString &text)
{
    for (const QChar ch : text) {
        QKeyEvent press(QEvent::KeyPress, 0, Qt::NoModifier, QString(ch));
        QKeyEvent release(QEvent::KeyRelease, 0, Qt::NoModifier, QString(ch));
        QApplication::sendEvent(w, &press);
        QApplication::sendEvent(w, &release);
    }
}

QString uname(const QString &prefix)
{
    return prefix + "_" + QUuid::createUuid().toString(QUuid::Id128).left(8);
}

} // namespace

class UiTest : public QObject {
    Q_OBJECT

private slots:
    void initTestCase();
    void registerAccount();
    void addContactAndOpenChat();
    void composeSendAndFile();
    void receiptsAndGroup();
    void themeSettingsAndSidebar();
    void keyChangeBlocksSending();
    void cleanupTestCase();

private:
    template <typename T> T *find(const std::function<bool(T *)> &pred)
    {
        for (T *w : m_window->findChildren<T *>())
            if (w->isVisible() && pred(w))
                return w;
        return nullptr;
    }
    ui::TextField *field(const QString &label)
    {
        return find<ui::TextField>([&](ui::TextField *f) { return f->edit()->accessibleName() == label; });
    }
    QAbstractButton *button(const QString &textOrName)
    {
        return find<QAbstractButton>([&](QAbstractButton *b) { return b->text() == textOrName || b->accessibleName() == textOrName; });
    }
    void shot(const QString &name)
    {
        QTest::qWait(Theme::reducedMotion() ? 50 : 500);
        m_window->grab().save(m_shots + "/" + name + ".png");
    }
    int messageCount(MessageView *v)
    {
        int n = 0;
        auto *m = static_cast<MessageModel *>(v->model());
        for (int i = 0; i < m->rowCount(); ++i)
            if (!m->item(i).isDate)
                ++n;
        return n;
    }
    QJsonObject lastMessage(MessageView *v)
    {
        auto *m = static_cast<MessageModel *>(v->model());
        return m->rowCount() ? m->item(m->rowCount() - 1).msg : QJsonObject();
    }

    QTemporaryDir m_dir;
    QString m_server, m_shots;
    MainWindow *m_window = nullptr;
    Peer *m_alice = nullptr;
    QString m_aliceName, m_aliceCode, m_bobName, m_conv;
};

void UiTest::initTestCase()
{
    // Qt's own widgets (context menus, file dialogs) must speak Russian too.
    QTranslator qtRu;
    QVERIFY(qtRu.load(QStringLiteral(":/i18n/qtbase_ru.qm")));
    QVERIFY(!qtRu.translate("QLineEdit", "&Copy").isEmpty());
    m_server = qEnvironmentVariable("NEOCHAT_E2E_SERVER");
    // The onboarding form has no server field; point it at the test server.
    qputenv("NEOCHAT_SERVER", m_server.toUtf8());
    m_shots = qEnvironmentVariable("NEOCHAT_SHOTS", m_dir.path() + "/shots");
    if (m_server.isEmpty())
        QSKIP("NEOCHAT_E2E_SERVER is required");
    QDir().mkpath(m_shots);
    QSettings().clear();
    Theme::instance()->init();
    Theme::instance()->setMode(Theme::Dark);
    QString err;
    QVERIFY2(Core::instance()->open(m_dir.path() + "/bob", QString(64, 'b'), &err), qPrintable(err));
    m_window = new MainWindow;
    m_window->resize(1360, 850);
    m_window->show();
    m_window->start();
    QVERIFY(QTest::qWaitForWindowExposed(m_window));
}

void UiTest::registerAccount()
{
    QTRY_VERIFY(field("Пароль"));
    // A click anywhere on an empty field, label area included, must focus it.
    QTest::mouseClick(field("Никнейм"), Qt::LeftButton, {}, QPoint(30, 12));
    QTRY_VERIFY(field("Никнейм")->edit()->hasFocus());
    QTest::mouseClick(field("Пароль")->edit(), Qt::LeftButton);
    QTRY_VERIFY(field("Пароль")->edit()->hasFocus());
    // The QR code for sign-in from a phone appears once the server answers.
    QTest::qWait(3000);
    shot("01-onboarding");
    button("Создать аккаунт")->click();
    QTRY_VERIFY(button("Уже есть аккаунт? Войти"));
    m_bobName = uname("bob");
    field("Имя")->setText(QStringLiteral("Боб"));
    field("Никнейм")->setText(m_bobName);
    QTest::keyClicks(field("Пароль")->edit(), "correct horse battery");
    button("Создать аккаунт")->click();
    QTRY_VERIFY_WITH_TIMEOUT(find<QCheckBox>([](QCheckBox *) { return true; }), 15000);
    shot("02-recovery-code");
    QAbstractButton *cont = button("Продолжить");
    QVERIFY(!cont->isEnabled());
    find<QCheckBox>([](QCheckBox *) { return true; })->setChecked(true);
    QVERIFY(cont->isEnabled());
    cont->click();
    QTRY_VERIFY(find<ChatPanel>([](ChatPanel *) { return true; }));
    shot("03-empty-main");
}

void UiTest::addContactAndOpenChat()
{
    m_alice = new Peer(m_dir.path() + "/alice");
    m_aliceName = uname("alice");
    const QJsonObject reg = m_alice->call("register", {{"server", m_server}, {"username", m_aliceName}, {"display_name", "Алиса"}, {"password", "correct horse battery"},
                                                       {"device_name", "peer"}, {"platform", "android"}})
                                .toObject();
    m_aliceCode = reg.value("recovery_code").toString();
    QVERIFY(!m_aliceCode.isEmpty());
    const QJsonObject bob = m_alice->call("resolve_contact", {{"username", m_bobName}, {"first_name", "Боб"}}).toObject();
    m_conv = m_alice->call("open_direct", {{"user_id", bob.value("user_id")}}).toObject().value("id").toString();
    QVERIFY(!m_conv.isEmpty());
    m_alice->call("send_text", {{"conversation_id", m_conv}, {"text", "Привет! Это Алиса. Давай в субботу к озеру?"}});
    m_alice->call("sync_now");

    button("Контакты")->click();
    QTRY_VERIFY(button("Новый контакт"));
    shot("04-contacts-empty");
    button("Новый контакт")->click();
    QTRY_VERIFY(field("Имя (необязательно)"));
    field("Имя (необязательно)")->setText("Алиса");
    field("Фамилия (необязательно)")->setText("Смирнова");
    field("Никнейм")->setText(m_aliceName);
    shot("05-new-contact");
    button("Добавить")->click();
    QTRY_VERIFY_WITH_TIMEOUT(find<QAbstractButton>([](QAbstractButton *b) { return b->accessibleName().startsWith("Алиса Смирнова"); }), 10000);
    QTest::qWait(400);
    shot("06-contacts");
    find<QAbstractButton>([](QAbstractButton *b) { return b->accessibleName().startsWith("Алиса Смирнова"); })->click();
    QTRY_VERIFY(find<MessageView>([](MessageView *) { return true; }));
    auto *view = find<MessageView>([](MessageView *) { return true; });
    QTRY_VERIFY_WITH_TIMEOUT(messageCount(view) >= 1, 20000);
    QCOMPARE(view->conversation(), m_conv);
    shot("07-chat-incoming");
}

void UiTest::composeSendAndFile()
{
    auto *composer = find<Composer>([](Composer *) { return true; });
    auto *edit = composer->findChild<ComposerEdit *>();
    auto *send = button("Отправить");
    QVERIFY(!send || !send->isVisible());
    edit->setFocus();
    typeText(edit, "Я за! Только без подъёма в шесть утра)");
    QTRY_VERIFY(button("Отправить"));
    shot("08-composing");
    QTest::keyClick(edit, Qt::Key_Return);
    auto *view = find<MessageView>([](MessageView *) { return true; });
    QTRY_VERIFY_WITH_TIMEOUT(lastMessage(view).value("outgoing").toBool(), 5000);
    QVERIFY(edit->toPlainText().isEmpty());
    QTRY_VERIFY_WITH_TIMEOUT(lastMessage(view).value("status").toString() != "queued", 15000);

    // A 300 KB file goes through the encrypted attachment path.
    const QString path = m_dir.path() + "/Маршрут на выходные.pdf";
    QFile f(path);
    QVERIFY(f.open(QIODevice::WriteOnly));
    QByteArray data(300000, 0);
    for (int i = 0; i < data.size(); ++i)
        data[i] = char(i % 251);
    f.write(data);
    f.close();
    emit composer->filesDropped({path});
    QTRY_VERIFY_WITH_TIMEOUT(lastMessage(view).value("kind").toString() == "file" && lastMessage(view).value("status").toString() == "sent", 20000);
    const QString fileId = lastMessage(view).value("id").toString();

    m_alice->call("sync_now");
    const QJsonArray msgs = m_alice->call("list_messages", {{"conversation_id", m_conv}}).toArray();
    QStringList texts;
    for (const auto &m : msgs)
        texts << m.toObject().value("text").toString();
    QVERIFY2(texts.contains("Я за! Только без подъёма в шесть утра)"), qPrintable(texts.join('|')));
    const QString got = m_alice->call("download_file", {{"message_id", fileId}}).toObject().value("path").toString();
    QFile g(got);
    QVERIFY(g.open(QIODevice::ReadOnly));
    QCOMPARE(g.readAll(), data);
    shot("09-file-sent");
}

void UiTest::receiptsAndGroup()
{
    auto *view = find<MessageView>([](MessageView *) { return true; });
    m_alice->call("mark_read", {{"conversation_id", m_conv}});
    m_alice->call("send_text", {{"conversation_id", m_conv}, {"text", "От города всего час. Можно выехать в десять."}});
    m_alice->call("sync_now");
    QTRY_VERIFY_WITH_TIMEOUT(lastMessage(view).value("text").toString().startsWith("От города"), 20000);
    // Bob's earlier text message is now read by Alice.
    auto *model = static_cast<MessageModel *>(view->model());
    QTRY_VERIFY_WITH_TIMEOUT(
        [&] {
            for (int i = 0; i < model->rowCount(); ++i)
                if (model->item(i).msg.value("outgoing").toBool() && model->item(i).msg.value("status").toString() == "read")
                    return true;
            return false;
        }(),
        20000);
    shot("10-read-receipts");

    const QJsonObject bob = m_alice->call("resolve_contact", {{"username", m_bobName}}).toObject();
    const QString gid = m_alice->call("create_group", {{"title", "Наши"}, {"member_ids", QJsonArray{bob.value("user_id")}}}).toObject().value("id").toString();
    m_alice->call("send_text", {{"conversation_id", gid}, {"text", "Создала группу для поездки"}});
    m_alice->call("sync_now");
    auto *panel = find<ChatPanel>([](ChatPanel *) { return true; });
    QTRY_VERIFY_WITH_TIMEOUT(!panel->conversation(gid).isEmpty(), 20000);
    shot("11-group-in-list");
}

void UiTest::themeSettingsAndSidebar()
{
    button("Настройки")->click();
    QTRY_VERIFY(find<ui::ThemeSlider>([](ui::ThemeSlider *) { return true; }));
    shot("12-settings-dark");
    auto *slider = find<ui::ThemeSlider>([](ui::ThemeSlider *) { return true; });
    slider->setFocus(Qt::TabFocusReason);
    // Device -> day -> night: one step left from night is day.
    QTest::keyClick(slider, Qt::Key_Left);
    QCOMPARE(Theme::instance()->mode(), Theme::Light);
    shot("13-settings-light");
    QTest::keyClick(slider, Qt::Key_Escape);
    QTRY_VERIFY(!Sheet::anyOpen(m_window->centralWidget()));
    shot("14-main-light");
    button("Скрыть список чатов")->click();
    QTest::qWait(400);
    shot("15-conversation-only");
    QVERIFY(button("Показать список чатов"));
    button("Показать список чатов")->click();
    QTest::qWait(400);
    Theme::instance()->setMode(Theme::Dark);
}

void UiTest::keyChangeBlocksSending()
{
    // Alice recovers her account on a new device: Bob must see the key change and stop sending.
    Peer alice2(m_dir.path() + "/alice2");
    QVERIFY(!alice2.call("recover", {{"server", m_server}, {"username", m_aliceName}, {"recovery_code", m_aliceCode}, {"new_password", "another long pass"},
                                      {"device_name", "new"}, {"platform", "android"}})
                 .isNull());
    auto *panel = find<ChatPanel>([](ChatPanel *) { return true; });
    panel->select(m_conv);
    emit panel->activated(m_conv);
    Core::instance()->call("sync_now", {});
    QTRY_VERIFY_WITH_TIMEOUT(button("Сверить код"), 20000);
    auto *composer = find<Composer>([](Composer *) { return true; });
    auto *edit = composer->findChild<ComposerEdit *>();
    typeText(edit, "Это точно ты?");
    QTRY_VERIFY(button("Отправить"));
    QVERIFY(!button("Отправить")->isEnabled());
    shot("16-key-changed");
    button("Сверить код")->click();
    QTRY_VERIFY(button("Код сверен, принять новый ключ"));
    shot("17-safety-number");
}

void UiTest::cleanupTestCase()
{
    delete m_window;
    delete m_alice;
    Core::instance()->close();
}

int main(int argc, char **argv)
{
    QApplication app(argc, argv);
    Q_INIT_RESOURCE(resources);
    QApplication::setOrganizationName("NeoChatTest");
    QApplication::setApplicationName("NeoChatUiTest");
    QApplication::setStyle(createStyle());
    QApplication::setFont(Theme::instance()->font(14));
    UiTest t;
    return QTest::qExec(&t, argc, argv);
}

#include "uitest.moc"
