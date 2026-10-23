#include "profiles.h"
#include "core.h"
#include "calls.h"
#include "filepicker.h"
#include "messages.h"
#include "popupmenu.h"
#include "profilestyle.h"
#include "sheet.h"
#include "theme.h"
#include "widgets.h"
#include "transition.h"
#include <QApplication>
#include <QClipboard>
#include <QDesktopServices>
#include <QEvent>
#include <QDir>
#include <QHBoxLayout>
#include <QImageReader>
#include <QMouseEvent>
#include <QPainter>
#include <QPointer>
#include <QPropertyAnimation>
#include <QScrollArea>
#include <QStringList>
#include <QTimer>
#include <QShortcut>
#include <QTemporaryFile>
#include <QThreadPool>
#include <QVBoxLayout>
#include <memory>

namespace {
class ProfileAvatar : public QAbstractButton {
public:
    QString name, seed, path;
    ProfileAvatar() { setFixedSize(112,112); setAccessibleName(QStringLiteral("Открыть фото профиля")); }
    void paintEvent(QPaintEvent *) override { QPainter p(this); p.setRenderHint(QPainter::Antialiasing); ui::paintAvatar(&p,QRectF(rect()),name,seed,path); }
};

class ProfileCard : public QWidget {
    void paintEvent(QPaintEvent *) override {
        QPainter p(this); p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(Theme::p().border,1)); p.setBrush(Theme::p().raised);
        p.drawRoundedRect(QRectF(rect()).adjusted(.5,.5,-.5,-.5),18,18);
    }
};

class ProfileField : public QAbstractButton {
public:
    ui::Label *value;
    ProfileField(const QString &text,const QString &caption,bool wrap=false) {
        setText(text); setAccessibleName(caption); setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus); setSizePolicy(QSizePolicy::Expanding,QSizePolicy::Preferred);
        auto *column=new QVBoxLayout(this); column->setContentsMargins(4,8,4,8); column->setSpacing(4);
        value=new ui::Label(text,16); value->setTextFormat(Qt::PlainText); value->setWordWrap(wrap);
        value->setSizePolicy(QSizePolicy::Ignored,QSizePolicy::Preferred);
        value->setAttribute(Qt::WA_TransparentForMouseEvents); column->addWidget(value);
        auto *label=new ui::Label(caption,12,400,ui::Label::Muted);
        label->setAttribute(Qt::WA_TransparentForMouseEvents); column->addWidget(label);
    }
    void paintEvent(QPaintEvent *) override {
        if(hasFocus()) {QPainter p(this);p.setRenderHint(QPainter::Antialiasing);ui::paintFocusRing(&p,QRectF(rect()).adjusted(1,1,-1,-1),10);}
    }
};

void uploadNext(const DialogHost &h, QPointer<QWidget> host, QStringList files, int at, std::function<void()> done)
{
    if (!host) return;
    if (at >= files.size()) { done(); return; }
    QThreadPool::globalInstance()->start([=] {
        QImageReader reader(files[at]); reader.setAutoTransform(true);
        if (reader.size().isValid()) reader.setScaledSize(reader.size().scaled(4096,4096,Qt::KeepAspectRatio));
        const QImage image = reader.read();
        auto temp = std::make_shared<QTemporaryFile>(QDir::tempPath()+"/neochat-profile-XXXXXX.jpg");
        const bool ok = !image.isNull() && temp->open() && image.save(temp.get(),"JPEG",90) && temp->size() <= (8<<20);
        temp->close();
        if (host) QMetaObject::invokeMethod(host,[=] {
            if (!host) return;
            if (!ok) return h.toast(QStringLiteral("Не удалось подготовить фото до 8 МБ"));
            Core::instance()->call("set_profile_photo",{{"path",temp->fileName()}},host,[=](const QJsonValue &,const CoreError &e) {
                if (!e.ok()) return h.toast(e.message);
                uploadNext(h,host,files,at+1,done);
            });
        },Qt::QueuedConnection);
    });
}
void pickPhotos(const DialogHost &h, QWidget *host, std::function<void()> done)
{
    const QPointer<QWidget> guard(host);
    filepicker::open(host,QStringLiteral("Фото профиля"),{"jpg","jpeg","png","webp","heic"},true,[=](const QStringList &files) { uploadNext(h,guard,files.mid(0,10),0,done); });
}

class GalleryImage : public QWidget {
public:
    QImage image;
    std::function<void(int)> turn;
    QPoint start;
    GalleryImage() { setMinimumHeight(320); }
    void paintEvent(QPaintEvent *) override {
        QPainter p(this); p.setRenderHint(QPainter::SmoothPixmapTransform); p.fillRect(rect(),QColor(4,9,20));
        if (!image.isNull()) { const QSizeF size = QSizeF(image.size()).scaled(QSizeF(width()-16,height()-16),Qt::KeepAspectRatio); p.drawImage(QRectF(QPointF(width()/2.0-size.width()/2,height()/2.0-size.height()/2),size),image); }
    }
    void mousePressEvent(QMouseEvent *e) override { start=e->pos(); }
    void mouseReleaseEvent(QMouseEvent *e) override { if (qAbs(e->pos().x()-start.x())>50 && turn) turn(e->pos().x()>start.x()?-1:1); }
};

class MediaTile : public QAbstractButton {
public:
    QPixmap image;
    explicit MediaTile(QPixmap pix) : image(std::move(pix))
    {
        setFixedSize(96, 96);
        setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
        setAccessibleName(QStringLiteral("Открыть фото"));
        setCursor(Qt::PointingHandCursor);
    }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        p.setRenderHint(QPainter::Antialiasing);
        if (!image.isNull()) {
            // Keep the photo's own shape. The square's empty sides stay transparent.
            const QPixmap scaled = image.scaled(size(), Qt::KeepAspectRatio, Qt::SmoothTransformation);
            p.drawPixmap((width() - scaled.width()) / 2, (height() - scaled.height()) / 2, scaled);
        }
        if (hasFocus())
            ui::paintFocusRing(&p, QRectF(rect()).adjusted(2, 2, -2, -2), 12);
    }
};

// As many tiles per row as fit (six in a profile), the rows centered as one block;
// a single short row is centered by its own tiles.
class MediaGrid : public QWidget {
public:
    void add(QWidget *tile)
    {
        tile->setParent(this);
        m_tiles.append(tile);
        updateGeometry();
    }
    bool hasHeightForWidth() const override { return true; }
    int heightForWidth(int w) const override
    {
        const int cols = columns(w);
        const int rows = (int(m_tiles.size()) + cols - 1) / cols;
        return rows > 0 ? rows * (Tile + Gap) - Gap : 0;
    }
    QSize sizeHint() const override
    {
        const int w = 6 * (Tile + Gap) - Gap;
        return {w, heightForWidth(w)};
    }
    QSize minimumSizeHint() const override { return {Tile, 0}; }

protected:
    void resizeEvent(QResizeEvent *) override
    {
        const int cols = columns(width());
        const int used = qMin(cols, int(m_tiles.size()));
        const int left = (width() - (used * (Tile + Gap) - Gap)) / 2;
        for (int i = 0; i < m_tiles.size(); ++i)
            m_tiles[i]->move(left + i % cols * (Tile + Gap), i / cols * (Tile + Gap));
    }

private:
    static constexpr int Tile = 96;
    static constexpr int Gap = 8;
    static int columns(int w) { return qMax(1, (w + Gap) / (Tile + Gap)); }
    QList<QWidget *> m_tiles;
};

void showPhotos(const DialogHost &h, QWidget *owner, const QString &uid, bool own, std::function<void()> changed)
{
    Core::instance()->call("profile_photos",{{"user_id",uid}},owner,[=](const QJsonValue &v,const CoreError &e) {
        if (!e.ok()) return h.toast(e.message);
        const auto photos=v.toArray();
        if (photos.isEmpty()) { if (own) pickPhotos(h,owner,changed); return; }
        auto *sheet=new Sheet(h.host,QStringLiteral("Фото профиля"),1000);
        auto *image=new GalleryImage; image->setMinimumHeight(qMax(260,h.host->height()-220));
        sheet->body()->addWidget(image);
        auto index=std::make_shared<int>(0);
        auto load=[=](int next) {
            if (next<0 || next>=photos.size()) return;
            *index=next; image->image={}; image->update();
            sheet->setTitle(QStringLiteral("Фото %1 из %2").arg(next+1).arg(photos.size()));
            Core::instance()->call("profile_photo",{{"user_id",uid},{"photo_id",photos[next].toObject().value("id")}},sheet,[=](const QJsonValue &result,const CoreError &error) {
                if (!error.ok()) return h.toast(error.message);
                if (*index!=next) return;
                QImageReader reader(result.toObject().value("path").toString()); reader.setAutoTransform(true);
                if(reader.size().isValid())reader.setScaledSize(reader.size().scaled(2732,2732,Qt::KeepAspectRatio));
                image->image=reader.read(); image->update();
            });
        };
        image->turn=[=](int d) {load(*index+d);};
        auto *row=new QHBoxLayout;
        for (const auto &pair : {qMakePair(-1,QStringLiteral("← Назад")),qMakePair(1,QStringLiteral("Вперёд →"))}) {
            auto *b=new ui::Button(pair.second,ui::Button::Secondary); row->addWidget(b);
            QObject::QObject::connect(b,&QAbstractButton::clicked,sheet,[=] { load(*index+pair.first); });
            auto *key=new QShortcut(pair.first<0?QKeySequence(Qt::Key_Left):QKeySequence(Qt::Key_Right),sheet);
            QObject::QObject::connect(key,&QShortcut::activated,sheet,[=] {load(*index+pair.first);});
        }
        if (own) {
            auto *edit=new ui::Button(QStringLiteral("✎ Изменить"),ui::Button::Secondary); row->addWidget(edit);
            QObject::QObject::connect(edit,&QAbstractButton::clicked,sheet,[=] {
                auto *menu=new PopupMenu(sheet);
                menu->addAction(QStringLiteral("Добавить фото"),sheet,[=] {pickPhotos(h,sheet,[=] {sheet->close();changed();});});
                menu->addAction(QStringLiteral("Удалить это фото"),sheet,[=] {
                    Core::instance()->call("delete_profile_photo",{{"photo_id",photos[*index].toObject().value("id")}},sheet,[=](const QJsonValue &,const CoreError &err) {if(!err.ok())h.toast(err.message);else {sheet->close();changed();}});
                });
                menu->popup(edit->mapToGlobal(QPoint(0,edit->height())));
            });
        }
        sheet->body()->addLayout(row); sheet->open(); load(0);
    });
}

void clearLayout(QLayout *layout)
{
    while(auto *item=layout->takeAt(0)) { if(item->widget())delete item->widget(); if(item->layout())clearLayout(item->layout());delete item; }
}

class TabPill : public QWidget {
public:
    explicit TabPill(QWidget *parent = nullptr) : QWidget(parent) { setAttribute(Qt::WA_TransparentForMouseEvents); hide(); }
    void moveTo(QWidget *tab) {
        m_target = tab;
        const QRect r = tab->geometry().adjusted(0, 4, 0, -4);
        if (!isVisible() || Theme::ms(1) == 0 || geometry().width() <= 1) {
            setGeometry(r);
            show();
            lower();
            return;
        }
        auto *a = new QPropertyAnimation(this, "geometry", this);
        a->setDuration(Theme::ms(280));
        a->setEasingCurve(Theme::surfaceEasing());
        a->setStartValue(geometry());
        a->setEndValue(r);
        a->start(QAbstractAnimation::DeleteWhenStopped);
        show();
        lower();
    }
protected:
    void paintEvent(QPaintEvent *) override {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(Qt::NoPen);
        p.setBrush(Theme::p().raised);
        p.drawRoundedRect(QRectF(rect()), 16, 16);
    }
    bool eventFilter(QObject *o, QEvent *e) override {
        if (o == parent() && e->type() == QEvent::Resize && m_target) {
            const QRect r = m_target->geometry().adjusted(0, 4, 0, -4);
            if (r != geometry())
                setGeometry(r);
            show();
            lower();
        }
        return false;
    }
private:
    QPointer<QWidget> m_target;
};

int profileTabIndex(const QString &key) {
    const QStringList order{QStringLiteral("media"), QStringLiteral("files"), QStringLiteral("links"), QStringLiteral("groups"), QStringLiteral("privacy")};
    const int i = order.indexOf(key);
    return i < 0 ? 0 : i;
}

class ProfileContent : public QWidget {
public:
    DialogHost host;
    QString uid, kind;
    QJsonObject profile;
    QJsonArray items;
    QVBoxLayout *column;
    int generation=0;
    int tabIndex=0;
    int slide=0;
    ProfileContent(const DialogHost &h,QJsonObject p) : host(h),uid(p.value("user_id").toString()),profile(p) { column=new QVBoxLayout(this);column->setContentsMargins(0,0,0,0); }
    void select(const QString &key) {
        const int next = profileTabIndex(key);
        slide = next == tabIndex ? 0 : (next > tabIndex ? 1 : -1);
        tabIndex = next;
        const auto motion = slide < 0 ? ui::Motion::ShiftRight : ui::Motion::ShiftLeft;
        if (slide != 0 && (key == QLatin1String("privacy") || key == QLatin1String("groups")))
            ui::transition(this, rect(), motion, [this, key] { build(key); });
        else
            build(key);
    }
    void build(const QString &key) {
        kind=key;items={};++generation;
        if (key != QLatin1String("privacy") && key != QLatin1String("groups")) { load(); return; }
        clearLayout(column);
        if(key=="privacy") {
            column->addWidget(new ui::Label(QStringLiteral("Сквозное шифрование"),18,QFont::DemiBold));
            auto *code=new ui::Label(profile.value("me").toBool()?QStringLiteral("Коды безопасности доступны в профилях собеседников"):profile.value("safety_number").toString(),17,QFont::DemiBold,ui::Label::Accent);
            code->setWordWrap(true);code->setTextInteractionFlags(Qt::TextSelectableByMouse);column->addWidget(code);
            if(!profile.value("me").toBool()) {
                auto *hint=new ui::Label(QStringLiteral("Сравните код с собеседником по другому надёжному каналу."),14,400,ui::Label::Muted);hint->setWordWrap(true);column->addWidget(hint);
                const bool changed=profile.value("key_changed").toBool();
                if(changed || !profile.value("verified").toBool()) {
                    auto *b=new ui::Button(changed?QStringLiteral("Код сверен, принять новый ключ"):QStringLiteral("Отметить как сверенный"),ui::Button::Secondary);column->addWidget(b);
                    QObject::connect(b,&QAbstractButton::clicked,this,[=] {Core::instance()->call(changed?"accept_key_change":"verify_contact",{{"user_id",uid}},this,[=](const QJsonValue &,const CoreError &e){host.toast(e.ok()?QStringLiteral("Ключ сверен"):e.message);});});
                }
            }
        } else if(key=="groups") {
            const auto groups=profile.value("groups").toArray();
            if(groups.isEmpty())column->addWidget(new ui::Label(QStringLiteral("Общих групп пока нет"),15,400,ui::Label::Muted));
            for(const auto &v:groups) {auto g=v.toObject();auto *b=new ui::Button(g.value("title").toString(),ui::Button::Secondary);column->addWidget(b);QObject::connect(b,&QAbstractButton::clicked,this,[=]{Sheet::closeAll(host.host);host.openChat(g.value("id").toString());});}
        }
    }
    void load() {
        const int gen=generation;
        QJsonObject p{{"user_id",uid},{"kind",kind},{"limit",48}};
        if(!items.isEmpty())p["before"]=items.last().toObject().value("local_id");
        Core::instance()->call("profile_items",p,this,[=](const QJsonValue &v,const CoreError &e) {
            if(gen!=generation)return;
            if(!e.ok())return host.toast(e.message);
            const bool arrived = items.isEmpty();
            for(const auto &m:v.toArray())items.append(m);
            const auto motion = slide < 0 ? ui::Motion::ShiftRight : ui::Motion::ShiftLeft;
            const auto fill = [=] { clearLayout(column);
            if(items.isEmpty())column->addWidget(new ui::Label(QStringLiteral("Общих материалов в этом разделе пока нет"),15,400,ui::Label::Muted));
            auto *grid=kind=="media"?new MediaGrid:nullptr;
            for(int i=0;i<items.size();++i) {
                const auto m=items[i].toObject();
                if(kind=="media") {
                    auto *b=new MediaTile(ImageCache::instance()->thumb(m));grid->add(b);
                    QObject::connect(b,&QAbstractButton::clicked,this,[=]{Core::instance()->call("download_file",{{"message_id",m.value("id")}},this,[=](const QJsonValue &r,const CoreError &err){if(!err.ok())host.toast(err.message);else if(!showPhotoFile(window(),r.toObject().value("path").toString()))host.toast(QStringLiteral("Не удалось открыть фото"));});});
                } else {
                    QString text=m.value("file").toObject().value("name").toString(); QString url;
                    if(kind=="links")for(const auto &span:m.value("spans").toArray()){url=span.toObject().value("link").toString();if(!url.isEmpty())break;}
                    if(kind=="links")text=url;
                    auto *b=new ui::Button(text,ui::Button::Secondary);column->addWidget(b);
                    QObject::connect(b,&QAbstractButton::clicked,this,[=]{if(!url.isEmpty())QDesktopServices::openUrl(QUrl(url));else Core::instance()->call("download_file",{{"message_id",m.value("id")}},this,[=](const QJsonValue &r,const CoreError &err){if(!err.ok())host.toast(err.message);else QDesktopServices::openUrl(QUrl::fromLocalFile(r.toObject().value("path").toString()));});});
                }
            }
            if(grid)column->addWidget(grid);
            if(v.toArray().size()==48){auto *b=new ui::Button(QStringLiteral("Показать ещё"),ui::Button::Flat);column->addWidget(b);QObject::connect(b,&QAbstractButton::clicked,this,[this]{load();});}
            };
            if (arrived && slide != 0)
                ui::transition(this, rect(), motion, fill);
            else
                fill();
        });
    }
};
}

void dialogs::personProfile(const DialogHost &h,const QString &userId)
{
    auto *sheet=new Sheet(h.host,QStringLiteral("Профиль"),720);
    auto *body=sheet->body();
    Core::instance()->call("user_profile",userId.isEmpty()?QJsonObject():QJsonObject{{"user_id",userId}},sheet,[=](const QJsonValue &v,const CoreError &e) {
        if(!e.ok())return h.toast(e.message);
        const auto p=v.toObject();const bool own=p.value("me").toBool();const QString uid=p.value("user_id").toString();
        auto *avatar=new ProfileAvatar;avatar->name=p.value("display_name").toString();avatar->seed=uid;avatar->path=p.value("avatar_path").toString();
        auto *cover=new profilestyle::Cover(avatar->name,uid,avatar->path);cover->setColors(profilestyle::colors(p.value("header").toString(),uid));cover->setAvatarWidget(avatar);body->addWidget(cover);
        QObject::connect(avatar,&QAbstractButton::clicked,sheet,[=]{showPhotos(h,sheet,uid,own,[=]{sheet->close();dialogs::personProfile(h,uid);});});
        auto *name=new ui::Label(avatar->name.isEmpty()?"@"+p.value("username").toString():avatar->name,28,QFont::Bold);name->setTextFormat(Qt::PlainText);name->setWordWrap(true);name->setAlignment(Qt::AlignCenter);name->setColorOverride(profilestyle::nameOn(p.value("name_color").toString(),Theme::p().surface));body->addWidget(name);
        if(!own) {
            auto *actions=new QHBoxLayout;
            auto *chat=new ui::Button(QStringLiteral("Написать"),ui::Button::Secondary);actions->addWidget(chat);
            QObject::connect(chat,&QAbstractButton::clicked,sheet,[=] { Core::instance()->call("open_direct",{{"user_id",uid}},sheet,[=](const QJsonValue &c,const CoreError &err) { if(!err.ok())h.toast(err.message);else {sheet->close();h.openChat(c.toObject().value("id").toString());} }); });
            for(bool video:{false,true}) {
                auto *call=new ui::Button(video?QStringLiteral("Видео"):QStringLiteral("Звонок"),ui::Button::Secondary);actions->addWidget(call);
                QObject::connect(call,&QAbstractButton::clicked,sheet,[=] { Core::instance()->call("open_direct",{{"user_id",uid}},sheet,[=](const QJsonValue &c,const CoreError &err) { if(!err.ok())h.toast(err.message);else {sheet->close();CallController::instance()->start(c.toObject(),video);} }); });
            }
            body->addLayout(actions);
        } else {
            auto *actions=new QHBoxLayout;
            auto *edit=new ui::Button(QStringLiteral("Изменить профиль"),ui::Button::Secondary);actions->addWidget(edit);QObject::connect(edit,&QAbstractButton::clicked,sheet,[=]{sheet->close();dialogs::editProfile(h);});
            auto *look=new ui::Button(QStringLiteral("Оформление"),ui::Button::Secondary);actions->addWidget(look);QObject::connect(look,&QAbstractButton::clicked,sheet,[=]{sheet->close();dialogs::profileStyle(h,p,[=]{dialogs::personProfile(h);});});
            auto *settings=new ui::Button(QStringLiteral("Настройки"),ui::Button::Secondary);actions->addWidget(settings);QObject::connect(settings,&QAbstractButton::clicked,sheet,[=]{sheet->close();dialogs::settings(h);});
            body->addLayout(actions);
        }
        auto *info=new ProfileCard;info->setObjectName("profileInfo");auto *fields=new QVBoxLayout(info);fields->setContentsMargins(16,8,16,8);fields->setSpacing(0);
        auto *handleRow=new QHBoxLayout;
        auto *nick=new ProfileField("@"+p.value("username").toString(),QStringLiteral("Имя пользователя"),true);nick->setObjectName("profileUsername");handleRow->addWidget(nick,1);
        nick->value->setToolTip(nick->text());
        QObject::connect(nick,&QAbstractButton::clicked,sheet,[=]{QApplication::clipboard()->setText(nick->text());h.toast(QStringLiteral("Имя пользователя скопировано"));});
        auto *qr=new ui::IconButton("qr",QStringLiteral("QR-код профиля"));qr->setObjectName("profileQr");handleRow->addWidget(qr,0,Qt::AlignVCenter);
        QObject::connect(qr,&QAbstractButton::clicked,sheet,[=]{dialogs::profileQr(h,uid);});
        if(!own && !p.value("is_contact").toBool()){auto *add=new ui::IconButton("person-add",QStringLiteral("Добавить в контакт"));handleRow->addWidget(add);QObject::connect(add,&QAbstractButton::clicked,sheet,[=]{Core::instance()->call("resolve_contact",{{"username",p.value("username")}},sheet,[=](const QJsonValue &,const CoreError &err){if(!err.ok())h.toast(err.message);else {add->hide();h.toast(QStringLiteral("Контакт добавлен"));}});});}
        fields->addLayout(handleRow);
        auto *about=new QWidget;auto *aboutColumn=new QVBoxLayout(about);aboutColumn->setContentsMargins(4,8,4,8);aboutColumn->setSpacing(4);
        auto *bio=new ui::Label(p.value("bio").toString().isEmpty()?QStringLiteral("Не указано"):p.value("bio").toString(),16);bio->setTextFormat(Qt::PlainText);bio->setWordWrap(true);bio->setSizePolicy(QSizePolicy::Ignored,QSizePolicy::Preferred);aboutColumn->addWidget(bio);
        aboutColumn->addWidget(new ui::Label(QStringLiteral("О себе"),12,400,ui::Label::Muted));fields->addWidget(about);
        if(!own) {
            auto *note=new ProfileField(p.value("note").toString().isEmpty()?QStringLiteral("Добавить заметку"):p.value("note").toString(),QStringLiteral("Заметки · видны только вам"),true);fields->addWidget(note);
            auto noteText=std::make_shared<QString>(p.value("note").toString());
            QObject::connect(note,&QAbstractButton::clicked,sheet,[=]{
                auto *editor=new Sheet(h.host,QStringLiteral("Личная заметка"));
                auto *input=new ui::TextArea(QStringLiteral("До 100 символов"),2);input->setMaxLength(100);input->setText(*noteText);editor->body()->addWidget(input);
                auto *save=new ui::Button(QStringLiteral("Сохранить"));editor->body()->addWidget(save);
                QObject::connect(save,&QAbstractButton::clicked,editor,[=]{const QString text=input->text().trimmed();Core::instance()->call("set_profile_note",{{"user_id",uid},{"note",text}},editor,[=](const QJsonValue &,const CoreError &err){if(!err.ok())return h.toast(err.message);*noteText=text;note->value->setText(text.isEmpty()?QStringLiteral("Добавить заметку"):text);editor->close();h.toast(QStringLiteral("Заметка сохранена"));});});
                editor->open();
            });
        }
        body->addWidget(info);
        auto *content=new ProfileContent(h,p);
        auto *tabScroll=new QScrollArea;tabScroll->setWidgetResizable(true);tabScroll->setFrameShape(QFrame::NoFrame);tabScroll->setFixedHeight(60);auto *tabBox=new QWidget;auto *tabs=new QHBoxLayout(tabBox);tabs->setContentsMargins(0,0,0,0);
        auto *pill=new TabPill(tabBox);tabBox->installEventFilter(pill);
        auto buttons=std::make_shared<QList<ui::Button *>>();
        for(const auto &pair:{qMakePair("media",QStringLiteral("Медиа")),qMakePair("files",QStringLiteral("Файлы")),qMakePair("links",QStringLiteral("Ссылки")),qMakePair("groups",QStringLiteral("Группы")),qMakePair("privacy",QStringLiteral("Конфиденциальность"))}) {
            auto *b=new ui::Button(pair.second,ui::Button::Flat);
            b->setCheckable(true);b->setChecked(pair.first==QString("media"));buttons->append(b);tabs->addWidget(b);
            QObject::connect(b,&QAbstractButton::clicked,content,[=] {
                for(auto *tab:*buttons) tab->setChecked(tab==b);
                pill->moveTo(b);
                content->select(pair.first);
            });
        }
        tabScroll->setWidget(tabBox);body->addWidget(tabScroll);body->addWidget(content);content->select("media");
        QTimer::singleShot(0, tabBox, [pill, buttons] { if (!buttons->isEmpty()) pill->moveTo(buttons->first()); });
    });
    sheet->open();
}
