# Pacer

Pacer (配速员) — free and open-source Android motion simulation tool for development testing and personal research. No accounts, no backend, no paywall. Licensed GPL-3.0.

> Disclaimer: for learning, research and lawful testing only, using your own devices and test accounts.
> Do not use for fake check-ins, exam cheating, or anything violating platform rules or laws. Users are solely responsible for their behavior.

## Modules

- `core-motion/` — pure Kotlin, JVM-tested, no Android dependency. Motion
  engine + geometry + signal realism + anti-detection policy + joystick/coordinate helpers.
- `app/` — minimal Android shell (`org.pacer.app`), wires the engine into a
  sample-run demo page. Real map UI comes later.
- `xposed/` — reserved hook module, LSPosed API pulled in (compileOnly).

## Engine coverage (`core-motion`)

- `MotionPhase`, `MotionConfig` (walk/run/ride + custom) with ramp/noise/fatigue/pause model
- `SpeedProfile` — ACQUIRE -> CRUISE -> PAUSE O-U curve with fatigue
- `StrideLinker` — steps = distance / stride, cadence clamped [140,220] SPM
- `Humanizer` — facade producing self-consistent run
- `RoutePath`, `GeoMath` — haversine, bearings, interpolation, nearest snapping
- `RouteSimulator` + `FixSample` — fused position/speed/steps/signal samples
- `SignalRealism` — accuracy warmup 30->5m, altitude drift, satellites 7-14, dropouts, bearing hold
- `StationaryJitter` — micro-movement while standing still
- `SensorWaveGenerator` — accelerometer sine wave + step counter/detector
- `ConsistencyChecker` — 3-stream physical self-consistency report
- `FrequencyEstimator` — accelerometer cadence verification
- `CoordinateTransform` — WGS84 <-> GCJ02 <-> BD09
- `StackCleaner`, `AntiDetectionPolicy` — anti-detection rule logic
- `JoystickMath` — floating joystick velocity mapping with dead zone
- `PolylineSimplify` — Douglas-Peucker route smoothing
- `RoutePath.toJson/fromJson` — route persistence

## Build (CI only)

No local Android SDK is expected. All builds and tests run in GitHub Actions.
See `.github/workflows/ci.yml` (push to master) and `.github/workflows/release.yml`
(tag `v*` triggers a release).

## Third parties

No source code is copied from any repository; inspirations listed in
`THIRD_PARTY_NOTICES.md`. See `HANDOFF.md` for the full handover spec (local only).
