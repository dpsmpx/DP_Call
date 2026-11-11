# Формат SDP-строки DP_Call

**Версия:** 1

```
DPCALL1.<type>.<payload>
```

- `O` = Offer
- `A` = Answer
- payload = base64url(gzip(json(SdpPayload)))

SdpPayload: sdp, candidates, identity?, ts

Модуль: `com.dpcall.app.signaling`
