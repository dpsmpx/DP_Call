# Текст заявки (merge request) в fdroiddata

Заголовок заявки:

```
New app: DP Call
```

Ниже — описание. GitLab сам подставит шаблон с вопросами и чек-листом; ответы на два
вопроса замените этим текстом, а пункты чек-листа отметьте (`[x]`) после того, как
убедитесь в каждом. Ответ на вопрос «зачем написано приложение» — черновик: поправьте
его своими словами.

```markdown
## Abstract for new app inclusion

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

DP Call is a minimalist dialer for peer-to-peer voice calls. There are no accounts,
no phone numbers and no developer-run servers. Every user has a code (a public key);
two people exchange codes (text or QR), add each other to contacts and call. Audio goes
directly between the two phones over WebRTC (DTLS-SRTP). The call request is delivered
end-to-end encrypted through public Nostr relays, so the other phone rings even when the
app is closed; no Google Play Services or push services are used. If the other side is
offline, the invitation can be passed manually through any messenger.

Voice only: no video, chats or file transfer. Interface languages: English and Russian.

Screenshots are in the upstream repo:
https://github.com/dpsmpx/DP_Call/tree/main/fastlane/metadata/android/en-US/images/phoneScreenshots

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

I wanted a way to call another person that needs nothing but the two phones: no
registration, no phone number, no home server to choose or run. Other calling apps in
F-Droid are messengers built around an account or a server (Matrix, XMPP, SIP, Jitsi).
DP Call does one thing — a voice call — and identifies people only by a key generated
on the device. Signaling uses public Nostr relays that the user can replace in Settings,
and a custom TURN server can be set there as well.

### Notes for reviewers

- **Network.** The app connects to public Nostr relays (signaling), public STUN servers
  (Google, Cloudflare) and a public TURN server (`staticauth.openrelay.metered.ca`) for
  NAT traversal. All of them are open protocols and can be replaced in Settings; no
  server is run by the developer. If you consider this `NonFreeNet`, please add it.
- **Prebuilt libraries.** WebRTC (`io.getstream:stream-webrtc-android`, BSD-3-Clause /
  Apache-2.0) and secp256k1 (`fr.acinq.secp256k1`, Apache-2.0) come from Maven Central
  and contain native code. ZXing (Apache-2.0) is used for QR codes.
- **Reproducible build.** `Binaries` and `AllowedAPKSigningKeys` are set. A clean build
  of the previous tag with a different JDK (21 instead of 17) produced an APK identical
  to the published one except for `META-INF/version-control-info.textproto`; since 1.1.1
  that file is no longer included (`vcsInfo.include = false`).
- **`app/debug.keystore`** in the repo is a public test key used only for pre-release CI
  builds; releases are signed with a private key that is not in the repo.
- **Camera** is used only to scan a contact's QR code, on user request.
- **Persistent notification / background connection** is how incoming calls arrive
  without push services; it can be turned off in Settings.
```

Чек-лист шаблона — что уже выполнено:

| Пункт | Состояние |
|---|---|
| Inclusion policy | Выполнено, кроме возраста проекта — см. «Риски» в [FDROID.md](../FDROID.md) |
| Author notified | Вы и есть автор |
| Fastlane в репозитории, есть `en-US` | Есть: описание, иконка, скриншоты, список изменений |
| Заголовок «New app: …» | См. выше |
| Форк публичный, ветка не защищена | Проверьте в настройках своего форка |
| Один файл `metadata/com.dpsmpx.dpcall.yml`, LF | Файл готов: [com.dpsmpx.dpcall.yml](com.dpsmpx.dpcall.yml) |
| Релизы с тегами, автообновление | `v1.1.1`, `AutoUpdateMode: Version` |
| Issue tracker, контакт автора, AuthorName | Указаны в файле |
| Reproducible Builds | Включено (`Binaries`, `AllowedAPKSigningKeys`) |
| ABI split | Не делаем: APK 22 МБ, две ABI; если попросят — добавим |
| `commit` — полный хеш | Да |
| Pipelines pass | Станет видно после отправки |
