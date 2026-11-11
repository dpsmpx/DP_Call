# ADR-001: Формат SDP-строки

**Статус:** Принято

## Решение

```
DPCALL1.<O|A>.<base64url(gzip(json(SdpPayload)))>
```

Подробности — в `docs/design/SDP_FORMAT.md`.
