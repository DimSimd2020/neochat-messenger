#include "core.h"
#include "keystore.h"
#include "mainwindow.h"
#include "style.h"
#include "theme.h"

#include <QApplication>
#include <QDir>
#include <QLockFile>
#include <QMessageBox>
#include <QStandardPaths>

int main(int argc, char *argv[])
{
    QApplication::setHighDpiScaleFactorRoundingPolicy(Qt::HighDpiScaleFactorRoundingPolicy::PassThrough);
    QApplication app(argc, argv);
    Q_INIT_RESOURCE(resources);
    QApplication::setOrganizationName("NeoChat");
    QApplication::setApplicationName("NeoChat");
    QApplication::setApplicationVersion(NEOCHAT_VERSION);
    QApplication::setStyle(createStyle());
    QApplication::setWindowIcon(QIcon(":/logo.png"));
    QApplication::setFont(Theme::instance()->font(14));
    Theme::instance()->init();

    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    QDir().mkpath(dataDir);
    // One running copy per Windows user: two processes must not share the database.
    QLockFile lock(dataDir + "/neochat.lock");
    lock.setStaleLockTime(0);
    if (!lock.tryLock(200)) {
        QMessageBox::information(nullptr, "NeoChat", QStringLiteral("NeoChat уже запущен."));
        return 0;
    }
    QString error;
    const QString key = loadOrCreateDatabaseKey(dataDir, &error);
    if (key.isEmpty() || !Core::instance()->open(dataDir + "/core", key, &error)) {
        QMessageBox::critical(nullptr, "NeoChat", error);
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
