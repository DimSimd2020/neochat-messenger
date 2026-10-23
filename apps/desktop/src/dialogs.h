#pragma once

#include <QJsonArray>
#include <QString>
#include <QStringList>
#include <QWidget>
#include <functional>

// What the dialogs need from the main window.
struct DialogHost {
    QWidget *host = nullptr;
    std::function<void(const QString &conversationId)> openChat;
    std::function<void(const QString &text)> toast;
    std::function<void()> logout;
    std::function<void(bool enterSends)> enterSendsChanged;
    std::function<void()> checkUpdate;
};

namespace dialogs {
// A section of the main window that fills the space next to the rail; `reload` refreshes its data.
class Page : public QWidget {
    Q_OBJECT
public:
    using QWidget::QWidget;
    std::function<void()> reload;
};
Page *contactsPage(const DialogHost &h);
Page *callsPage(const DialogHost &h);
void newContact(const DialogHost &h, std::function<void()> added = {});
void newChat(const DialogHost &h);
void devices(const DialogHost &h);
void accounts(const DialogHost &h);
void settings(const DialogHost &h);
void graphics(const DialogHost &h);
// Built-in themes, the user's own, and the editor with gradient points.
void appearance(const DialogHost &h);
void whatsNew(const DialogHost &h);
void profile(const DialogHost &h);
void editProfile(const DialogHost &h);
void myQr(const DialogHost &h);
void profileQr(const DialogHost &h, const QString &userId = {});
void conversationInfo(const DialogHost &h, const QString &conversationId);
void newPoll(const DialogHost &h, const QString &conversationId);
void forward(const DialogHost &h, const QJsonArray &msgs);
// Preview of the picked files with a caption field and "send as a file" for pictures; `send` gets the final set.
void sendAttachments(const DialogHost &h, const QStringList &paths, bool asPhotos, std::function<void(const QStringList &paths, bool asPhotos, const QString &caption)> send, std::function<void()> cancelled = {});
}
