#include "composer.h"
#include "messages.h"
#include "popupmenu.h"
#include "sheet.h"
#include "theme.h"

#include <QApplication>
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
    void initTestCase() { Theme::instance()->init(); }

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
