#include "keystore.h"

#include <QDir>
#include <QFile>
#include <QSaveFile>

#include <windows.h>
#include <bcrypt.h>
#include <dpapi.h>

namespace {

const wchar_t kDescription[] = L"NeoChat database key";
const char kEntropy[] = "NeoChat-db-key-v1";

QByteArray dpapi(const QByteArray &in, bool protect)
{
    DATA_BLOB input{DWORD(in.size()), reinterpret_cast<BYTE *>(const_cast<char *>(in.data()))};
    DATA_BLOB entropy{DWORD(sizeof kEntropy - 1), reinterpret_cast<BYTE *>(const_cast<char *>(kEntropy))};
    DATA_BLOB output{};
    const BOOL ok = protect
        ? CryptProtectData(&input, kDescription, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output)
        : CryptUnprotectData(&input, nullptr, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output);
    if (!ok)
        return {};
    QByteArray out(reinterpret_cast<const char *>(output.pbData), int(output.cbData));
    SecureZeroMemory(output.pbData, output.cbData);
    LocalFree(output.pbData);
    return out;
}

} // namespace

QString loadOrCreateDatabaseKey(const QString &dataDir, QString *error)
{
    QDir().mkpath(dataDir);
    const QString path = dataDir + "/db.key";
    QFile f(path);
    if (f.exists()) {
        if (!f.open(QIODevice::ReadOnly)) {
            *error = QStringLiteral("Нет доступа к ключу хранилища");
            return {};
        }
        QByteArray key = dpapi(f.readAll(), false);
        if (key.size() != 32) {
            *error = QStringLiteral("Ключ хранилища недоступен для этого пользователя Windows");
            return {};
        }
        const QString hex = QString::fromLatin1(key.toHex());
        key.fill(0);
        return hex;
    }
    QByteArray key(32, 0);
    if (!BCRYPT_SUCCESS(BCryptGenRandom(nullptr, reinterpret_cast<PUCHAR>(key.data()), 32, BCRYPT_USE_SYSTEM_PREFERRED_RNG))) {
        *error = QStringLiteral("Не удалось создать ключ хранилища");
        return {};
    }
    const QByteArray sealed = dpapi(key, true);
    QSaveFile out(path);
    if (sealed.isEmpty() || !out.open(QIODevice::WriteOnly) || out.write(sealed) != sealed.size() || !out.commit()) {
        *error = QStringLiteral("Не удалось сохранить ключ хранилища");
        return {};
    }
    const QString hex = QString::fromLatin1(key.toHex());
    key.fill(0);
    return hex;
}
