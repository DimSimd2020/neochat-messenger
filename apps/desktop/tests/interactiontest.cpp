#include "composer.h"
#include "dialogs.h"
#include "messages.h"
#include "popupmenu.h"
#include "sheet.h"
#include "theme.h"

#include <QApplication>
#include <QAbstractButton>
#include <QPainter>
#include <QSettings>
#include <QClipboard>
#include <QContextMenuEvent>
#include <QDir>
#include <QMimeData>
#include <QLineEdit>
#include <QPointer>
#include <QScrollArea>
#include <QVBoxLayout>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTemporaryFile>
#include <QTest>

class InteractionTest : public QObject {
    Q_OBJECT
private slots:
    void initTestCase()
    {
        // Own settings key: the test saves themes and must not touch the real app settings.
        QCoreApplication::setOrganizationName("NeoChatTest");
        QCoreApplication::setApplicationName("NeoChatInteractionTest");
        QSettings().clear();
        Theme::instance()->init();
    }

    void cleanupTestCase() { QSettings().clear(); }

    void accountSettingsAndDeviceViewport() {
        QWidget host;
        host.resize(973, 742);
        host.show();
        DialogHost h;
        h.host = &host;
        h.toast = [](const QString &) {};
        auto shot = [&](const QString &name) {
            const QString dir = qEnvironmentVariable("NEOCHAT_SHOTS");
            if (dir.isEmpty()) return;
            QDir().mkpath(dir);
            host.grab().save(dir + "/" + name + ".png");
        };
        dialogs::settings(h);
        auto button = [&](const QString &text) -> QAbstractButton * {
            for (auto *b : host.findChildren<QAbstractButton *>())
                if (b->isVisible() && b->text() == text) return b;
            return nullptr;
        };
        QTRY_VERIFY(button(QStringLiteral("Сменить аккаунт")));
        QVERIFY(button(QStringLiteral("Устройства")));
        QVERIFY(button(QStringLiteral("Сменить аккаунт"))->mapTo(&host, QPoint()).y() < button(QStringLiteral("Устройства"))->mapTo(&host, QPoint()).y());
        QTest::qWait(250);
        shot("multi-account-settings");
        button(QStringLiteral("Сменить аккаунт"))->click();
        QTRY_VERIFY(button(QStringLiteral("Добавить аккаунт")));
        QVERIFY(button(QStringLiteral("Добавить аккаунт"))->focusPolicy() != Qt::NoFocus);
        QTest::qWait(250);
        Sheet::closeAll(&host);
        QTest::qWait(250);
        dialogs::devices(h);
        auto *list = host.findChild<QScrollArea *>(QStringLiteral("devicesList"));
        QVERIFY(list);
        QTRY_COMPARE(list->height(), 208);
        QVERIFY(list->viewport()->height() >= 206);
        QTest::qWait(250);
        shot("multi-account-devices");
        Sheet::closeAll(&host);
        QTest::qWait(250);
    }

    void appearanceThemesAreStoredAndChosen() {
        auto *theme = Theme::instance();
        QCOMPARE(theme->appearance(), QStringLiteral("ocean"));
        QVERIFY(Theme::darkBase(QColor("#101A2E")));
        QVERIFY(!Theme::darkBase(QColor("#E9F0FC")));
        // Unknown ids and empty slots never become the active theme.
        theme->setAppearance(QStringLiteral("custom0"));
        theme->setAppearance(QStringLiteral("nonsense"));
        QCOMPARE(theme->appearance(), QStringLiteral("ocean"));
        CustomTheme mine;
        mine.name = QStringLiteral("Мята");
        mine.base = QColor("#0E2A24");
        mine.spots = {Spot{{0.8, 0.2}, QColor("#2FBF9B")}, Spot{{0.1, 0.9}, QColor("#3B6FD8")}};
        theme->saveCustom(1, mine);
        QCOMPARE(theme->appearance(), QStringLiteral("custom1"));
        const CustomTheme back = theme->custom(1);
        QCOMPARE(back.name, mine.name);
        QCOMPARE(back.base, mine.base);
        QCOMPARE(back.spots.size(), 2);
        QCOMPARE(back.spots[0].color, QColor("#2FBF9B"));
        QVERIFY(theme->isDark());
        QVERIFY(!theme->custom(0).valid());
        // A light base turns the whole interface light, whatever the day/night mode says.
        mine.base = QColor("#F4F0E6");
        theme->saveCustom(1, mine);
        QVERIFY(!theme->isDark());
        theme->deleteCustom(1);
        QVERIFY(!theme->custom(1).valid());
        QCOMPARE(theme->appearance(), QStringLiteral("ocean"));
        // A blob that exists on one side only grows out of the blob on the other side.
        const Palette two = Palette::dark();
        const Palette five = theme->paletteFor(QStringLiteral("sunset"), true);
        QCOMPARE(Palette::mix(two, five, 0).spots[1].color.rgb(), two.spots[1].color.rgb());
        QCOMPARE(Palette::mix(two, five, 1).spots[0].color.rgb(), five.spots[0].color.rgb());
        QVERIFY(Palette::mix(two, five, 0.5).spots[3].color.alpha() == 0);
    }

    void appearanceSheetCreatesEditsAndDeletes() {
        QWidget host;
        host.resize(973, 742);
        host.show();
        DialogHost h;
        h.host = &host;
        h.toast = [](const QString &) {};
        auto shot = [&](const QString &name) {
            const QString dir = qEnvironmentVariable("NEOCHAT_SHOTS");
            if (dir.isEmpty()) return;
            QDir().mkpath(dir);
            host.grab().save(dir + "/" + name + ".png");
        };
        auto named = [&](const QString &name) -> QAbstractButton * {
            for (auto *b : host.findChildren<QAbstractButton *>())
                if (b->isVisible() && (b->accessibleName() == name || b->text() == name)) return b;
            return nullptr;
        };
        auto *theme = Theme::instance();
        theme->setAppearance(QStringLiteral("ocean"));
        dialogs::appearance(h);
        for (const char *name : {"Океан", "Закат", "Лес", "Сирень"})
            QTRY_VERIFY(named(QString::fromUtf8(name)));
        QVERIFY(named(QStringLiteral("Океан"))->isChecked());
        named(QStringLiteral("Закат"))->click();
        QCOMPARE(theme->appearance(), QStringLiteral("sunset"));
        QVERIFY(named(QStringLiteral("Закат"))->isChecked());
        QVERIFY(!named(QStringLiteral("Океан"))->isChecked());
        QTest::qWait(500);
        shot("appearance-list");

        // New theme: the editor opens with the points of the theme on screen.
        named(QStringLiteral("Новая тема"))->click();
        QTRY_VERIFY(named(QStringLiteral("Сохранить")));
        QVERIFY(named(QStringLiteral("Точка 2")));
        QVERIFY(!named(QStringLiteral("Точка 3")));
        QWidget *canvas = nullptr;
        for (auto *w : host.findChildren<QWidget *>())
            if (w->isVisible() && w->accessibleName().startsWith(QStringLiteral("Фон:"))) canvas = w;
        QVERIFY(canvas);
        QTest::mouseClick(canvas, Qt::LeftButton, {}, QPoint(canvas->width() / 2, canvas->height() / 2));
        QTRY_VERIFY(named(QStringLiteral("Точка 3")));
        QTest::keyClick(canvas, Qt::Key_Right, Qt::ShiftModifier);
        auto *name = host.findChild<QLineEdit *>();
        QVERIFY(name);
        name->setText(QStringLiteral("Вечер"));
        QTest::qWait(300);
        shot("appearance-editor");
        named(QStringLiteral("Сохранить"))->click();
        QTRY_COMPARE(theme->appearance(), QStringLiteral("custom0"));
        const CustomTheme saved = theme->custom(0);
        QCOMPARE(saved.name, QStringLiteral("Вечер"));
        QCOMPARE(saved.spots.size(), 3);
        QVERIFY(qAbs(saved.spots[2].pos.x() - 0.55) < 0.02);

        // The page now lists it; deleting needs a second click and returns to the ocean.
        QTRY_VERIFY(named(QStringLiteral("Вечер")));
        QVERIFY(named(QStringLiteral("Вечер"))->isChecked());
        QTest::qWait(500);
        shot("appearance-list-custom");
        auto *remove = named(QStringLiteral("Удалить"));
        QVERIFY(remove);
        remove->click();
        QVERIFY(theme->custom(0).valid());
        named(QStringLiteral("Точно?"))->click();
        QTRY_VERIFY(!theme->custom(0).valid());
        QCOMPARE(theme->appearance(), QStringLiteral("ocean"));
        Sheet::closeAll(&host);
        QTest::qWait(250);
    }

    void presetBackdropsPaint() {
        const QString dir = qEnvironmentVariable("NEOCHAT_SHOTS");
        if (dir.isEmpty()) QSKIP("no shots directory");
        QDir().mkpath(dir);
        QImage sheet(4 * 310 + 10, 2 * 530 + 10, QImage::Format_ARGB32);
        sheet.fill(Qt::black);
        QPainter p(&sheet);
        p.setRenderHint(QPainter::Antialiasing);
        int col = 0;
        for (const auto &preset : Theme::presets()) {
            for (int row = 0; row < 2; ++row) {
                const Palette c = Theme::instance()->paletteFor(QString::fromLatin1(preset.id), row == 0);
                const QRectF r(10 + col * 310, 10 + row * 530, 300, 520);
                Theme::paintBackdropIn(p, r, r, c);
                p.setPen(Qt::NoPen);
                p.setBrush(c.raised);
                p.drawRoundedRect(r.adjusted(24, 60, -24, -400), 16, 16);
                p.setBrush(c.surface);
                p.drawRoundedRect(r.adjusted(24, 150, -90, -330), 16, 16);
                p.setBrush(c.outgoing);
                p.drawRoundedRect(r.adjusted(100, 230, -24, -260), 16, 16);
                p.setPen(c.text);
                p.drawText(r.adjusted(34, 60, -34, -400), Qt::AlignVCenter, QString::fromUtf8(preset.name) + (row == 0 ? " · ночь" : " · день"));
            }
            ++col;
        }
        p.end();
        sheet.save(dir + "/presets.png");
    }

    void pasteImageAndSeveralFiles() {
        ComposerEdit edit;
        edit.show();
        edit.setFocus();
        QSignalSpy images(&edit, &ComposerEdit::imagePasted);
        QSignalSpy files(&edit, &ComposerEdit::filesDropped);
        QImage image(64, 48, QImage::Format_ARGB32);
        image.fill(Qt::blue);
        QApplication::clipboard()->setImage(image);
        QTest::keyClick(&edit, Qt::Key_V, Qt::ControlModifier);
        QCOMPARE(images.size(), 1);
        QCOMPARE(qvariant_cast<QImage>(images.first().first()), image);
        QVERIFY(edit.toPlainText().isEmpty());

        QTemporaryFile first, second;
        QVERIFY(first.open());
        QVERIFY(second.open());
        auto *mime = new QMimeData;
        mime->setUrls({QUrl::fromLocalFile(first.fileName()), QUrl::fromLocalFile(second.fileName())});
        QApplication::clipboard()->setMimeData(mime);
        QTest::keyClick(&edit, Qt::Key_V, Qt::ControlModifier);
        QCOMPARE(files.size(), 1);
        QCOMPARE(files.first().first().toStringList(), (QStringList{first.fileName(), second.fileName()}));
        QVERIFY(edit.toPlainText().isEmpty());

        QApplication::clipboard()->setText(QStringLiteral("Обычный текст"));
        QTest::keyClick(&edit, Qt::Key_V, Qt::ControlModifier);
        QCOMPARE(edit.toPlainText(), QStringLiteral("Обычный текст"));
    }

    void messageMenuOnlyOnRightClick() {
        MessageView view;
        view.resize(900, 540);
        view.setCanReact(false);
        auto *model = static_cast<MessageModel *>(view.model());
        model->flat = true;
        const QJsonObject message{{"id", "fixture"}, {"kind", "text"}, {"text", QStringLiteral("Тестовое сообщение")}, {"status", "received"}, {"sender_name", QStringLiteral("Тест")}};
        model->setFlat({message});
        view.show();
        QTest::qWait(100);
        const QModelIndex idx = model->index(model->rowCount() - 1);
        const QRect row = view.visualRect(idx);
        const auto layout = MessageDelegate::layout(model->item(idx.row()), row.width(), false);
        const QPoint at = row.topLeft() + layout.text.center().toPoint();
        QTest::mouseClick(view.viewport(), Qt::LeftButton, {}, at);
        QVERIFY(QApplication::activePopupWidget() == nullptr);
        // Context menu events use the same dispatch path as an OS right click.
        QContextMenuEvent event(QContextMenuEvent::Mouse, at, view.viewport()->mapToGlobal(at));
        QApplication::sendEvent(view.viewport(), &event);
        QTRY_VERIFY(dynamic_cast<PopupMenu *>(QApplication::activePopupWidget()));
        auto *menu = dynamic_cast<PopupMenu *>(QApplication::activePopupWidget());
        QTest::qWait(250);
        const QString shots = qEnvironmentVariable("NEOCHAT_SHOTS");
        if (!shots.isEmpty()) { QDir().mkpath(shots); menu->grab().save(shots + "/062-menu.png"); }
        bool copied = false;
        for (auto *action : menu->actions()) {
            if (action->text() == QStringLiteral("Копировать текст")) { action->trigger(); copied = true; }
        }
        QVERIFY(copied);
        QCOMPARE(QApplication::clipboard()->text(), message.value("text").toString());
        QTest::keyClick(menu, Qt::Key_Escape);
        QTRY_VERIFY(QApplication::activePopupWidget() == nullptr);

        // Empty space to the right belongs to the same message for a right click.
        const QPoint beside(view.viewport()->width() - 10, row.center().y());
        QContextMenuEvent second(QContextMenuEvent::Mouse, beside, view.viewport()->mapToGlobal(beside));
        QApplication::sendEvent(view.viewport(), &second);
        QTRY_VERIFY(dynamic_cast<PopupMenu *>(QApplication::activePopupWidget()));
        QTest::keyClick(QApplication::activePopupWidget(), Qt::Key_Escape);
        QTRY_VERIFY(QApplication::activePopupWidget() == nullptr);
    }

    void replyKeepsInputFocusAfterMenuCloses() {
        QWidget window;
        QVBoxLayout layout(&window);
        QLineEdit opener, input;
        layout.addWidget(&opener);
        layout.addWidget(&input);
        window.show();
        QTest::qWait(100);
        opener.setFocus();
        auto *menu = new PopupMenu(&window);
        bool replied = false;
        menu->addAction(QStringLiteral("Ответить"), &window, [&] { replied = true; input.setFocus(); });
        menu->popup(window.mapToGlobal(QPoint(20, 20)));
        QTest::qWait(100);
        QTest::keyClick(menu, Qt::Key_Return);
        QVERIFY(replied);
        QTRY_VERIFY(QApplication::activePopupWidget() == nullptr);
        QVERIFY(input.hasFocus());
        QTest::keyClicks(&input, "reply");
        QCOMPARE(input.text(), QString("reply"));
    }

    // Settings → section: the window behind stays equally dim and the old card is gone at once.
    void switchingSheetsKeepsBackdrop() {
        QWidget host;
        host.resize(900, 640);
        host.show();
        QPointer<Sheet> first = new Sheet(&host, QStringLiteral("Настройки"));
        first->open();
        QTest::qWait(400);
        const QPoint outside(8, 8);
        const QRgb dimmed = host.grab().toImage().pixel(outside);
        first->close();
        auto *second = new Sheet(&host, QStringLiteral("Графика"));
        second->open();
        for (int i = 0; i < 8; ++i) {
            QVERIFY(!first || !first->isVisible());
            QCOMPARE(host.grab().toImage().pixel(outside), dimmed);
            QTest::qWait(30);
        }
    }

    // The tinted body has round corners and thins out into the card instead of ending in a hard edge.
    void sheetBodyMeltsIntoCard() {
        QWidget host;
        host.resize(900, 640);
        host.show();
        auto *sheet = new Sheet(&host, QStringLiteral("Графика"));
        auto *rows = new QWidget;
        rows->setFixedHeight(200);
        sheet->body()->addWidget(rows);
        sheet->open();
        QTest::qWait(400);
        auto *scroll = sheet->findChild<QScrollArea *>();
        QVERIFY(scroll);
        const QImage card = scroll->parentWidget()->grab().toImage();
        const QRect body = scroll->geometry();
        const Palette &c = Theme::p();
        QCOMPARE(card.pixel(body.center()), c.canvas.rgb());
        const QRgb halfway = card.pixel(body.left() - 6, body.center().y());
        QVERIFY(halfway != c.canvas.rgb() && halfway != c.surface.rgb());
        QCOMPARE(card.pixel(body.left() - 16, body.center().y()), c.surface.rgb());
        QVERIFY(card.pixel(body.topLeft()) != c.canvas.rgb());
        const QString shots = qEnvironmentVariable("NEOCHAT_SHOTS");
        if (!shots.isEmpty()) { QDir().mkpath(shots); host.grab().save(shots + "/0672-sheet.png"); }
    }
};

QTEST_MAIN(InteractionTest)
#include "interactiontest.moc"
