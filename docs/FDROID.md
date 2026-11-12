# Публикация в F-Droid

F-Droid сам собирает приложение из исходников и подписывает своим ключом. От вас нужны:
открытый репозиторий с тегом версии и запрос на включение (merge request) в
[fdroiddata](https://gitlab.com/fdroid/fdroiddata).

## Что уже готово в репозитории

- Лицензия MIT (`LICENSE`), открытый исходный код, сборка одной командой Gradle.
- Нет Google Play Services, Firebase, аналитики и рекламы.
- Тег версии `v1.1.0` (создаётся релизом GitHub Actions).
- Описание, иконка и список изменений в формате, который читает F-Droid:
  `fastlane/metadata/android/{ru-RU,en-US}/`.
- Готовый файл метаданных: [`docs/fdroid/com.dpcall.app.yml`](fdroid/com.dpcall.app.yml).

Стоит добавить до подачи: 2–4 скриншота в
`fastlane/metadata/android/ru-RU/images/phoneScreenshots/` (`1.png`, `2.png`, …) и то же
для `en-US`. Без них приложение примут, но страница будет пустой.

## Шаги

### 1. Аккаунт и форк

1. Зарегистрируйтесь на [gitlab.com](https://gitlab.com).
2. Откройте <https://gitlab.com/fdroid/fdroiddata> и нажмите **Fork**.

### 2. Добавьте метаданные

```bash
git clone https://gitlab.com/<ваш-логин>/fdroiddata.git
cd fdroiddata
git checkout -b com.dpcall.app
cp /путь/к/DP_Call/docs/fdroid/com.dpcall.app.yml metadata/com.dpcall.app.yml
```

В файле указана версия 1.1.0. Если к моменту подачи вышла более новая, замените
`versionName`, `versionCode`, `commit`, `CurrentVersion` и `CurrentVersionCode`.

### 3. Проверьте локально (желательно)

Нужен Docker:

```bash
docker run --rm -it -v "$PWD":/repo registry.gitlab.com/fdroid/fdroidserver:buildserver bash
# внутри контейнера:
cd /repo
fdroid readmeta
fdroid rewritemeta com.dpcall.app
fdroid lint com.dpcall.app
fdroid build -v -l com.dpcall.app
```

`fdroid lint` должен пройти без ошибок, `fdroid build` — собрать APK. Если Docker нет,
шаг можно пропустить: те же проверки выполнит CI GitLab после отправки.

### 4. Отправьте merge request

```bash
git add metadata/com.dpcall.app.yml
git commit -m "New app: DP Call"
git push origin com.dpcall.app
```

На GitLab откройте **Merge request** в `fdroid/fdroiddata`, выберите шаблон **App inclusion**
и отметьте пункты чек-листа. В описании коротко: что делает приложение, лицензия, ссылка
на репозиторий.

### 5. Ревью

Дождитесь зелёного CI и ответьте на вопросы ревьюеров. Обычно включение занимает от
нескольких дней до пары недель; после слияния приложение появляется в каталоге в течение
1–3 суток.

## О чём, скорее всего, спросят

Лучше сразу написать об этом в описании merge request.

- **Сеть.** Приложение подключается к публичным релеям Nostr (сигналинг), STUN-серверам
  Google и Cloudflare и публичному TURN `staticauth.openrelay.metered.ca`. Всё это открытые протоколы, свои
  серверы не используются. Ревьюеры могут поставить отметку (anti-feature) **NonFreeNet**
  из-за STUN/TURN — это не препятствие для включения.
- **Готовые бинарные библиотеки.** WebRTC (`io.getstream:stream-webrtc-android`, BSD/Apache-2.0)
  и secp256k1 (`fr.acinq.secp256k1`, Apache-2.0) подключаются с Maven Central и содержат
  нативный код. Обе библиотеки открытые; если ревьюеры попросят собирать их из исходников,
  это отдельная задача — сообщите, поможем с рецептом сборки.
- **Камера.** Используется только для сканирования QR-кода контакта (библиотека ZXing,
  Apache-2.0); разрешение запрашивается в момент сканирования.
- **Постоянное уведомление и работа в фоне.** Нужны, чтобы принимать звонки без push-сервисов
  Google; отключаются в настройках.
- **Ключ подписи в репозитории** (`app/debug.keystore`). Используется только для тестовых
  сборок GitHub; F-Droid подписывает своим ключом.

## Воспроизводимые сборки (по желанию)

Сборка детерминирована: две чистые сборки одного коммита дают побайтно одинаковый APK
(блок `dependenciesInfo` отключён, временных меток в сборке нет). Это позволяет попросить
F-Droid распространять APK с **вашей** подписью — тогда версии из F-Droid и из GitHub
Releases взаимозаменяемы. Для этого нужен собственный ключ подписи
(см. [RELEASING.md](RELEASING.md)) и две строки в метаданных:

```yaml
Binaries: https://github.com/dpsmpx/DP_Call/releases/download/v%v/DP_Call-v%v.apk
AllowedAPKSigningKeys: <SHA-256 сертификата вашего ключа>
```

Отпечаток: `keytool -list -v -keystore <файл> | grep SHA256` (без двоеточий, в нижнем
регистре). Если сборка F-Droid не совпадёт с вашей, ревьюеры сообщат — тогда эти строки
убирают, и F-Droid подписывает своим ключом, как обычно.

## Обновления

В метаданных указано `AutoUpdateMode: Version` и `UpdateCheckMode: Tags`: F-Droid сам
находит новые теги вида `v1.2.3`, берёт `versionCode`/`versionName` из
`app/build.gradle.kts` и собирает новую версию. От вас — только выпуск по
[RELEASING.md](RELEASING.md) с файлом `changelogs/<versionCode>.txt`.

## После публикации

Страница приложения будет по адресу <https://f-droid.org/packages/com.dpcall.app/>
(адрес определяется идентификатором пакета, поэтому известен заранее).

В `README.md` для этого уже есть место: в разделе «Установка» уберите пометку
«*ожидает публикации*» у ссылки на F-Droid. Сам адрес записан в последней строке файла
(`[fdroid]: …`) — менять его нужно, только если он окажется другим.

## Важно для пользователей

APK из F-Droid и APK из GitHub Releases подписаны разными ключами: обновить один другим
нельзя. При переходе приложение придётся переустановить, и код пользователя сменится.

## Альтернатива: запрос без merge request

Можно создать заявку в <https://gitlab.com/fdroid/rfp/-/issues> (Request For Packaging) и
приложить ссылку на репозиторий — метаданные напишут волонтёры, но это заметно дольше.
