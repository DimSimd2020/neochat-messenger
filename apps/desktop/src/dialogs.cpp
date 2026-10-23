#include "dialogs.h"
#include "profiles.h"

#include "animatedemoji.h"
#include "calls.h"
#include "filepicker.h"
#include "core.h"
#include "messages.h"
#include "popupmenu.h"
#include "sheet.h"
#include "profilestyle.h"
#include "theme.h"
#include "widgets.h"

#include <QButtonGroup>
#include <QClipboard>
#include <QMouseEvent>
#include <functional>
#include <memory>
#include <QDateTime>
#include <QDesktopServices>
#include <QStandardPaths>
#include <QUrl>
#include <QGuiApplication>
#include <QFileInfo>
#include <QImageReader>
#include <QMimeDatabase>
#include <QPixmap>
#include <algorithm>
#include <QDir>
#include <QTemporaryFile>
#include <QThreadPool>
#include <QLinearGradient>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QPainter>
#include <QPainterPath>
#include <QPointer>
#include <QScrollArea>
#include <QSettings>
#include <QVBoxLayout>

namespace {

// Preview of what is about to be sent: the first picture, or the first few file names.
class AttachPreview : public QWidget {
public:
    explicit AttachPreview(QWidget *parent = nullptr) : QWidget(parent)
    {
        setAccessibleName(QStringLiteral("Что будет отправлено"));
        connect(Theme::instance(), &Theme::changed, this, qOverload<>(&QWidget::update));
    }

    void setPaths(const QStringList &paths, bool asPhotos)
    {
        m_paths = paths;
        m_image = {};
        if (asPhotos && !paths.isEmpty()) {
            QImageReader r(paths.first());
            r.setAutoTransform(true);
            const QSize s = r.size();
            if (s.isValid()) {
                r.setScaledSize(s.scaled(QSize(760, 520), Qt::KeepAspectRatioByExpanding));
                m_image = QPixmap::fromImageReader(&r);
            }
        }
        updateGeometry();
        update();
    }

    int rows() const { return qMin(4, int(m_paths.size())); }
    bool showsImage() const { return !m_image.isNull(); }
    QSize sizeHint() const override { return {300, showsImage() ? 230 : rows() * 48 + (m_paths.size() > 4 ? 22 : 0) + 4}; }
    QSize minimumSizeHint() const override { return sizeHint(); }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const Palette &c = Theme::p();
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        if (showsImage()) {
            QPainterPath clip;
            clip.addRoundedRect(QRectF(rect()), 14, 14);
            p.fillPath(clip, c.raised);
            p.setClipPath(clip);
            const QSizeF box = size();
            const qreal k = qMax(box.width() / m_image.width(), box.height() / m_image.height());
            const QSizeF drawn(m_image.width() * k, m_image.height() * k);
            p.drawPixmap(QRectF((box.width() - drawn.width()) / 2, (box.height() - drawn.height()) / 2, drawn.width(), drawn.height()), m_image, QRectF(m_image.rect()));
            if (m_paths.size() > 1) {
                const QString badge = QStringLiteral("+") + QString::number(m_paths.size() - 1);
                p.setFont(Theme::instance()->font(13, QFont::DemiBold));
                const QRectF b(width() - 12 - 46, height() - 12 - 28, 46, 28);
                p.setPen(Qt::NoPen);
                p.setBrush(QColor(0, 0, 0, 150));
                p.drawRoundedRect(b, 14, 14);
                p.setPen(Qt::white);
                p.drawText(b, Qt::AlignCenter, badge);
            }
            return;
        }
        int y = 2;
        for (int i = 0; i < rows(); ++i) {
            const QFileInfo fi(m_paths.at(i));
            const QRectF icon(4, y + 4, 40, 40);
            p.setPen(Qt::NoPen);
            p.setBrush(c.raised);
            p.drawRoundedRect(icon, 12, 12);
            ui::paintIcon(&p, icon.adjusted(9, 9, -9, -9), "file", c.accent, 1.8);
            p.setFont(Theme::instance()->font(14.5, QFont::DemiBold));
            p.setPen(c.text);
            p.drawText(QRectF(54, y + 5, width() - 60, 20), Qt::AlignLeft | Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(fi.fileName(), Qt::ElideMiddle, width() - 60));
            p.setFont(Theme::instance()->font(12.5));
            p.setPen(c.muted);
            p.drawText(QRectF(54, y + 25, width() - 60, 18), Qt::AlignLeft | Qt::AlignVCenter, sizeText(fi.size()));
            y += 48;
        }
        if (m_paths.size() > 4) {
            p.setFont(Theme::instance()->font(13));
            p.setPen(c.muted);
            p.drawText(QRectF(4, y, width() - 8, 20), Qt::AlignLeft | Qt::AlignVCenter, QStringLiteral("и ещё ") + QString::number(m_paths.size() - 4));
        }
    }

private:
    static QString sizeText(qint64 b)
    {
        if (b < 1024)
            return QString::number(b) + QStringLiteral(" Б");
        if (b < 1024 * 1024)
            return QString::number(b / 1024) + QStringLiteral(" КБ");
        return QString::number(b / 1048576.0, 'f', 1).replace('.', ',') + QStringLiteral(" МБ");
    }

    QStringList m_paths;
    QPixmap m_image;
};

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
    QString avatar;
    // The colour the person chose for their name, as '#rrggbb'.
    QString nameHex;
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
        ui::paintAvatar(&p, QRectF(8, (height() - 40) / 2.0, 40, 40), m_name, m_seed, avatar);
        const qreal right = multi ? width() - 44 : width() - 10;
        p.setFont(Theme::instance()->font(15, QFont::DemiBold));
        const QColor chosen = profilestyle::nameOn(nameHex, c.surface);
        p.setPen(chosen.isValid() ? chosen : c.text);
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

// Scrollable rows. In a dialog it is one column that grows up to a height limit; a page fills its space
// and spreads the rows over several columns while they stay at least `colWidth` wide.
class RowList : public QScrollArea {
public:
    explicit RowList(int maxHeight = 360, int maxColumns = 1, int colWidth = 380) : m_max(maxHeight), m_maxCols(maxColumns), m_colWidth(colWidth)
    {
        setWidgetResizable(true);
        setFrameShape(QFrame::NoFrame);
        setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        auto *w = new QWidget;
        w->setAutoFillBackground(false);
        m_lay = new QGridLayout(w);
        m_lay->setContentsMargins(0, 0, 0, 0);
        m_lay->setVerticalSpacing(2);
        m_lay->setHorizontalSpacing(10);
        setWidget(w);
        // setWidget turns the fill back on; a page lets its backdrop show through the rows.
        w->setAutoFillBackground(false);
        viewport()->setAutoFillBackground(false);
        if (maxHeight > 0)
            setMaximumHeight(maxHeight);
    }
    void clear()
    {
        for (auto *it : m_items)
            delete it;
        m_items.clear();
        place();
    }
    void add(QWidget *w)
    {
        m_items << w;
        const int i = int(m_items.size()) - 1;
        m_lay->addWidget(w, i / m_cols, i % m_cols);
        stretchBelow();
    }
    void fit()
    {
        if (m_max <= 0)
            return;
        widget()->adjustSize();
        setFixedHeight(qMin(m_max, qMax(60, widget()->sizeHint().height())));
    }

protected:
    void resizeEvent(QResizeEvent *e) override
    {
        QScrollArea::resizeEvent(e);
        const int cols = qBound(1, viewport()->width() / m_colWidth, m_maxCols);
        if (cols != m_cols) {
            m_cols = cols;
            place();
        }
    }

private:
    void place()
    {
        for (auto *w : m_items)
            m_lay->removeWidget(w);
        for (int i = 0; i < m_items.size(); ++i)
            m_lay->addWidget(m_items[i], i / m_cols, i % m_cols);
        for (int c = 0; c < m_maxCols; ++c)
            m_lay->setColumnStretch(c, c < m_cols ? 1 : 0);
        stretchBelow();
    }
    // The free space goes under the last row, so rows keep their height.
    void stretchBelow()
    {
        m_lay->setRowStretch(m_stretchRow, 0);
        m_stretchRow = (int(m_items.size()) + m_cols - 1) / m_cols;
        m_lay->setRowStretch(m_stretchRow, 1);
    }

    QGridLayout *m_lay;
    QList<QWidget *> m_items;
    int m_max, m_maxCols, m_colWidth;
    int m_cols = 1;
    int m_stretchRow = 0;
};

// Profile QR in the Telegram style: round dots and rounded eyes on a white plate
// inside an accent gradient, the avatar in the middle (error correction Q).
class QrCard : public QWidget {
public:
    QrCard()
    {
        setMinimumSize(180, 204);
        setMaximumSize(300, 340);
        QSizePolicy policy(QSizePolicy::Preferred, QSizePolicy::Preferred);
        policy.setHeightForWidth(true);
        setSizePolicy(policy);
        setAccessibleName(QStringLiteral("QR-код профиля"));
    }
    QSize sizeHint() const override { return {300, 340}; }
    int heightForWidth(int w) const override { return qRound(w * 340.0 / 300); }
    void set(const QStringList &rows, const QString &name, const QString &seed, const QString &handle)
    {
        m_rows = rows;
        m_name = name;
        m_seed = seed;
        m_handle = handle;
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.scale(width() / 300.0, height() / 340.0);
        QLinearGradient g(0, 0, 300, 340);
        g.setColorAt(0, QColor("#4169CF"));
        g.setColorAt(0.5, QColor("#7B5CD6"));
        g.setColorAt(1, QColor("#2FA5B5"));
        p.setPen(Qt::NoPen);
        p.setBrush(g);
        p.drawRoundedRect(QRectF(0, 0, 300, 340), 26, 26);
        const QRectF plate(26, 22, 248, 296);
        p.setBrush(Qt::white);
        p.drawRoundedRect(plate, 22, 22);
        const int n = int(m_rows.size());
        if (n == 0)
            return;
        const QColor dot("#16233C");
        const int cell = qMax(1, 212 / n);
        const qreal side = cell * n;
        const QPointF o(plate.center().x() - side / 2, plate.top() + 16 + (212 - side) / 2);
        // Preserve every module, including alignment patterns. Artwork over the
        // centre can make an otherwise detected code impossible to decode.
        p.setRenderHint(QPainter::Antialiasing, false);
        for (int y = 0; y < n; ++y)
            for (int x = 0; x < n && x < m_rows[y].size(); ++x) {
                if (m_rows[y][x] == '1')
                    p.fillRect(QRectF(o.x() + x * cell, o.y() + y * cell, cell, cell), dot);
            }
        p.setRenderHint(QPainter::Antialiasing);
        ui::paintAvatar(&p, QRectF(plate.left() + 18, plate.bottom() - 50, 34, 34), m_name, m_seed);
        p.setFont(Theme::instance()->font(17, QFont::Bold));
        p.setPen(dot);
        p.drawText(QRectF(plate.left() + 62, plate.bottom() - 50, plate.width() - 80, 34), Qt::AlignVCenter, QFontMetrics(p.font()).elidedText(m_handle, Qt::ElideRight, int(plate.width() - 80)));
    }

private:
    QStringList m_rows;
    QString m_name, m_seed, m_handle;
};

// Muted line that copies `value` on click.
class CopyLabel : public ui::Label {
public:
    CopyLabel(const QString &text, const QString &value, std::function<void()> copied)
        : ui::Label(text, 14, 400, ui::Label::Muted), m_value(value), m_copied(std::move(copied))
    {
        setCursor(Qt::PointingHandCursor);
        setToolTip(QStringLiteral("Скопировать"));
        setWordWrap(true);
    }
    void setValue(const QString &v) { m_value = v; }

protected:
    void mousePressEvent(QMouseEvent *e) override
    {
        if (e->button() != Qt::LeftButton)
            return ui::Label::mousePressEvent(e);
        QGuiApplication::clipboard()->setText(m_value);
        if (m_copied)
            m_copied();
    }

private:
    QString m_value;
    std::function<void()> m_copied;
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

Page *contactsPage(const DialogHost &h)
{
    auto *page = new Page;
    auto *root = new QVBoxLayout(page);
    root->setContentsMargins(36, 30, 36, 20);
    root->setSpacing(14);
    auto *head = new QHBoxLayout;
    head->addWidget(new ui::Label(QStringLiteral("Контакты"), 26, QFont::Bold), 1);
    auto *add = new ui::Button(QStringLiteral("Новый контакт"), ui::Button::Secondary);
    head->addWidget(add);
    root->addLayout(head);
    auto *tools = new QHBoxLayout;
    auto *search = new ui::SearchField(QStringLiteral("Поиск контактов"));
    search->setMaximumWidth(460);
    tools->addWidget(search, 100);
    tools->addStretch(1);
    auto *sortBtn = new ui::Button(QStringLiteral("Имя А–Я"), ui::Button::Flat);
    sortBtn->setAccessibleName(QStringLiteral("Сортировать по"));
    tools->addWidget(new ui::Label(QStringLiteral("Сортировать по"), 13, 400, ui::Label::Muted));
    tools->addWidget(sortBtn);
    root->addLayout(tools);
    auto *list = new RowList(0, 3, 400);
    auto *empty = mutedText({});
    root->addWidget(list, 1);
    root->addWidget(empty, 1, Qt::AlignTop);

    auto sort = std::make_shared<QString>(QSettings().value("contacts/sort", "name-asc").toString());
    auto reload = std::make_shared<std::function<void()>>();
    *reload = [=] {
        static const QHash<QString, QString> names{{"name-asc", QStringLiteral("Имя А–Я")}, {"name-desc", QStringLiteral("Имя Я–А")}, {"recent", QStringLiteral("Дата добавления")}};
        sortBtn->setText(names.value(*sort));
        Core::instance()->call("list_contacts", {{"query", search->edit()->text()}, {"sort", *sort}}, page, [=](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return fail(h, e);
            list->clear();
            const QJsonArray arr = v.toArray();
            for (const auto &x : arr) {
                const QJsonObject c = x.toObject();
                auto *row = new PersonRow(c.value("display_name").toString(), "@" + c.value("username").toString(), c.value("user_id").toString());
                row->avatar = c.value("avatar_path").toString();
                if (c.value("key_changed").toBool())
                    row->warning = QStringLiteral("Ключ изменился — сверьте код");
                const QString uid = c.value("user_id").toString();
                QObject::connect(row, &QAbstractButton::clicked, page, [=] {
                    Core::instance()->call("open_direct", {{"user_id", uid}}, page, [=](const QJsonValue &cv, const CoreError &ce) {
                        if (!ce.ok())
                            return fail(h, ce);
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
    QObject::connect(search->edit(), &QLineEdit::textChanged, page, [=] { (*reload)(); });
    QObject::connect(sortBtn, &QAbstractButton::clicked, page, [=] {
        auto *menu = new PopupMenu(sortBtn);
        const QList<QPair<QString, QString>> modes{{"name-asc", QStringLiteral("Имя А–Я")}, {"name-desc", QStringLiteral("Имя Я–А")}, {"recent", QStringLiteral("Дата добавления")}};
        for (const auto &m : modes) {
            menu->addChoice(m.second, *sort == m.first, page, [=] {
                *sort = m.first;
                QSettings().setValue("contacts/sort", m.first);
                (*reload)();
            });
        }
        menu->popup(sortBtn->mapToGlobal(QPoint(0, sortBtn->height())));
    });
    QObject::connect(add, &QAbstractButton::clicked, page, [=] { newContact(h, [=] { (*reload)(); }); });
    QObject::connect(Core::instance(), &Core::event, page, [=](const QJsonObject &ev) {
        if (ev.value("type").toString() == "contacts_changed")
            (*reload)();
    });
    page->reload = [=] { (*reload)(); };
    (*reload)();
    return page;
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
    sheet->body()->addWidget(mutedText(QStringLiteral("Поиск идёт только по точному никнейму: публичного каталога нет. Без своего имени контакт показывается под именем из его профиля. "
                                                            "Можно вставить ссылку из QR-кода профиля (neochat://u/…).")));
    sheet->body()->addWidget(submit);
    auto go = [=] {
        if (user->text().trimmed().isEmpty()) {
            err->setText(QStringLiteral("Введите никнейм"));
            return err->show();
        }
        err->hide();
        submit->setEnabled(false);
        sheet->setBusy(true);
        // A pasted profile link adds the contact with its key already verified.
        if (user->text().trimmed().startsWith("neochat://u/")) {
            Core::instance()->call("add_contact_qr", {{"link", user->text().trimmed()}}, sheet, [=](const QJsonValue &, const CoreError &e) {
                submit->setEnabled(true);
                sheet->setBusy(false);
                if (!e.ok()) {
                    err->setText(e.message);
                    return err->show();
                }
                if (h.toast)
                    h.toast(QStringLiteral("Контакт добавлен, ключ сверен"));
                if (added)
                    added();
                sheet->close();
            });
            return;
        }
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
    auto *saved = new ui::Button(QStringLiteral("Избранное"), ui::Button::Flat);
    modeBar->addWidget(direct);
    modeBar->addWidget(group);
    modeBar->addWidget(channel);
    modeBar->addStretch();
    modeBar->addWidget(saved);
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
                row->avatar = c.value("avatar_path").toString();
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
    QObject::connect(saved, &QAbstractButton::clicked, sheet, [=] {
        Core::instance()->call("open_saved", {}, sheet, [=](const QJsonValue &cv, const CoreError &ce) {
            if (!ce.ok())
                return fail(h, ce);
            sheet->close();
            h.openChat(cv.toObject().value("id").toString());
        });
    });
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
    sheet->setBack([h] { dialogs::settings(h); });
    // 3.5 rows (58 px + 2 px spacing), with the next row hinting at scrolling.
    auto *list = new RowList(208);
    list->setObjectName("devicesList");
    list->setMinimumHeight(208);
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
                        auto *menu = new PopupMenu(row);
                        menu->addAction(QStringLiteral("Отозвать «%1»").arg(name), sheet, [=] {
                            sheet->setBusy(true);
                            Core::instance()->call("revoke_device", {{"device_id", id}}, sheet, [=](const QJsonValue &, const CoreError &re) {
                                sheet->setBusy(false);
                                if (!re.ok())
                                    return fail(h, re);
                                h.toast(QStringLiteral("Устройство отозвано"));
                                (*reload)();
                            });
                        });
                        menu->popup(row->mapToGlobal(QPoint(60, row->height())));
                    });
                    list->add(row);
                }
                list->setFixedHeight(208);
            });
        }
    };
    (*reload)();
    sheet->open();
}

// ---- calls ----

Page *callsPage(const DialogHost &h)
{
    auto *page = new Page;
    auto *root = new QVBoxLayout(page);
    root->setContentsMargins(36, 30, 36, 20);
    root->setSpacing(14);
    root->addWidget(new ui::Label(QStringLiteral("Звонки"), 26, QFont::Bold));
    auto *list = new RowList(0, 3, 400);
    auto *empty = mutedText(QStringLiteral("Звонков пока нет. Позвонить можно из личного чата: кнопки звонка в шапке переписки."));
    root->addWidget(list, 1);
    root->addWidget(empty, 1, Qt::AlignTop);
    root->addWidget(mutedText(QStringLiteral("Звонки идут напрямую между устройствами и шифруются. Журнал хранится только на этом компьютере.")));
    auto reload = std::make_shared<std::function<void()>>();
    *reload = [=] {
        Core::instance()->call("list_calls", {{"limit", 100}}, page, [=](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return fail(h, e);
            list->clear();
            const QJsonArray arr = v.toArray();
            for (const auto &x : arr) {
                const QJsonObject c = x.toObject();
                const QDateTime at = QDateTime::fromMSecsSinceEpoch(qint64(c.value("at").toDouble()));
                const QString when = at.date() == QDate::currentDate() ? at.toString("HH:mm") : at.toString("dd.MM HH:mm");
                const QString sub = c.value("text").toString() + QStringLiteral(" · ") + when;
                auto *row = new PersonRow(c.value("peer_name").toString(), sub, c.value("peer_user_id").toString());
                row->avatar = c.value("avatar_path").toString();
                if (c.value("missed").toBool())
                    row->warning = sub;
                const QString cid = c.value("conversation_id").toString();
                QObject::connect(row, &QAbstractButton::clicked, page, [=] {
                    auto *menu = new PopupMenu(row);
                    auto call = [=](bool video) {
                        Core::instance()->call("get_conversation", {{"conversation_id", cid}}, page, [=](const QJsonValue &cv, const CoreError &ce) {
                            if (!ce.ok())
                                return fail(h, ce);
                            CallController::instance()->start(cv.toObject(), video);
                        });
                    };
                    menu->addAction(QStringLiteral("Позвонить"), page, [=] { call(false); });
                    menu->addAction(QStringLiteral("Видеозвонок"), page, [=] { call(true); });
                    menu->addAction(QStringLiteral("Открыть чат"), page, [=] { h.openChat(cid); });
                    menu->popup(row->mapToGlobal(QPoint(60, row->height())));
                });
                list->add(row);
            }
            list->fit();
            empty->setVisible(arr.isEmpty());
            list->setVisible(!arr.isEmpty());
        });
    };
    QObject::connect(Core::instance(), &Core::event, page, [=](const QJsonObject &ev) {
        if (ev.value("type").toString() == "conversations_changed")
            (*reload)();
    });
    page->reload = [=] { (*reload)(); };
    (*reload)();
    return page;
}

// Devices for the next call. Ids are kept even while the device is unplugged;
// the call then uses the system default.
void callSettings(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Звонки"), 480);
    sheet->setBack([h] { dialogs::settings(h); });
    struct Choice {
        const char *key, *list, *title, *fallback;
    };
    // Windows has default audio devices but no default camera.
    static const Choice choices[] = {
        {"calls/input", "inputs", "Устройство ввода по умолчанию", "Как в системе"},
        {"calls/output", "outputs", "Устройство вывода по умолчанию", "Как в системе"},
        {"calls/camera", "cameras", "Камера", "Первая найденная"},
    };
    QList<ui::Button *> buttons;
    for (const Choice &ch : choices) {
        sheet->body()->addWidget(new ui::Label(QString::fromUtf8(ch.title), 15, QFont::DemiBold));
        auto *b = new ui::Button(QStringLiteral("Загрузка…"), ui::Button::Secondary);
        b->setEnabled(false);
        b->setAccessibleName(QString::fromUtf8(ch.title));
        sheet->body()->addWidget(b);
        buttons << b;
    }
    sheet->body()->addWidget(mutedText(QStringLiteral("Выбор применяется к следующему звонку. «Как в системе» следует за устройством, выбранным в Windows.")));
    CallController::instance()->devices(sheet, [=](const QJsonObject &all) {
        for (int i = 0; i < 3; ++i) {
            const Choice ch = choices[i];
            ui::Button *b = buttons[i];
            const QJsonArray devs = all.value(ch.list).toArray();
            auto label = [=] {
                const QString id = QSettings().value(ch.key).toString();
                if (id.isEmpty())
                    return QString::fromUtf8(ch.fallback);
                for (const auto &d : devs)
                    if (d.toObject().value("id").toString() == id)
                        return d.toObject().value("name").toString();
                return QStringLiteral("Не подключено — ") + QString::fromUtf8(ch.fallback).toLower();
            };
            b->setText(label());
            b->setEnabled(true);
            QObject::connect(b, &QAbstractButton::clicked, sheet, [=] {
                auto *menu = new PopupMenu(b);
                const QString cur = QSettings().value(ch.key).toString();
                auto add = [&](const QString &name, const QString &id) {
                    menu->addChoice(name, cur == id, sheet, [=] {
                        if (id.isEmpty())
                            QSettings().remove(ch.key);
                        else
                            QSettings().setValue(ch.key, id);
                        b->setText(label());
                    });
                };
                add(QString::fromUtf8(ch.fallback), {});
                for (const auto &d : devs)
                    add(d.toObject().value("name").toString(), d.toObject().value("id").toString());
                if (devs.isEmpty())
                    menu->addAction(QString::fromUtf8(i == 2 ? "Камеры не найдены" : "Устройства не найдены"), sheet, [] {})->setEnabled(false);
                menu->popup(b->mapToGlobal(QPoint(0, b->height())));
            });
        }
    });
    sheet->open();
}

// ---- settings ----

void graphicsSettings(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Графика"), 480);
    sheet->setBack([h] { dialogs::settings(h); });
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
    auto *look = new ui::Button(QStringLiteral("Оформление интерфейса"), ui::Button::Secondary);
    QObject::connect(look, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        appearance(h);
    });
    sheet->body()->addWidget(look);

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
    sheet->open();
}

void graphics(const DialogHost &h)
{
    graphicsSettings(h);
}

// Account-wide: stored on the server and applied on every device.
void privacySettings(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Конфиденциальность"), 480);
    sheet->setBack([h] { dialogs::settings(h); });
    auto *twoFactor = new ui::Toggle(QStringLiteral("Подтверждение входа"));
    auto *lastSeen = new ui::Toggle(QStringLiteral("Время последнего визита"));
    twoFactor->setEnabled(false);
    lastSeen->setEnabled(false);
    Core::instance()->call("account_settings", {}, sheet, [=](const QJsonValue &v, const CoreError &) {
        QSignalBlocker b1(twoFactor), b2(lastSeen);
        twoFactor->setChecked(v.toObject().value("two_factor").toBool());
        lastSeen->setChecked(v.toObject().value("show_last_seen").toBool(true));
        twoFactor->setEnabled(true);
        lastSeen->setEnabled(true);
    });
    QObject::connect(twoFactor, &QAbstractButton::toggled, sheet, [=](bool on) {
        Core::instance()->call("set_two_factor", {{"enabled", on}}, sheet, [=](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                fail(h, e);
        });
    });
    QObject::connect(lastSeen, &QAbstractButton::toggled, sheet, [=](bool on) {
        Core::instance()->call("set_last_seen_visible", {{"visible", on}}, sheet, [=](const QJsonValue &, const CoreError &e) {
            if (!e.ok())
                fail(h, e);
        });
    });
    sheet->body()->addWidget(twoFactor);
    sheet->body()->addWidget(lastSeen);
    sheet->open();
}

void accounts(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Сменить аккаунт"));
    sheet->setBack([h] { dialogs::settings(h); });
    auto *list = new RowList(360);
    sheet->body()->addWidget(list);
    sheet->body()->addWidget(mutedText(QStringLiteral("Остальные аккаунты остаются подключёнными. Их переписка и ключи хранятся отдельно.")));
    auto run = [=](const QString &method, const QJsonObject &params) {
        sheet->body()->parentWidget()->setEnabled(false);
        Core::instance()->call(method, params, sheet, [=](const QJsonValue &, const CoreError &e) {
            sheet->body()->parentWidget()->setEnabled(true);
            if (!e.ok()) fail(h, e);
            else sheet->close();
        });
    };
    auto *add = new ui::Button(QStringLiteral("Добавить аккаунт"), ui::Button::Secondary);
    sheet->body()->addWidget(add);
    QObject::connect(add, &QAbstractButton::clicked, sheet, [=] { run("add_account", {}); });
    Core::instance()->call("list_accounts", {}, sheet, [=](const QJsonValue &v, const CoreError &e) {
        if (!e.ok()) return fail(h, e);
        for (const auto &value : v.toArray()) {
            const auto a = value.toObject();
            QString sub = a.value("username").toString();
            if (!sub.isEmpty()) sub.prepend('@');
            if (a.value("current").toBool()) sub += QStringLiteral(" · текущий");
            if (!a.value("logged_in").toBool()) sub += QStringLiteral(" · требуется вход");
            const QString id = a.value("account_id").toString();
            auto *row = new PersonRow(a.value("display_name").toString(QStringLiteral("Новый аккаунт")), sub, id);
            row->avatar = a.value("avatar_path").toString();
            list->add(row);
            QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                run("switch_account", {{"account_id", id}});
            });
        }
        list->fit();
    });
    sheet->open();
}

void settings(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Настройки"), 480);
    auto *groups = new QHBoxLayout;
    auto *graphics = new ui::Button(QStringLiteral("Графика"), ui::Button::Secondary);
    auto *privacy = new ui::Button(QStringLiteral("Конфиденциальность"), ui::Button::Secondary);
    auto *calls = new ui::Button(QStringLiteral("Звонки"), ui::Button::Secondary);
    QObject::connect(calls, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        callSettings(h);
    });
    QObject::connect(graphics, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        graphicsSettings(h);
    });
    QObject::connect(privacy, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        privacySettings(h);
    });
    groups->addWidget(graphics);
    groups->addWidget(privacy);
    groups->addWidget(calls);
    sheet->body()->addLayout(groups);
    sheet->body()->addWidget(new ui::Label(QStringLiteral("Аккаунт"), 14, QFont::DemiBold));
    auto *switchBtn = new ui::Button(QStringLiteral("Сменить аккаунт"), ui::Button::Secondary);
    QObject::connect(switchBtn, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        accounts(h);
    });
    sheet->body()->addWidget(switchBtn);
    auto *devicesBtn = new ui::Button(QStringLiteral("Устройства"), ui::Button::Secondary);
    QObject::connect(devicesBtn, &QAbstractButton::clicked, sheet, [=] {
        sheet->close();
        devices(h);
    });
    sheet->body()->addWidget(devicesBtn);

    sheet->body()->addSpacing(4);
    auto *enter = new ui::Toggle(QStringLiteral("Enter отправляет сообщение"));
    enter->setChecked(QSettings().value("composer/enterSends", true).toBool());
    QObject::connect(enter, &QAbstractButton::toggled, sheet, [=](bool on) {
        QSettings().setValue("composer/enterSends", on);
        if (h.enterSendsChanged)
            h.enterSendsChanged(on);
    });
    sheet->body()->addWidget(enter);
    auto *notify = new ui::Toggle(QStringLiteral("Уведомления о новых сообщениях"));
    notify->setChecked(QSettings().value("notifications/enabled", true).toBool());
    QObject::connect(notify, &QAbstractButton::toggled, sheet, [](bool on) { QSettings().setValue("notifications/enabled", on); });
    sheet->body()->addWidget(notify);
    auto *moving = new ui::Toggle(QStringLiteral("Анимированные эмодзи"));
    moving->setChecked(animatedemoji::enabled());
    QObject::connect(moving, &QAbstractButton::toggled, sheet, [](bool on) { animatedemoji::setEnabled(on); });
    sheet->body()->addWidget(moving);
    sheet->body()->addWidget(mutedText(QStringLiteral("Анимированные эмодзи: Animated Noto Emoji, © Google, лицензия CC BY 4.0 (изменены размер и формат). Играют только в сообщениях из одних эмодзи.")));
    // Shared by the user's devices through the core; a double click puts it on a message.
    auto *quick = new ui::Button(QStringLiteral("Быстрая реакция · ") + quickReaction(), ui::Button::Secondary);
    quick->setToolTip(QStringLiteral("Двойной щелчок по сообщению ставит эту реакцию. Настройка общая для всех ваших устройств"));
    QObject::connect(quick, &QAbstractButton::clicked, sheet, [=] {
        auto *menu = new PopupMenu(quick);
        menu->addGrid(quickReactionChoices(), quickReaction(), 5, [=](const QString &e) {
            Core::instance()->call("set_settings", {{"quick_reaction", e}}, sheet, [=](const QJsonValue &, const CoreError &err) {
                if (!err.ok())
                    return h.toast(err.message);
                QSettings().setValue("chat/quickReaction", e);
                quick->setText(QStringLiteral("Быстрая реакция · ") + e);
            });
        });
        menu->popup(quick->mapToGlobal(QPoint(0, quick->height())));
    });
    sheet->body()->addWidget(quick);
    auto channelOf = [] {
        return QSettings().value(QStringLiteral("updates/channel")).toString() == QLatin1String("beta") ? QStringLiteral("beta")
                                                                                                        : QStringLiteral("stable");
    };
    auto *channel = new ui::Button(QStringLiteral("Сообщать об обновлениях: ") + channelOf(), ui::Button::Secondary);
    QObject::connect(channel, &QAbstractButton::clicked, sheet, [=] {
        auto *menu = new PopupMenu(channel);
        const QString choices[] = {QStringLiteral("stable"), QStringLiteral("beta")};
        for (const QString &label : choices) {
            menu->addAction((label == channelOf() ? QStringLiteral("✓ ") : QString()) + label, sheet, [=] {
                QSettings().setValue(QStringLiteral("updates/channel"), label);
                channel->setText(QStringLiteral("Сообщать об обновлениях: ") + label);
                if (h.checkUpdate)
                    h.checkUpdate();
            });
        }
        menu->popup(channel->mapToGlobal(QPoint(0, channel->height())));
    });
    sheet->body()->addWidget(channel);
    auto *update = new ui::Button(QStringLiteral("Проверить обновления · версия ") + QStringLiteral(NEOCHAT_VERSION_LABEL), ui::Button::Secondary);
    QObject::connect(update, &QAbstractButton::clicked, sheet, [=] {
        if (h.checkUpdate)
            h.checkUpdate();
    });
    sheet->body()->addWidget(update);
    // Alpha builds keep a log of every call; this is how it reaches the person fixing the bug.
    auto *logs = new ui::Button(QStringLiteral("Открыть журналы звонков (alpha)"), ui::Button::Secondary);
    QObject::connect(logs, &QAbstractButton::clicked, sheet, [] {
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + QStringLiteral("/logs");
        QDir().mkpath(dir);
        QDesktopServices::openUrl(QUrl::fromLocalFile(dir));
    });
    sheet->body()->addWidget(logs);
    sheet->body()->addSpacing(6);
    auto *logout = new ui::Button(QStringLiteral("Выйти из аккаунта"), ui::Button::Danger);
    QObject::connect(logout, &QAbstractButton::clicked, sheet, [=] {
        auto *menu = new PopupMenu(logout);
        menu->addAction(QStringLiteral("Выйти: ключи и история на этом компьютере будут удалены"), sheet, [=] {
            sheet->close();
            h.logout();
        });
        menu->popup(logout->mapToGlobal(QPoint(0, logout->height())));
    });
    sheet->body()->addWidget(logout);
    sheet->open();
}

// ---- release notes ----

void whatsNew(const DialogHost &h)
{
    const QString version = QStringLiteral(NEOCHAT_VERSION);
    Core::instance()->call("whats_new", {{"version", version}, {"platform", "windows"}}, h.host, [h, version](const QJsonValue &v, const CoreError &e) {
        const QJsonObject o = v.toObject();
        if (!e.ok() || !o.value("show").toBool())
            return;
        Core::instance()->call("whats_new_seen", {{"version", version}});
        auto *sheet = new Sheet(h.host, o.value("title").toString(), 460);
        for (const auto &item : o.value("items").toArray()) {
            auto *l = new ui::Label(QStringLiteral("•  ") + item.toString(), 14);
            l->setWordWrap(true);
            sheet->body()->addWidget(l);
        }
        sheet->body()->addSpacing(8);
        auto *ok = new ui::Button(QStringLiteral("Понятно"));
        QObject::connect(ok, &QAbstractButton::clicked, sheet, [sheet] { sheet->close(); });
        sheet->body()->addWidget(ok);
        sheet->open();
    });
}

// ---- profile ----

// Picks an image, cuts the middle square and saves it as a small JPEG in a temporary file
// (nullptr when it cannot be read or stays above `maxBytes` even at low quality).
void pickSquareJpeg(const DialogHost &h, QWidget *anchor, const QString &title, int side, qint64 maxBytes,
                    std::function<void(std::shared_ptr<QTemporaryFile>)> done)
{
    const QPointer<QWidget> guard(anchor);
    filepicker::open(anchor, title, {"jpg", "jpeg", "png", "webp", "bmp", "heic"}, false, [h, guard, side, maxBytes, done](const QStringList &files) {
        const QString path = files.first();
        QThreadPool::globalInstance()->start([h, guard, side, maxBytes, done, path] {
            QImageReader reader(path);
            reader.setAutoTransform(true);
            const QSize size = reader.size();
            if (size.isValid())
                reader.setScaledSize(size.scaled(side * 2, side * 2, Qt::KeepAspectRatio));
            const QImage image = reader.read();
            std::shared_ptr<QTemporaryFile> temp;
            if (!image.isNull()) {
                const int s = qMin(image.width(), image.height());
                const QImage square = image.copy((image.width() - s) / 2, (image.height() - s) / 2, s, s).scaled(side, side, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
                for (int quality : {75, 60, 45, 30}) {
                    auto t = std::make_shared<QTemporaryFile>(QDir::tempPath() + "/neochat-avatar-XXXXXX.jpg");
                    if (t->open() && square.save(t.get(), "JPEG", quality) && t->size() <= maxBytes) {
                        t->close();
                        temp = t;
                        break;
                    }
                }
            }
            if (guard)
                QMetaObject::invokeMethod(guard.data(), [guard, temp, done] {
                    if (guard)
                        done(temp);
                }, Qt::QueuedConnection);
        });
    });
}

void editProfile(const DialogHost &h)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Изменить профиль"), 460);
    sheet->setBack([h] { personProfile(h); });
    auto *name = new ui::TextField(QStringLiteral("Имя"));
    auto *username = new ui::TextField(QStringLiteral("Имя пользователя"));
    auto *bio = new ui::TextArea(QStringLiteral("О себе, до 100 символов"), 3);
    bio->setMaxLength(100);
    auto *save = new ui::Button(QStringLiteral("Сохранить"), ui::Button::Secondary);
    sheet->body()->addWidget(name); sheet->body()->addWidget(username); sheet->body()->addWidget(bio); sheet->body()->addWidget(save);
    Core::instance()->call("status", {}, sheet, [=](const QJsonValue &v, const CoreError &e) {
        if (!e.ok()) return;
        const auto p = v.toObject();
        name->setText(p.value("display_name").toString()); username->setText("@"+p.value("username").toString()); bio->setText(p.value("bio").toString());
    });
    QObject::connect(save,&QAbstractButton::clicked,sheet,[=] {
        save->setEnabled(false);
        Core::instance()->call("set_profile",{{"display_name",name->text().trimmed()},{"username",username->text().trimmed()},{"bio",bio->text().trimmed()}},sheet,[=](const QJsonValue &,const CoreError &e) {
            save->setEnabled(true);
            if (!e.ok()) return h.toast(e.message);
            sheet->close(); personProfile(h); h.toast(QStringLiteral("Профиль сохранён"));
        });
    });
    sheet->open();
}

void profile(const DialogHost &h) { personProfile(h); }

void myQr(const DialogHost &h)
{
    profileQr(h);
}

void profileQr(const DialogHost &h, const QString &userId)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("QR-код профиля"), 380);
    auto *qr = new QrCard;
    auto *copyLink = new ui::Button(QStringLiteral("Скопировать ссылку"), ui::Button::Flat);
    sheet->body()->addWidget(qr, 0, Qt::AlignHCenter);
    sheet->body()->addWidget(copyLink);
    const auto params = userId.isEmpty() ? QJsonObject() : QJsonObject{{"user_id", userId}};
    Core::instance()->call("profile_qr", params, sheet, [=](const QJsonValue &v, const CoreError &e) {
        if (!e.ok())
            return h.toast(e.message);
        const QJsonObject o = v.toObject();
        QStringList rows;
        for (const auto &r : o.value("qr_rows").toArray())
            rows << r.toString();
        const QString link = o.value("link").toString();
        QObject::connect(copyLink, &QAbstractButton::clicked, sheet, [=] {
            QGuiApplication::clipboard()->setText(link);
            h.toast(QStringLiteral("Ссылка скопирована"));
        });
        Core::instance()->call("user_profile", params, sheet, [=](const QJsonValue &sv, const CoreError &) {
            const QJsonObject s = sv.toObject();
            const QString display = s.value("display_name").toString().isEmpty() ? s.value("username").toString() : s.value("display_name").toString();
            qr->set(rows, display, s.value("user_id").toString(), "@" + o.value("username").toString());
        });
    });
    sheet->open();
}

// ---- conversation info ----

static void groupInfo(const DialogHost &h, const QString &cid);
void conversationInfo(const DialogHost &h, const QString &cid)
{
    Core::instance()->call("get_conversation",{{"conversation_id",cid}},h.host,[=](const QJsonValue &v,const CoreError &e) {
        if (!e.ok()) return h.toast(e.message);
        const auto c=v.toObject();
        if(c.value("kind")=="direct") personProfile(h,c.value("peer_user_id").toString());
        else groupInfo(h,cid);
    });
}

static void groupInfo(const DialogHost &h, const QString &cid)
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
                QString bioText;
                for (const auto &x : c.value("members").toArray())
                    if (!x.toObject().value("me").toBool())
                        bioText = x.toObject().value("bio").toString();
                const QString seen = c.value("presence").toObject().value("text").toString();
                Core::instance()->call("contact_info", {{"user_id", peer}}, sheet, [=](const QJsonValue &iv, const CoreError &ie) {
                    if (!ie.ok())
                        return fail(h, ie);
                    const QJsonObject info = iv.toObject();
                    const QString nick = "@" + info.value("username").toString();
                    lay->addWidget(new CopyLabel(nick + (seen.isEmpty() ? QString() : QStringLiteral(" · ") + seen), nick,
                                                 [h] { h.toast(QStringLiteral("Имя пользователя скопировано")); }));
                    if (!bioText.isEmpty()) {
                        auto *b = new ui::Label(bioText, 14);
                        b->setWordWrap(true);
                        lay->addWidget(b);
                    }
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
            auto others = [members](const QString &without, const QStringList &with) {
                QJsonArray ids;
                for (const auto &y : members) {
                    const QJsonObject o = y.toObject();
                    if (!o.value("me").toBool() && o.value("user_id").toString() != without)
                        ids << o.value("user_id");
                }
                for (const QString &w : with)
                    ids << w;
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
            const bool canEditInfo = c.value("can_edit_info").toBool() && active;
            const QString groupPhoto = c.value("avatar_path").toString();
            if (canEditInfo || !groupPhoto.isEmpty()) {
                auto *photo = new PersonRow(c.value("title").toString(), canEditInfo ? QStringLiteral("Нажмите, чтобы сменить фото") : QString(), cid);
                photo->avatar = groupPhoto;
                lay->addWidget(photo);
                if (canEditInfo) {
                    QObject::connect(photo, &QAbstractButton::clicked, sheet, [=] {
                        pickSquareJpeg(h, sheet, QStringLiteral("Фото группы"), 192, 18000, [=](std::shared_ptr<QTemporaryFile> temp) {
                            if (!temp)
                                return h.toast(QStringLiteral("Не удалось подготовить фото"));
                            Core::instance()->call("group_set_avatar", {{"conversation_id", cid}, {"path", temp->fileName()}}, sheet, [=](const QJsonValue &, const CoreError &ae) {
                                Q_UNUSED(temp)
                                if (!ae.ok())
                                    return fail(h, ae);
                                (*build)();
                            });
                        });
                    });
                    if (!groupPhoto.isEmpty()) {
                        auto *remove = new ui::Button(QStringLiteral("Удалить фото"), ui::Button::Flat);
                        QObject::connect(remove, &QAbstractButton::clicked, sheet, [=] {
                            Core::instance()->call("group_set_avatar", {{"conversation_id", cid}, {"path", ""}}, sheet, [=](const QJsonValue &, const CoreError &ae) {
                                if (!ae.ok())
                                    return fail(h, ae);
                                (*build)();
                            });
                        });
                        lay->addWidget(remove);
                    }
                }
            }
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
                auto *about = new ui::TextArea(QStringLiteral("Описание, до 255 символов"), 3);
                about->setMaxLength(255);
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
                const bool muted = m.value("muted_until").toDouble() != 0;
                if (muted)
                    sub += QStringLiteral(" · не может писать");
                auto *row = new PersonRow(m.value("display_name").toString(), sub, uid);
                row->avatar = m.value("avatar_path").toString();
                row->nameHex = m.value("name_color").toString();
                if (m.value("key_changed").toBool())
                    row->warning = QStringLiteral("Ключ изменился — сверьте код в личном чате");
                list->add(row);
                if (!admin || !active)
                    continue;
                const QString name = m.value("display_name").toString();
                const QString tag = m.value("tag").toString();
                const bool me = m.value("me").toBool();
                QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                    auto *menu = new PopupMenu(row);
                    menu->addAction(QStringLiteral("Тег…"), sheet, [=] {
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
                            menu->addAction(QStringLiteral("Снять права админа"), sheet, [=] {
                                setMembers({{"member_ids", others({}, {})}, {"admin_ids", adminIds(uid, {})}}, QStringLiteral("Права админа сняты"));
                            });
                        else
                            menu->addAction(QStringLiteral("Назначить админом"), sheet, [=] {
                                setMembers({{"member_ids", others({}, {})}, {"admin_ids", adminIds({}, uid)}}, QStringLiteral("Назначен админ"));
                            });
                    }
                    if (!me && !channel && uid != ownerId && (owner || !admins.contains(uid))) {
                        auto mute = [=](int minutes, const QString &done) {
                            Core::instance()->call("group_mute", {{"conversation_id", cid}, {"user_id", uid}, {"minutes", minutes}}, sheet,
                                                   [=](const QJsonValue &, const CoreError &me2) {
                                                       if (!me2.ok())
                                                           return fail(h, me2);
                                                       h.toast(done);
                                                       (*build)();
                                                   });
                        };
                        if (muted) {
                            menu->addAction(QStringLiteral("Снять ограничение"), sheet, [=] { mute(0, QStringLiteral("Ограничение снято")); });
                        } else {
                            menu->addAction(QStringLiteral("Заглушить на час"), sheet, [=] { mute(60, QStringLiteral("Участник заглушён на час")); });
                            menu->addAction(QStringLiteral("Заглушить на сутки"), sheet, [=] { mute(1440, QStringLiteral("Участник заглушён на сутки")); });
                            menu->addAction(QStringLiteral("Заглушить навсегда"), sheet, [=] { mute(-1, QStringLiteral("Участник заглушён")); });
                        }
                    }
                    if (!me && uid != ownerId && (owner || !admins.contains(uid)))
                        menu->addAction(channel ? QStringLiteral("Удалить из канала") : QStringLiteral("Исключить из группы"), sheet, [=] {
                            setMembers({{"member_ids", others(uid, {})}}, QStringLiteral("Исключён. Новые сообщения ему не придут"));
                        });
                    menu->popup(row->mapToGlobal(QPoint(60, row->height())));
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
                        auto *pick = new Sheet(h.host, channel ? QStringLiteral("Добавить подписчиков") : QStringLiteral("Добавить участников"), 440);
                        auto *pl = new RowList(320);
                        auto chosen = std::make_shared<QStringList>();
                        auto *confirm = new ui::Button(QStringLiteral("Добавить"));
                        confirm->setEnabled(false);
                        int shown = 0;
                        for (const auto &y : lv.toArray()) {
                            const QJsonObject ct = y.toObject();
                            const QString uid = ct.value("user_id").toString();
                            if (current.contains(uid))
                                continue;
                            auto *row = new PersonRow(ct.value("display_name").toString(), "@" + ct.value("username").toString(), uid);
                            row->avatar = ct.value("avatar_path").toString();
                            row->multi = true;
                            row->setCheckable(true);
                            QObject::connect(row, &QAbstractButton::toggled, pick, [=](bool on) {
                                if (on)
                                    chosen->append(uid);
                                else
                                    chosen->removeAll(uid);
                                confirm->setEnabled(!chosen->isEmpty());
                                confirm->setText(chosen->isEmpty() ? QStringLiteral("Добавить") : QStringLiteral("Добавить (%1)").arg(chosen->size()));
                            });
                            pl->add(row);
                            ++shown;
                        }
                        if (shown == 0) {
                            pick->body()->addWidget(mutedText(QStringLiteral("Все ваши контакты уже здесь. Новых можно добавить в разделе «Контакты».")));
                        } else {
                            pl->fit();
                            pick->body()->addWidget(pl);
                        }
                        QObject::connect(confirm, &QAbstractButton::clicked, pick, [=] {
                            pick->close();
                            setMembers({{"member_ids", others({}, *chosen)}}, QStringLiteral("Добавлено. Прошлые сообщения новым участникам не видны"));
                        });
                        if (shown > 0)
                            pick->body()->addWidget(confirm);
                        else
                            confirm->deleteLater();
                        pick->open();
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
                    auto *menu = new PopupMenu(leave);
                    const QString what = owner ? (channel ? QStringLiteral("Выйти: канал станет архивным для всех") : QStringLiteral("Выйти: группа станет архивной для всех"))
                                               : QStringLiteral("Выйти: новые сообщения перестанут приходить");
                    menu->addAction(what, sheet, [=] {
                        Core::instance()->call("group_leave", {{"conversation_id", cid}}, sheet, [=](const QJsonValue &, const CoreError &ge) {
                            if (!ge.ok())
                                return fail(h, ge);
                            sheet->close();
                        });
                    });
                    menu->popup(leave->mapToGlobal(QPoint(0, leave->height())));
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

void forward(const DialogHost &h, const QJsonArray &msgs)
{
    auto *sheet = new Sheet(h.host, QStringLiteral("Переслать"), 480);
    auto *search = new ui::SearchField(QStringLiteral("Поиск чата"));
    sheet->body()->addWidget(search);
    auto *list = new RowList(380);
    auto *empty = mutedText({});
    sheet->body()->addWidget(list);
    sheet->body()->addWidget(empty);
    auto reload = [=] {
        Core::instance()->call("list_conversations", {{"query", search->edit()->text()}}, sheet, [=](const QJsonValue &v, const CoreError &e) {
            if (!e.ok())
                return fail(h, e);
            list->clear();
            const QJsonArray arr = v.toArray();
            int count = 0;
            for (const auto &x : arr) {
                const QJsonObject c = x.toObject();
                if (!c.value("can_send").toBool())
                    continue;
                const QString cid = c.value("id").toString();
                static const QHash<QString, QString> kinds{
                    {"direct", QStringLiteral("Личный чат")}, {"group", QStringLiteral("Группа")}, {"channel", QStringLiteral("Канал")}};
                auto *row = new PersonRow(c.value("title").toString(), kinds.value(c.value("kind").toString()), cid);
                QObject::connect(row, &QAbstractButton::clicked, sheet, [=] {
                    sheet->close();
                    auto sent = std::make_shared<int>(0);
                    const int total = int(msgs.size());
                    for (const auto &mx : msgs) {
                        const QString mid = mx.toObject().value("id").toString();
                        Core::instance()->call("forward_message", {{"message_id", mid}, {"conversation_id", cid}}, h.host,
                                               [h, cid, sent, total](const QJsonValue &, const CoreError &fe) mutable {
                                                   if (!fe.ok())
                                                       return fail(h, fe);
                                                   if (++*sent == total)
                                                       h.openChat(cid);
                                               });
                    }
                });
                list->add(row);
                ++count;
            }
            list->fit();
            const bool noChats = count == 0;
            empty->setText(noChats ? QStringLiteral("Нет чатов по этому запросу.") : QString());
            empty->setVisible(noChats);
            list->setVisible(!noChats);
        });
    };
    QObject::connect(search->edit(), &QLineEdit::textChanged, sheet, reload);
    reload();
    sheet->open();
}


// ---- attachments ----

void sendAttachments(const DialogHost &h, const QStringList &initial, bool asPhotos, std::function<void(const QStringList &, bool, const QString &)> send, std::function<void()> cancelled)
{
    if (initial.isEmpty())
        return;
    auto *sheet = new Sheet(h.host, QStringLiteral("Отправить"), 420);
    auto paths = std::make_shared<QStringList>(initial);
    auto *preview = new AttachPreview;
    auto *asFile = new ui::Toggle(QStringLiteral("Отправить как файл"));
    auto *caption = new ui::TextField(QStringLiteral("Подпись"));
    caption->edit()->setMaxLength(1000);
    auto *add = new ui::Button(QStringLiteral("Добавить"), ui::Button::Flat);
    auto *cancel = new ui::Button(QStringLiteral("Отмена"), ui::Button::Secondary);
    auto *go = new ui::Button(QStringLiteral("Отправить"));
    auto *buttons = new QHBoxLayout;
    buttons->setSpacing(8);
    buttons->addWidget(add);
    buttons->addStretch(1);
    buttons->addWidget(cancel);
    buttons->addWidget(go);
    sheet->body()->addWidget(preview);
    sheet->body()->addWidget(asFile);
    sheet->body()->addWidget(caption);
    sheet->body()->addLayout(buttons);

    auto isImage = [](const QString &path) { return QMimeDatabase().mimeTypeForFile(path).name().startsWith("image/"); };
    auto isVideo = [](const QString &path) { return QMimeDatabase().mimeTypeForFile(path).name().startsWith("video/"); };
    auto isMedia = [=](const QString &path) { return isImage(path) || isVideo(path); };
    // Pictures and videos can go as such or as files; with anything else in the set there is no choice to offer.
    auto refresh = [=] {
        const bool media = std::all_of(paths->begin(), paths->end(), isMedia);
        asFile->setVisible(media);
        const bool photos = media ? !asFile->isChecked() : asPhotos;
        const bool images = std::all_of(paths->begin(), paths->end(), isImage);
        preview->setPaths(*paths, photos && images);
        if (media && photos && std::all_of(paths->begin(), paths->end(), isVideo))
            sheet->setTitle(paths->size() == 1 ? QStringLiteral("Отправить видео") : QStringLiteral("Отправить видео: %1").arg(paths->size()));
        else if (media && photos && !images)
            sheet->setTitle(QStringLiteral("Отправить медиа: %1").arg(paths->size()));
        else if (images && photos)
            sheet->setTitle(paths->size() == 1 ? QStringLiteral("Отправить фото") : QStringLiteral("Отправить фото: %1").arg(paths->size()));
        else
            sheet->setTitle(paths->size() == 1 ? QStringLiteral("Отправить файл") : QStringLiteral("Отправить файлы: %1").arg(paths->size()));
    };
    // Videos picked as documents still go as videos unless "as a file" is switched on.
    asFile->setChecked(!(asPhotos || std::all_of(paths->begin(), paths->end(), isVideo)));
    QObject::connect(asFile, &QAbstractButton::toggled, sheet, refresh);
    QObject::connect(add, &QAbstractButton::clicked, sheet, [=] {
        filepicker::open(sheet, QStringLiteral("Добавить файлы"), {}, true, [=](const QStringList &more) {
            for (const QString &f : more)
                if (!paths->contains(f))
                    paths->append(f);
            refresh();
        });
    });
    QObject::connect(cancel, &QAbstractButton::clicked, sheet, &Sheet::close);
    auto sent = std::make_shared<bool>(false);
    QObject::connect(sheet, &Sheet::closed, sheet, [=] {
        if (!*sent && cancelled)
            cancelled();
    });
    auto submit = [=] {
        *sent = true;
        const bool media = std::all_of(paths->begin(), paths->end(), isMedia);
        const bool photos = media ? !asFile->isChecked() : asPhotos;
        const QStringList chosen = *paths;
        const QString text = caption->text().trimmed();
        sheet->close();
        send(chosen, photos, text);
    };
    QObject::connect(go, &QAbstractButton::clicked, sheet, submit);
    QObject::connect(caption->edit(), &QLineEdit::returnPressed, sheet, submit);
    refresh();
    sheet->open();
    caption->edit()->setFocus(Qt::OtherFocusReason);
}

} // namespace dialogs
