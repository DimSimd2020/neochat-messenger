#pragma once

#include <QString>

// Returns the 32-byte database key as hex. The key is random, created on first
// run and stored only in DPAPI-protected form bound to the Windows user.
QString loadOrCreateDatabaseKey(const QString &dataDir, QString *error);
