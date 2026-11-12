# Формат SDP-строки DP_Call

**Версия:** 1

```
DPCALL1.<type>.<payload>
```

- `O` = Offer
- `A` = Answer
- payload = base64url(gzip(json(SdpPayload)))

SdpPayload:

| Поле | Назначение |
|---|---|
| `sdp` | SDP-описание сессии |
| `candidates` | ICE-кандидаты в виде `mid\|mLineIndex\|candidate` |
| `identity` | код отправителя (публичный ключ), необязательное |
| `ts` | время создания, мс |
| `callId` | идентификатор звонка: связывает приглашение и ответ, необязательное |

Строка передаётся двумя путями: внутри зашифрованного сообщения сигнального канала
(см. [ADR-002](../adr/002-relay-signaling.md)) либо вручную через мессенджер.
`SignalCodec.extract` находит строку в произвольном тексте сообщения.

Модуль: `com.dpcall.app.signaling`
