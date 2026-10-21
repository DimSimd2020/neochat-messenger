#include "chrome.h"

#include "popupmenu.h"
#include "theme.h"

#include <QApplication>
#include <QClipboard>
#include <QContextMenuEvent>
#include <QEvent>
#include <QHelpEvent>
#include <QLabel>
#include <QLineEdit>
#include <QPainter>
#include <QPlainTextEdit>
#include <QPointer>
#include <QScreen>
#include <QTextEdit>
#include <QTimer>

namespace {

class Tip : public QWidget {
public:
    Tip() : QWidget(nullptr, Qt::ToolTip | Qt::FramelessWindowHint | Qt::NoDropShadowWindowHint)
    {
        setAttribute(Qt::WA_TranslucentBackground);
        setAttribute(Qt::WA_ShowWithoutActivating);
        setAttribute(Qt::WA_TransparentForMouseEvents);
        m_timer.setSingleShot(true);
        connect(&m_timer, &QTimer::timeout, this, &QWidget::hide);
    }
    void show(const QPoint &pos, const QString &text)
    {
        m_text = text;
        const QFont f = Theme::instance()->font(12.5);
        const QRect box = QFontMetrics(f).boundingRect(QRect(0, 0, 320, 400), Qt::TextWordWrap, text);
        resize(box.width() + 32, box.height() + 24);
        QScreen *screen = QGuiApplication::screenAt(pos);
        const QRect area = (screen ? screen : QGuiApplication::primaryScreen())->availableGeometry();
        move(qBound(area.left(), pos.x() + 8, qMax(area.left(), area.right() - width())),
             qBound(area.top(), pos.y() + 20, qMax(area.top(), area.bottom() - height())));
        QWidget::show();
        raise();
        update();
        m_timer.start(5000);
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const Palette &c = Theme::p();
        p.setPen(QPen(c.border, 1));
        p.setBrush(c.raised);
        p.drawRoundedRect(QRectF(rect()).adjusted(5.5, 5.5, -5.5, -5.5), 10, 10);
        p.setFont(Theme::instance()->font(12.5));
        p.setPen(c.text);
        p.drawText(rect().adjusted(16, 12, -16, -12), Qt::AlignLeft | Qt::AlignVCenter | Qt::TextWordWrap, m_text);
    }

private:
    QString m_text;
    QTimer m_timer;
};

QPointer<Tip> g_tip;

class Filter : public QObject {
public:
    using QObject::QObject;

protected:
    bool eventFilter(QObject *o, QEvent *e) override
    {
        switch (e->type()) {
        case QEvent::ToolTip: {
            auto *w = qobject_cast<QWidget *>(o);
            if (!w || w->toolTip().isEmpty())
                return false;
            chrome::showTip(static_cast<QHelpEvent *>(e)->globalPos(), w->toolTip(), w);
            return true;
        }
        case QEvent::Leave:
        case QEvent::MouseButtonPress:
        case QEvent::KeyPress:
        case QEvent::Wheel:
        case QEvent::WindowDeactivate:
            chrome::hideTip();
            return false;
        case QEvent::ContextMenu:
            return textMenu(o, static_cast<QContextMenuEvent *>(e));
        default:
            return false;
        }
    }

private:
    static bool textMenu(QObject *o, QContextMenuEvent *e)
    {
        auto *w = qobject_cast<QWidget *>(o);
        if (!w)
            return false;
        QWidget *target = w;
        if (qobject_cast<QPlainTextEdit *>(w->parentWidget()) || qobject_cast<QTextEdit *>(w->parentWidget()))
            target = w->parentWidget();
        auto *line = qobject_cast<QLineEdit *>(target);
        auto *plain = qobject_cast<QPlainTextEdit *>(target);
        auto *rich = qobject_cast<QTextEdit *>(target);
        auto *label = qobject_cast<QLabel *>(target);
        if (!line && !plain && !rich && !(label && (label->textInteractionFlags() & Qt::TextSelectableByMouse)))
            return false;
        QString selected;
        bool editable = false;
        if (line) {
            selected = line->selectedText();
            editable = !line->isReadOnly();
        } else if (plain) {
            selected = plain->textCursor().selectedText();
            editable = !plain->isReadOnly();
        } else if (rich) {
            selected = rich->textCursor().selectedText();
            editable = !rich->isReadOnly();
        } else {
            selected = label->selectedText();
        }
        auto *menu = new PopupMenu(target);
        auto run = [target](const char *slot) { QMetaObject::invokeMethod(target, slot); };
        if (editable)
            menu->addAction(QStringLiteral("Вырезать"), target, [=] { run("cut"); })->setEnabled(!selected.isEmpty());
        menu->addAction(QStringLiteral("Копировать"), target, [=] {
            if (label)
                QApplication::clipboard()->setText(label->selectedText());
            else
                run("copy");
        })->setEnabled(!selected.isEmpty());
        if (editable)
            menu->addAction(QStringLiteral("Вставить"), target, [=] { run("paste"); })->setEnabled(!QApplication::clipboard()->text().isEmpty());
        if (!label)
            menu->addAction(QStringLiteral("Выделить всё"), target, [=] { run("selectAll"); });
        menu->popup(e->globalPos());
        return true;
    }
};

} // namespace

namespace chrome {

void install(QApplication *app)
{
    app->installEventFilter(new Filter(app));
}

void showTip(const QPoint &globalPos, const QString &text, QWidget *)
{
    if (!g_tip)
        g_tip = new Tip;
    g_tip->show(globalPos, text);
}

void hideTip()
{
    if (g_tip && g_tip->isVisible())
        g_tip->hide();
}

} // namespace chrome
