#pragma once

#include <QString>
#include <functional>

class QWidget;

// What the dialogs need from the main window.
struct DialogHost {
    QWidget *host = nullptr;
    std::function<void(const QString &conversationId)> openChat;
    std::function<void(const QString &text)> toast;
    std::function<void()> logout;
    std::function<void(bool enterSends)> enterSendsChanged;
};

namespace dialogs {
void contacts(const DialogHost &h);
void newContact(const DialogHost &h, std::function<void()> added = {});
void newChat(const DialogHost &h);
void devices(const DialogHost &h);
void settings(const DialogHost &h);
void profile(const DialogHost &h);
void conversationInfo(const DialogHost &h, const QString &conversationId);
void newPoll(const DialogHost &h, const QString &conversationId);
}
