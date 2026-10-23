#pragma once

#include <QJsonObject>
#include <QMainWindow>
#include <QQueue>
#include <QVariantAnimation>
#include <functional>

class QStackedWidget;
class QSystemTrayIcon;
class QTimer;
class ChatPanel;
class MessageView;
class Composer;
class Onboarding;
class Rail;
class SlideBox;
class ConversationHeader;
class NoticeBar;
class PinBar;
namespace dialogs {
class Page;
}
namespace ui {
enum class Motion;
class Toast;
class SearchField;
class IconButton;
class Label;
}
struct DialogHost;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    MainWindow();
    void start();

protected:
    void resizeEvent(QResizeEvent *e) override;
    void closeEvent(QCloseEvent *e) override;
    void changeEvent(QEvent *e) override;

private:
    void onEvent(const QJsonObject &ev);
    void showOnboarding(const QJsonObject &status);
    void showMain();
    void openConversation(const QString &id);
    void refreshHeader();
    void applyConversation(const QJsonObject &c);
    void updateNotice();
    void setSidebarHidden(bool hidden, bool animated = true);
    void setTab(int tab);
    void selectTab(int tab);
    void showList(bool list);
    void animateConversation(ui::Motion motion, const std::function<void()> &change);
    void updateLayoutMode();
    void sendText(const QString &text, const QString &replyTo);
    void editText(const QString &id, const QString &text);
    void attachDialog(const QStringList &paths, bool photos);
    void sendFiles(const QStringList &paths, bool photos, const QString &caption = {});
    void sendNextFile();
    void markRead();
    void toast(const QString &text);
    void notify(const QJsonObject &ev);
    DialogHost host();
    void checkUpdate(bool manual = false);
    void refreshPresence();
    void refreshEmail();

    QStackedWidget *m_stack;
    Onboarding *m_onboarding;
    QWidget *m_main;
    SlideBox *m_side;
    Rail *m_rail;
    ChatPanel *m_panel;
    QWidget *m_conversationArea;
    QWidget *m_pagesArea;
    QStackedWidget *m_pages;
    dialogs::Page *m_contacts = nullptr;
    dialogs::Page *m_calls = nullptr;
    int m_tab = 0; // rail section: 0 chats, 1 calls, 2 contacts
    QWidget *m_conversation;
    QWidget *m_placeholder;
    ConversationHeader *m_header;
    NoticeBar *m_notice;
    PinBar *m_pinBar = nullptr;
    NoticeBar *m_updateBar;
    NoticeBar *m_emailBar;
    bool m_emailPrompted = false;
    QString m_updatePath;
    ui::SearchField *m_search;
    QWidget *m_searchWrap;
    MessageView *m_view;
    Composer *m_composer;
    ui::Toast *m_toast;
    QSystemTrayIcon *m_tray = nullptr;
    QTimer *m_listTimer;
    QTimer *m_messagesTimer;
    QVariantAnimation m_sideAnim;
    QVariantAnimation m_searchAnim;
    QJsonObject m_current;
    QString m_connection = "connecting";
    QString m_pendingConversation;
    bool m_sidebarHidden = false;
    bool m_onePane = false;
    bool m_listMode = true; // one-pane: list shown instead of the conversation
    bool m_readPending = false;
    QQueue<QJsonObject> m_files;
    bool m_preparingFile = false;
};
