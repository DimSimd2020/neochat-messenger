#include "mathtext.h"

#include <QAbstractTextDocumentLayout>
#include <QFontInfo>
#include <QFontMetrics>
#include <QJsonArray>
#include <QPainter>
#include <QPainterPath>
#include <QTextBlock>
#include <QTextCharFormat>
#include <QTextDocument>
#include <QTextFragment>
#include <QTextObject>
#include <cmath>
#include <memory>

namespace mathtext {
namespace {

constexpr int ObjectType = QTextFormat::UserObject + 1;
constexpr int ShapeProp = QTextFormat::UserProperty;
constexpr int SourceProp = QTextFormat::UserProperty + 1;
constexpr int EmProp = QTextFormat::UserProperty + 2;

struct Placed {
    int glyph;
    qreal x, y, scale;
};

// The display list of one formula, in em of the surrounding text, y down from the baseline.
struct Shape {
    qreal w = 0, h = 0, d = 0;
    QHash<int, QPainterPath> glyphs;
    QVector<Placed> placed;
    QVector<QRectF> rules;
};
using ShapePtr = std::shared_ptr<const Shape>;

ShapePtr parse(const QJsonObject &math)
{
    auto s = std::make_shared<Shape>();
    s->w = math.value("w").toDouble();
    s->h = math.value("h").toDouble();
    s->d = math.value("d").toDouble();
    const QJsonObject glyphs = math.value("glyphs").toObject();
    for (auto it = glyphs.begin(); it != glyphs.end(); ++it) {
        const QJsonArray c = it.value().toArray();
        QPainterPath path;
        for (int i = 0; i < c.size();) {
            const int cmd = c[i].toInt();
            auto v = [&](int k) { return c[i + k].toDouble(); };
            switch (cmd) {
            case 0: path.moveTo(v(1), v(2)); i += 3; break;
            case 1: path.lineTo(v(1), v(2)); i += 3; break;
            case 2: path.quadTo(v(1), v(2), v(3), v(4)); i += 5; break;
            case 3: path.cubicTo(v(1), v(2), v(3), v(4), v(5), v(6)); i += 7; break;
            default: path.closeSubpath(); i += 1; break;
            }
        }
        s->glyphs.insert(it.key().toInt(), path);
    }
    for (const auto &v : math.value("g").toArray()) {
        const QJsonArray g = v.toArray();
        s->placed.append({g[0].toInt(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble()});
    }
    for (const auto &v : math.value("r").toArray()) {
        const QJsonArray r = v.toArray();
        s->rules.append(QRectF(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble()));
    }
    return s;
}

} // namespace
} // namespace mathtext

Q_DECLARE_METATYPE(mathtext::ShapePtr)

namespace mathtext {
namespace {

class Handler : public QObject, public QTextObjectInterface {
    Q_OBJECT
    Q_INTERFACES(QTextObjectInterface)
public:
    // For AlignBaseline Qt puts the descent of the object's font below the baseline and the rest above,
    // so the object is `h` em tall above the baseline plus that descent; insert() picks a font whose
    // descent covers the depth of the formula.
    QSizeF intrinsicSize(QTextDocument *, int, const QTextFormat &f) override
    {
        const ShapePtr s = f.property(ShapeProp).value<ShapePtr>();
        if (!s)
            return {};
        const qreal em = f.property(EmProp).toDouble();
        return QSizeF(s->w * em, s->h * em + QFontMetrics(f.toCharFormat().font()).descent());
    }

    void drawObject(QPainter *p, const QRectF &rect, QTextDocument *, int, const QTextFormat &f) override
    {
        const ShapePtr s = f.property(ShapeProp).value<ShapePtr>();
        if (!s)
            return;
        const qreal em = f.property(EmProp).toDouble();
        // The pen holds the color of the text around the formula.
        const QColor color = p->pen().color();
        p->save();
        p->setRenderHint(QPainter::Antialiasing);
        p->setPen(Qt::NoPen);
        p->setBrush(color);
        p->translate(rect.left(), rect.bottom() - QFontMetrics(f.toCharFormat().font()).descent());
        for (const Placed &g : s->placed) {
            p->save();
            p->translate(g.x * em, g.y * em);
            p->scale(em * g.scale, em * g.scale);
            p->drawPath(s->glyphs.value(g.glyph));
            p->restore();
        }
        for (const QRectF &r : s->rules)
            p->fillRect(QRectF(r.x() * em, r.y() * em, r.width() * em, r.height() * em), color);
        p->restore();
    }
};

Handler *handler()
{
    static Handler *h = new Handler;
    return h;
}

} // namespace

void insert(QTextCursor &cur, const QJsonObject &span, const QFont &font)
{
    QTextDocument *d = cur.document();
    if (!d->documentLayout()->handlerForObject(ObjectType))
        d->documentLayout()->registerHandler(ObjectType, handler());
    const bool display = span.value("display").toBool();
    QTextCharFormat f;
    f.setObjectType(ObjectType);
    const ShapePtr shape = parse(span.value("math").toObject());
    f.setProperty(ShapeProp, QVariant::fromValue(shape));
    f.setProperty(SourceProp, span.value("text").toString());
    const qreal em = QFontInfo(font).pixelSize();
    f.setProperty(EmProp, em);
    // The descent of this font is what the line keeps below the baseline for the formula.
    QFont metrics = font;
    const int depth = int(std::ceil(shape->d * em));
    if (const int own = QFontMetrics(font).descent(); own < depth) {
        // Descent grows with the size of the font: jump near the size that has enough and step the rest.
        metrics.setPixelSize(int(std::ceil(em * depth / qMax(1, own))));
        for (int i = 0; i < 40 && QFontMetrics(metrics).descent() < depth; ++i)
            metrics.setPixelSize(metrics.pixelSize() + 1);
    }
    f.setFont(metrics);
    f.setVerticalAlignment(QTextCharFormat::AlignBaseline);
    if (display) {
        d->setProperty("mathDisplay", true);
        // A display formula stands alone on its line, centered.
        if (!cur.atBlockStart())
            cur.insertBlock();
        QTextBlockFormat b;
        b.setAlignment(Qt::AlignHCenter);
        cur.setBlockFormat(b);
        cur.insertText(QString(QChar::ObjectReplacementCharacter), f);
        cur.insertBlock();
        cur.setBlockFormat(QTextBlockFormat());
        cur.setCharFormat({});
        return;
    }
    cur.insertText(QString(QChar::ObjectReplacementCharacter), f);
}

qreal fit(QTextDocument *d, qreal inner)
{
    if (!d->property("mathDisplay").toBool()) {
        if (d->textWidth() != inner)
            d->setTextWidth(inner);
        return qMin(inner, std::ceil(d->idealWidth()) + 1);
    }
    // A display formula is centered in the width of the text, so the bubble must be exactly as wide as the document.
    if (d->property("fitFor").toReal() != inner) {
        d->setTextWidth(inner);
        d->setProperty("fit", std::ceil(d->idealWidth()) + 1);
        d->setProperty("fitFor", inner);
    }
    const qreal width = qMin(inner, d->property("fit").toReal());
    if (d->textWidth() != width)
        d->setTextWidth(width);
    return width;
}

QString plainText(QTextDocument *d, int from, int to)
{
    QString out;
    for (QTextBlock b = d->findBlock(from); b.isValid() && b.position() < to; b = b.next()) {
        if (b != d->findBlock(from))
            out += QChar(u'\n');
        for (auto it = b.begin(); !it.atEnd(); ++it) {
            const QTextFragment fr = it.fragment();
            const int a = qMax(from, fr.position()), z = qMin(to, fr.position() + fr.length());
            if (a >= z)
                continue;
            const QString source = fr.charFormat().property(SourceProp).toString();
            const QString part = fr.text().mid(a - fr.position(), z - a);
            // Equal formulas side by side share a fragment: one object character each.
            out += source.isEmpty() ? part : source.repeated(part.size());
        }
    }
    return out;
}

} // namespace mathtext

#include "mathtext.moc"
