#include "mainwindow.h"

#include "calls.h"
#include "email.h"
#include "chatlist.h"
#include "composer.h"
#include "core.h"
#include "dialogs.h"
#include "profiles.h"
#include "filepicker.h"
#include "messages.h"
#include "onboarding.h"
#include "popupmenu.h"
#include "sheet.h"
#include "profilestyle.h"
#include "theme.h"
#include "transition.h"
#include "widgets.h"

#include <QApplication>
#include <QCloseEvent>
#include <QDir>
#include <QFileInfo>
#include <QUuid>
#include <QCursor>
#include <QDateTime>
#include <QSet>
#include <QStandardPaths>
#include <QImageReader>
#include <QBuffer>
#include <QHBoxLayout>
#include <QJsonArray>
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

// Pictures pasted from the clipboard live in a temp folder of their own until they are sent or dismissed.
void dropPasted(const QString &path)
{
    if (path.startsWith(QDir::tempPath() + "/neochat-paste-"))
        QFileInfo(path).dir().removeRecursively();
}

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

// Narrow navigation strip: chats, calls, contacts; settings and profile at the bottom.
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
        settings = new ui::IconButton("settings", QStringLiteral("Настройки"), this, 48);
        profile = new ui::IconButton("profile", QStringLiteral("Профиль"), this, 48);
        for (auto *b : {chats, calls, people, settings, profile})
            b->setRail(true);
        for (auto *b : {chats, calls, people})
            b->setCheckable(true);
        chats->setChecked(true);
        lay->addWidget(chats);
        lay->addWidget(calls);
        lay->addWidget(people);
        lay->addStretch();
        lay->addWidget(settings);
        lay->addWidget(profile);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
        connect(&m_mark, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
            m_t = v.toReal();
            update();
        });
    }
    ui::IconButton *chats, *calls, *people, *settings, *profile;

    // Section 0 is chats, 1 calls, 2 contacts; the accent mark glides to its button.
    void setTab(int tab)
    {
        ui::IconButton *const buttons[] = {chats, calls, people};
        for (int i = 0; i < 3; ++i)
            buttons[i]->setChecked(i == tab);
        if (tab == m_to)
            return;
        m_fromY = markY();
        m_to = tab;
        m_mark.stop();
        m_t = 1;
        if (isVisible() && Theme::ms(1) > 0) {
            m_t = 0;
            m_mark.setStartValue(0.0);
            m_mark.setEndValue(1.0);
            m_mark.setDuration(240);
            m_mark.setEasingCurve(Theme::surfaceEasing());
            m_mark.start();
        }
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QColor glass = Theme::p().rail;
        glass.setAlphaF(0.72);
        Theme::paintBackdrop(p, this, glass);
        p.setPen(Qt::NoPen);
        p.setBrush(Theme::p().accent);
        p.drawRoundedRect(QRectF(0, markY() - 10, 3, 20), 1.5, 1.5);
    }

private:
    qreal center(int tab) const { return (tab == 0 ? chats : tab == 1 ? calls : people)->geometry().center().y(); }
    qreal markY() const
    {
        const qreal to = center(m_to);
        return m_t >= 1 ? to : m_fromY + (to - m_fromY) * m_t;
    }

    QVariantAnimation m_mark;
    qreal m_t = 1;
    qreal m_fromY = 0;
    int m_to = 0;
};

// Holds the side column flush with its right edge: as the box narrows the column slides out to the left
// instead of being squeezed.
class SlideBox : public QWidget {
public:
    explicit SlideBox(QWidget *inner) : m_inner(inner) { inner->setParent(this); }
    // Width of the column inside; 0 makes it as wide as the box.
    void setInnerWidth(int w)
    {
        m_width = w;
        place();
    }

protected:
    void resizeEvent(QResizeEvent *) override { place(); }

private:
    void place()
    {
        const int w = m_width > 0 ? m_width : width();
        m_inner->setGeometry(width() - w, 0, w, height());
    }
    QWidget *m_inner;
    int m_width = 0;
};

class HeaderTitle : public QAbstractButton {
public:
    explicit HeaderTitle(QWidget *parent) : QAbstractButton(parent)
    {
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    // The colour a person chose for their name, as '#rrggbb'.
    void setTitleColor(const QString &hex)
    {
        m_titleColor = hex;
        update();
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
        const QColor chosen = profilestyle::nameOn(m_titleColor, c.canvas);
        p.setPen(chosen.isValid() ? chosen : c.text);
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
    QString m_title, m_count, m_names, m_seed, m_avatar, m_titleColor;
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

class CallBanner : public QAbstractButton {
public:
    explicit CallBanner(QWidget *parent) : QAbstractButton(parent)
    {
        setObjectName("active-call-banner");
        setAccessibleName(QStringLiteral("Показать текущий звонок"));
        setFocusPolicy(Qt::StrongFocus);
        setCursor(Qt::PointingHandCursor);
        setFixedHeight(46);
        auto *timer = new QTimer(this);
        timer->setInterval(1000);
        auto refresh = [this] {
            auto *calls = CallController::instance();
            const qint64 seconds = calls->phase() == CallController::Active ? qMax<qint64>(0, (QDateTime::currentMSecsSinceEpoch() - calls->activeSince()) / 1000) : 0;
            QString status = QStringLiteral("Идёт звонок");
            if (calls->phase() == CallController::Incoming) status = QStringLiteral("Входящий звонок");
            else if (calls->phase() == CallController::Starting || calls->phase() == CallController::Outgoing) status = QStringLiteral("Вызов…");
            else if (calls->phase() == CallController::Connecting) status = QStringLiteral("Подключение…");
            setText(status + QStringLiteral("     %1:%2").arg(seconds / 60, 2, 10, QChar('0')).arg(seconds % 60, 2, 10, QChar('0')));
            update();
        };
        connect(timer, &QTimer::timeout, this, refresh);
        auto sync = [this, timer, refresh] {
            const auto phase = CallController::instance()->phase();
            const bool live = phase != CallController::Idle && phase != CallController::Ended;
            setVisible(live);
            if (live) { refresh(); timer->start(); } else timer->stop();
        };
        connect(CallController::instance(), &CallController::changed, this, sync);
        connect(this, &QAbstractButton::clicked, CallController::instance(), &CallController::showCurrentCall);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
        sync();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QColor tint = Theme::p().action;
        tint.setAlphaF(isDown() ? .32 : underMouse() ? .26 : .20);
        Theme::paintBackdrop(p, this, tint);
        p.setFont(Theme::instance()->font(14, QFont::DemiBold));
        const int textWidth = p.fontMetrics().horizontalAdvance(text());
        const int start = (width() - textWidth - 32) / 2;
        ui::paintIcon(&p, QRectF(start, 14, 18, 18), "phone", Theme::p().accent);
        p.setPen(Theme::p().text);
        p.drawText(QRect(start + 32, 0, textWidth, height()), Qt::AlignCenter, text());
        if (hasFocus()) ui::paintFocusRing(&p, rect().adjusted(4, 4, -4, -4), 8);
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

// Pinned messages under the header: the one in turn, its place among the pins and the way to the list.
// A click goes to the message shown and moves on to the next older pin.
class PinBar : public QWidget {
public:
    explicit PinBar(QWidget *parent) : QWidget(parent)
    {
        setFixedHeight(0);
        setCursor(Qt::PointingHandCursor);
        auto *lay = new QHBoxLayout(this);
        lay->setContentsMargins(24, 0, 10, 0);
        lay->addStretch(1);
        list = new ui::IconButton("more", QStringLiteral("Все закреплённые"), this, 36);
        unpin = new ui::IconButton("close", QStringLiteral("Открепить"), this, 36);
        lay->addWidget(list);
        lay->addWidget(unpin);
        m_anim.setStartValue(0.0);
        connect(&m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) {
            setFixedHeight(int(v.toReal() * 52));
            update();
        });
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
        connect(list, &QAbstractButton::clicked, this, [this] {
            auto *menu = new PopupMenu(list);
            for (const auto &x : m_pins) {
                const QJsonObject m = x.toObject();
                const QString id = m.value("id").toString();
                menu->addAction(preview(m, 48), this, [this, id] { jump(id); });
            }
            menu->popup(list->mapToGlobal(QPoint(0, list->height())));
        });
        connect(unpin, &QAbstractButton::clicked, this, [this] {
            if (!m_pins.isEmpty())
                unpinMessage(m_pins.at(qMin(m_index, int(m_pins.size()) - 1)).toObject().value("id").toString());
        });
    }

    // `pins` newest first; the same list keeps the place reached so far.
    void set(const QString &conv, const QJsonArray &pins, bool canPin)
    {
        if (conv != m_conv || pins.size() != m_pins.size())
            m_index = 0;
        m_conv = conv;
        m_pins = pins;
        if (m_index >= m_pins.size())
            m_index = 0;
        list->setVisible(pins.size() > 1);
        unpin->setVisible(canPin);
        const bool show = !pins.isEmpty();
        if (show != m_shown) {
            m_shown = show;
            ui::animate(m_anim, show ? 1 : 0, Theme::ms(180));
        }
        setAccessibleName(show ? QStringLiteral("Закреплённое сообщение: ") + preview(m_pins.at(m_index).toObject(), 80) : QString());
        update();
    }
    ui::IconButton *list;
    ui::IconButton *unpin;
    std::function<void(const QString &)> jump;
    std::function<void(const QString &)> unpinMessage;

protected:
    void paintEvent(QPaintEvent *) override
    {
        if (m_pins.isEmpty())
            return;
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const Palette &c = Theme::p();
        p.fillRect(rect(), c.surface);
        p.setPen(QPen(c.border, 1));
        p.drawLine(0, height() - 1, width(), height() - 1);
        // One mark per pin (up to four); the one shown is accent-coloured.
        const int n = qMin(int(m_pins.size()), 4);
        const qreal seg = (height() - 20.0 - (n - 1) * 3) / n;
        const int cur = m_pins.size() > 4 ? m_index * 4 / int(m_pins.size()) : m_index;
        p.setPen(Qt::NoPen);
        for (int i = 0; i < n; ++i) {
            p.setBrush(i == cur ? c.accent : c.border);
            p.drawRoundedRect(QRectF(24, 10 + i * (seg + 3), 3, seg), 1.5, 1.5);
        }
        const QJsonObject m = m_pins.at(m_index).toObject();
        const qreal right = (list->isVisible() ? list->x() : unpin->isVisible() ? unpin->x() : width()) - 8;
        p.setFont(Theme::instance()->font(12.5, QFont::DemiBold));
        p.setPen(c.accent);
        QString head = QStringLiteral("Закреплённое сообщение");
        if (m_pins.size() > 1)
            head += QStringLiteral(" · %1 из %2").arg(m_index + 1).arg(m_pins.size());
        p.drawText(QRectF(36, 7, right - 36, 18), Qt::AlignLeft | Qt::AlignVCenter, head);
        p.setFont(Theme::instance()->font(14));
        p.setPen(c.text);
        p.drawText(QRectF(36, 25, right - 36, 20), Qt::AlignLeft | Qt::AlignVCenter, QFontMetricsF(p.font()).elidedText(preview(m, 200), Qt::ElideRight, right - 36));
    }
    void mouseReleaseEvent(QMouseEvent *e) override
    {
        if (e->button() != Qt::LeftButton || m_pins.isEmpty() || !rect().contains(e->pos()))
            return;
        const QString id = m_pins.at(m_index).toObject().value("id").toString();
        m_index = (m_index + 1) % m_pins.size();
        update();
        jump(id);
    }

private:
    static QString preview(const QJsonObject &m, int max)
    {
        const QString kind = m.value("kind").toString();
        QString t = m.value("text").toString().simplified();
        if (kind == "file") {
            const QJsonObject f = m.value("file").toObject();
            t = (f.value("image").toBool() ? QStringLiteral("Фото") : QStringLiteral("Файл ") + f.value("name").toString()) + (t.isEmpty() ? QString() : ": " + t);
        } else if (kind == "poll") {
            t = QStringLiteral("Опрос: ") + t;
        }
        return t.size() > max ? t.left(max - 1) + QStringLiteral("…") : t;
    }
    QVariantAnimation m_anim;
    QJsonArray m_pins;
    QString m_conv;
    int m_index = 0;
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
    m_emailBar = new NoticeBar(central);
    centralLay->addWidget(m_emailBar);
    connect(m_emailBar->action,&QAbstractButton::clicked,this,[this] { showEmailBinding(host(),[this] { refreshEmail(); }); });
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
    connect(m_onboarding, &Onboarding::chooseAccount, this, [this] { dialogs::accounts(host()); });

    m_main = new Surface;
    auto *row = new QHBoxLayout(m_main);
    row->setContentsMargins(0, 0, 0, 0);
    row->setSpacing(0);
    auto *sideInner = new QWidget;
    auto *sideLay = new QHBoxLayout(sideInner);
    sideLay->setContentsMargins(0, 0, 0, 0);
    sideLay->setSpacing(0);
    m_rail = new Rail(sideInner);
    m_panel = new ChatPanel(sideInner);
    sideLay->addWidget(m_rail);
    sideLay->addWidget(m_panel, 1);
    m_side = new SlideBox(sideInner);
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
    conv->addWidget(new CallBanner(m_conversation));
    m_notice = new NoticeBar(m_conversation);
    conv->addWidget(m_notice);
    m_pinBar = new PinBar(m_conversation);
    conv->addWidget(m_pinBar);
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
    // Contacts and calls take the whole window next to the rail.
    m_pagesArea = new Surface;
    auto *pagesLay = new QVBoxLayout(m_pagesArea);
    pagesLay->setContentsMargins(0, 0, 0, 0);
    m_pages = new QStackedWidget;
    pagesLay->addWidget(m_pages);
    m_pagesArea->hide();
    row->addWidget(m_pagesArea, 1);
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
    connect(m_panel, &ChatPanel::archived, this, [this](const QStringList &ids) {
        m_toast->show(ids.size() == 1 ? QStringLiteral("Чат в архиве") : QStringLiteral("Чаты в архиве"), QStringLiteral("Отмена"), [this, ids] {
            for (const auto &id : ids)
                Core::instance()->call("set_archived", {{"conversation_id", id}, {"archived", false}}, this, [this](const QJsonValue &, const CoreError &e) { if (!e.ok()) toast(e.message); });
        });
    });
    connect(m_rail->people, &QAbstractButton::clicked, this, [this] { setTab(2); });
    connect(m_rail->calls, &QAbstractButton::clicked, this, [this] { setTab(1); });
    CallController::instance()->toast = [this](const QString &t) { toast(t); };
    connect(m_header->audioCall, &QAbstractButton::clicked, this, [this] { CallController::instance()->start(m_current, false); });
    connect(m_header->videoCall, &QAbstractButton::clicked, this, [this] { CallController::instance()->start(m_current, true); });
    connect(m_rail->settings, &QAbstractButton::clicked, this, [this] { dialogs::settings(host()); });
    connect(m_rail->profile, &QAbstractButton::clicked, this, [this] { dialogs::profile(host()); });
    connect(m_rail->chats, &QAbstractButton::clicked, this, [this] {
        if (m_tab != 0)
            return setTab(0);
        m_rail->setTab(0);
        if (m_onePane)
            showList(true);
        m_panel->focusSearch();
    });
    connect(m_header->toggle, &QAbstractButton::clicked, this, [this] { setSidebarHidden(!m_sidebarHidden); });
    connect(m_header->back, &QAbstractButton::clicked, this, [this] { showList(true); });
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
    connect(m_composer, &Composer::filesDropped, this, [this](const QStringList &paths) { attachDialog(paths, true); });
    connect(m_composer, &Composer::imagePasted, this, [this](const QImage &image) {
        if (m_current.isEmpty() || !m_current.value("can_media").toBool()) return;
        const QPointer<MainWindow> guard(this);
        // The picture waits in the same preview window as picked files; the file lives in its own temp folder.
        QThreadPool::globalInstance()->start([guard, image] {
            const QString dir = QDir::tempPath() + "/neochat-paste-" + QUuid::createUuid().toString(QUuid::WithoutBraces);
            const QString path = dir + QStringLiteral("/Изображение.png");
            const bool ok = QDir().mkpath(dir) && image.save(path, "PNG");
            if (!guard) return;
            QMetaObject::invokeMethod(guard, [guard, path, ok] {
                if (!guard) return;
                if (!ok) return guard->toast(QStringLiteral("Не удалось вставить изображение"));
                guard->attachDialog({path}, true);
            }, Qt::QueuedConnection);
        });
    });
    connect(m_composer, &Composer::attach, this, [this] {
        // Ask what is attached first: photos go to the gallery-like picker and arrive as photos.
        auto *menu = new PopupMenu(this);
        menu->addAction(QStringLiteral("Фото"), this, [this] {
            filepicker::open(this, QStringLiteral("Выберите фото"), {"jpg", "jpeg", "png", "webp", "gif", "bmp", "heic"}, true,
                             [this](const QStringList &files) { attachDialog(files, true); });
        });
        menu->addAction(QStringLiteral("Файл"), this, [this] {
            filepicker::open(this, QStringLiteral("Прикрепить файл"), {}, true, [this](const QStringList &files) { attachDialog(files, false); });
        });
        if (m_current.value("can_polls").toBool() && m_current.value("kind").toString() != "direct")
            menu->addAction(QStringLiteral("Опрос"), this, [this] { dialogs::newPoll(host(), m_current.value("id").toString()); });
        menu->popup(QCursor::pos());
    });
    connect(m_composer, &Composer::draftChanged, this, [this](const QString &text) {
        if (!m_current.isEmpty())
            Core::instance()->call("set_draft", {{"conversation_id", m_current.value("id")}, {"text", text}});
    });
    connect(m_view, &MessageView::replyRequested, m_composer, &Composer::setReply);
    connect(m_view, &MessageView::profileRequested, this, [this](const QString &uid) { dialogs::personProfile(host(),uid); });
    connect(m_view, &MessageView::editRequested, m_composer, &Composer::setEdit);
    connect(m_composer, &Composer::edited, this, &MainWindow::editText);
    connect(m_view, &MessageView::forwardRequested, this, [this](const QJsonArray &msgs) { dialogs::forward(host(), msgs); });
    connect(m_view, &MessageView::toast, this, &MainWindow::toast);
    m_pinBar->jump = [this](const QString &id) { m_view->jumpToMessage(id); };
    m_pinBar->unpinMessage = [this](const QString &id) {
        Core::instance()->call("pin_message", {{"message_id", id}, {"on", false}}, this, [this](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                toast(e.message);
        });
    };
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
        if (m_tab != 0)
            selectTab(0);
        if (m_sidebarHidden)
            setSidebarHidden(false);
        if (m_onePane)
            showList(true);
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
    const QString channel = QSettings().value(QStringLiteral("updates/channel")).toString() == QLatin1String("beta")
                                 ? QStringLiteral("beta")
                                 : QStringLiteral("stable");
    const QJsonObject p{{"platform", "windows"}, {"version", QStringLiteral(NEOCHAT_VERSION)}, {"channel", channel}};
    Core::instance()->call("check_update", p, this, [this, p, manual](const QJsonValue &v, const CoreError &e) {
        if (!e.ok()) {
            if (manual)
                toast(e.message);
            return;
        }
        if (!v.toObject().value("available").toBool()) {
            if (manual)
                toast(QStringLiteral("Установлена последняя версия ") + QStringLiteral(NEOCHAT_VERSION_LABEL));
            return;
        }
        if (manual)
            toast(QStringLiteral("Скачиваем обновление…"));
        Core::instance()->call("download_update", p, this, [this](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return;
            const QJsonObject o = v.toObject();
            const QString tag = o.value("tag").toString();
            m_updatePath = o.value("path").toString();
            m_updateBar->set(QStringLiteral("Обновление %1%2 готово к установке").arg(o.value("version").toString(), tag.isEmpty() ? QString() : QLatin1Char(' ') + tag),
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
    m_emailBar->set({},{});
    m_emailPrompted=false;
    Sheet::closeAll(m_stack);
    m_current = {};
    m_view->setConversation({}, false);
    m_onboarding->start(status);
    m_stack->setCurrentWidget(m_onboarding);
}

void MainWindow::showMain()
{
    refreshEmail();
    m_stack->setCurrentWidget(m_main);
    if (!m_contacts) {
        m_calls = dialogs::callsPage(host());
        m_contacts = dialogs::contactsPage(host());
        m_pages->addWidget(m_calls);
        m_pages->addWidget(m_contacts);
    } else {
        m_calls->reload();
        m_contacts->reload();
    }
    m_tab = 0;
    m_rail->setTab(0);
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
    const QString last = m_pendingConversation.isEmpty() ? QSettings().value("window/lastConversation").toString() : m_pendingConversation;
    m_pendingConversation.clear();
    if (!last.isEmpty() && m_current.isEmpty()) {
        Core::instance()->call("get_conversation", {{"conversation_id", last}}, this, [this, last](const QJsonValue &, const CoreError &e) {
            if (e.ok() && !m_onePane) {
                m_panel->select(last);
                openConversation(last);
            }
        });
    }
}

void MainWindow::refreshEmail()
{
    Core::instance()->call("email_status",{},this,[this](const QJsonValue &value,const CoreError &error) {
        if (!error.ok()) return;
        const bool missing=!value.toObject().value("verified").toBool();
        m_emailBar->set(missing ? QStringLiteral("Привяжите электронную почту к аккаунту") : QString(),QStringLiteral("Привязать"));
        if (missing && !m_emailPrompted && m_stack->currentWidget()==m_main) {
            if (Sheet::anyOpen(m_stack)) {
                for (auto *sheet : m_stack->findChildren<Sheet *>())
                    connect(sheet,&Sheet::closed,this,[this] { QTimer::singleShot(0,this,&MainWindow::refreshEmail); },Qt::SingleShotConnection);
            } else {
                m_emailPrompted=true;
                showEmailBinding(host(),[this] { refreshEmail(); });
            }
        }
    });
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
            m_composer->clearReply();
            m_composer->setDraft({});
            m_search->edit()->clear();
            m_view->setConversation(ev.value("hidden").toBool() ? QString() : m_current.value("id").toString(), m_current.value("kind") == "group");
            if (ev.value("hidden").toBool()) {
                m_current = {};
                QSettings().remove("window/lastConversation");
                showList(true);
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
    } else if (type == "account_selected") {
        m_composer->clearReply();
        if (!m_current.isEmpty())
            Core::instance()->call("set_draft", {{"_account_id", ev.value("previous_account_id")}, {"conversation_id", m_current.value("id")}, {"text", m_composer->text()}});
        Sheet::closeAll(m_stack);
        m_panel->resetAccount();
        Typing::instance()->reset();
        m_current = {};
        m_view->setConversation({}, false);
        m_composer->setDraft({});
        m_composer->setConversation({});
        m_files.clear();
        m_preparingFile = false;
        m_readPending = false;
        m_conversation->hide();
        m_placeholder->show();
        m_emailBar->set({}, {});
        m_emailPrompted = false;
        QSettings().remove("window/lastConversation");
        start();
    } else if (type == "account_notify") {
        notify(ev);
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
        auto apply = [this, id, c, switched] {
            m_current = c;
            QSettings().setValue("window/lastConversation", id);
            if (switched) {
                if (m_searchAnim.endValue().toReal() == 1.0) {
                    m_search->edit()->clear();
                    ui::animate(m_searchAnim, 0, 0);
                }
                m_composer->clearReply();
                m_composer->setDraft(c.value("draft").toString());
                m_composer->setConversation(id);
                m_view->setConversation(id, c.value("kind").toString() == "group");
            }
            applyConversation(c);
            m_listMode = false;
            if (m_tab != 0)
                selectTab(0);
            else
                updateLayoutMode();
            if (switched) {
                m_composer->focusInput();
                if (c.value("kind").toString() == "group")
                    refreshPresence();
            }
        };
        if (m_tab != 0)
            ui::transition(m_main, QRect(m_rail->width(), 0, m_main->width() - m_rail->width(), m_main->height()), ui::Motion::Fade, apply);
        else if (m_onePane && m_listMode)
            animateConversation(ui::Motion::PullLeft, apply);
        else if (switched)
            animateConversation(ui::Motion::Dissolve, apply);
        else
            apply();
    });
}

// The conversation area (everything in one-pane mode) moves as `change` is applied.
void MainWindow::animateConversation(ui::Motion motion, const std::function<void()> &change)
{
    ui::transition(m_main, m_onePane ? m_main->rect() : m_conversationArea->geometry(), motion, change);
}

// One-pane mode: the list or the conversation is on screen.
void MainWindow::showList(bool list)
{
    const bool animate = m_onePane && m_tab == 0 && m_listMode != list;
    auto change = [this, list] {
        m_listMode = list;
        updateLayoutMode();
    };
    if (animate)
        animateConversation(list ? ui::Motion::PullRight : ui::Motion::PullLeft, change);
    else
        change();
}

void MainWindow::selectTab(int tab)
{
    m_tab = tab;
    m_rail->setTab(tab);
    if (tab == 1 && m_calls)
        m_pages->setCurrentWidget(m_calls);
    if (tab == 2 && m_contacts)
        m_pages->setCurrentWidget(m_contacts);
    updateLayoutMode();
}

// Rail sections: 0 chats, 1 calls, 2 contacts.
void MainWindow::setTab(int tab)
{
    m_rail->setTab(m_tab); // the click toggled the button; it shows the real section until the switch
    if (tab == m_tab || (tab != 0 && !m_contacts))
        return;
    ui::transition(m_main, QRect(m_rail->width(), 0, m_main->width() - m_rail->width(), m_main->height()), ui::Motion::Fade, [this, tab] { selectTab(tab); });
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
    // Members of the open group, for "N участников, X в сети".
    if (!m_current.isEmpty() && m_current.value("kind").toString() == "group")
        for (const auto &x : m_current.value("members").toArray())
            if (!x.toObject().value("me").toBool())
                ids.insert(x.toObject().value("user_id").toString());
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
        if (c.value("online_count").toInt() > 0)
            count += QStringLiteral(", %1 в сети").arg(c.value("online_count").toInt());
        names = memberNames(c.value("members").toArray());
    } else if (channel) {
        count = c.value("saved").toBool() ? QStringLiteral("Заметки для себя") : subscribers(c.value("member_count").toInt());
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
    m_header->title->setTitleColor(c.value("title_color").toString());
    m_header->title->set(c.value("title").toString(), count, names, seed, accent, c.value("avatar_path").toString());
    const bool gone = c.value("left").toBool() || c.value("archived").toBool() || system;
    m_composer->setVisible(!gone && c.value("can_post").toBool(true) && c.value("muted").toDouble() == 0);
    // Calls are one-to-one, through a direct chat the peer can receive in.
    const bool callable = c.value("kind").toString() == "direct" && !gone && c.value("can_post").toBool(true) && !c.value("peer_user_id").toString().isEmpty();
    m_header->audioCall->setVisible(callable);
    m_header->videoCall->setVisible(callable);
    m_composer->setBlocked(!c.value("key_changed_users").toArray().isEmpty());
    m_view->setCanReact(!gone && c.value("key_changed_users").toArray().isEmpty());
    m_view->setCanPin(c.value("can_pin").toBool());
    // Pinned messages: the bar follows the conversation's pins.
    const QString convId = c.value("id").toString();
    const bool canPin = c.value("can_pin").toBool();
    if (c.value("pin_count").toInt() == 0) {
        m_pinBar->set(convId, {}, false);
    } else {
        Core::instance()->call("list_pins", {{"conversation_id", convId}}, this, [this, convId, canPin](const QJsonValue &v, const CoreError &e) {
            if (e.ok() && m_current.value("id").toString() == convId)
                m_pinBar->set(convId, v.toArray(), canPin);
        });
    }
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
    if (m_tab != 0) {
        // Contacts or calls: the rail stays and the page takes the rest.
        m_panel->hide();
        m_side->setInnerWidth(m_rail->width());
        m_side->setFixedWidth(m_rail->width());
        m_side->show();
        m_conversationArea->hide();
        m_pagesArea->show();
        return;
    }
    m_panel->show();
    m_pagesArea->hide();
    if (m_onePane) {
        const bool list = m_listMode || m_current.isEmpty();
        m_side->setVisible(list);
        m_side->setInnerWidth(0);
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
    m_side->setInnerWidth(full);
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

void MainWindow::editText(const QString &id, const QString &text)
{
    Core::instance()->call("edit_message", {{"message_id", id}, {"text", text}}, this, [this](const QJsonValue &, const CoreError &e) {
        if (!e.ok())
            return toast(e.message);
        m_composer->clearReply();
    });
}

void MainWindow::attachDialog(const QStringList &paths, bool photos)
{
    if (m_current.isEmpty() || paths.isEmpty() || !m_current.value("can_media").toBool())
        return;
    dialogs::sendAttachments(host(), paths, photos, [this](const QStringList &chosen, bool asPhotos, const QString &caption) { sendFiles(chosen, asPhotos, caption); },
                             [paths] { for (const QString &p : paths) dropPasted(p); });
}

void MainWindow::sendFiles(const QStringList &paths, bool photos, const QString &caption)
{
    if (m_current.isEmpty())
        return;
    if (m_files.size() + paths.size() > 100)
        return toast(QStringLiteral("Можно прикрепить до 100 файлов за раз. Дождитесь подготовки текущих вложений"));
    // The caption belongs to the first file of the set.
    bool first = true;
    for (const auto &path : paths) {
        QJsonObject item{{"_account_id", Core::instance()->accountId()}, {"conversation_id", m_current.value("id")}, {"path", path}, {"photo", photos}};
        if (first && !caption.isEmpty())
            item["caption"] = caption;
        first = false;
        m_files.enqueue(item);
    }
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
        QJsonObject p{{"_account_id", file.value("_account_id")}, {"conversation_id", file.value("conversation_id")}, {"path", path}, {"mime", type}};
        if (file.contains("caption"))
            p["caption"] = file.value("caption");
        QSize size;
        const QString thumb = file.value("photo").toBool() && type.startsWith("image/") ? photoThumb(path, &size) : QString();
        if (!thumb.isEmpty()) {
            p["thumb"] = thumb;
            p["width"] = size.width();
            p["height"] = size.height();
        }
        if (guard) QMetaObject::invokeMethod(guard, [guard, p] {
            if (!guard) return;
            const QString account = p.value("_account_id").toString();
            if (account != Core::instance()->accountId()) { dropPasted(p.value("path").toString()); return; }
            Core::instance()->call("send_file", p, guard, [guard, account, path = p.value("path").toString()](const QJsonValue &, const CoreError &e) {
                dropPasted(path);
                if (!guard || account != Core::instance()->accountId()) return;
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
    const bool visible = ev.value("account_id").toString() == Core::instance()->accountId() && isActiveWindow() && cid == m_current.value("id").toString() && m_view->atBottom();
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
            const QString account = m_tray->property("account_id").toString();
            const QString cid = m_tray->property("conversation_id").toString();
            if (account.isEmpty() || account == Core::instance()->accountId()) {
                openConversation(cid);
            } else {
                m_pendingConversation = cid;
                Core::instance()->call("switch_account", {{"account_id", account}}, this, [this](const QJsonValue &, const CoreError &e) {
                    if (!e.ok()) { m_pendingConversation.clear(); toast(e.message); }
                });
            }
        });
        connect(m_tray, &QSystemTrayIcon::activated, this, [this] {
            showNormal();
            raise();
            activateWindow();
        });
        m_tray->show();
    }
    // The message text is not shown in system notifications; a missed call names itself.
    m_tray->setProperty("account_id", ev.value("account_id").toString());
    m_tray->setProperty("conversation_id", cid);
    m_tray->showMessage(ev.value("title").toString(), ev.value("text").toString(QStringLiteral("Новое сообщение")), QSystemTrayIcon::NoIcon, 4000);
}
