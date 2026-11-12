# Contributing

1. Keep the app focused: **voice calls only**.
2. No developer-run servers in the critical path.
3. No proprietary dependencies (the app must stay F-Droid compatible).
4. Prefer clarity over cleverness.
5. Open source (MIT).

## Build and test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

JDK 17, Android SDK 35; minSdk 26, targetSdk 35.

Tests that talk to the real public relays are opt-in:

```bash
DPCALL_LIVE_RELAYS=1 ./gradlew testDebugUnitTest --tests '*Live*' -i | grep LIVE
```

## Where things live

See [ARCHITECTURE.md](ARCHITECTURE.md) and [STRUCTURE.md](STRUCTURE.md). The signaling layer
(`signaling/`) is Android-free on purpose so it can be unit-tested on the JVM — keep it that way.

## Releases

See [RELEASING.md](RELEASING.md).
