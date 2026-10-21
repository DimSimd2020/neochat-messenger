#include "filepicker.h"

#include "sheet.h"
#include "theme.h"
#include "widgets.h"

#include <QAbstractScrollArea>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLocale>
#include <QMainWindow>
#include <QMouseEvent>
#include <QPainter>
#include <QPointer>
#include <QScrollBar>
#include <QSet>
#include <QSettings>
#include <QStackedWidget>
#include <QStandardPaths>
#include <QVBoxLayout>
#include <algorithm>
#include <memory>

namespace {

constexpr int kRow = 46;

struct Entry {
    QString name;
    bool dir = false;
    qint64 size = 0;
    QDateTime when;
};

QString sizeText(qint64 b)
{
    if (b < 1024)
        return QString::number(b) + QStringLiteral(" Б");
    if (b < (1 << 20))
        return QString::number(b / 1024.0, 'f', b < 10240 ? 1 : 0) + QStringLiteral(" КБ");
    if (b < (1 << 30))
        return QString::number(b / 1048576.0, 'f', b < (10 << 20) ? 1 : 0) + QStringLiteral(" МБ");
    return QString::number(b / 1073741824.0, 'f', 1) + QStringLiteral(" ГБ");
}

// Only the visible rows are painted, so a folder with thousands of files stays fast.
class FileList : public QAbstractScrollArea {
    Q_OBJECT
public:
    explicit FileList(bool multiple, QWidget *parent = nullptr) : QAbstractScrollArea(parent), m_multiple(multiple)
    {
        setFrameShape(QFrame::NoFrame);
        setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        viewport()->setMouseTracking(true);
        viewport()->setAutoFillBackground(false);
        setFocusPolicy(Qt::StrongFocus);
        setAccessibleName(QStringLiteral("Список файлов"));
        verticalScrollBar()->setSingleStep(kRow);
        setFixedHeight(kRow * 7 + 4);
        connect(Theme::instance(), &Theme::changed, viewport(), qOverload<>(&QWidget::update));
    }
    void setEntries(QVector<Entry> e)
    {
        m_entries = std::move(e);
        m_selected.clear();
        m_current = m_entries.isEmpty() ? -1 : 0;
        updateRange();
        verticalScrollBar()->setValue(0);
        viewport()->update();
        emit selectionChanged();
    }
    const QVector<Entry> &entries() const { return m_entries; }
    QList<int> selected() const { return QList<int>(m_selected.begin(), m_selected.end()); }

signals:
    void openDir(const QString &name);
    void selectionChanged();
    void picked(int index); // double click or Enter on a file

protected:
    void updateRange()
    {
        verticalScrollBar()->setRange(0, qMax(0, int(m_entries.size()) * kRow - viewport()->height()));
        verticalScrollBar()->setPageStep(viewport()->height());
    }
    int rowAt(const QPoint &p) const
    {
        const int i = (p.y() + verticalScrollBar()->value()) / kRow;
        return i >= 0 && i < m_entries.size() ? i : -1;
    }
    void resizeEvent(QResizeEvent *) override { updateRange(); }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(viewport());
        p.setRenderHint(QPainter::Antialiasing);
        const Palette &c = Theme::p();
        const int top = verticalScrollBar()->value();
        const int first = top / kRow, last = qMin(int(m_entries.size()) - 1, (top + viewport()->height()) / kRow);
        const QLocale loc;
        for (int i = first; i <= last; ++i) {
            const Entry &e = m_entries[i];
            const QRectF r(2, i * kRow - top + 1, viewport()->width() - 4, kRow - 2);
            const bool on = m_selected.contains(i);
            if (on || i == m_hover) {
                QColor bg = on ? c.accent : c.raised;
                bg.setAlphaF(on ? 0.22 : 0.7);
                p.setPen(Qt::NoPen);
                p.setBrush(bg);
                p.drawRoundedRect(r, 11, 11);
            }
            ui::paintIcon(&p, QRectF(r.left() + 12, r.top() + 11, 22, 22), e.dir ? "folder" : "file", e.dir ? c.accent : c.muted, 1.7);
            const qreal right = r.right() - 12 - (m_multiple && !e.dir ? 28 : 0);
            const QString meta = e.dir ? QString() : sizeText(e.size) + QStringLiteral("  ·  ") + loc.toString(e.when, QStringLiteral("d MMM yyyy"));
            p.setFont(Theme::instance()->font(12));
            const qreal metaW = meta.isEmpty() ? 0 : QFontMetricsF(p.font()).horizontalAdvance(meta) + 12;
            p.setFont(Theme::instance()->font(14.5, e.dir ? QFont::DemiBold : QFont::Normal));
            p.setPen(c.text);
            const int nameW = int(right - r.left() - 46 - metaW);
            p.drawText(QRectF(r.left() + 46, r.top(), nameW, r.height()), Qt::AlignVCenter | Qt::AlignLeft,
                       QFontMetrics(p.font()).elidedText(e.name, Qt::ElideMiddle, nameW));
            if (!meta.isEmpty()) {
                p.setFont(Theme::instance()->font(12));
                p.setPen(c.muted);
                p.drawText(QRectF(right - metaW, r.top(), metaW, r.height()), Qt::AlignVCenter | Qt::AlignRight, meta);
            }
            if (m_multiple && !e.dir) {
                const QRectF box(r.right() - 30, r.top() + (r.height() - 20) / 2, 20, 20);
                p.setPen(on ? Qt::NoPen : QPen(c.border, 1.5));
                p.setBrush(on ? c.accent : Qt::transparent);
                p.drawRoundedRect(box, 6, 6);
                if (on)
                    ui::paintIcon(&p, box.adjusted(3, 3, -3, -3), "tick", Qt::white, 2.4);
            }
            if (hasFocus() && i == m_current)
                ui::paintFocusRing(&p, r.adjusted(1, 1, -1, -1), 11);
        }
        if (m_entries.isEmpty()) {
            p.setFont(Theme::instance()->font(14));
            p.setPen(c.muted);
            p.drawText(viewport()->rect(), Qt::AlignCenter, QStringLiteral("Здесь нет подходящих файлов"));
        }
    }
    void mouseMoveEvent(QMouseEvent *e) override
    {
        const int i = rowAt(e->position().toPoint());
        if (i != m_hover) {
            m_hover = i;
            viewport()->setCursor(i >= 0 ? Qt::PointingHandCursor : Qt::ArrowCursor);
            viewport()->update();
        }
    }
    void leaveEvent(QEvent *) override
    {
        m_hover = -1;
        viewport()->update();
    }
    void mousePressEvent(QMouseEvent *e) override
    {
        setFocus(Qt::MouseFocusReason);
        const int i = rowAt(e->position().toPoint());
        if (i >= 0) {
            m_current = i;
            activate(i, false);
        }
    }
    void mouseDoubleClickEvent(QMouseEvent *e) override
    {
        const int i = rowAt(e->position().toPoint());
        if (i >= 0 && !m_entries[i].dir && !m_multiple)
            emit picked(i);
    }
    void keyPressEvent(QKeyEvent *e) override
    {
        e->accept();
        switch (e->key()) {
        case Qt::Key_Down: moveTo(m_current + 1); break;
        case Qt::Key_Up: moveTo(m_current - 1); break;
        case Qt::Key_PageDown: moveTo(m_current + 6); break;
        case Qt::Key_PageUp: moveTo(m_current - 6); break;
        case Qt::Key_Home: moveTo(0); break;
        case Qt::Key_End: moveTo(int(m_entries.size()) - 1); break;
        case Qt::Key_Space:
            if (m_current >= 0)
                activate(m_current, false);
            break;
        case Qt::Key_Return:
        case Qt::Key_Enter:
            if (m_current >= 0)
                activate(m_current, true);
            break;
        case Qt::Key_Backspace: emit openDir(QStringLiteral("..")); break;
        default: QAbstractScrollArea::keyPressEvent(e);
        }
    }
    void focusInEvent(QFocusEvent *) override { viewport()->update(); }
    void focusOutEvent(QFocusEvent *) override { viewport()->update(); }

private:
    void moveTo(int i)
    {
        if (m_entries.isEmpty())
            return;
        m_current = qBound(0, i, int(m_entries.size()) - 1);
        const int y = m_current * kRow;
        if (y < verticalScrollBar()->value())
            verticalScrollBar()->setValue(y);
        else if (y + kRow > verticalScrollBar()->value() + viewport()->height())
            verticalScrollBar()->setValue(y + kRow - viewport()->height());
        viewport()->update();
    }
    void activate(int i, bool enter)
    {
        const Entry &e = m_entries[i];
        if (e.dir) {
            emit openDir(e.name);
            return;
        }
        if (m_multiple) {
            if (!m_selected.remove(i))
                m_selected.insert(i);
        } else if (enter && m_selected.contains(i)) {
            emit picked(i);
            return;
        } else {
            m_selected = {i};
        }
        viewport()->update();
        emit selectionChanged();
    }
    QVector<Entry> m_entries;
    QSet<int> m_selected;
    int m_current = -1, m_hover = -1;
    bool m_multiple;
};

struct Picker {
    QPointer<Sheet> sheet;
    FileList *list = nullptr;
    ui::Label *path = nullptr;
    ui::Button *confirm = nullptr;
    ui::TextField *name = nullptr;
    QDir dir;
    QStringList exts;
    bool saving = false;
    std::function<void(const QStringList &)> done;

    void show(const QString &to)
    {
        QDir d(to);
        if (!d.exists())
            d = QDir::home();
        dir = d;
        QSettings().setValue("filepicker/dir", dir.absolutePath());
        path->setText(QDir::toNativeSeparators(dir.absolutePath()));
        QVector<Entry> out;
        const auto infos = dir.entryInfoList(QDir::Dirs | QDir::Files | QDir::NoDotAndDotDot, QDir::DirsFirst | QDir::IgnoreCase | QDir::Name);
        for (const QFileInfo &fi : infos) {
            if (!fi.isDir() && !exts.isEmpty() && !exts.contains(fi.suffix().toLower()))
                continue;
            out.push_back({fi.fileName(), fi.isDir(), fi.size(), fi.lastModified()});
        }
        list->setEntries(std::move(out));
        refresh();
    }
    void refresh()
    {
        const int n = list->selected().size();
        if (saving) {
            confirm->setEnabled(!name->text().trimmed().isEmpty());
        } else {
            confirm->setEnabled(n > 0);
            confirm->setText(n > 1 ? QStringLiteral("Выбрать (%1)").arg(n) : QStringLiteral("Выбрать"));
        }
    }
};

void build(QWidget *host, const QString &title, const QStringList &exts, bool multiple, bool saving, const QString &suggested,
           std::function<void(const QStringList &)> done)
{
    auto *sheet = new Sheet(host, title, 600);
    auto st = std::make_shared<Picker>();
    st->sheet = sheet;
    st->exts = exts;
    st->saving = saving;
    st->done = std::move(done);

    st->path = new ui::Label({}, 13, QFont::DemiBold, ui::Label::Muted);
    st->list = new FileList(multiple && !saving);

    struct Place {
        QString label;
        QStandardPaths::StandardLocation loc;
    };
    const QList<Place> places{{QStringLiteral("Домой"), QStandardPaths::HomeLocation},
                              {QStringLiteral("Стол"), QStandardPaths::DesktopLocation},
                              {QStringLiteral("Документы"), QStandardPaths::DocumentsLocation},
                              {QStringLiteral("Фото"), QStandardPaths::PicturesLocation},
                              {QStringLiteral("Загрузки"), QStandardPaths::DownloadLocation}};
    auto *placeRow = new QHBoxLayout;
    placeRow->setSpacing(6);
    for (const Place &pl : places) {
        const QString target = QStandardPaths::writableLocation(pl.loc);
        if (target.isEmpty())
            continue;
        auto *b = new ui::Button(pl.label, ui::Button::Flat);
        QObject::connect(b, &QAbstractButton::clicked, sheet, [st, target] { st->show(target); });
        placeRow->addWidget(b);
    }
    placeRow->addStretch(1);
    sheet->body()->addLayout(placeRow);

    auto *driveRow = new QHBoxLayout;
    driveRow->setSpacing(6);
    for (const QFileInfo &d : QDir::drives()) {
        auto *b = new ui::Button(QDir::toNativeSeparators(d.absoluteFilePath()).left(2), ui::Button::Flat);
        const QString target = d.absoluteFilePath();
        QObject::connect(b, &QAbstractButton::clicked, sheet, [st, target] { st->show(target); });
        driveRow->addWidget(b);
    }
    driveRow->addStretch(1);
    sheet->body()->addLayout(driveRow);

    auto *where = new QHBoxLayout;
    auto *up = new ui::IconButton("up", QStringLiteral("На уровень выше"), sheet, 36);
    QObject::connect(up, &QAbstractButton::clicked, sheet, [st] {
        QDir d = st->dir;
        if (d.cdUp())
            st->show(d.absolutePath());
    });
    where->addWidget(up);
    where->addWidget(st->path, 1);
    sheet->body()->addLayout(where);
    sheet->body()->addWidget(st->list);

    if (saving) {
        st->name = new ui::TextField(QStringLiteral("Имя файла"));
        st->name->setText(suggested);
        sheet->body()->addWidget(st->name);
        QObject::connect(st->name->edit(), &QLineEdit::textChanged, sheet, [st] { st->refresh(); });
    }
    st->confirm = new ui::Button(saving ? QStringLiteral("Сохранить") : QStringLiteral("Выбрать"));
    sheet->body()->addWidget(st->confirm);

    QObject::connect(st->list, &FileList::openDir, sheet, [st](const QString &n) {
        QDir d = st->dir;
        if (n == QStringLiteral("..") ? d.cdUp() : d.cd(n))
            st->show(d.absolutePath());
    });
    QObject::connect(st->list, &FileList::selectionChanged, sheet, [st] {
        if (st->saving) {
            const auto sel = st->list->selected();
            if (!sel.isEmpty())
                st->name->setText(st->list->entries()[sel.first()].name);
        }
        st->refresh();
    });
    auto finish = [st] {
        QStringList out;
        if (st->saving) {
            const QString base = QFileInfo(st->name->text().trimmed()).fileName();
            if (base.isEmpty())
                return;
            const QFileInfo fi(base);
            QString candidate = st->dir.absoluteFilePath(base);
            for (int n = 1; QFileInfo::exists(candidate); ++n)
                candidate = st->dir.absoluteFilePath(fi.completeBaseName() + QStringLiteral(" (%1)").arg(n) + (fi.suffix().isEmpty() ? QString() : "." + fi.suffix()));
            out << candidate;
        } else {
            auto idx = st->list->selected();
            std::sort(idx.begin(), idx.end());
            for (int i : idx)
                out << st->dir.absoluteFilePath(st->list->entries()[i].name);
        }
        if (out.isEmpty() || !st->sheet)
            return;
        auto cb = st->done;
        st->sheet->close();
        cb(out);
    };
    QObject::connect(st->confirm, &QAbstractButton::clicked, sheet, finish);
    QObject::connect(st->list, &FileList::picked, sheet, [finish](int) { finish(); });
    if (saving)
        QObject::connect(st->name->edit(), &QLineEdit::returnPressed, sheet, finish);

    st->show(QSettings().value("filepicker/dir", QStandardPaths::writableLocation(QStandardPaths::HomeLocation)).toString());
    sheet->open();
}

} // namespace

namespace filepicker {

// Sheets of one window share a parent (the main window's page stack), like every other dialog:
// sheets with different parents would both claim to be on top and fight over the focus.
static QWidget *sheetHost(QWidget *w)
{
    QWidget *outer = nullptr;
    for (QWidget *p = w; p; p = p->parentWidget()) {
        if (qobject_cast<Sheet *>(p))
            return p->parentWidget();
        if (qobject_cast<QStackedWidget *>(p))
            outer = p;
    }
    if (outer)
        return outer;
    QWidget *top = w->window();
    if (auto *mw = qobject_cast<QMainWindow *>(top)) {
        if (auto *stack = mw->centralWidget()->findChild<QStackedWidget *>())
            return stack;
        return mw->centralWidget();
    }
    return top;
}

void open(QWidget *host, const QString &title, const QStringList &extensions, bool multiple, std::function<void(const QStringList &)> done)
{
    build(sheetHost(host), title, extensions, multiple, false, {}, std::move(done));
}

void save(QWidget *host, const QString &title, const QString &suggestedName, std::function<void(const QString &)> done)
{
    build(sheetHost(host), title, {}, false, true, suggestedName, [done = std::move(done)](const QStringList &p) { done(p.first()); });
}

} // namespace filepicker

#include "filepicker.moc"
