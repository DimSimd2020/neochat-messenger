#include "chrome.h"
#include "core.h"
#include "keystore.h"
#include "mainwindow.h"
#include "style.h"
#include "theme.h"
#include "widgets.h"

#include <QApplication>
#include <QDir>
#include <QLockFile>
#include <QEventLoop>
#include <QPainter>
#include <QVBoxLayout>
#include <QStandardPaths>
#include <QTranslator>

#include <windows.h>

namespace {

// A start-up message in the app's own style (before the main window exists).
class NoticeWindow : public QWidget {
public:
    NoticeWindow(const QString &text, bool failure) : QWidget(nullptr, Qt::Window | Qt::FramelessWindowHint | Qt::WindowStaysOnTopHint)
    {
        setAttribute(Qt::WA_TranslucentBackground);
        auto *lay = new QVBoxLayout(this);
        lay->setContentsMargins(28, 24, 28, 24);
        lay->setSpacing(14);
        lay->addWidget(new ui::Label(QStringLiteral("NeoChat"), 18, QFont::DemiBold, failure ? ui::Label::Warning : ui::Label::Text));
        auto *body = new ui::Label(text, 14);
        body->setWordWrap(true);
        body->setTextInteractionFlags(Qt::TextSelectableByMouse);
        lay->addWidget(body);
        auto *ok = new ui::Button(QStringLiteral("Закрыть"));
        connect(ok, &QAbstractButton::clicked, this, [this] { m_loop.quit(); });
        lay->addWidget(ok);
        setFixedWidth(420);
    }
    void run()
    {
        adjustSize();
        show();
        activateWindow();
        m_loop.exec();
        hide();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(Theme::p().border, 1));
        p.setBrush(Theme::p().surface);
        p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 18, 18);
    }
    void closeEvent(QCloseEvent *) override { m_loop.quit(); }

private:
    QEventLoop m_loop;
};

void notice(const QString &text, bool failure)
{
    NoticeWindow w(text, failure);
    w.run();
}

} // namespace

int main(int argc, char *argv[])
{
    QApplication::setHighDpiScaleFactorRoundingPolicy(Qt::HighDpiScaleFactorRoundingPolicy::PassThrough);
    QApplication app(argc, argv);
    Q_INIT_RESOURCE(resources);
    QTranslator qtRu;
    if (qtRu.load(QStringLiteral(":/i18n/qtbase_ru.qm")))
        QApplication::installTranslator(&qtRu);
    QApplication::setOrganizationName("NeoChat");
    QApplication::setApplicationName("NeoChat");
    QApplication::setApplicationVersion(NEOCHAT_VERSION);
    QApplication::setStyle(createStyle());
    QApplication::setWindowIcon(QIcon(":/logo.png"));
    QApplication::setFont(Theme::instance()->font(14));
    Theme::instance()->init();
    chrome::install(&app);

    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    QDir().mkpath(dataDir);
    // Where neocall.dll writes the call diagnostics of this build.
    SetEnvironmentVariableW(L"NEOCALL_LOG_DIR", QDir::toNativeSeparators(dataDir + "/logs").toStdWString().c_str());
    // One running copy per Windows user: two processes must not share the database.
    QLockFile lock(dataDir + "/neochat.lock");
    lock.setStaleLockTime(0);
    if (!lock.tryLock(200)) {
        notice(QStringLiteral("NeoChat уже запущен."), false);
        return 0;
    }
    QString error;
    const QString key = loadOrCreateDatabaseKey(dataDir, &error);
    if (key.isEmpty() || !Core::instance()->open(dataDir + "/core", key, &error)) {
        notice(error, true);
        return 1;
    }
    int rc;
    {
        MainWindow w;
        w.show();
        w.start();
        rc = app.exec();
    }
    Core::instance()->close();
    return rc;
}
