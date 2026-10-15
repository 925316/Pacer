# Pacer

An Android app in Kotlin.

## Build

```bash
gradle :app:assembleDebug
```

Tests: `gradle :core-motion:test :xposed:testDebugUnitTest :app:testDebugUnitTest`.

Requires JDK 17, Android SDK 36, and Gradle 9.6.1 — all installed by the workflows
under `.github/workflows/`.

## Layout

| Module        | Contents                                              |
| ------------- | ----------------------------------------------------- |
| `core-motion` | Pure Kotlin, no Android dependency.                   |
| `xposed`      | Framework module; packaged into the app as a library. |
| `app`         | The control UI.                                       |

## License

Licensed under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE).
