#include "mainwindow.h"

#include "calls.h"
#include "chatlist.h"
#include "composer.h"
#include "core.h"
#include "dialogs.h"
#include "messages.h"
#include "onboarding.h"
#include "popupmenu.h"
#include "sheet.h"
#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QCloseEvent>
#include <QDir>
#include <QFileDialog>
#include <QCursor>
#include <QDateTime>
#include <QSet>
#include <QStandardPaths>
#include <QImageReader>
#include <QBuffer>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QMenu>
#include <QMimeDatabase>
#include <QPainter>
#include <QProcess>
#include <QScreen>
#include <QSettings>
#include <QShortcut>
#include <QStackedWidget>
#include <QSystemTrayIcon>
#include <QTimer>
#include <QTemporaryFile>
#include <QThreadPool>
#include <QVBoxLayout>

namespace {

QString participants(int n)
{
    const int m10 = n % 10, m100 = n % 100;
    const char *word = (m10 == 1 && m100 != 11) ? "участник" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "участника" : "участников";
    return QString::number(n) + " " + QString::fromUtf8(word);
}

QString subscribers(int n)
{
    const int m10 = n % 10, m100 = n % 100;
    const char *word = (m10 == 1 && m100 != 11) ? "подписчик" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "подписчика" : "подписчиков";
    return QString::number(n) + " " + QString::fromUtf8(word);
}

// Small JPEG preview for photo bubbles; kept tiny so it fits the encrypted envelope.
QString photoThumb(const QString &path, QSize *size)
{
    QImageReader reader(path);
    reader.setAutoTransform(true);
    QSize sz = reader.size();
    if (!sz.isValid())
        return {};
    if (reader.transformation() & QImageIOHandler::TransformationRotate90)
        sz.transpose();
    *size = sz;
    if (qMax(sz.width(), sz.height()) > 640)
        reader.setScaledSize(reader.size().scaled(640, 640, Qt::KeepAspectRatio));
    const QImage img = reader.read();
    if (img.isNull())
        return {};
    for (int side : {160, 120, 90}) {
        QByteArray jpeg;
        QBuffer buf(&jpeg);
        buf.open(QIODevice::WriteOnly);
        img.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation).save(&buf, "JPEG", 55);
        const QByteArray b64 = jpeg.toBase64();
        if (b64.size() <= 12000)
            return QString::fromLatin1(b64);
    }
    return {};
}

QString memberNames(const QJsonArray &members)
{
    QStringList names;
    bool me = false;
    for (const auto &x : members) {
        const QJsonObject m = x.toObject();
        if (m.value("me").toBool())
            me = true;
        else
            names << m.value("display_name").toString().section(' ', 0, 0);
    }
    if (names.size() > 3)
        names = names.mid(0, 3) << QStringLiteral("ещё %1").arg(names.size() - 3);
    QString s = names.join(", ");
    if (me)
        s += names.isEmpty() ? QStringLiteral("только вы") : QStringLiteral(" и вы");
    return s;
}

} // namespace

// Narrow navigation strip: chats, contacts, devices; settings and profile at the bottom.
class Rail : public QWidget {
public:
    explicit Rail(QWidget *parent) : QWidget(parent)
    {
        setFixedWidth(76);
        auto *lay = new QVBoxLayout(this);
        lay->setContentsMargins(14, 16, 14, 16);
        lay->setSpacing(10);
        chats = new ui::IconButton("chat", QStringLiteral("Чаты"), this, 48);
        calls = new ui::IconButton("phone", QStringLiteral("Звонки"), this, 48);
        people = new ui::IconButton("people", QStringLiteral("Контакты"), this, 48);
        devices = new ui::IconButton("devices", QStringLiteral("Устройства"), this, 48);
        settings = new ui::IconButton("settings", QStringLiteral("Настройки"), this, 48);
        profile = new ui::IconButton("profile", QStringLiteral("Профиль"), this, 48);
        for (auto *b : {chats, calls, people, devices, settings, profile})
            b->setRail(true);
        chats->setCheckable(true);
        chats->setChecked(true);
        lay->addWidget(chats);
        lay->addWidget(calls);
        lay->addWidget(people);
        lay->addWidget(devices);
        lay->addStretch();
        lay->addWidget(settings);
        lay->addWidget(profile);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    ui::IconButton *chats, *calls, *people, *devices, *settings, *profile;

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QColor glass = Theme::p().rail;
        glass.setAlphaF(0.72);
        Theme::paintBackdrop(p, this, glass);
        const QRect r = chats->geometry();
        p.setPen(Qt::NoPen);
        p.setBrush(Theme::p().accent);
        p.drawRoundedRect(QRectF(0, r.center().y() - 10, 3, 20), 1.5, 1.5);
    }
};

class HeaderTitle : public QAbstractButton {
public:
    explicit HeaderTitle(QWidget *parent) : QAbstractButton(parent)
    {
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    void set(const QString &title, const QString &count, const QString &names, const QString &seed, bool accent = false, const QString &avatar = {})
    {
        m_title = title;
        m_count = count;
        m_names = names;
        m_seed = seed;
        m_avatar = avatar;
        m_accent = accent;
        setAccessibleName(title + (count.isEmpty() ? QString() : ", " + count) + (names.isEmpty() ? QString() : ", " + names) + QStringLiteral(". Сведения"));
        update();
    }
    QSize sizeHint() const override { return {300, 48}; }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        ui::paintAvatar(&p, QRectF(0, (height() - 36) / 2.0, 36, 36), m_title, m_seed, m_avatar);
        const qreal x = 48;
        p.setFont(Theme::instance()->font(17, QFont::DemiBold));
        p.setPen(c.text);
        const qreal w = width() - x;
        p.drawText(QRectF(x, 4, w, 24), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(m_title, Qt::ElideRight, int(w)));
        p.setFont(Theme::instance()->font(12.5));
        p.setPen(m_accent ? c.accent : c.muted);
        QString sub = m_count;
        if (!m_names.isEmpty() && width() > 360)
            sub += (sub.isEmpty() ? QString() : QStringLiteral("  ·  ")) + m_names;
        p.drawText(QRectF(x, 27, w, 18), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(sub, Qt::ElideRight, int(w)));
        if (hasFocus())
            ui::paintFocusRing(&p, rect(), 10);
    }

private:
    QString m_title, m_count, m_names, m_seed, m_avatar;
    bool m_accent = false;
};

class ConversationHeader : public QWidget {
public:
    explicit ConversationHeader(QWidget *parent) : QWidget(parent)
    {
        setFixedHeight(66);
        auto *lay = new QHBoxLayout(this);
        lay->setContentsMargins(20, 0, 16, 0);
        lay->setSpacing(8);
        toggle = new ui::IconButton("panel", QStringLiteral("Скрыть список чатов"), this);
        back = new ui::IconButton("back", QStringLiteral("Вернуться к чатам"), this);
        back->hide();
        title = new HeaderTitle(this);
        audioCall = new ui::IconButton("phone", QStringLiteral("Аудиозвонок"), this);
        videoCall = new ui::IconButton("video", QStringLiteral("Видеозвонок"), this);
        search = new ui::IconButton("search", QStringLiteral("Поиск в переписке"), this);
        more = new ui::IconButton("more", QStringLiteral("Меню диалога"), this);
        lay->addWidget(toggle);
        lay->addWidget(back);
        lay->addSpacing(8);
        lay->addWidget(title, 1);
        lay->addWidget(audioCall);
        lay->addWidget(videoCall);
        lay->addWidget(search);
        lay->addWidget(more);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    ui::IconButton *toggle, *back, *audioCall, *videoCall, *search, *more;
    HeaderTitle *title;

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        QColor glass = Theme::p().surface;
        glass.setAlphaF(0.55);
        Theme::paintBackdrop(p, this, glass);
        p.fillRect(QRect(0, height() - 1, width(), 1), Theme::p().border);
    }
};

// Warning strip under the header; height and opacity change over 180 ms.
class NoticeBar : public QWidget {
public:
    explicit NoticeBar(QWidget *parent) : QWidget(parent)
    {
        setFixedHeight(0);
        auto *lay = new QHBoxLayout(this);
        lay->setContentsMargins(24, 0, 16, 0);
        label = new ui::Label({}, 13.5, QFont::DemiBold, ui::Label::Warning);
        label->setWordWrap(true);
        action = new ui::Button({}, ui::Button::Flat);
        action->setMinimumHeight(32);
        lay->addWidget(label, 1);
        lay->addWidget(action);
        m_anim.setStartValue(0.0);
        connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
            setFixedHeight(int(v.toReal() * 46));
            update();
        });
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    void set(const QString &text, const QString &actionText)
    {
        const bool show = !text.isEmpty();
        if (show) {
            label->setText(text);
            label->setAccessibleName(text);
            action->setText(actionText);
            action->setVisible(!actionText.isEmpty());
        }
        if (show != m_shown) {
            m_shown = show;
            // The warning applies at once; only its appearance is animated.
            ui::animate(m_anim, show ? 1 : 0, Theme::ms(180));
        }
    }
    ui::Label *label;
    ui::Button *action;

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.fillRect(rect(), Theme::p().warningBg);
    }

private:
    QVariantAnimation m_anim;
    bool m_shown = false;
};

namespace {

class Surface : public QWidget {
public:
    explicit Surface(QWidget *parent = nullptr) : QWidget(parent)
    {
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        Theme::paintBackdrop(p, this);
    }
};

} // namespace

MainWindow::MainWindow()
{
    setWindowTitle(QStringLiteral("NeoChat"));
    setMinimumSize(480, 600);
    auto *central = new QWidget;
    auto *centralLay = new QVBoxLayout(central);
    centralLay->setContentsMargins(0, 0, 0, 0);
    centralLay->setSpacing(0);
    m_updateBar = new NoticeBar(central);
    m_stack = new QStackedWidget;
    centralLay->addWidget(m_updateBar);
    centralLay->addWidget(m_stack, 1);
    setCentralWidget(central);
    connect(m_updateBar->action, &QAbstractButton::clicked, this, [this] {
        // msiexec replaces the running build; the installer starts NeoChat again (LAUNCHAPP).
        if (m_updatePath.isEmpty()
            || !QProcess::startDetached("msiexec", {"/i", QDir::toNativeSeparators(m_updatePath), "/passive", "LAUNCHAPP=1"})) {
            toast(QStringLiteral("Не удалось запустить установку обновления"));
            return;
        }
        QApplication::quit();
    });
    // First check shortly after start, then every hour.
    auto *updateTimer = new QTimer(this);
    updateTimer->setInterval(60 * 60 * 1000);
    connect(updateTimer, &QTimer::timeout, this, [this] { checkUpdate(false); });
    updateTimer->start();
    QTimer::singleShot(10 * 1000, this, [this] { checkUpdate(false); });

    m_onboarding = new Onboarding;
    m_stack->addWidget(m_onboarding);
    connect(m_onboarding, &Onboarding::finished, this, &MainWindow::showMain);

    m_main = new Surface;
    auto *row = new QHBoxLayout(m_main);
    row->setContentsMargins(0, 0, 0, 0);
    row->setSpacing(0);
    m_side = new QWidget;
    auto *sideLay = new QHBoxLayout(m_side);
    sideLay->setContentsMargins(0, 0, 0, 0);
    sideLay->setSpacing(0);
    m_rail = new Rail(m_side);
    m_panel = new ChatPanel(m_side);
    sideLay->addWidget(m_rail);
    sideLay->addWidget(m_panel, 1);
    row->addWidget(m_side);

    m_conversationArea = new Surface;
    auto *areaLay = new QStackedWidget;
    auto *areaWrap = new QVBoxLayout(m_conversationArea);
    areaWrap->setContentsMargins(0, 0, 0, 0);
    areaWrap->addWidget(areaLay);
    m_placeholder = new Surface;
    auto *ph = new QVBoxLayout(m_placeholder);
    auto *phText = new ui::Label(QStringLiteral("Выберите чат или начните новый"), 15, 400, ui::Label::Muted);
    phText->setAlignment(Qt::AlignCenter);
    ph->addWidget(phText);
    areaLay->addWidget(m_placeholder);

    m_conversation = new Surface;
    auto *conv = new QVBoxLayout(m_conversation);
    conv->setContentsMargins(0, 0, 0, 0);
    conv->setSpacing(0);
    m_header = new ConversationHeader(m_conversation);
    conv->addWidget(m_header);
    m_notice = new NoticeBar(m_conversation);
    conv->addWidget(m_notice);
    m_searchWrap = new QWidget;
    auto *sw = new QHBoxLayout(m_searchWrap);
    sw->setContentsMargins(24, 10, 24, 4);
    m_search = new ui::SearchField(QStringLiteral("Найти в этой переписке"));
    sw->addWidget(m_search);
    m_searchWrap->setFixedHeight(0);
    m_searchWrap->hide();
    conv->addWidget(m_searchWrap);
    m_view = new MessageView(m_conversation);
    conv->addWidget(m_view, 1);
    m_composer = new Composer(m_conversation);
    conv->addWidget(m_composer);
    areaLay->addWidget(m_conversation);
    row->addWidget(m_conversationArea, 1);
    m_stack->addWidget(m_main);

    m_toast = new ui::Toast(m_stack);

    m_sideAnim.setStartValue(1.0);
    m_sideAnim.setEndValue(1.0);
    connect(&m_sideAnim, &QVariantAnimation::valueChanged, this, [this](const QVariant &) { updateLayoutMode(); });
    m_searchAnim.setStartValue(0.0);
    connect(&m_searchAnim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
        m_searchWrap->setFixedHeight(int(v.toReal() * 56));
        if (v.toReal() <= 0.001 && m_searchAnim.endValue().toReal() == 0)
            m_searchWrap->hide();
    });

    m_listTimer = new QTimer(this);
    m_listTimer->setSingleShot(true);
    m_listTimer->setInterval(80);
    connect(m_listTimer, &QTimer::timeout, this, [this] {
        m_panel->reload();
        refreshHeader();
    });
    m_messagesTimer = new QTimer(this);
    m_messagesTimer->setSingleShot(true);
    m_messagesTimer->setInterval(40);
    connect(m_messagesTimer, &QTimer::timeout, m_view, &MessageView::refresh);

    // ---- wiring ----
    connect(Core::instance(), &Core::event, this, &MainWindow::onEvent);
    connect(m_panel, &ChatPanel::activated, this, &MainWindow::openConversation);
    connect(m_panel, &ChatPanel::newChat, this, [this] { dialogs::newChat(host()); });
    connect(m_rail->people, &QAbstractButton::clicked, this, [this] { dialogs::contacts(host()); });
    connect(m_rail->calls, &QAbstractButton::clicked, this, [this] { dialogs::recentCalls(host()); });
    CallController::instance()->toast = [this](const QString &t) { toast(t); };
    connect(m_header->audioCall, &QAbstractButton::clicked, this, [this] { CallController::instance()->start(m_current, false); });
    connect(m_header->videoCall, &QAbstractButton::clicked, this, [this] { CallController::instance()->start(m_current, true); });
    connect(m_rail->devices, &QAbstractButton::clicked, this, [this] { dialogs::devices(host()); });
    connect(m_rail->settings, &QAbstractButton::clicked, this, [this] { dialogs::settings(host()); });
    connect(m_rail->profile, &QAbstractButton::clicked, this, [this] { dialogs::profile(host()); });
    connect(m_rail->chats, &QAbstractButton::clicked, this, [this] {
        m_rail->chats->setChecked(true);
        if (m_onePane) {
            m_listMode = true;
            updateLayoutMode();
        }
        m_panel->focusSearch();
    });
    connect(m_header->toggle, &QAbstractButton::clicked, this, [this] { setSidebarHidden(!m_sidebarHidden); });
    connect(m_header->back, &QAbstractButton::clicked, this, [this] {
        m_listMode = true;
        updateLayoutMode();
    });
    connect(m_header->title, &QAbstractButton::clicked, this, [this] {
        if (!m_current.isEmpty())
            dialogs::conversationInfo(host(), m_current.value("id").toString());
    });
    connect(m_header->more, &QAbstractButton::clicked, this, [this] {
        if (m_current.isEmpty())
            return;
        auto *menu = new PopupMenu(this);
        menu->addAction(QStringLiteral("Сведения"), this, [this] { dialogs::conversationInfo(host(), m_current.value("id").toString()); });
        menu->addAction(QStringLiteral("Поиск в переписке"), this, [this] { m_header->search->click(); });
        const QString cid = m_current.value("id").toString();
        menu->addAction(QStringLiteral("Очистить чат…"), this, [this, cid] {
            auto *sheet = new Sheet(host().host, QStringLiteral("Очистить чат?"), 420);
            auto *text = new ui::Label(QStringLiteral("История удалится только на этом устройстве. У собеседника сообщения останутся. Отправки из очереди продолжатся."), 14);
            text->setWordWrap(true);
            sheet->body()->addWidget(text);
            auto *clear = new ui::Button(QStringLiteral("Очистить"), ui::Button::Danger);
            sheet->body()->addWidget(clear);
            connect(clear, &QAbstractButton::clicked, sheet, [this, sheet, cid, clear] {
                clear->setEnabled(false);
                Core::instance()->call("clear_conversation", {{"conversation_id", cid}}, sheet, [this, sheet, clear](const QJsonValue &, const CoreError &e) {
                    if (!e.ok()) { clear->setEnabled(true); toast(e.message); return; }
                    sheet->close();
                });
            });
            sheet->open();
        });
        menu->popup(m_header->more->mapToGlobal(QPoint(m_header->more->width() - 220, m_header->more->height())));
    });
    connect(m_header->search, &QAbstractButton::clicked, this, [this] {
        const bool open = m_searchAnim.endValue().toReal() != 1.0;
        if (open) {
            m_searchWrap->show();
            m_search->edit()->setFocus(Qt::OtherFocusReason);
        } else {
            m_search->edit()->clear();
            m_composer->focusInput();
        }
        ui::animate(m_searchAnim, open ? 1 : 0, Theme::ms(180));
    });
    connect(m_search->edit(), &QLineEdit::textChanged, m_view, &MessageView::showSearch);
    connect(m_notice->action, &QAbstractButton::clicked, this, [this] {
        if (!m_current.isEmpty())
            dialogs::conversationInfo(host(), m_current.value("id").toString());
    });
    connect(m_composer, &Composer::send, this, &MainWindow::sendText);
    connect(m_composer, &Composer::filesDropped, this, [this](const QStringList &paths) { sendFiles(paths, true); });
    connect(m_composer, &Composer::imagePasted, this, [this](const QImage &image) {
        if (m_current.isEmpty() || !m_current.value("can_media").toBool()) return;
        const QString cid = m_current.value("id").toString();
        const QPointer<MainWindow> guard(this);
        QThreadPool::globalInstance()->start([guard, image, cid] {
            auto tmp = std::make_shared<QTemporaryFile>(QDir::tempPath() + "/neochat-paste-XXXXXX.png");
            const bool ok = tmp->open() && image.save(tmp.get(), "PNG");
            tmp->close();
            if (!guard) return;
            QSize size;
            const QString thumb = ok ? photoThumb(tmp->fileName(), &size) : QString();
            QMetaObject::invokeMethod(guard, [guard, tmp, cid, thumb, size, ok] {
                if (!guard) return;
                if (!ok) return guard->toast(QStringLiteral("Не удалось вставить изображение"));
                QJsonObject p{{"conversation_id", cid}, {"path", tmp->fileName()}, {"name", QStringLiteral("Изображение.png")}, {"mime", "image/png"}};
                if (!thumb.isEmpty()) { p["thumb"] = thumb; p["width"] = size.width(); p["height"] = size.height(); }
                Core::instance()->call("send_file", p, guard, [guard, tmp](const QJsonValue &, const CoreError &e) {
                    if (guard && !e.ok()) guard->toast(e.message);
                });
            }, Qt::QueuedConnection);
        });
    });
    connect(m_composer, &Composer::attach, this, [this] {
        // Ask what is attached first: photos go to the gallery-like picker and arrive as photos.
        QMenu menu(this);
        menu.addAction(QStringLiteral("Фото"), this, [this] {
            const QStringList files = QFileDialog::getOpenFileNames(this, QStringLiteral("Выберите фото"),
                                                                    QStandardPaths::writableLocation(QStandardPaths::PicturesLocation),
                                                                    QStringLiteral("Изображения (*.jpg *.jpeg *.png *.webp *.gif *.bmp *.heic)"));
            if (!files.isEmpty())
                sendFiles(files, true);
        });
        menu.addAction(QStringLiteral("Файл"), this, [this] {
            const QStringList files = QFileDialog::getOpenFileNames(this, QStringLiteral("Прикрепить файл"));
            if (!files.isEmpty())
                sendFiles(files, false);
        });
        if (m_current.value("can_polls").toBool() && m_current.value("kind").toString() != "direct")
            menu.addAction(QStringLiteral("Опрос"), this, [this] { dialogs::newPoll(host(), m_current.value("id").toString()); });
        menu.exec(QCursor::pos());
    });
    connect(m_composer, &Composer::draftChanged, this, [this](const QString &text) {
        if (!m_current.isEmpty())
            Core::instance()->call("set_draft", {{"conversation_id", m_current.value("id")}, {"text", text}});
    });
    connect(m_view, &MessageView::replyRequested, m_composer, &Composer::setReply);
    connect(m_view, &MessageView::forwardRequested, this, [this](const QJsonArray &msgs) { dialogs::forward(host(), msgs); });
    connect(m_view, &MessageView::toast, this, &MainWindow::toast);
    connect(m_view, &MessageView::readable, this, &MainWindow::markRead);
    connect(m_composer, &Composer::typing, this, [this] {
        // The core sends the hint at most every 4 s.
        if (!m_current.isEmpty())
            Core::instance()->call("send_typing", {{"conversation_id", m_current.value("id")}});
    });
    connect(Typing::instance(), &Typing::changed, this, [this](const QString &conv) {
        if (!m_current.isEmpty() && conv == m_current.value("id").toString())
            applyConversation(m_current);
    });
    // Online dots and "был(а) …" once a minute.
    auto *presenceTimer = new QTimer(this);
    presenceTimer->setInterval(60 * 1000);
    connect(presenceTimer, &QTimer::timeout, this, &MainWindow::refreshPresence);
    presenceTimer->start();

    auto *ctrlK = new QShortcut(QKeySequence(QStringLiteral("Ctrl+K")), this);
    connect(ctrlK, &QShortcut::activated, this, [this] {
        if (m_stack->currentWidget() != m_main || Sheet::anyOpen(m_stack))
            return;
        if (m_sidebarHidden)
            setSidebarHidden(false);
        if (m_onePane) {
            m_listMode = true;
            updateLayoutMode();
        }
        m_panel->focusSearch();
    });
    auto *find = new QShortcut(QKeySequence::Find, this);
    connect(find, &QShortcut::activated, this, [this] {
        if (!m_current.isEmpty() && m_stack->currentWidget() == m_main && !Sheet::anyOpen(m_stack) && m_searchAnim.endValue().toReal() != 1.0)
            m_header->search->click();
    });

    const QByteArray geo = QSettings().value("window/geometry").toByteArray();
    if (geo.isEmpty() || !restoreGeometry(geo)) {
        const QRect avail = screen()->availableGeometry();
        resize(qMin(1360, avail.width() - 80), qMin(850, avail.height() - 80));
    }
    m_sidebarHidden = QSettings().value("window/sidebarHidden", false).toBool();
    m_composer->setEnterSends(QSettings().value("composer/enterSends", true).toBool());
    connect(Theme::instance(), &Theme::changed, this, [this] { update(); });
}

DialogHost MainWindow::host()
{
    DialogHost h;
    h.host = m_stack;
    h.openChat = [this](const QString &id) {
        m_panel->select(id);
        openConversation(id);
    };
    h.toast = [this](const QString &t) { toast(t); };
    h.logout = [this] {
        Core::instance()->call("logout", {}, this, [this](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                toast(e.message);
            Core::instance()->call("status", {}, this, [this](const QJsonValue &v, const CoreError &) { showOnboarding(v.toObject()); });
        });
    };
    h.enterSendsChanged = [this](bool on) { m_composer->setEnterSends(on); };
    h.checkUpdate = [this] { checkUpdate(true); };
    return h;
}

void MainWindow::checkUpdate(bool manual)
{
    if (!m_updatePath.isEmpty()) {
        if (manual)
            toast(QStringLiteral("Обновление уже скачано: нажмите «Перезапустить» вверху окна"));
        return;
    }
    const QJsonObject p{{"platform", "windows"}, {"version", QStringLiteral(NEOCHAT_VERSION)}};
    Core::instance()->call("check_update", p, this, [this, p, manual](const QJsonValue &v, const CoreError &e) {
        if (!e.ok()) {
            if (manual)
                toast(e.message);
            return;
        }
        if (!v.toObject().value("available").toBool()) {
            if (manual)
                toast(QStringLiteral("Установлена последняя версия ") + QStringLiteral(NEOCHAT_VERSION));
            return;
        }
        if (manual)
            toast(QStringLiteral("Скачиваем обновление…"));
        Core::instance()->call("download_update", p, this, [this](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return;
            m_updatePath = v.toObject().value("path").toString();
            m_updateBar->set(QStringLiteral("Обновление %1 готово к установке").arg(v.toObject().value("version").toString()),
                             QStringLiteral("Перезапустить"));
        });
    });
}

void MainWindow::start()
{
    Core::instance()->call("status", {}, this, [this](const QJsonValue &v, const CoreError &e) {
        if (!e.ok()) {
            toast(e.message);
            return;
        }
        const QJsonObject s = v.toObject();
        if (s.value("logged_in").toBool())
            showMain();
        else
            showOnboarding(s);
    });
}

void MainWindow::showOnboarding(const QJsonObject &status)
{
    Sheet::closeAll(m_stack);
    m_current = {};
    m_view->setConversation({}, false);
    m_onboarding->start(status);
    m_stack->setCurrentWidget(m_onboarding);
}

void MainWindow::showMain()
{
    m_stack->setCurrentWidget(m_main);
    Core::instance()->call("set_foreground", {{"active", true}});
    m_panel->reload();
    QTimer::singleShot(1500, this, &MainWindow::refreshPresence);
    setSidebarHidden(m_sidebarHidden, false);
    updateLayoutMode();
    Core::instance()->call("status", {}, this, [this](const QJsonValue &v, const CoreError &) {
        m_connection = v.toObject().value("connection").toString();
        m_panel->setConnection(m_connection);
        m_rail->profile->setLabel(QStringLiteral("Профиль @") + v.toObject().value("username").toString());
    });
    loadQuickReaction(this);
    dialogs::whatsNew(host());
    const QString last = QSettings().value("window/lastConversation").toString();
    if (!last.isEmpty() && m_current.isEmpty()) {
        Core::instance()->call("get_conversation", {{"conversation_id", last}}, this, [this, last](const QJsonValue &, const CoreError &e) {
            if (e.ok() && !m_onePane) {
                m_panel->select(last);
                openConversation(last);
            }
        });
    }
}

void MainWindow::onEvent(const QJsonObject &ev)
{
    const QString type = ev.value("type").toString();
    if (type == "connection") {
        m_connection = ev.value("state").toString();
        m_panel->setConnection(m_connection);
        updateNotice();
    } else if (type == "conversations_changed" || type == "contacts_changed" || type == "identity_changed") {
        m_listTimer->start();
        // Names and photos of senders in the open chat follow contact changes.
        if (type == "contacts_changed" && !m_current.isEmpty())
            m_messagesTimer->start();
    } else if (type == "settings_changed") {
        loadQuickReaction(this);
    } else if (type == "history_cleared") {
        if (ev.value("conversation_id") == m_current.value("id")) {
            m_messagesTimer->stop();
            m_composer->setDraft({});
            m_composer->clearReply();
            m_search->edit()->clear();
            m_view->setConversation(ev.value("hidden").toBool() ? QString() : m_current.value("id").toString(), m_current.value("kind") == "group");
            if (ev.value("hidden").toBool()) {
                m_current = {};
                QSettings().remove("window/lastConversation");
                m_listMode = true;
                updateLayoutMode();
            }
        }
        m_listTimer->start();
    } else if (type == "messages_changed") {
        if (!m_current.isEmpty() && ev.value("conversation_id").toString() == m_current.value("id").toString())
            m_messagesTimer->start();
    } else if (type == "notify") {
        Typing::instance()->clear(ev.value("conversation_id").toString());
        notify(ev);
    } else if (type == "typing") {
        Typing::instance()->onEvent(ev);
    } else if (type == "presence") {
        m_listTimer->start();
    } else if (type == "warning") {
        toast(ev.value("message").toString());
    } else if (type == "sync_reset") {
        m_listTimer->start();
        m_messagesTimer->start();
    } else if (type == "auth_required" || type == "device_revoked") {
        if (m_stack->currentWidget() != m_main)
            return;
        if (type == "device_revoked")
            toast(QStringLiteral("Это устройство отозвано. Новые сообщения сюда не придут"));
        Core::instance()->call("status", {}, this, [this](const QJsonValue &v, const CoreError &) { showOnboarding(v.toObject()); });
    }
}

void MainWindow::openConversation(const QString &id)
{
    if (id.isEmpty())
        return;
    if (!m_current.isEmpty() && m_current.value("id").toString() != id)
        Core::instance()->call("set_draft", {{"conversation_id", m_current.value("id")}, {"text", m_composer->text()}});
    Core::instance()->call("get_conversation", {{"conversation_id", id}}, this, [this, id](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return toast(e.message);
        const QJsonObject c = v.toObject();
        const bool switched = m_current.value("id").toString() != id;
        m_current = c;
        QSettings().setValue("window/lastConversation", id);
        if (switched) {
            if (m_searchAnim.endValue().toReal() == 1.0) {
                m_search->edit()->clear();
                ui::animate(m_searchAnim, 0, 0);
            }
            m_composer->clearReply();
            m_composer->setDraft(c.value("draft").toString());
            m_view->setConversation(id, c.value("kind").toString() == "group");
        }
        applyConversation(c);
        m_listMode = false;
        updateLayoutMode();
        if (switched)
            m_composer->focusInput();
    });
}

void MainWindow::refreshHeader()
{
    if (m_current.isEmpty())
        return;
    const QString id = m_current.value("id").toString();
    Core::instance()->call("get_conversation", {{"conversation_id", id}}, this, [this, id](const QJsonValue &v, const CoreError &e) {
        if (!e.ok() || m_current.value("id").toString() != id)
            return;
        m_current = v.toObject();
        applyConversation(m_current);
    });
}

void MainWindow::refreshPresence()
{
    if (m_stack->currentWidget() != m_main)
        return;
    QSet<QString> ids;
    for (const auto &c : m_panel->all())
        if (c.value("kind").toString() == "direct" && !c.value("peer_user_id").toString().isEmpty())
            ids.insert(c.value("peer_user_id").toString());
    if (!m_current.isEmpty() && m_current.value("kind").toString() == "direct")
        ids.insert(m_current.value("peer_user_id").toString());
    if (ids.isEmpty())
        return;
    QJsonArray arr;
    for (const QString &id : ids)
        if (arr.size() < 200)
            arr << id;
    const int tz = QDateTime::currentDateTime().offsetFromUtc() / 60;
    Core::instance()->call("presence", {{"user_ids", arr}, {"tz_offset", tz}}, this, [this](const QJsonValue &, const CoreError &e) {
        if (e.ok())
            m_listTimer->start();
    });
}

void MainWindow::applyConversation(const QJsonObject &c)
{
    const bool group = c.value("kind").toString() == "group";
    const bool channel = c.value("kind").toString() == "channel";
    QString count, names;
    bool accent = false;
    if (group) {
        count = participants(c.value("member_count").toInt());
        names = memberNames(c.value("members").toArray());
    } else if (channel) {
        count = subscribers(c.value("member_count").toInt());
    } else {
        for (const auto &x : c.value("members").toArray())
            if (!x.toObject().value("me").toBool())
                count = "@" + x.toObject().value("username").toString();
        const QJsonObject presence = c.value("presence").toObject();
        if (!presence.value("text").toString().isEmpty()) {
            count = presence.value("text").toString();
            accent = presence.value("online").toBool();
        }
    }
    const bool system = c.value("kind").toString() == "system";
    if (system)
        count = QStringLiteral("Коды входа и новые устройства");
    const QString typing = system || channel ? QString() : Typing::instance()->text(c.value("id").toString(), c.value("kind").toString() != "direct");
    if (!typing.isEmpty()) {
        count = typing;
        accent = true;
    }
    const QString seed = c.value("kind").toString() == "direct" && !c.value("peer_user_id").toString().isEmpty() ? c.value("peer_user_id").toString() : c.value("id").toString();
    m_header->title->set(c.value("title").toString(), count, names, seed, accent, c.value("avatar_path").toString());
    const bool gone = c.value("left").toBool() || c.value("archived").toBool() || system;
    m_composer->setVisible(!gone && c.value("can_post").toBool(true));
    // Calls are one-to-one, through a direct chat the peer can receive in.
    const bool callable = c.value("kind").toString() == "direct" && !gone && c.value("can_post").toBool(true) && !c.value("peer_user_id").toString().isEmpty();
    m_header->audioCall->setVisible(callable);
    m_header->videoCall->setVisible(callable);
    m_composer->setBlocked(!c.value("key_changed_users").toArray().isEmpty());
    m_view->setCanReact(!gone && c.value("key_changed_users").toArray().isEmpty());
    updateNotice();
}

void MainWindow::updateNotice()
{
    QString text, action;
    if (!m_current.isEmpty() && m_current.value("notice").isString()) {
        text = m_current.value("notice").toString();
        if (!m_current.value("key_changed_users").toArray().isEmpty())
            action = QStringLiteral("Сверить код");
    } else if (!m_current.isEmpty() && m_current.value("kind").toString() == "group" && !m_current.value("can_post").toBool(true) &&
               !m_current.value("left").toBool()) {
        text = QStringLiteral("Писать здесь могут только владелец и админы");
    } else if (m_connection == "offline") {
        text = QStringLiteral("Нет сети. Сообщения отправятся после подключения");
    }
    m_notice->set(text, action);
}

void MainWindow::setSidebarHidden(bool hidden, bool animated)
{
    m_sidebarHidden = hidden;
    QSettings().setValue("window/sidebarHidden", hidden);
    m_header->toggle->setIcon(hidden ? "panel-open" : "panel");
    m_header->toggle->setLabel(hidden ? QStringLiteral("Показать список чатов") : QStringLiteral("Скрыть список чатов"));
    ui::animate(m_sideAnim, hidden ? 0 : 1, animated ? Theme::ms(240) : 0, Theme::surfaceEasing());
    if (!animated)
        updateLayoutMode();
}

void MainWindow::updateLayoutMode()
{
    if (m_stack->currentWidget() != m_main)
        return;
    const int w = width();
    m_onePane = w <= 760;
    const int panelW = w <= 1050 ? 286 : 324;
    auto *area = static_cast<QStackedWidget *>(m_conversationArea->layout()->itemAt(0)->widget());
    area->setCurrentWidget(m_current.isEmpty() ? m_placeholder : m_conversation);
    if (m_onePane) {
        const bool list = m_listMode || m_current.isEmpty();
        m_side->setVisible(list);
        m_side->setMinimumWidth(0);
        m_side->setMaximumWidth(QWIDGETSIZE_MAX);
        m_conversationArea->setVisible(!list);
        m_header->toggle->hide();
        m_header->back->show();
        return;
    }
    m_side->setVisible(true);
    m_conversationArea->setVisible(true);
    m_header->toggle->show();
    m_header->back->hide();
    const qreal t = m_sideAnim.currentValue().isValid() ? m_sideAnim.currentValue().toReal() : 1.0;
    const int full = 76 + panelW;
    m_side->setFixedWidth(int(full * t));
}

void MainWindow::resizeEvent(QResizeEvent *e)
{
    QMainWindow::resizeEvent(e);
    updateLayoutMode();
}

void MainWindow::closeEvent(QCloseEvent *e)
{
    if (!m_current.isEmpty())
        Core::instance()->call("set_draft", {{"conversation_id", m_current.value("id")}, {"text", m_composer->text()}});
    QSettings().setValue("window/geometry", saveGeometry());
    CallController::instance()->shutdown();
    QMainWindow::closeEvent(e);
}

void MainWindow::changeEvent(QEvent *e)
{
    if (e->type() == QEvent::ActivationChange && isActiveWindow())
        markRead();
    QMainWindow::changeEvent(e);
}

void MainWindow::sendText(const QString &text, const QString &replyTo)
{
    if (m_current.isEmpty())
        return;
    QJsonObject p{{"conversation_id", m_current.value("id")}, {"text", text}};
    if (!replyTo.isEmpty())
        p["reply_to"] = replyTo;
    // The message is stored locally first; the field clears at once and the
    // queue delivers it, so offline input is never blocked.
    m_composer->setDraft({});
    m_composer->clearReply();
    const QString cid = m_current.value("id").toString();
    Core::instance()->call("send_text", p, this, [this, text, cid](const QJsonValue &, const CoreError &e) {
        if (e.ok()) {
            Core::instance()->call("set_draft", {{"conversation_id", cid}, {"text", ""}});
            return;
        }
        toast(e.message);
        if (m_current.value("id").toString() == cid && m_composer->text().isEmpty())
            m_composer->setDraft(text);
        refreshHeader();
    });
}

void MainWindow::sendFiles(const QStringList &paths, bool photos)
{
    if (m_current.isEmpty())
        return;
    if (m_files.size() + paths.size() > 100)
        return toast(QStringLiteral("Можно прикрепить до 100 файлов за раз. Дождитесь подготовки текущих вложений"));
    for (const auto &path : paths)
        m_files.enqueue({{"conversation_id", m_current.value("id")}, {"path", path}, {"photo", photos}});
    if (!m_preparingFile) sendNextFile();
}

void MainWindow::sendNextFile()
{
    if (m_files.isEmpty()) { m_preparingFile = false; return; }
    m_preparingFile = true;
    const auto file = m_files.dequeue();
    const QPointer<MainWindow> guard(this);
    QThreadPool::globalInstance()->start([guard, file] {
        const QString path = file.value("path").toString();
        const QString type = QMimeDatabase().mimeTypeForFile(path).name();
        QJsonObject p{{"conversation_id", file.value("conversation_id")}, {"path", path}, {"mime", type}};
        QSize size;
        const QString thumb = file.value("photo").toBool() && type.startsWith("image/") ? photoThumb(path, &size) : QString();
        if (!thumb.isEmpty()) {
            p["thumb"] = thumb;
            p["width"] = size.width();
            p["height"] = size.height();
        }
        if (guard) QMetaObject::invokeMethod(guard, [guard, p] {
            if (!guard) return;
            Core::instance()->call("send_file", p, guard, [guard](const QJsonValue &, const CoreError &e) {
                if (!guard) return;
                if (!e.ok()) guard->toast(e.message);
                guard->sendNextFile();
            });
        }, Qt::QueuedConnection);
    });
}

void MainWindow::markRead()
{
    if (m_current.isEmpty() || !isActiveWindow() || m_readPending || !m_view->atBottom() || m_stack->currentWidget() != m_main)
        return;
    if (m_onePane && m_listMode)
        return;
    m_readPending = true;
    Core::instance()->call("mark_read", {{"conversation_id", m_current.value("id")}}, this, [this](const QJsonValue &, const CoreError &) { m_readPending = false; });
}

void MainWindow::toast(const QString &text)
{
    if (!text.isEmpty())
        m_toast->show(text);
}

void MainWindow::notify(const QJsonObject &ev)
{
    const QString cid = ev.value("conversation_id").toString();
    const bool visible = isActiveWindow() && cid == m_current.value("id").toString() && m_view->atBottom();
    if (visible) {
        markRead();
        return;
    }
    if (!QSettings().value("notifications/enabled", true).toBool())
        return;
    QApplication::alert(this);
    if (!m_tray) {
        m_tray = new QSystemTrayIcon(QIcon(":/logo.png"), this);
        m_tray->setToolTip(QStringLiteral("NeoChat"));
        connect(m_tray, &QSystemTrayIcon::messageClicked, this, [this] {
            showNormal();
            raise();
            activateWindow();
        });
        connect(m_tray, &QSystemTrayIcon::activated, this, [this] {
            showNormal();
            raise();
            activateWindow();
        });
        m_tray->show();
    }
    // The message text is not shown in system notifications; a missed call names itself.
    m_tray->showMessage(ev.value("title").toString(), ev.value("text").toString(QStringLiteral("Новое сообщение")), QSystemTrayIcon::NoIcon, 4000);
}
