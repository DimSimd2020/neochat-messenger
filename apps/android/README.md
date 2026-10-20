# NeoChat для Android

Kotlin-клиент на Android Views без Compose и WebView. Шифрование, хранилище, синхронизация и очередь отправки находятся в общем Rust-ядре (`core/`). Ядро подключено через JNI как `libneocore.so`.

- minSdk 26, targetSdk 35, ABI `arm64-v8a` и `x86_64`.
- Ключ базы SQLCipher: 32 случайных байта, обёрнутые AES-GCM-ключом из Android Keystore.
- Резервное копирование отключено (`allowBackup=false`, `dataExtractionRules`).
- Экран с кодом восстановления защищён `FLAG_SECURE`.
- Уведомления показывают только название чата, без текста сообщения.
- В 0.6.2: свайп ответа по всей строке сообщения; анимированный статус набора; смена фото профиля; выбор чатов удержанием и массовые действия; локальная очистка истории; компактный архив раскрывается потягиванием списка вниз и скрывается при возврате. Фото профиля требует обновления сервера с миграцией `0006_avatar.sql`.

## Сборка

Нужны JDK 17, Android SDK 35, NDK 26.3, Rust с целями `aarch64-linux-android` и `x86_64-linux-android`, `cargo-ndk`, `protoc`. Пути к инструментам на этой машине задаёт `E:/dev/env.sh`.

1. Соберите ядро в `jniLibs`. Обёртка `uperl.bat` нужна OpenSSL под Windows: Perl от Git с модулями из `E:/dev/perl5lib`, плюс обход ассемблера NDK r26 для SM3.

```bash
source /e/dev/env.sh
cd core
PERL=E:/dev/shim/uperl.bat PATH=/e/dev/shim:$PATH \
  cargo ndk -t arm64-v8a -t x86_64 --platform 26 -o ../apps/android/app/src/main/jniLibs build --release --lib
```

2. Соберите APK. Подпись хранится вне репозитория, в файле свойств с полями `storeFile`, `storePassword`, `keyAlias`, `keyPassword`:

```bash
cd apps/android
NEOCHAT_SIGNING=E:/dev/keys/signing.properties ./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Без `NEOCHAT_SIGNING` release-сборка получится неподписанной. Debug-сборка: `./gradlew assembleDebug`.

## Проверка

Проверка на эмуляторе `Medium_Phone_API_36.1` (x86_64) против рабочего сервера:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.neochat/.ui.MainActivity
```

Второго участника можно запустить из ядра (`core/examples/peer.rs`):

```bash
cd core && cargo build --release --example peer
target/release/examples/peer <dir> register https://r1270286.s2566.vm.mydomain.zone:8443 boris_test
target/release/examples/peer <dir> send <username> "Привет"
target/release/examples/peer <dir> expect <username> "Ответ"
```

Пройденные шаги:
1. Регистрация. Код восстановления не попадает на снимок экрана.
2. Входящее сообщение и уведомление.
3. Ответ, статусы до «Прочитано».
4. Код безопасности в сведениях.
5. Вход на новом телефоне по паролю: ключи и переписка восстанавливаются из копии на сервере.
6. «Устройства → Сканировать QR-код»: запрос доступа к камере, превью Camera2, закрытие сканера и возврат из фона без сбоев. Декодирование ZXing проверено на кадре с выравниванием строк (row stride).

### Регрессии 0.6.2

Проверка жеста на эмуляторе без дополнительных тестовых библиотек:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.neochat.test/app.neochat.ui.InteractionInstrumentation
```

Проверяет свайпы из пустой части строки в обе стороны, вертикальную прокрутку, системный край «назад» и отменённый жест. Результат 0.6.2: пройдено на x86_64, API 36.1. Полный список проверок и ограничения iOS — [заметки 0.6.2](../../docs/12-release-0.6.2.md).

## Ограничения

- Push через FCM не подключён, нужны учётные данные Firebase. Пока приложение закрыто, новые сообщения забирает периодическая задача JobScheduler раз в 15 минут.
- «Устройства → Сканировать QR-код» впускает компьютер, который показывает QR-код на экране входа (Camera2 и ZXing core, разрешение `CAMERA` запрашивается при первом нажатии).
- Размытие стекла заменено полупрозрачной заливкой с контуром. Такую замену разрешает спецификация.
- Звонков нет. «Недавние звонки» показывает пустое окно.
