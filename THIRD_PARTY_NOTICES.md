# Third-party notices

Pacer is GPL-3.0-only (see `LICENSE`). This file records where ideas and adapted
code came from.

## Adapted code

The anti-detection hooker and the sensor step/accelerometer hooker under
`xposed/src/main/java/org/pacer/hook/` are **adapted from the public GPL-3.0
sources of `HuangZhuoRui/LocationSpoofer`** (public pre-3.0 branches only; the
commercial 3.0.13+ code was never public and was not touched). Pacer is also
GPL-3.0-only, so the combination is license-compatible. Each adapted file carries
a header noting this.

Adapted areas:
- `hooks/AntiDetectionHooker.kt` -- AppOps / Settings.Secure / permission /
  system-feature interception.
- `hooks/SensorStepHooker.kt` -- step counter/detector and accelerometer overlay.
- `utils/HookUtils.kt` -- reflection and hook helper style.

## Inspirations (no code taken)

| Project | License | Role |
|---|---|---|
| HuangZhuoRui/LocationSpoofer (public pre-3.0 sources) | GPL-3.0 | Module layout; basis for the adapted hooks above |
| BieFan1029/FUCK-RUN- | GPL-3.0 | Step-frequency / sensor idea (reimplemented from scratch in core-motion) |
| shortcuts/locationjoystick | GPL | GPS realism parameter set concept |
| noobexon1/XposedFakeLocation | MIT | Anti-detection checklist concept |
| narumiruna/kestrel | AGPL-3.0 | Design reference only. Do NOT copy code without an AGPL review. |
| mcastillof/FakeTraveler | GPL-3.0 | Root-free mock-location approach reference |

## Closed source (never touched)

- ZCShou/GoGoGo 2.x, Lerist/FakeLocation: closed-source. No reverse-engineering,
  no code or resource extraction.

## Disclaimer

All outputs are for learning, research and lawful testing only on your own
devices and accounts. Prohibited: fake attendance, exam cheating, or any use
violating platform rules or laws.
