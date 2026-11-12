# Политика конфиденциальности DP Call / Privacy Policy

*Действует с версии 1.1.0. / Effective from version 1.1.0.*

## Русский

DP Call не имеет собственных серверов, учётных записей, аналитики и рекламы.
Разработчик не получает и не хранит никаких данных пользователей.

**Что хранится на вашем устройстве**

- ключ пользователя (секретная часть — под защитой Android Keystore) и ваш код;
- контакты: имя и код, которые вы ввели сами;
- журнал звонков (последние 100 записей);
- настройки и, если приложение аварийно завершалось, журнал ошибок.

Эти данные не покидают устройство и не попадают в резервные копии. Удаление приложения
удаляет их полностью.

**Что передаётся по сети**

- *Релеям Nostr* (публичные серверы третьих лиц, список в настройках) — зашифрованные
  сообщения о вызове. Релей видит ваш IP-адрес, обезличенный тег получателя, время и
  размер сообщения. Он не видит ваш код, код собеседника, кто кому звонит и параметры
  соединения. Сообщения не хранятся.
- *STUN/TURN-серверам* (публичные серверы третьих лиц или ваш собственный TURN) —
  служебный обмен для установления соединения; они видят ваш IP-адрес. Если прямое
  соединение невозможно, зашифрованный звук идёт через TURN-сервер; расшифровать его он
  не может.
- *Собеседнику* — ваш IP-адрес (это свойство прямого соединения) и звук разговора,
  зашифрованный DTLS-SRTP. Чтобы скрыть IP, используйте VPN.

**Разрешения**

Микрофон — для разговора; уведомления — для входящих звонков; устройства поблизости —
для Bluetooth-гарнитур; камера — только для сканирования QR-кода контакта; работа в фоне —
чтобы принимать звонки.

**Журнал ошибок** отправляется только вами вручную через кнопку «Отправить журнал ошибок».

Вопросы: <https://github.com/dpsmpx/DP_Call/issues>.

## English

DP Call has no servers of its own, no accounts, no analytics and no ads.
The developer does not receive or store any user data.

**Stored on your device**

- your key (the secret part is protected by the Android Keystore) and your code;
- contacts: the names and codes you entered;
- call history (the last 100 entries);
- settings and, if the app has crashed, an error log.

This data never leaves the device and is excluded from backups. Uninstalling the app
removes it completely.

**Sent over the network**

- *Nostr relays* (public third-party servers, listed in Settings) receive encrypted call
  requests. A relay can see your IP address, an anonymized recipient tag, the time and the
  size of the message. It cannot see your code, the other person's code, who is calling
  whom, or the connection details. Messages are not stored.
- *STUN/TURN servers* (public third-party servers or your own TURN) take part in
  connection setup and can see your IP address. If a direct connection is impossible, the
  encrypted audio is relayed through the TURN server, which cannot decrypt it.
- *The other party* learns your IP address (inherent to a direct connection) and receives
  the audio, encrypted with DTLS-SRTP. Use a VPN to hide your IP address.

**Permissions**

Microphone — to talk; notifications — for incoming calls; nearby devices — for Bluetooth
headsets; camera — only to scan a contact's QR code; background work — to receive calls.

**The error log** is shared only by you, manually, with the “Share the error log” button.

Questions: <https://github.com/dpsmpx/DP_Call/issues>.
