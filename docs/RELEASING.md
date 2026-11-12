# Выпуск версий

## Как устроена сборка

Workflow `.github/workflows/build.yml` запускается при каждом push в `main` (и вручную
кнопкой **Run workflow**):

1. модульные тесты;
2. релизная сборка `assembleRelease`;
3. публикация APK и `SHA256SUMS.txt` в GitHub Releases.

Тег и тип релиза определяются по `versionName` в `app/build.gradle.kts`:

| versionName | Тег | Тип |
|---|---|---|
| `1.0.0` (без суффикса) | `v1.0.0` | обычный релиз |
| `1.1.0-dev` (с суффиксом) | `v1.1.0-dev-build.N` | pre-release на каждый push |

Если релиз с таким тегом уже есть, новый не создаётся — нужно поднять версию.

## Выпуск новой версии

1. В `app/build.gradle.kts` увеличьте `versionCode` (на 1) и задайте `versionName`.
2. Добавьте описание изменений:
   - `CHANGELOG.md`;
   - `fastlane/metadata/android/ru-RU/changelogs/<versionCode>.txt` и `en-US/…`
     (до 500 символов; используется как текст релиза и в F-Droid).
3. Влейте в `main`. Через несколько минут релиз появится в Releases, а F-Droid подхватит
   новый тег автоматически (см. [FDROID.md](FDROID.md)).

Между выпусками удобно держать в `main` версию с суффиксом (`1.1.0-dev`), чтобы каждая
сборка публиковалась как pre-release.

## Подпись

Без настроенных секретов APK подписывается общим тестовым ключом `app/debug.keystore`,
который лежит в репозитории. Это удобно для тестирования, но **ключ публичный**: кто угодно
может собрать APK с той же подписью. Для распространения используйте собственный ключ.

### Свой ключ подписи

1. Создайте хранилище (один раз; сохраните файл и пароли в надёжном месте — без них
   обновления выпускать будет нельзя):

   ```bash
   keytool -genkeypair -v -keystore dpcall-release.jks -alias dpcall \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Закодируйте его в base64:

   ```bash
   base64 -w0 dpcall-release.jks > dpcall-release.jks.b64
   ```

3. В GitHub: **Settings → Secrets and variables → Actions → New repository secret**:

   | Секрет | Значение |
   |---|---|
   | `DPCALL_KEYSTORE_BASE64` | содержимое `dpcall-release.jks.b64` |
   | `DPCALL_KEYSTORE_PASSWORD` | пароль хранилища |
   | `DPCALL_KEY_ALIAS` | `dpcall` |
   | `DPCALL_KEY_PASSWORD` | пароль ключа |

4. Поднимите версию и влейте в `main` — релиз будет подписан вашим ключом.

Смена ключа подписи означает, что установленное приложение нельзя обновить поверх: его
придётся удалить, и код пользователя сменится. Поэтому ключ лучше подключить до того, как
приложением начнут пользоваться.

## Проверка релеев

Список публичных релеев — `RelayPool.DEFAULT_RELAYS`. Перед выпуском стоит убедиться, что
они по-прежнему доставляют вызовы:

```bash
DPCALL_LIVE_RELAYS=1 ./gradlew testDebugUnitTest --tests '*Live*' -i | grep LIVE
```

## Минификация

R8 для релизной сборки выключен (`isMinifyEnabled = false`): на устройствах проверялась
только неминифицированная сборка. Перед включением проверьте на R8-сборке звонок целиком —
правила лежат в `app/proguard-rules.pro`.
