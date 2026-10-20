#include "dialogs.h"

#include "core.h"
#include "sheet.h"
#include "theme.h"
#include "widgets.h"

#include <QButtonGroup>
#include <QDateTime>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QMenu>
#include <QPainter>
#include <QPointer>
#include <QScrollArea>
#include <QSettings>
#include <QVBoxLayout>

namespace {

// Avatar, name and a second line; optional check mark for multi-select.
class PersonRow : public QAbstractButton {
public:
    PersonRow(const QString &name, const QString &sub, const QString &seed, QWidget *parent = nullptr)
        : QAbstractButton(parent), m_name(name), m_sub(sub), m_seed(seed)
    {
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        setAccessibleName(sub.isEmpty() ? name : name + ", " + sub);
        setMinimumHeight(58);
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }
    bool multi = false;
    QString warning;
    QSize sizeHint() const override { return {300, 58}; }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        if (underMouse() || isDown()) {
            QColor bg = c.raised;
            bg.setAlphaF(isDown() ? 1 : 0.6);
            p.setPen(Qt::NoPen);
            p.setBrush(bg);
            p.drawRoundedRect(rect(), 12, 12);
        }
        ui::paintAvatar(&p, QRectF(8, (height() - 40) / 2.0, 40, 40), m_name, m_seed);
        const qreal right = multi ? width() - 44 : width() - 10;
        p.setFont(Theme::instance()->font(15, QFont::DemiBold));
        p.setPen(c.text);
        p.drawText(QRectF(60, 9, right - 60, 22), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(m_name, Qt::ElideRight, int(right - 60)));
        p.setFont(Theme::instance()->font(12.5));
        p.setPen(warning.isEmpty() ? c.muted : c.warning);
        const QString sub = warning.isEmpty() ? m_sub : warning;
        p.drawText(QRectF(60, 31, right - 60, 18), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(sub, Qt::ElideRight, int(right - 60)));
        if (multi) {
            const QRectF box(width() - 34, (height() - 22) / 2.0, 22, 22);
            p.setPen(isChecked() ? Qt::NoPen : QPen(c.border, 1.5));
            p.setBrush(isChecked() ? QColor("#367FF4") : Qt::transparent);
            p.drawRoundedRect(box, 7, 7);
            if (isChecked())
                ui::paintIcon(&p, box.adjusted(3, 3, -3, -3), "tick", Qt::white, 2.4);
        }
        if (hasFocus())
            ui::paintFocusRing(&p, rect(), 12);
    }
    void enterEvent(QEnterEvent *) override { update(); }
    void leaveEvent(QEvent *) override { update(); }

private:
    QString m_name, m_sub, m_seed;
};

// Scrollable column of rows that grows up to a height limit.
class RowList : public QScrollArea {
public:
    explicit RowList(int maxHeight = 360)
    {
        setWidgetResizable(true);
        setFrameShape(QFrame::NoFrame);
        setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        auto *w = new QWidget;
        w->setAutoFillBackground(false);
        m_lay = new QVBoxLayout(w);
        m_lay->setContentsMargins(0, 0, 0, 0);
        m_lay->setSpacing(2);
        m_lay->addStretch();
        setWidget(w);
        viewport()->setAutoFillBackground(false);
        setMaximumHeight(maxHeight);
        m_max = maxHeight;
    }
    void clear()
    {
        while (m_lay->count() > 1) {
            QLayoutItem *it = m_lay->takeAt(0);
            delete it->widget();
            delete it;
        }
    }
    void add(QWidget *w) { m_lay->insertWidget(m_lay->count() - 1, w); }
    void fit()
    {
        widget()->adjustSize();
        setFixedHeight(qMin(m_max, qMax(60, widget()->sizeHint().height())));
    }

private:
    QVBoxLayout *m_lay;
    int m_max;
};

ui::Label *mutedText(const QString &t)
{
    auto *l = new ui::Label(t, 13.5, 400, ui::Label::Muted);
    l->setWordWrap(true);
    return l;
}

QString platformName(const QString &p)
{
    if (p == "windows")
        return QStringLiteral("Windows");
    if (p == "android")
        return QStringLiteral("Android");
    if (p == "ios")
        return QStringLiteral("iOS");
    return p;
}

void fail(const DialogHost &h, const CoreError &e)
{
    if (h.toast)
        h.toast(e.message);
}

} // namespace

namespace dialogs {

// ---- contacts ----

void contacts(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Контакты"));
    auto *search = new ui::SearchField(QStringLiteral("Поиск контактов"));
    sheet->body()->addWidget(search);
    auto *actions = new QHBoxLayout;
    auto *add = new ui::Button(QStringLiteral("Новый контакт"), ui::Button::Secondary);
    actions->addWidget(add);
    actions->addStretch();
    auto *sortBtn = new ui::Button(QStringLiteral("Имя А–Я"), ui::Button::Flat);
    sortBtn->setAccessibleName(QStringLiteral("Сортировать по"));
    actions->addWidget(new ui::Label(QStringLiteral("Сортировать по"), 13, 400, ui::Label::Muted));
    actions->addWidget(sortBtn);
    sheet->body()->addLayout(actions);
    auto *list = new RowList(380);
    auto *empty = mutedText({});
    sheet->body()->addWidget(list);
    sheet->body()->addWidget(empty);

    auto sort = std::make_shared<QString>(QSettings().value("contacts/sort", "name-asc").toString());
    QPointer<Sheet> guard(sheet);
    auto reload = std::make_shared<std::function<void()>>();
    *reload = [=] {
        static const QHash<QString, QString> names{{"name-asc", QStringLiteral("Имя А–Я")}, {"name-desc", QStringLiteral("Имя Я–А")}, {"recent", QStringLiteral("Дата добавления")}};
        sortBtn->setText(names.value(*sort));
        Core::instance()->call("list_contacts", {{"query", search->edit()->text()}, {"sort", *sort}}, sheet, [=](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return fail(h, e);
            list->clear();
            const QJsonArray arr = v.toArray();
            for (const auto &x : arr) {
                const QJsonObject c = x.toObject();
                auto *row = new PersonRow(c.value("display_name").toString(), "@" + c.value("username").toString(), c.value("user_id").toString());
                if (c.value("key_changed").toBool())
                    row->warning = QStringLiteral("Ключ изменился — сверьте код");
                const QString uid = c.value("user_id").toString();
                QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                    Core::instance()->call("open_direct", {{"user_id", uid}}, sheet, [=](const QJsonValue &cv, const CoreError &ce) {
                        if (!ce.ok())
                            return fail(h, ce);
                        sheet->close();
                        h.openChat(cv.toObject().value("id").toString());
                    });
                });
                list->add(row);
            }
            list->fit();
            empty->setText(arr.isEmpty() ? (search->edit()->text().isEmpty() ? QStringLiteral("Контактов пока нет. Добавьте человека по его точному имени пользователя.")
                                                                            : QStringLiteral("Никого не нашли по этому запросу."))
                                         : QString());
            empty->setVisible(arr.isEmpty());
            list->setVisible(!arr.isEmpty());
        });
    };
    QObject::connect(search->edit(), &QLineEdit::textChanged, sheet, [=] { (*reload)(); });
    QObject::connect(sortBtn, &QAbstractButton::clicked, sheet, [=] {
        QMenu menu(sortBtn);
        const QList<QPair<QString, QString>> modes{{"name-asc", QStringLiteral("Имя А–Я")}, {"name-desc", QStringLiteral("Имя Я–А")}, {"recent", QStringLiteral("Дата добавления")}};
        for (const auto &m : modes) {
            QAction *a = menu.addAction(m.second);
            a->setCheckable(true);
            a->setChecked(*sort == m.first);
            QObject::connect(a, &QAction::triggered, sheet, [=] {
                *sort = m.first;
                QSettings().setValue("contacts/sort", m.first);
                (*reload)();
            });
        }
        menu.exec(sortBtn->mapToGlobal(QPoint(0, sortBtn->height())));
    });
    QObject::connect(add, &QAbstractButton::clicked, sheet, [=] { newContact(h, [=] { (*reload)(); }); });
    QObject::connect(Core::instance(), &Core::event, sheet, [=](const QJsonObject &ev) {
        if (ev.value("type").toString() == "contacts_changed")
            (*reload)();
    });
    (*reload)();
    sheet->open();
}

void newContact(const DialogHost &h, std::function<void()> added)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Новый контакт"), 420);
    auto *user = new ui::TextField(QStringLiteral("Никнейм"));
    auto *first = new ui::TextField(QStringLiteral("Имя (необязательно)"));
    auto *last = new ui::TextField(QStringLiteral("Фамилия (необязательно)"));
    auto *err = new ui::Label({}, 13.5, 400, ui::Label::Warning);
    err->setWordWrap(true);
    err->hide();
    auto *submit = new ui::Button(QStringLiteral("Добавить"));
    for (QWidget *w : {static_cast<QWidget *>(user), static_cast<QWidget *>(first), static_cast<QWidget *>(last), static_cast<QWidget *>(err)})
        sheet->body()->addWidget(w);
    sheet->body()->addWidget(mutedText(QStringLiteral("Поиск идёт только по точному никнейму: публичного каталога нет. Без своего имени контакт показывается под именем из его профиля.")));
    sheet->body()->addWidget(submit);
    auto go = [=] {
        if (user->text().trimmed().isEmpty()) {
            err->setText(QStringLiteral("Введите никнейм"));
            return err->show();
        }
        err->hide();
        submit->setEnabled(false);
        sheet->setBusy(true);
        Core::instance()->call("resolve_contact", {{"username", user->text().trimmed()}, {"first_name", first->text().trimmed()}, {"last_name", last->text().trimmed()}}, sheet,
                               [=](const QJsonValue &, const CoreError &e) {
                                   submit->setEnabled(true);
                                   sheet->setBusy(false);
                                   if (!e.ok()) {
                                       err->setText(e.code == "not_found" ? QStringLiteral("Пользователь с таким никнеймом не найден") : e.message);
                                       return err->show();
                                   }
                                   if (h.toast)
                                       h.toast(QStringLiteral("Контакт добавлен"));
                                   if (added)
                                       added();
                                   sheet->close();
                               });
    };
    QObject::connect(submit, &QAbstractButton::clicked, sheet, go);
    for (auto *f : {first, last, user})
        QObject::connect(f->edit(), &QLineEdit::returnPressed, sheet, go);
    sheet->open();
}

// ---- new chat / group ----

void newChat(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Новый чат"));
    auto *modeBar = new QHBoxLayout;
    auto *direct = new ui::Button(QStringLiteral("Личный"), ui::Button::Secondary);
    auto *group = new ui::Button(QStringLiteral("Группа"), ui::Button::Flat);
    auto *channel = new ui::Button(QStringLiteral("Канал"), ui::Button::Flat);
    auto *addContact = new ui::Button(QStringLiteral("Новый контакт"), ui::Button::Flat);
    modeBar->addWidget(direct);
    modeBar->addWidget(group);
    modeBar->addWidget(channel);
    modeBar->addStretch();
    modeBar->addWidget(addContact);
    sheet->body()->addLayout(modeBar);
    auto *title = new ui::TextField(QStringLiteral("Название группы"));
    title->hide();
    sheet->body()->addWidget(title);
    auto *about = new ui::TextField(QStringLiteral("Описание (необязательно)"));
    about->hide();
    sheet->body()->addWidget(about);
    auto *search = new ui::SearchField(QStringLiteral("Поиск контактов"));
    sheet->body()->addWidget(search);
    auto *list = new RowList(340);
    sheet->body()->addWidget(list);
    auto *empty = mutedText(QStringLiteral("Контактов пока нет. Добавьте человека по имени пользователя."));
    empty->hide();
    sheet->body()->addWidget(empty);
    auto *create = new ui::Button(QStringLiteral("Создать группу"));
    create->hide();
    sheet->body()->addWidget(create);

    auto isGroup = std::make_shared<bool>(false); // group or channel: several people are picked
    auto isChannel = std::make_shared<bool>(false);
    auto selected = std::make_shared<QSet<QString>>();
    auto createText = [=] {
        const QString base = *isChannel ? QStringLiteral("Создать канал") : QStringLiteral("Создать группу");
        return selected->isEmpty() ? base : base + QStringLiteral(" (%1)").arg(selected->size());
    };
    auto reload = std::make_shared<std::function<void()>>();
    *reload = [=] {
        Core::instance()->call("list_contacts", {{"query", search->edit()->text()}}, sheet, [=](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return fail(h, e);
            list->clear();
            const QJsonArray arr = v.toArray();
            for (const auto &x : arr) {
                const QJsonObject c = x.toObject();
                const QString uid = c.value("user_id").toString();
                auto *row = new PersonRow(c.value("display_name").toString(), "@" + c.value("username").toString(), uid);
                row->multi = *isGroup;
                row->setCheckable(*isGroup);
                row->setChecked(selected->contains(uid));
                QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                    if (*isGroup) {
                        if (row->isChecked()) {
                            if (selected->size() >= (*isChannel ? 199 : 49)) {
                                row->setChecked(false);
                                if (h.toast)
                                    h.toast(*isChannel ? QStringLiteral("В канале может быть до 199 подписчиков") : QStringLiteral("В группе может быть до 49 других участников"));
                                return;
                            }
                            selected->insert(uid);
                        } else {
                            selected->remove(uid);
                        }
                        create->setText(createText());
                        return;
                    }
                    Core::instance()->call("open_direct", {{"user_id", uid}}, sheet, [=](const QJsonValue &cv, const CoreError &ce) {
                        if (!ce.ok())
                            return fail(h, ce);
                        sheet->close();
                        h.openChat(cv.toObject().value("id").toString());
                    });
                });
                list->add(row);
            }
            list->fit();
            empty->setVisible(arr.isEmpty() && search->edit()->text().isEmpty());
            list->setVisible(!arr.isEmpty());
        });
    };
    // 0 — direct chat, 1 — group, 2 — channel.
    auto setMode = [=](int mode) {
        *isGroup = mode != 0;
        *isChannel = mode == 2;
        direct->setKind(mode == 0 ? ui::Button::Secondary : ui::Button::Flat);
        group->setKind(mode == 1 ? ui::Button::Secondary : ui::Button::Flat);
        channel->setKind(mode == 2 ? ui::Button::Secondary : ui::Button::Flat);
        title->setLabel(mode == 2 ? QStringLiteral("Название канала") : QStringLiteral("Название группы"));
        title->setVisible(mode != 0);
        about->setVisible(mode != 0);
        create->setVisible(mode != 0);
        create->setText(createText());
        static const char *const titles[] = {"Новый чат", "Новая группа", "Новый канал"};
        sheet->setTitle(QString::fromUtf8(titles[mode]));
        (*reload)();
    };
    QObject::connect(direct, &QAbstractButton::clicked, sheet, [=] { setMode(0); });
    QObject::connect(group, &QAbstractButton::clicked, sheet, [=] { setMode(1); });
    QObject::connect(channel, &QAbstractButton::clicked, sheet, [=] { setMode(2); });
    QObject::connect(addContact, &QAbstractButton::clicked, sheet, [=] { newContact(h, [=] { (*reload)(); }); });
    QObject::connect(search->edit(), &QLineEdit::textChanged, sheet, [=] { (*reload)(); });
    QObject::connect(create, &QAbstractButton::clicked, sheet, [=] {
        if (title->text().trimmed().isEmpty())
            return h.toast(*isChannel ? QStringLiteral("Введите название канала") : QStringLiteral("Введите название группы"));
        if (selected->isEmpty() && !*isChannel)
            return h.toast(QStringLiteral("Выберите участников"));
        QJsonArray ids;
        for (const auto &id : *selected)
            ids << id;
        create->setEnabled(false);
        sheet->setBusy(true);
        const QJsonObject p{{"title", title->text().trimmed()}, {"description", about->text().trimmed()}, {"member_ids", ids},
                            {"kind", *isChannel ? "channel" : "group"}};
        Core::instance()->call("create_group", p, sheet, [=](const QJsonValue &v, const CoreError &e) {
            create->setEnabled(true);
            sheet->setBusy(false);
            if (!e.ok())
                return fail(h, e);
            sheet->close();
            h.openChat(v.toObject().value("id").toString());
        });
    });
    (*reload)();
    sheet->open();
}

// ---- devices ----

void devices(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Устройства"));
    auto *list = new RowList(300);
    sheet->body()->addWidget(list);
    sheet->body()->addWidget(mutedText(QStringLiteral("Отзыв устройства прекращает доставку новых сообщений на него. "
                                                      "Уже скачанные на нём копии удалённо не стираются.")));
    sheet->body()->addWidget(mutedText(QStringLiteral("На аккаунте может быть до 10 устройств.")));
    sheet->body()->addWidget(mutedText(QStringLiteral("Новое устройство входит по паролю или по QR-коду, который телефон сканирует с экрана входа.")));

    auto reload = std::make_shared<std::function<void()>>();
    *reload = [=] {
        {
            Core::instance()->call("devices", {}, sheet, [=](const QJsonValue &v, const CoreError &e) {
                if (!e.ok())
                    return fail(h, e);
                list->clear();
                for (const auto &x : v.toObject().value("devices").toArray()) {
                    const QJsonObject d = x.toObject();
                    QString sub = platformName(d.value("platform").toString());
                    if (d.value("current").toBool())
                        sub += QStringLiteral(" · это устройство");
                    const QDateTime seen = QDateTime::fromString(d.value("last_seen_at").toString(), Qt::ISODate).toLocalTime();
                    if (seen.isValid() && !d.value("current").toBool())
                        sub += QStringLiteral(" · активность ") + seen.toString("dd.MM HH:mm");
                    auto *row = new PersonRow(d.value("name").toString(), sub, d.value("id").toString());
                    const bool canRevoke = !d.value("current").toBool();
                    if (!canRevoke)
                        row->setCursor(Qt::ArrowCursor);
                    const QString id = d.value("id").toString();
                    const QString name = d.value("name").toString();
                    QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                        if (!canRevoke)
                            return;
                        QMenu menu(row);
                        menu.addAction(QStringLiteral("Отозвать «%1»").arg(name), sheet, [=] {
                            sheet->setBusy(true);
                            Core::instance()->call("revoke_device", {{"device_id", id}}, sheet, [=](const QJsonValue &, const CoreError &re) {
                                sheet->setBusy(false);
                                if (!re.ok())
                                    return fail(h, re);
                                h.toast(QStringLiteral("Устройство отозвано"));
                                (*reload)();
                            });
                        });
                        menu.exec(row->mapToGlobal(QPoint(60, row->height())));
                    });
                    list->add(row);
                }
                list->fit();
            });
        }
    };
    (*reload)();
    sheet->open();
}

// ---- settings ----

void settings(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Настройки"), 480);
    auto *themeHead = new QHBoxLayout;
    themeHead->addWidget(new ui::Label(QStringLiteral("Тема"), 15, QFont::DemiBold), 1);
    static const char *const names[] = {"Устройство", "День", "Ночь"};
    auto *themeName = new ui::Label(QString::fromUtf8(names[Theme::instance()->mode()]), 15, QFont::DemiBold, ui::Label::Accent);
    themeHead->addWidget(themeName);
    sheet->body()->addLayout(themeHead);
    auto *slider = new ui::ThemeSlider;
    slider->setValue(Theme::instance()->mode(), false);
    QObject::connect(slider, &ui::ThemeSlider::valueChanged, sheet, [=](int v) {
        themeName->setText(QString::fromUtf8(names[v]));
        Theme::instance()->setMode(Theme::Mode(v));
    });
    sheet->body()->addWidget(slider);

    sheet->body()->addSpacing(4);
    sheet->body()->addWidget(new ui::Label(QStringLiteral("Размер текста"), 15, QFont::DemiBold));
    auto *sizes = new QHBoxLayout;
    auto *sizeGroup = new QButtonGroup(sheet);
    const QList<QPair<QString, qreal>> scales{{QStringLiteral("Мельче"), 0.9}, {QStringLiteral("Обычный"), 1.0}, {QStringLiteral("Крупнее"), 1.15}};
    for (int i = 0; i < scales.size(); ++i) {
        auto *b = new ui::Button(scales[i].first, qFuzzyCompare(Theme::instance()->fontScale(), scales[i].second) ? ui::Button::Secondary : ui::Button::Flat);
        sizeGroup->addButton(b, i);
        sizes->addWidget(b);
    }
    QObject::connect(sizeGroup, &QButtonGroup::idClicked, sheet, [=](int id) {
        for (int i = 0; i < scales.size(); ++i)
            static_cast<ui::Button *>(sizeGroup->button(i))->setKind(i == id ? ui::Button::Secondary : ui::Button::Flat);
        Theme::instance()->setFontScale(scales[id].second);
    });
    sheet->body()->addLayout(sizes);

    sheet->body()->addSpacing(4);
    auto *enter = new ui::Toggle(QStringLiteral("Enter отправляет сообщение"));
    enter->setChecked(QSettings().value("composer/enterSends", true).toBool());
    QObject::connect(enter, &QAbstractButton::toggled, sheet, [=](bool on) {
        QSettings().setValue("composer/enterSends", on);
        if (h.enterSendsChanged)
            h.enterSendsChanged(on);
    });
    sheet->body()->addWidget(enter);
    sheet->body()->addWidget(mutedText(QStringLiteral("Перенос строки — Shift+Enter. Если выключить, отправка — Ctrl+Enter.")));
    auto *notify = new ui::Toggle(QStringLiteral("Уведомления о новых сообщениях"));
    notify->setChecked(QSettings().value("notifications/enabled", true).toBool());
    QObject::connect(notify, &QAbstractButton::toggled, sheet, [](bool on) { QSettings().setValue("notifications/enabled", on); });
    sheet->body()->addWidget(notify);
    auto *twoFactor = new ui::Toggle(QStringLiteral("Подтверждение входа"));
    twoFactor->setEnabled(false);
    Core::instance()->call("account_settings", {}, sheet, [=](const QJsonValue &v, const CoreError &) {
        QSignalBlocker b(twoFactor);
        twoFactor->setChecked(v.toObject().value("two_factor").toBool());
        twoFactor->setEnabled(true);
    });
    QObject::connect(twoFactor, &QAbstractButton::toggled, sheet, [=](bool on) {
        Core::instance()->call("set_two_factor", {{"enabled", on}}, sheet, [=](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                fail(h, e);
        });
    });
    sheet->body()->addWidget(twoFactor);
    sheet->body()->addWidget(mutedText(QStringLiteral("При входе на новом устройстве понадобится код. Он придёт в чат «NeoChat уведомления» на ваших устройствах.")));
    sheet->body()->addSpacing(6);
    auto *logout = new ui::Button(QStringLiteral("Выйти из аккаунта"), ui::Button::Danger);
    QObject::connect(logout, &QAbstractButton::clicked, sheet, [=] {
        QMenu menu(logout);
        menu.addAction(QStringLiteral("Выйти: ключи и история на этом компьютере будут удалены"), sheet, [=] {
            sheet->close();
            h.logout();
        });
        menu.exec(logout->mapToGlobal(QPoint(0, logout->height())));
    });
    sheet->body()->addWidget(logout);
    sheet->open();
}

// ---- profile ----

void profile(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Профиль"), 420);
    auto *name = new ui::Label({}, 20, QFont::Bold);
    auto *sub = new ui::Label({}, 13.5, 400, ui::Label::Muted);
    sub->setWordWrap(true);
    sheet->body()->addWidget(name);
    sheet->body()->addWidget(sub);
    auto *edit = new ui::TextField(QStringLiteral("Имя"));
    auto *save = new ui::Button(QStringLiteral("Сохранить имя"), ui::Button::Secondary);
    sheet->body()->addWidget(edit);
    sheet->body()->addWidget(save);
    Core::instance()->call("status", {}, sheet, [=](const QJsonValue &v, const CoreError &) {
        const QJsonObject s = v.toObject();
        const QString nick = "@" + s.value("username").toString();
        const QString display = s.value("display_name").toString();
        name->setText(display.isEmpty() ? nick : display);
        QString line = nick;
        if (!s.value("number").toString().isEmpty())
            line += QStringLiteral(" · ID ") + s.value("number").toString();
        sub->setText(line);
        sub->setTextInteractionFlags(Qt::TextSelectableByMouse);
        edit->setText(display);
    });
    QObject::connect(save, &QAbstractButton::clicked, sheet, [=] {
        const QString n = edit->text().trimmed();
        if (n.isEmpty())
            return h.toast(QStringLiteral("Введите имя"));
        save->setEnabled(false);
        Core::instance()->call("set_profile", {{"display_name", n}}, sheet, [=](const QJsonValue &, const CoreError &e) {
            save->setEnabled(true);
            if (!e.ok())
                return fail(h, e);
            name->setText(n);
            h.toast(QStringLiteral("Имя сохранено"));
        });
    });
    auto *dev = new ui::Button(QStringLiteral("Устройства"), ui::Button::Secondary);
    auto *set = new ui::Button(QStringLiteral("Настройки"), ui::Button::Secondary);
    QObject::connect(dev, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        devices(h);
    });
    QObject::connect(set, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        settings(h);
    });
    sheet->body()->addWidget(dev);
    sheet->body()->addWidget(set);
    sheet->open();
}

// ---- conversation info ----

void conversationInfo(const DialogHost &h, const QString &cid)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Сведения"), 480);
    auto *content = new QWidget;
    auto *lay = new QVBoxLayout(content);
    lay->setContentsMargins(0, 0, 0, 0);
    lay->setSpacing(10);
    sheet->body()->addWidget(content);

    auto build = std::make_shared<std::function<void()>>();
    *build = [=] {
        Core::instance()->call("get_conversation", {{"conversation_id", cid}}, sheet, [=](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return fail(h, e);
            while (QLayoutItem *it = lay->takeAt(0)) {
                if (it->widget())
                    it->widget()->deleteLater();
                delete it;
            }
            const QJsonObject c = v.toObject();
            const bool group = c.value("kind").toString() == "group" || c.value("kind").toString() == "channel";
            sheet->setTitle(c.value("title").toString());
            if (!c.value("notice").isNull() && !c.value("notice").toString().isEmpty()) {
                auto *n = new ui::Label(c.value("notice").toString(), 13.5, QFont::DemiBold, ui::Label::Warning);
                n->setWordWrap(true);
                lay->addWidget(n);
            }
            if (!group) {
                const QString peer = c.value("peer_user_id").toString();
                Core::instance()->call("contact_info", {{"user_id", peer}}, sheet, [=](const QJsonValue &iv, const CoreError &ie) {
                    if (!ie.ok())
                        return fail(h, ie);
                    const QJsonObject info = iv.toObject();
                    lay->addWidget(new ui::Label("@" + info.value("username").toString(), 14, 400, ui::Label::Muted));
                    lay->addWidget(new ui::Label(QStringLiteral("Код безопасности"), 15, QFont::DemiBold));
                    lay->addWidget(mutedText(QStringLiteral("Сравните код с собеседником при встрече или по другому надёжному каналу. "
                                                            "Совпадение означает, что сообщения шифруются для его настоящих ключей.")));
                    auto *num = new ui::Label(info.value("safety_number").toString(), 15, QFont::DemiBold, ui::Label::Accent);
                    num->setMono(true);
                    num->setWordWrap(true);
                    num->setTextInteractionFlags(Qt::TextSelectableByMouse);
                    num->setAccessibleName(QStringLiteral("Код безопасности"));
                    lay->addWidget(num);
                    if (info.value("key_changed").toBool()) {
                        auto *accept = new ui::Button(QStringLiteral("Код сверен, принять новый ключ"));
                        QObject::connect(accept, &QAbstractButton::clicked, sheet, [=] {
                            Core::instance()->call("accept_key_change", {{"user_id", peer}}, sheet, [=](const QJsonValue &, const CoreError &ae) {
                                if (!ae.ok())
                                    return fail(h, ae);
                                h.toast(QStringLiteral("Новый ключ принят. Сверьте код ещё раз при возможности"));
                                (*build)();
                            });
                        });
                        lay->addWidget(accept);
                    } else if (info.value("verified").toBool()) {
                        lay->addWidget(new ui::Label(QStringLiteral("Ключ сверен"), 14, QFont::DemiBold, ui::Label::Accent));
                    } else {
                        lay->addWidget(new ui::Label(QStringLiteral("Ключ ещё не сверен"), 14, 400, ui::Label::Muted));
                        auto *verify = new ui::Button(QStringLiteral("Отметить как сверенный"), ui::Button::Secondary);
                        QObject::connect(verify, &QAbstractButton::clicked, sheet, [=] {
                            Core::instance()->call("verify_contact", {{"user_id", peer}}, sheet, [=](const QJsonValue &, const CoreError &ve) {
                                if (!ve.ok())
                                    return fail(h, ve);
                                (*build)();
                            });
                        });
                        lay->addWidget(verify);
                    }
                    lay->addWidget(mutedText(QStringLiteral("Файлы хранятся на сервере в зашифрованном виде 30 дней.")));
                });
                return;
            }
            const bool channel = c.value("kind").toString() == "channel";
            const QJsonArray members = c.value("members").toArray();
            const bool owner = c.value("is_owner").toBool();
            const bool admin = c.value("is_admin").toBool();
            const bool active = !c.value("left").toBool() && !c.value("archived").toBool();
            const QString ownerId = c.value("owner_id").toString();
            QSet<QString> admins;
            for (const auto &a : c.value("admins").toArray())
                admins.insert(a.toString());
            // Everyone except me, optionally without one person or with one more.
            auto others = [members](const QString &without, const QString &with) {
                QJsonArray ids;
                for (const auto &y : members) {
                    const QJsonObject o = y.toObject();
                    if (!o.value("me").toBool() && o.value("user_id").toString() != without)
                        ids << o.value("user_id");
                }
                if (!with.isEmpty())
                    ids << with;
                return ids;
            };
            auto setMembers = [=](QJsonObject p, const QString &done) {
                p["conversation_id"] = cid;
                Core::instance()->call("group_set_members", p, sheet, [=](const QJsonValue &, const CoreError &ge) {
                    if (!ge.ok())
                        return fail(h, ge);
                    h.toast(done);
                    (*build)();
                });
            };
            auto adminIds = [admins](const QString &without, const QString &with) {
                QJsonArray ids;
                for (const auto &a : admins)
                    if (a != without)
                        ids << a;
                if (!with.isEmpty())
                    ids << with;
                return ids;
            };

            lay->addWidget(new ui::Label(channel ? QStringLiteral("Канал") : QStringLiteral("Группа"), 14, 400, ui::Label::Muted));
            const QString description = c.value("description").toString();
            if (!description.isEmpty()) {
                auto *d = new ui::Label(description, 14.5);
                d->setWordWrap(true);
                d->setTextInteractionFlags(Qt::TextSelectableByMouse);
                lay->addWidget(d);
            }
            if (c.value("can_edit_info").toBool() && active) {
                auto *rename = new ui::TextField(channel ? QStringLiteral("Название канала") : QStringLiteral("Название группы"));
                rename->setText(c.value("title").toString());
                auto *about = new ui::TextField(QStringLiteral("Описание"));
                about->setText(description);
                auto *save = new ui::Button(QStringLiteral("Сохранить название и описание"), ui::Button::Secondary);
                lay->addWidget(rename);
                lay->addWidget(about);
                lay->addWidget(save);
                QObject::connect(save, &QAbstractButton::clicked, sheet, [=] {
                    const QJsonObject p{{"conversation_id", cid}, {"title", rename->text().trimmed()}, {"description", about->text().trimmed()}};
                    Core::instance()->call("group_set_info", p, sheet, [=](const QJsonValue &, const CoreError &re) {
                        if (!re.ok())
                            return fail(h, re);
                        h.toast(QStringLiteral("Сохранено"));
                        (*build)();
                    });
                });
            }

            lay->addWidget(new ui::Label((channel ? QStringLiteral("Подписчики: %1") : QStringLiteral("Участники: %1")).arg(c.value("member_count").toInt()), 15, QFont::DemiBold));
            auto *list = new RowList(260);
            for (const auto &x : members) {
                const QJsonObject m = x.toObject();
                const QString uid = m.value("user_id").toString();
                QString sub = m.value("me").toBool() ? QStringLiteral("это вы") : "@" + m.value("username").toString();
                if (uid == ownerId)
                    sub += QStringLiteral(" · владелец");
                else if (admins.contains(uid))
                    sub += QStringLiteral(" · админ");
                if (!m.value("tag").toString().isEmpty())
                    sub += QStringLiteral(" · ") + m.value("tag").toString();
                auto *row = new PersonRow(m.value("display_name").toString(), sub, uid);
                if (m.value("key_changed").toBool())
                    row->warning = QStringLiteral("Ключ изменился — сверьте код в личном чате");
                list->add(row);
                if (!admin || !active)
                    continue;
                const QString name = m.value("display_name").toString();
                const QString tag = m.value("tag").toString();
                const bool me = m.value("me").toBool();
                QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                    QMenu menu(row);
                    menu.addAction(QStringLiteral("Тег…"), sheet, [=] {
                        auto *ts = new Sheet(h.host, QStringLiteral("Тег: ") + name, 380);
                        auto *field = new ui::TextField(QStringLiteral("Например, «дизайн» или «модератор»"));
                        field->setText(tag);
                        auto *ok = new ui::Button(QStringLiteral("Сохранить"));
                        ts->body()->addWidget(field);
                        ts->body()->addWidget(mutedText(QStringLiteral("Тег виден всем рядом с именем. Пустое поле убирает тег.")));
                        ts->body()->addWidget(ok);
                        auto go = [=] {
                            Core::instance()->call("group_set_tag", {{"conversation_id", cid}, {"user_id", uid}, {"tag", field->text().trimmed()}}, ts,
                                                   [=](const QJsonValue &, const CoreError &te) {
                                                       if (!te.ok())
                                                           return fail(h, te);
                                                       ts->close();
                                                       (*build)();
                                                   });
                        };
                        QObject::connect(ok, &QAbstractButton::clicked, ts, go);
                        QObject::connect(field->edit(), &QLineEdit::returnPressed, ts, go);
                        ts->open();
                    });
                    if (owner && !me) {
                        if (admins.contains(uid))
                            menu.addAction(QStringLiteral("Снять права админа"), sheet, [=] {
                                setMembers({{"member_ids", others({}, {})}, {"admin_ids", adminIds(uid, {})}}, QStringLiteral("Права админа сняты"));
                            });
                        else
                            menu.addAction(QStringLiteral("Назначить админом"), sheet, [=] {
                                setMembers({{"member_ids", others({}, {})}, {"admin_ids", adminIds({}, uid)}}, QStringLiteral("Назначен админ"));
                            });
                    }
                    if (!me && uid != ownerId && (owner || !admins.contains(uid)))
                        menu.addAction(channel ? QStringLiteral("Удалить из канала") : QStringLiteral("Исключить из группы"), sheet, [=] {
                            setMembers({{"member_ids", others(uid, {})}}, QStringLiteral("Исключён. Новые сообщения ему не придут"));
                        });
                    menu.exec(row->mapToGlobal(QPoint(60, row->height())));
                });
            }
            list->fit();
            lay->addWidget(list);
            if (admin && active) {
                auto *add = new ui::Button(channel ? QStringLiteral("Добавить подписчиков") : QStringLiteral("Добавить участников"), ui::Button::Secondary);
                lay->addWidget(add);
                QObject::connect(add, &QAbstractButton::clicked, sheet, [=] {
                    Core::instance()->call("list_contacts", {}, sheet, [=](const QJsonValue &lv, const CoreError &le) {
                        if (!le.ok())
                            return fail(h, le);
                        QSet<QString> current;
                        for (const auto &y : members)
                            current.insert(y.toObject().value("user_id").toString());
                        QMenu menu(add);
                        for (const auto &y : lv.toArray()) {
                            const QJsonObject ct = y.toObject();
                            const QString uid = ct.value("user_id").toString();
                            if (current.contains(uid))
                                continue;
                            menu.addAction(ct.value("display_name").toString() + "  @" + ct.value("username").toString(), sheet, [=] {
                                setMembers({{"member_ids", others({}, uid)}}, QStringLiteral("Добавлен. Прошлые сообщения ему не видны"));
                            });
                        }
                        if (menu.isEmpty())
                            menu.addAction(QStringLiteral("Все контакты уже здесь"))->setEnabled(false);
                        menu.exec(add->mapToGlobal(QPoint(0, add->height())));
                    });
                });
            }
            if (admin && active && !channel) {
                lay->addWidget(new ui::Label(QStringLiteral("Права участников"), 15, QFont::DemiBold));
                const QJsonObject st = c.value("settings").toObject();
                const QList<QPair<QString, QString>> rights{{"members_can_send", QStringLiteral("Писать сообщения")},
                                                            {"members_can_media", QStringLiteral("Отправлять фото и файлы")},
                                                            {"members_can_polls", QStringLiteral("Создавать опросы")},
                                                            {"members_can_info", QStringLiteral("Менять название и описание")}};
                for (const auto &r : rights) {
                    auto *t = new ui::Toggle(r.second);
                    t->setChecked(st.value(r.first).toBool());
                    const QString key = r.first;
                    QObject::connect(t, &QAbstractButton::toggled, sheet, [=](bool on) {
                        Core::instance()->call("group_set_settings", {{"conversation_id", cid}, {key, on}}, sheet, [=](const QJsonValue &, const CoreError &se) {
                            if (!se.ok()) {
                                fail(h, se);
                                (*build)();
                            }
                        });
                    });
                    lay->addWidget(t);
                }
                lay->addWidget(mutedText(QStringLiteral("Владелец и админы могут всё. Сервер не пропускает сообщения тех, кому писать нельзя.")));
            } else if (channel) {
                lay->addWidget(mutedText(QStringLiteral("В канале пишут только владелец и админы. Подписчики читают и голосуют в опросах.")));
            }
            lay->addWidget(mutedText(QStringLiteral("Файлы хранятся на сервере в зашифрованном виде 30 дней.")));
            if (!c.value("left").toBool()) {
                auto *leave = new ui::Button(channel ? QStringLiteral("Выйти из канала") : QStringLiteral("Выйти из группы"), ui::Button::Danger);
                QObject::connect(leave, &QAbstractButton::clicked, sheet, [=] {
                    QMenu menu(leave);
                    const QString what = owner ? (channel ? QStringLiteral("Выйти: канал станет архивным для всех") : QStringLiteral("Выйти: группа станет архивной для всех"))
                                               : QStringLiteral("Выйти: новые сообщения перестанут приходить");
                    menu.addAction(what, sheet, [=] {
                        Core::instance()->call("group_leave", {{"conversation_id", cid}}, sheet, [=](const QJsonValue &, const CoreError &ge) {
                            if (!ge.ok())
                                return fail(h, ge);
                            sheet->close();
                        });
                    });
                    menu.exec(leave->mapToGlobal(QPoint(0, leave->height())));
                });
                lay->addWidget(leave);
            }
        });
    };
    (*build)();
    sheet->open();
}

// ---- polls ----

void newPoll(const DialogHost &h, const QString &cid)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Новый опрос"), 440);
    auto *question = new ui::TextField(QStringLiteral("Вопрос"));
    sheet->body()->addWidget(question);
    sheet->body()->addWidget(new ui::Label(QStringLiteral("Варианты ответа"), 15, QFont::DemiBold));
    auto *box = new QVBoxLayout;
    box->setSpacing(8);
    sheet->body()->addLayout(box);
    auto *more = new ui::Button(QStringLiteral("Добавить вариант"), ui::Button::Flat);
    auto options = std::make_shared<QList<ui::TextField *>>();
    auto add = [=] {
        if (options->size() >= 10)
            return;
        auto *f = new ui::TextField(QStringLiteral("Вариант %1").arg(options->size() + 1));
        box->addWidget(f);
        *options << f;
        more->setVisible(options->size() < 10);
        f->edit()->setFocus();
    };
    add();
    add();
    question->edit()->setFocus();
    sheet->body()->addWidget(more);
    auto *anonymous = new ui::Toggle(QStringLiteral("Анонимное голосование"));
    anonymous->setChecked(true);
    auto *multiple = new ui::Toggle(QStringLiteral("Несколько ответов"));
    sheet->body()->addWidget(anonymous);
    sheet->body()->addWidget(multiple);
    auto *send = new ui::Button(QStringLiteral("Отправить опрос"));
    sheet->body()->addWidget(send);
    QObject::connect(more, &QAbstractButton::clicked, sheet, add);
    QObject::connect(send, &QAbstractButton::clicked, sheet, [=] {
        QJsonArray opts;
        for (auto *f : *options)
            if (!f->text().trimmed().isEmpty())
                opts << f->text().trimmed();
        if (question->text().trimmed().isEmpty())
            return h.toast(QStringLiteral("Введите вопрос"));
        if (opts.size() < 2)
            return h.toast(QStringLiteral("Нужно хотя бы два варианта"));
        send->setEnabled(false);
        const QJsonObject p{{"conversation_id", cid}, {"question", question->text().trimmed()}, {"options", opts},
                            {"anonymous", anonymous->isChecked()}, {"multiple", multiple->isChecked()}};
        Core::instance()->call("send_poll", p, sheet, [=](const QJsonValue &, const CoreError &e) {
            send->setEnabled(true);
            if (!e.ok())
                return fail(h, e);
            sheet->close();
        });
    });
    sheet->open();
}

} // namespace dialogs
