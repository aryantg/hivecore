# HiveCore

An Android app that turns a group of iQOO 15 phones into one AI system: any phone can take a request, plan it, place
models on the other phones, split the work into chunks, distribute, track, verify and merge, with a cloud model used
only by the requesting phone and only when the local models cannot deliver.

- `docs/spec.md` is the specification and the single authority (amendments at the end).
- `docs/status.md` says what `main` does today.

## Build

- JDK 17, Android SDK at `C:/Android/Sdk` (`local.properties` holds `sdk.dir`), Go 1.26 for `relayd/`.
- Always build through the lock wrapper (one Gradle build at a time on this machine):

```
tools/gradle.sh testDebugUnitTest                      # all JVM tests
tools/gradle.sh :engine:testDebugUnitTest --tests '*WorkQueueTest*'
tools/gradle.sh :app:assembleDebug -PhiveFeatures=mesh,runtime   # flagged APK
```

Modules: `:core`, `:mesh`, `:trust`, `:runtime`, `:engine`, `:planner`, `:node`, `:cloud`, `:ledger`, `:ui`, `:app`;
`:node` can never depend on `:cloud` (the build fails).
