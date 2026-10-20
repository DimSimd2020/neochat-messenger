#pragma once

#include <QAction>
#include <QPointer>
#include <QVariantAnimation>
#include <QWidget>
#include <functional>

class QVBoxLayout;

// Native widgets in a rounded surface; no platform QMenu or nested event loop.
class PopupMenu : public QWidget {
public:
    explicit PopupMenu(QWidget *parent);
    QAction *addAction(const QString &text, QObject *context, std::function<void()> run);
    void addWidget(QWidget *widget);
    void addSeparator();
    void popup(const QPoint &globalPos);
    void dismiss();

protected:
    void paintEvent(QPaintEvent *) override;
    void hideEvent(QHideEvent *) override;
    bool eventFilter(QObject *, QEvent *) override;

private:
    QVBoxLayout *m_layout;
    QVariantAnimation m_anim;
    QPointer<QWidget> m_opener;
    QPoint m_target;
    bool m_closing = false;
};
