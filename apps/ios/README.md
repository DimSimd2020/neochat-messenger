# NeoChat для iOS

Клиент на Swift и UIKit, iOS 15 и новее. Шифрование, хранилище и синхронизация находятся в общем Rust-ядре (`core/`). Ядро статически линкуется как `libneocore.a`.

- Ключ базы SQLCipher хранится в Keychain (`AfterFirstUnlockThisDeviceOnly`).
- Интерфейс только на русском: `CFBundleDevelopmentRegion` и `CFBundleLocalizations` = `ru`, поэтому системные кнопки («Закрыть», «Отменить») тоже показываются по-русски.
- Привязка нового устройства: «Устройства → Сканировать QR-код» (AVFoundation). Если камеры нет, код можно вставить вручную.
- Уведомления показывают только название чата.
- В 0.6.1: защита свайпа назад во время переходов, действия по всей ширине сообщения, удаление своих сообщений у всех, пересылка и короткое появление реакций. Галочки доставки повторяют Android.

## Сборка

Собирается в GitHub Actions на macOS (`.github/workflows/ios.yml`) как неподписанная IPA для установки через Scarlet. Локально на Mac:

```bash
rustup target add aarch64-apple-ios
cd core && cargo build --release --lib --target aarch64-apple-ios && cd ..
cd apps/ios && xcodegen generate
xcodebuild -project NeoChat.xcodeproj -scheme NeoChat -configuration Release -sdk iphoneos \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

## Проверка

CI проверяет, что сборка проходит и ядро слинковано статически (`otool -L` не находит `neocore`). На реальном устройстве нужно пройти вход, переписку, вход компьютера по QR и сверку кода безопасности.

## Ограничения

- Мгновенных push-уведомлений нет: для APNs нужна подпись разработчика. Новые сообщения приходят, пока приложение открыто, или при фоновом обновлении (`BGAppRefreshTask`).
- Звонков нет.
