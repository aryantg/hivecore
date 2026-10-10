# HiveCore: what `main` does today

Updated 2026-10-10 (phases 1-8 merged and wired behind their flags; the integration wave on top of the six tracks, then the closing wave: consent, CLOUD option, deputy, downloads, document executors, soak harness).

## Build and test

- Multi-module Android project (AGP 8.13.2, Kotlin 2.4.20, Compose BOM 2026.06.01, compileSdk 36, minSdk 31, JDK 17) plus `relayd/` (Go).
- `tools/gradle.sh <args>` is the only way to run Gradle on this machine (one build at a time across every checkout).
- JVM unit tests: `tools/gradle.sh testDebugUnitTest` runs 974 tests, all green, in about two minutes warm (the four-seed soak harness `SoakTest` is among them); `relayd` has 18 Go tests.
- Debug APK: `tools/gradle.sh :app:assembleDebug` (every flag off) and `tools/gradle.sh :app:assembleDebug -PhiveFeatures=all` both build. Flags: `mesh,runtime,split,graph,remote,state,cloud,hardening`.

## Module table

| Module | Holds | JVM tests |
| --- | --- | --- |
| `:core` | types, `OpRegistry`, `Canon`, `JsonSchema`, `Transport`, fakes incl. `FakeRelay` and `FakeDataChannel` | 31 |
| `:mesh` | envelope, `MeshRouter` (selected + always-on transports, best-ranked endpoint per node), `SecureTransport`, roster store, LAN / Nearby / Bluetooth / relay / WebRTC transports, link probe, relay presence and pairing channel | 101 |
| `:trust` | `KeystoreSigner`, approval keys, `PairingImpl` (QR / link + SAS, LAN and relay legs), `RosterAdmin`, `PlanAuthoriser` | 29 |
| `:runtime` | GenieX, `ModelPoolImpl` / `CapabilityPool`, catalog loader, `CapabilityBench` + `BenchTable`, tensor runtimes, `ModelDownloader` | 131 |
| `:engine` | `SwarmNode` (journal, memo, RECLAIM responder, resume, placement hooks), `Commander` (escalation triggers, cloud-answered steps), `SplitCommander` (PLAN per run, prefilled resume, wake before WAN TASKs), `ProfileBeacon` (cadence per link class), placement, journal, deputy, worker policy and service | 167 |
| `:planner` | tiers, patterns, compiler, `ExecutionOptions`, `GraphJson`, `T2Planner` seam | 61 |
| `:node` | executors incl. `LLM_MAP`, data and document stores; the document executors (`docai`: OCR, LAYOUT, DOC_CLASSIFY, METADATA, REDACT, FIELDS over ML Kit); the 3-phone fake swarm (`SwarmHarness` with memoize and relay rigs) and its scenarios: split, merge properties, commander, escalation incl. the CLOUD option, resume, authorisation, relay, the chaos soak | 354 |
| `:cloud` | OpenRouter client (internal), `EscalationGate`, `CloudPlannerImpl`, `ModelsConfig`, caps, key store, consent seam, `CloudFactory` (the only way out) | 36 |
| `:ledger` | hash chain, receipt exchange, split ledger entries and replay | 16 |
| `:ui` | the six screens and their view models over the five ports; `SwarmPort.warnings` for mesh warnings | 41 |
| `:app` | `AppWiring` (per-flag graph), `AppPorts` (the real ports), `HiveCoreGraph`, consent sheet, the plan approval screen and notifier, FCM wake; boundary, flag-combination and approval tests | 7 |

## What runs on a phone, per flag

- **no flags**: the six placeholder screens; the app always launches.
- **mesh**: Keystore identity, LAN (NSD + TCP), Nearby and Bluetooth below the session layer, SAS pairing by QR or link, the admin-signed roster with revocation and handover, P10 task authorisation; the Swarm screen is real (tiles from PROFILEs, invites, joins, SAS, admin actions, mesh warnings) and Settings' peers section works. Plan consent on an owner: the notification opens the approval screen over the lock screen and one fingerprint signs the whole plan; the in-app banner is the fallback.
- **runtime**: GenieX LLM, the capability catalog, the model pool with the qairt → llama_cpp fallback, BENCH, the worker policy, foreground service and owner yield, `MODEL_REQ` handling; the Models screen is real (per-phone states from profiles, load / unload / bench / pin; Download works for catalog entries that carry a `url`); `VLM_ASK` runs on the GenieX VLM, `GEN_IMAGE` on the SD 1.5 bundle (ORT QNN), OCR and the document executors on ML Kit.
- **split**: the swarm node over the executors, the SQLite journal and memo (`files/journal.db`), memoizing runner, RECLAIM, resume, journal pull and deputy store, placement per job (`MODEL_REQ/READY/FAIL` before the queue opens), receipts after each merge and per executed chunk; the Jobs screen runs recipes and split jobs on the node and offers resume after a restart.
- **graph**: the planner tiers and the compiler, the Ask screen (plan panel with LOCAL / CLOUD estimates, run, not-accurate) and the Data screen (private marks, confidential columns, releases and hidden tables).
- **cloud**: `models.json`, the encrypted OpenRouter key (Settings), spend caps, the consent bottom sheet, the escalation gate as the orchestrator's escalator (Commander and SplitCommander only), the T2 planner. The CLOUD option sends the eligible LLM / VLM steps to the cloud from this phone before any phone is asked (trigger `user_choice`; the choice is the consent for that plan); a refusal or cloud failure falls back to the phones.
- **remote**: the relay and WebRTC transports always-on beside the selected one (relay address from Settings), presence and wake, link probes with the metered flag, the relay leg of pairing, FCM wake of the worker service.
- **state**: the orchestrator streams every journal commit to a deputy (`StreamingJournal` over `DeputySelector`: LAN first, then the longest-seen phone) and records it in the job row, so a resume pulls the deputy's newer rows before RECLAIM; every phone serves as a deputy with `split`.
- **hardening**: no wiring of its own; the phase-9 pieces that exist (adaptive re-cut, cooldowns, deputy, relay hold, relay clock) ride on the other flags, and the soak harness is a JVM test (`SoakTest`).

Without `mesh`, the node runs over an in-process loopback transport, so split jobs and plans still run on one phone.

## Still fake or not wired

- Model downloads run only for catalog entries that carry a `url`; the shipped catalog has none (copy the bundles into `Download/HiveCoreModels`, or install a `files/models/catalog.json` with urls).
- Plan consent, the CLOUD option, deputy streaming, VLM_ASK, image generation and the document executors are wired and JVM-tested but have not run on a phone (biometric signing, the lock-screen intent, ML Kit and the GenieX VLM are exercised only through their seams).
- A split job started from a recipe has no CLOUD option (recipes run on the phones); the gate still re-runs a failed chunk in the cloud.
- Document ingest (`PdfExtractor` has its OCR now, but nothing feeds it), voice and the share sheet remain out, as in phase 0.
- The hardening flag has no wiring of its own; the soak harness is the JVM `SoakTest` (four seeded chaos sequences over the LAN and relay rigs); the 1-hour on-phone soak has not run.
- None of this has run on a phone yet: every gate of spec section 19 still needs its on-phone pass; the JVM suites are the evidence so far.
