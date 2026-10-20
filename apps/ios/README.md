# NeoChat для iOS

Клиент на Swift и UIKit, iOS 15 и новее. Шифрование, хранилище и синхронизация находятся в общем Rust-ядре (`core/`). Ядро статически линкуется как `libneocore.a`.

- Ключ базы SQLCipher хранится в Keychain (`AfterFirstUnlockThisDeviceOnly`).
- Интерфейс только на русском: `CFBundleDevelopmentRegion` и `CFBundleLocalizations` = `ru`, поэтому системные кнопки («Закрыть», «Отменить») тоже показываются по-русски.
- Привязка нового устройства: «Устройства → Сканировать QR-код» (AVFoundation). Если камеры нет, код можно вставить вручную.
- Уведомления показывают только название чата.
- В 0.6.2: собственный непрозрачный фон переписки и отключение неявных переходов фоновых слоёв для устранения наложения текста; анимированный статус набора; смена фото профиля; выбор чатов и массовые действия; локальная очистка; компактный архив по потягиванию списка вниз. Фото профиля требует обновления сервера с миграцией `0006_avatar.sql`.

## Сборка

Собирается в GitHub Actions на macOS (`.github/workflows/ios.yml`) как неподписанная IPA для установки через Scarlet. Локально на Mac:

```bash
rustup target add aarch64-apple-ios
cd core && cargo build --release --lib --target aarch64-apple-ios && cd ..
cd apps/ios && xcodegen generate
xcodebuild -project NeoChat.xcodeproj -scheme NeoChat -configuration Release -sdk iphoneos \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

Для ветки с изменениями достаточно отправить коммиты и запустить workflow:

```bash
git push
gh workflow run ios.yml --ref codex/release-0.6.2
gh run list --workflow ios.yml --branch codex/release-0.6.2
gh run download RUN_ID --name NeoChat-unsigned-ipa --dir dist/ios-ci
```

Ручной запуск также выполняет UI-тест на симуляторе. Артефакт `NeoChat-unsigned-ipa` содержит IPA; `ios-simulator-shots` — снимки теста. Сборка ветки не публикует GitHub Release.

## Проверка

CI проверяет, что сборка проходит и ядро слинковано статически (`otool -L` не находит `neocore`). На реальном устройстве нужно пройти вход, переписку, вход компьютера по QR и сверку кода безопасности.

UI-тест также проверяет свайп по отправленному сообщению, открытие его меню нажатием, выбор «Ответить» и отмену ответа. После действий приложение должно оставаться открытым в том же чате. Этот сценарий нужно повторить на физическом iPhone: успешный тест симулятора сам по себе не подтверждает устранение вылета на устройстве.

## Ограничения

IPA 0.6.2 (build 12, arm64, iOS 15+) собрана в [GitHub Actions](https://github.com/DimSimd2020/neochat-messenger/actions/runs/39474400837), коммит `837c059`, на macOS с Xcode 16.4 / iPhoneOS 18.5. Сборка IPA и UI-тест на симуляторе прошли; архив и версия артефакта проверены после загрузки. Проверка на физическом iPhone остаётся отдельным шагом; сценарии перечислены в [заметках 0.6.2](../../docs/12-release-0.6.2.md).

- Мгновенных push-уведомлений нет: для APNs нужна подпись разработчика. Новые сообщения приходят, пока приложение открыто, или при фоновом обновлении (`BGAppRefreshTask`).
- Звонков нет.
