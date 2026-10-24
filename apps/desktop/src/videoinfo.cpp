#include "videoinfo.h"
#include <QBuffer>
#include <QDir>
#include <QImage>
#include <windows.h>

#include <propkey.h>
#include <propsys.h>
#include <shobjidl.h>
#include <string>

namespace videoinfo {
namespace {
// Shrinks the frame until its JPEG, as base64, fits the encrypted envelope.
QString encode(const QImage &frame)
{
    for (int side : {160, 120, 90}) {
        QByteArray jpeg;
        QBuffer buf(&jpeg);
        buf.open(QIODevice::WriteOnly);
        frame.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation).save(&buf, "JPEG", 55);
        const QByteArray b64 = jpeg.toBase64();
        if (b64.size() <= 12000)
            return QString::fromLatin1(b64);
    }
    return {};
}

QImage fromBitmap(HBITMAP bitmap)
{
    BITMAP bm{};
    if (!GetObjectW(bitmap, sizeof bm, &bm) || bm.bmWidth <= 0 || bm.bmHeight <= 0)
        return {};
    BITMAPINFO info{};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = bm.bmWidth;
    info.bmiHeader.biHeight = -bm.bmHeight;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    // The thumbnail of a video is opaque: its alpha byte is ignored.
    QImage img(bm.bmWidth, bm.bmHeight, QImage::Format_RGB32);
    HDC dc = GetDC(nullptr);
    const int rows = GetDIBits(dc, bitmap, 0, UINT(bm.bmHeight), img.bits(), &info, DIB_RGB_COLORS);
    ReleaseDC(nullptr, dc);
    return rows == bm.bmHeight ? img : QImage();
}

quint32 number(IPropertyStore *store, const PROPERTYKEY &key)
{
    PROPVARIANT v;
    PropVariantInit(&v);
    quint32 out = 0;
    if (SUCCEEDED(store->GetValue(key, &v)) && v.vt == VT_UI4)
        out = v.ulVal;
    PropVariantClear(&v);
    return out;
}
}

Info read(const QString &path)
{
    Info out;
    // The thread pool's threads have no COM yet; S_FALSE (already there) also has to be balanced.
    const bool com = SUCCEEDED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED));
    const std::wstring name = QDir::toNativeSeparators(path).toStdWString();

    QSize declared;
    IPropertyStore *store = nullptr;
    if (SUCCEEDED(SHGetPropertyStoreFromParsingName(name.c_str(), nullptr, GPS_DEFAULT, IID_PPV_ARGS(&store))) && store) {
        PROPVARIANT v;
        PropVariantInit(&v);
        if (SUCCEEDED(store->GetValue(PKEY_Media_Duration, &v)) && v.vt == VT_UI8)
            out.durationMs = qint64(v.uhVal.QuadPart / 10000);
        PropVariantClear(&v);
        declared = QSize(int(number(store, PKEY_Video_FrameWidth)), int(number(store, PKEY_Video_FrameHeight)));
        store->Release();
    }

    QSize frame;
    IShellItemImageFactory *factory = nullptr;
    if (SUCCEEDED(SHCreateItemFromParsingName(name.c_str(), nullptr, IID_PPV_ARGS(&factory))) && factory) {
        HBITMAP bitmap = nullptr;
        if (SUCCEEDED(factory->GetImage(SIZE{320, 320}, SIIGBF_THUMBNAILONLY | SIIGBF_BIGGERSIZEOK, &bitmap)) && bitmap) {
            const QImage img = fromBitmap(bitmap);
            if (!img.isNull()) {
                frame = img.size();
                out.thumb = encode(img);
            }
            DeleteObject(bitmap);
        }
        factory->Release();
    }
    // A phone's portrait video is stored turned: trust the orientation of the picture the shell cut.
    if (!declared.isEmpty() && !frame.isEmpty() && (declared.width() > declared.height()) != (frame.width() > frame.height()))
        declared.transpose();
    out.size = declared.isEmpty() ? frame : declared;
    if (com)
        CoUninitialize();
    return out;
}
}
