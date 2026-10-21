#include "emoji.h"

#include "theme.h"
#include "widgets.h"

#include <QGraphicsOpacityEffect>
#include <QGridLayout>
#include <QPainter>
#include <QPropertyAnimation>
#include <QScrollArea>
#include <QVBoxLayout>

namespace emoji {

const QStringList &quick()
{
    static const QStringList q{QString::fromUtf8("👍"), QString::fromUtf8("❤️"), QString::fromUtf8("😂"), QString::fromUtf8("🔥"), QString::fromUtf8("😮"), QString::fromUtf8("😢"), QString::fromUtf8("🙏")};
    return q;
}

const QList<QPair<QString, QStringList>> &categories()
{
    static const QList<QPair<QString, QStringList>> c = [] {
        QList<QPair<QString, QStringList>> out;
        out.append({QString::fromUtf8("Смайлы"), QString::fromUtf8("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Жесты"), QString::fromUtf8("👋 🤚 🖐 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 👀 👁 👅 👄 🫶 🧠 🫀").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Сердца"), QString::fromUtf8("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💯 💢 💥 💫 💦 💨 🕳 💬 💭 💤").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Люди"), QString::fromUtf8("👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵 💂 🥷 👷 🤴 👸 👳 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 👯 🧖 🧗 🏇 ⛷ 🏂 🏌 🏄 🚣 🏊 ⛹ 🏋 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Животные"), QString::fromUtf8("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦂 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🕊 🐇 🦝 🦨 🦡 🦦 🦥 🐁 🐀 🐿 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🌺 🌻 🌹 🥀 🌷 🌼 🌸 💐 🍄 🌰").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Еда"), QString::fromUtf8("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🧆 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Занятия"), QString::fromUtf8("⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 🛼 ⛸ 🥌 🎿 🏆 🥇 🥈 🥉 🏅 🎖 🎗 🎫 🎟 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟ 🎯 🎳 🎮 🎰 🧩").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Места"), QString::fromUtf8("🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩 💺 🛰 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥 🛳 ⛴ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺 🗿 🗽 🗼 🏰 🏯 🏟 🎡 🎢 🎠 ⛲ ⛱ 🏖 🏝 🏜 🌋 ⛰ 🏔 🗻 🏕 ⛺ 🏠 🏡 🏘 🏚 🏗 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛 ⛪ 🕌 🕍 🛕 🕋 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙 🌃 🌌 🌉 🌁").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Предметы"), QString::fromUtf8("⌚ 📱 💻 ⌨️ 🖥 🖨 🖱 🕹 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙 ⏰ ⏳ 📡 🔋 🔌 💡 🔦 🕯 🧯 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🔧 🔨 ⚒ 🛠 ⛏ 🔩 ⚙️ 🧱 ⛓ 🧲 🔫 💣 🧨 🪓 🔪 🗡 ⚔️ 🛡 🚬 ⚰️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🩹 🩺 💊 💉 🧬 🦠 🧫 🧪 🌡 🧹 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🪒 🧽 🧴 🛎 🔑 🗝 🚪 🛋 🛏 🧸 🖼 🛍 🛒 🎁 🎈 🎏 🎀 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📮 📦 🏷 📋 📅 📆 🗓 📇 📈 📉 📊 📌 📍 📎 🖇 📏 📐 ✂️ 🗃 🗄 🗑 🔒 🔓 🔏 🔐 📝 ✏️ 🔍 🔎").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Символы"), QString::fromUtf8("✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚸 ⛔ 🚫 🔞 ☢️ ☣️ ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ 🔄 🔃 🔀 🔁 🔂 ▶️ ⏩ ⏭ ⏯ ◀️ ⏪ ⏮ 🔼 ⏫ 🔽 ⏬ ⏸ ⏹ ⏺ 🎦 🔅 🔆 📶 📳 📴 ♀️ ♂️ ⚧ ⚕️ ♻️ ⚜️ 🔱 📛 🔰 ⭕ 🌀 💠 🔷 🔶 🔹 🔸 🔺 🔻 ⚪ ⚫ 🔴 🟠 🟡 🟢 🔵 🟣 🟤 🟥 🟧 🟨 🟩 🟦 🟪 🟫 ⬛ ⬜ ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ 🌤 ⛅ 🌥 ☁️ 🌦 🌧 ⛈ 🌩 🌨 ❄️ ☃️ ⛄ 🌬 💧 🌊 🌙 🌚 🌝 🌞 🌍 🌎 🌏 🪐 💫 🎵 🎶 ➰ ➿ 🔔 🔕 📣 📢 🆗 🆕 🆒 🆓 🆙 🔝 🔜 🔛 🔚 🔙").split(' ', Qt::SkipEmptyParts)});
        out.append({QString::fromUtf8("Флаги"), QString::fromUtf8("🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🇷🇺 🇺🇦 🇧🇾 🇰🇿 🇺🇿 🇦🇲 🇬🇪 🇦🇿 🇰🇬 🇹🇯 🇲🇩 🇺🇸 🇬🇧 🇩🇪 🇫🇷 🇮🇹 🇪🇸 🇵🇹 🇳🇱 🇧🇪 🇨🇭 🇦🇹 🇵🇱 🇨🇿 🇸🇰 🇭🇺 🇷🇴 🇧🇬 🇷🇸 🇭🇷 🇬🇷 🇹🇷 🇮🇱 🇪🇬 🇦🇪 🇸🇦 🇮🇳 🇨🇳 🇯🇵 🇰🇷 🇹🇭 🇻🇳 🇮🇩 🇦🇺 🇨🇦 🇲🇽 🇧🇷 🇦🇷 🇨🇱 🇫🇮 🇸🇪 🇳🇴 🇩🇰 🇪🇪 🇱🇻 🇱🇹").split(' ', Qt::SkipEmptyParts)});
        return out;
    }();
    return c;
}

} // namespace emoji

namespace {
// One emoji in the picker, painted by hand.
class EmojiCell : public QAbstractButton {
public:
    EmojiCell(const QString &e, const QFont &font, QWidget *parent) : QAbstractButton(parent), m_font(font)
    {
        setText(e);
        setFixedSize(40, 40);
        setCursor(Qt::PointingHandCursor);
        setAccessibleName(e);
        setFocusPolicy(Qt::StrongFocus);
    }

protected:
    void enterEvent(QEnterEvent *) override { update(); }
    void leaveEvent(QEvent *) override { update(); }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        if (underMouse() || isDown() || hasFocus()) {
            p.setPen(Qt::NoPen);
            p.setBrush(Theme::p().raised);
            p.drawRoundedRect(QRectF(rect()).adjusted(1, 1, -1, -1), 10, 10);
        }
        if (hasFocus())
            ui::paintFocusRing(&p, QRectF(rect()).adjusted(1, 1, -1, -1), 10);
        p.setFont(m_font);
        p.setPen(Theme::p().text);
        p.drawText(rect(), Qt::AlignCenter, text());
    }

private:
    QFont m_font;
};
}

EmojiPopup::EmojiPopup(QWidget *anchor, std::function<void(const QString &)> pick)
    : QWidget(anchor, Qt::Popup | Qt::FramelessWindowHint | Qt::NoDropShadowWindowHint)
{
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_DeleteOnClose);
    setAccessibleName(QStringLiteral("Выбор эмодзи"));
    setFixedSize(392, 400);
    auto *outer = new QVBoxLayout(this);
    outer->setContentsMargins(6, 8, 6, 8);
    auto *scroll = new QScrollArea(this);
    scroll->setFrameShape(QFrame::NoFrame);
    scroll->setWidgetResizable(true);
    scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll->viewport()->setAutoFillBackground(false);
    auto *content = new QWidget;
    content->setAutoFillBackground(false);
    auto *lay = new QVBoxLayout(content);
    lay->setContentsMargins(6, 0, 6, 0);
    lay->setSpacing(4);
    QFont f(QStringLiteral("Segoe UI Emoji"));
    f.setPixelSize(22);
    for (const auto &cat : emoji::categories()) {
        auto *h = new ui::Label(cat.first, 12.5, QFont::DemiBold, ui::Label::Muted);
        lay->addWidget(h);
        auto *gridHost = new QWidget;
        auto *grid = new QGridLayout(gridHost);
        grid->setContentsMargins(0, 0, 0, 6);
        grid->setSpacing(2);
        for (int i = 0; i < cat.second.size(); ++i) {
            const QString e = cat.second[i];
            auto *b = new EmojiCell(e, f, gridHost);
            connect(b, &QAbstractButton::clicked, this, [this, pick, e] {
                pick(e);
                close();
            });
            grid->addWidget(b, i / 8, i % 8);
        }
        lay->addWidget(gridHost);
    }
    scroll->setWidget(content);
    outer->addWidget(scroll);
}

void EmojiPopup::popup(const QPoint &bottomRight)
{
    move(bottomRight - QPoint(width(), height() + 8));
    auto *fx = new QGraphicsOpacityEffect(this);
    setGraphicsEffect(fx);
    auto *fade = new QPropertyAnimation(fx, "opacity", this);
    fade->setStartValue(Theme::ms(180) ? 0.0 : 1.0);
    fade->setEndValue(1.0);
    fade->setDuration(qMax(1, Theme::ms(180)));
    connect(fade, &QPropertyAnimation::finished, this, [this] { setGraphicsEffect(nullptr); });
    show();
    fade->start(QAbstractAnimation::DeleteWhenStopped);
    if (auto *first = findChild<QAbstractButton *>())
        first->setFocus();
}

void EmojiPopup::paintEvent(QPaintEvent *)
{
    const Palette &c = Theme::p();
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(QPen(c.border, 1));
    p.setBrush(c.surface);
    p.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), 16, 16);
}
