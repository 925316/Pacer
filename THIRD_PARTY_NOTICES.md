# Third-party notices

Pacer is licensed **GPL-3.0-or-later** (see `LICENSE`). Every source file carries
an SPDX identifier, and those identifiers say "or later", so that is the licence
of the project as a whole.

## Adapted code

The files below are adapted from the **public, pre-3.0 GPL-3.0 sources of
`HuangZhuoRui/LocationSpoofer`**. That is the only upstream from which Pacer has
taken code. The commercial 3.0.13+ code was never published and was not consulted.
LocationSpoofer is GPL-3.0 and Pacer is GPL-3.0-or-later, so the combination is
licence-compatible. Each adapted file carries a header saying so.

| Pacer file | Adapted from | What was taken |
| --- | --- | --- |
| `hooks/AntiDetectionHooker.kt` | `AntiDetectionHooker` | AppOps / Settings.Secure / permission / system-feature interception |
| `hooks/SensorStepHooker.kt` | `SensorStepHooker` | step counter, step detector and accelerometer overlay |
| `hooks/GnssStatusHooker.kt` | `GnssStatusHooker` | satellite status spoofing approach |
| `hooks/LocationInjector.kt` | `BaseLocationHooker`, `LocationProviderHooker` | test-provider hiding and mock-flag scrubbing |
| `hooks/MapSdkHooker.kt` | `AMapHooker`, `BaiduMapHooker` | which SDK accessors to intercept |
| `utils/HookUtils.kt` | `XposedHelpers` | reflection and hook helper style |

## Own implementation, informed only by public sources

These files are Pacer's own code. Public material informed the design; no code was
copied.

| Pacer file | Informed by |
| --- | --- |
| `hooks/RouteReplayHooker.kt`, `hooks/RouteEngine.kt` | LocationSpoofer `RouteEngine` / `LocationGetterHooker` structure |
| `hooks/NmeaHooker.kt`, `NmeaFormatter.kt` | the public NMEA-0183 sentence formats |
| `hooks/TencentLocationHooker.kt` | nothing usable: the reference names a Tencent implementation class that does not exist and a field fallback chain that cannot work. Pacer uses a different design (capture the listener, hand back a dynamic proxy). |
| `hooks/system/*` | which system internals to target, from `AnyDoor` (MIT) and `DuckMock` (GPL-3.0) |
| `hooks/env/*` | Pacer's own deterministic environment; cell and Bluetooth are suppressed rather than fabricated, unlike the reference |
| `TrackAudit.kt` | the rule set described by `vibe-GIF/budaolepao` (GPL-3.0), reimplemented with Pacer's own math and thresholds |

## Dependencies

| Dependency | Licence | Scope |
| --- | --- | --- |
| `org.osmdroid:osmdroid-android:6.1.20` | Apache-2.0 | app runtime (map tiles) |
| `io.github.libxposed:api` | Apache-2.0 | compile only; the framework provides it at runtime |
| `io.github.libxposed:service` | Apache-2.0 | app runtime (the configuration channel) |
| `junit:junit:4.13.2` | EPL-1.0 | tests only |

osmdroid renders OpenStreetMap data, (c) OpenStreetMap contributors, licensed
ODbL. Tiles are requested from the OSM tile service, whose usage policy asks for
an identifying User-Agent and light traffic: Pacer sets a descriptive
User-Agent, fetches tiles only while the map screen is open, and caches them in
app-private storage.

## Examined for reference, no code taken

Risk-control and detection projects were read to learn what a detector checks.
No code from any of them is in this repository. The ones with no licence at all
are listed because they were deliberately treated as read-only:

| Project | Licence |
| --- | --- |
| `smarques84/MockLocationDetector` | none |
| `auag0/HideMockLocation` | none |
| `LYCaikano/FakeSteps` | none |
| `liujiayu5566/MockGps` | none |
| `BieFan1029/FUCK-RUN-` | GPL-3.0 |
| `vibe-GIF/budaolepao` | GPL-3.0 |
| `Bouteillepleine/DuckMock` | GPL-3.0 |
| `zhaoyuxiangyyds-lab/AnyDoor` | MIT |
| `noobexon1/XposedFakeLocation` | MIT |
| `ThePieMonster/HideMockLocation` | Apache-2.0 |
| `5xiao0qing5/Portal` | Apache-2.0 |
| `mcastillof/FakeTraveler` | GPL-3.0 |
| `shortcuts/locationjoystick` | GPL |
| `narumiruna/kestrel` | AGPL-3.0 (design reference only; do not copy code without an AGPL review) |

## Closed source (never touched)

`ZCShou/GoGoGo` 2.x and `Lerist/FakeLocation` are closed source. No
reverse-engineering, and no code or resource extraction from them.

## Disclaimer

For learning, research and lawful testing only, on your own devices and accounts.
Prohibited: fake attendance, exam cheating, or any use violating platform rules or
the law.
