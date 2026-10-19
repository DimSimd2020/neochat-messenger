#pragma once

#include <QJsonObject>
#include <QMainWindow>
#include <QVariantAnimation>

class QStackedWidget;
class QSystemTrayIcon;
class QTimer;
class ChatPanel;
class MessageView;
class Composer;
class Onboarding;
class Rail;
class ConversationHeader;
class NoticeBar;
namespace ui {
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
    void updateLayoutMode();
    void sendText(const QString &text, const QString &replyTo);
    void sendFiles(const QStringList &paths, bool photos);
    void markRead();
    void toast(const QString &text);
    void notify(const QJsonObject &ev);
    DialogHost host();
    void checkUpdate();

    QStackedWidget *m_stack;
    Onboarding *m_onboarding;
    QWidget *m_main;
    QWidget *m_side;
    Rail *m_rail;
    ChatPanel *m_panel;
    QWidget *m_conversationArea;
    QWidget *m_conversation;
    QWidget *m_placeholder;
    ConversationHeader *m_header;
    NoticeBar *m_notice;
    NoticeBar *m_updateBar;
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
    bool m_sidebarHidden = false;
    bool m_onePane = false;
    bool m_listMode = true; // one-pane: list shown instead of the conversation
    bool m_readPending = false;
};
