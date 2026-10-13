# Room 2.4.2 upgrade fixtures (WS03, risk R1)

Everything here was produced by the **Room 2.4.2** build of Hoodies-Network (`main` @ `b105af9`,
`room = "2.4.2"`, kapt) using `Room242FixtureGenerator.kt.txt`, which was dropped into that build's
`src/androidTest/kotlin/com/gap/hoodies_network/room242/` and run on the emulator.

| File | Written by | Used by |
|---|---|---|
| `room242-fixture-cache.db` | `generateFixtureDatabases` via Room 2.4.2 `CacheDao` | `Room242FixtureTest` |
| `room242-fixture-cookies.db` | `generateFixtureDatabases` via Room 2.4.2 `EncryptedCookieDao` | `Room242FixtureTest` |

Both files are WAL-checkpointed, `PRAGMA user_version = 1`, and carry the Room 2.4.2 identity hashes
(`7e46f50b0efdb1490662ae21a486aed0` cache, `98e4e3e9975a1a48f0d3f101c5208261` cookies), which equal the
v1 schemas exported by Room 2.8.5 under `Hoodies-Network/schemas/`.

## In-place upgrade seed

`RoomInPlaceUpgradeTest` needs data written on the device by the old build (Keystore keys cannot be
committed). Before each recorded `connectedDebugAndroidTest` run:

```bash
# 1. old build (b105af9 + Room242FixtureGenerator.kt) -> seed the real app databases
adb uninstall com.gap.hoodies_network.test
adb install -t <b105af9>/Hoodies-Network/build/outputs/apk/androidTest/debug/Hoodies-Network-debug-androidTest.apk
adb shell am instrument -w -e class com.gap.hoodies_network.room242.Room242FixtureGenerator#seedInPlaceUpgrade \
  com.gap.hoodies_network.test/androidx.test.runner.AndroidJUnitRunner
# 2. upgraded build installed over it, keeping app data
adb install -r -t Hoodies-Network/build/outputs/apk/androidTest/debug/Hoodies-Network-debug-androidTest.apk
```

`connectedDebugAndroidTest` then installs with `install -r`, so the seeded databases survive. Pass
`-Pandroid.testInstrumentationRunnerArguments.requireRoomUpgradeSeed=true` to make a missing seed fail
instead of skip.
