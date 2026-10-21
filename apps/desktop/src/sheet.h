#pragma once

#include <QPointer>
#include <QVariantAnimation>
#include <QWidget>
#include <functional>

class QHBoxLayout;
class QVBoxLayout;
class QGraphicsOpacityEffect;

namespace ui {
class Label;
class IconButton;
}

// Modal surface over the main window: dimmed backdrop (220 ms), card fades in
// (180 ms) and moves 10 px (220 ms). Escape, the close button and a real click
// outside close it; a press that started inside the card never does. Focus
// returns to the control that opened it.
class Sheet : public QWidget {
    Q_OBJECT
public:
    Sheet(QWidget *host, const QString &title, int width = 460);
    QVBoxLayout *body() const { return m_body; }
    void setTitle(const QString &title);
    // Shows a back arrow before the title; it closes this sheet and runs `to`.
    void setBack(std::function<void()> to);
    void open();
    void close();
    // The dialog itself refuses closing while a request is in flight.
    void setBusy(bool busy) { m_busy = busy; }

    static void closeAll(QWidget *host);
    static bool anyOpen(QWidget *host);

signals:
    void closed();

protected:
    void paintEvent(QPaintEvent *) override;
    void mousePressEvent(QMouseEvent *) override;
    void mouseReleaseEvent(QMouseEvent *) override;
    void keyPressEvent(QKeyEvent *) override;
    bool eventFilter(QObject *o, QEvent *e) override;

private:
    void layoutCard();
    void step(qreal t);

    QWidget *m_card;
    QVBoxLayout *m_body;
    ui::Label *m_title;
    ui::IconButton *m_back = nullptr;
    QHBoxLayout *m_head = nullptr;
    QGraphicsOpacityEffect *m_fade;
    QPointer<QWidget> m_opener;
    QVariantAnimation m_anim;
    int m_width;
    qreal m_t = 0;
    bool m_closing = false;
    bool m_pressOutside = false;
    bool m_busy = false;
};
