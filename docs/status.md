# HiveCore: what `track/integrate` does today

Updated 2026-10-10 (phases 1-8 merged and wired behind their flags; the integration wave on top of the six tracks).

## Build and test

- Multi-module Android project (AGP 8.13.2, Kotlin 2.4.20, Compose BOM 2026.06.01, compileSdk 36, minSdk 31, JDK 17) plus `relayd/` (Go).
- `tools/gradle.sh <args>` is the only way to run Gradle on this machine (one build at a time across every checkout).
- JVM unit tests: `tools/gradle.sh testDebugUnitTest` runs 901 tests, all green, in about two minutes warm; `relayd` has 18 Go tests.
- Debug APK: `tools/gradle.sh :app:assembleDebug` (every flag off) and `tools/gradle.sh :app:assembleDebug -PhiveFeatures=all` both build. Flags: `mesh,runtime,split,graph,remote,state,cloud,hardening`.

## Module table

| Module | Holds | JVM tests |
| --- | --- | --- |
| `:core` | types, `OpRegistry`, `Canon`, `JsonSchema`, `Transport`, fakes incl. `FakeRelay` and `FakeDataChannel` | 31 |
| `:mesh` | envelope, `MeshRouter` (selected + always-on transports, best-ranked endpoint per node), `SecureTransport`, roster store, LAN / Nearby / Bluetooth / relay / WebRTC transports, link probe, relay presence and pairing channel | 101 |
| `:trust` | `KeystoreSigner`, approval keys, `PairingImpl` (QR / link + SAS, LAN and relay legs), `RosterAdmin`, `PlanAuthoriser` | 29 |
| `:runtime` | GenieX, `ModelPoolImpl` / `CapabilityPool`, catalog loader, `CapabilityBench` + `BenchTable`, tensor runtimes | 117 |
| `:engine` | `SwarmNode` (journal, memo, RECLAIM responder, resume, placement hooks), `Commander` (escalation triggers, cloud-answered steps), `SplitCommander` (PLAN per run, prefilled resume, wake before WAN TASKs), `ProfileBeacon` (cadence per link class), placement, journal, deputy, worker policy and service | 167 |
| `:planner` | tiers, patterns, compiler, `ExecutionOptions`, `GraphJson`, `T2Planner` seam | 61 |
| `:node` | executors incl. `LLM_MAP`, data and document stores; the 3-phone fake swarm (`SwarmHarness` with memoize and relay rigs) and its scenarios: split, merge properties, commander, escalation, resume, authorisation, relay | 299 |
| `:cloud` | OpenRouter client (internal), `EscalationGate`, `CloudPlannerImpl`, `ModelsConfig`, caps, key store, consent seam, `CloudFactory` (the only way out) | 35 |
| `:ledger` | hash chain, receipt exchange, split ledger entries and replay | 16 |
| `:ui` | the six screens and their view models over the five ports; `SwarmPort.warnings` for mesh warnings | 41 |
| `:app` | `AppWiring` (per-flag graph), `AppPorts` (the real ports), `HiveCoreGraph`, consent sheet, FCM wake; boundary and flag-combination tests | 4 |

## What runs on a phone, per flag

- **no flags**: the six placeholder screens; the app always launches.
- **mesh**: Keystore identity, LAN (NSD + TCP), Nearby and Bluetooth below the session layer, SAS pairing by QR or link, the admin-signed roster with revocation and handover, P10 task authorisation; the Swarm screen is real (tiles from PROFILEs, invites, joins, SAS, admin actions, mesh warnings) and Settings' peers section works.
- **runtime**: GenieX LLM, the capability catalog, the model pool with the qairt → llama_cpp fallback, BENCH, the worker policy, foreground service and owner yield, `MODEL_REQ` handling; the Models screen is real (per-phone states from profiles, load / unload / bench / pin; downloads are not wired).
- **split**: the swarm node over the executors, the SQLite journal and memo (`files/journal.db`), memoizing runner, RECLAIM, resume, journal pull and deputy store, placement per job (`MODEL_REQ/READY/FAIL` before the queue opens), receipts after each merge and per executed chunk; the Jobs screen runs recipes and split jobs on the node and offers resume after a restart.
- **graph**: the planner tiers and the compiler, the Ask screen (plan panel with LOCAL / CLOUD estimates, run, not-accurate) and the Data screen (private marks, confidential columns, releases and hidden tables).
- **cloud**: `models.json`, the encrypted OpenRouter key (Settings), spend caps, the consent bottom sheet, the escalation gate as the orchestrator's escalator (Commander and SplitCommander only), the T2 planner.
- **remote**: the relay and WebRTC transports always-on beside the selected one (relay address from Settings), presence and wake, link probes with the metered flag, the relay leg of pairing, FCM wake of the worker service.
- **state**, **hardening**: no wiring of their own yet (the journal lands with `split`; the deputy stream is served but no orchestrator streams to a deputy yet).

Without `mesh`, the node runs over an in-process loopback transport, so split jobs and plans still run on one phone.

## Still fake or not wired

- Model downloads (`ModelsPort.download`) and image generation / VLM-ask executors (no `SdPipeline` or VLM seam is built in the wiring).
- Plan consent on the owner: `ApprovalStore` is built without a notifier and no sheet shows pending plan approvals (debug auto-approve only).
- The CLOUD execution option is estimated and offered but runs the plan locally first; the gate re-runs failed steps in the cloud. Pre-dispatch of eligible steps is open.
- `StreamingJournal` to a deputy is not started (every phone serves as a deputy, nobody streams yet); the hardening flag is empty.
- OCR for documents (`PdfExtractor` needs an `Ocr`), voice, ingest and the share sheet remain out, as in phase 0.
- None of this has run on a phone yet: every gate of spec section 19 still needs its on-phone pass; the JVM suites are the evidence so far.
