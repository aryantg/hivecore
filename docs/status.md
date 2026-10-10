# HiveCore: what `main` does today

Updated 2026-10-10 (phase 0 landed).

## Build and test

- Multi-module Android project (AGP 8.13.2, Kotlin 2.4.20, Compose BOM 2026.06.01, compileSdk 36, minSdk 31, JDK 17).
- Modules: `:core`, `:mesh`, `:trust`, `:runtime`, `:engine`, `:planner`, `:node`, `:cloud`, `:ledger`, `:ui`, `:app`, plus `relayd/` (Go 1.26).
- `tools/gradle.sh <args>` is the only way to run Gradle on this machine: it serialises builds across every checkout and worktree (15.6 GB RAM, one build at a time).
- JVM unit tests: `tools/gradle.sh testDebugUnitTest` runs 572 tests, all green, in about 50 s warm.
- Debug APK: `tools/gradle.sh :app:assembleDebug`. Feature flags: `-PhiveFeatures=mesh,runtime,split,graph,remote,state,cloud,hardening` (or `all`); every flag defaults off.

## Build-enforced rules (spec 18.2, invariant P3)

- `checkCloudBoundary` (root `build.gradle.kts`) runs before every module's `preBuild` and under `check`. It fails the build when `:node` or any module other than `:app` declares a dependency on `:cloud`, when any source outside `:cloud`/`:app` mentions `com.hivecore.cloud`, or when a `models.json` asset ships outside `:cloud`. Verified by a deliberate import and a deliberate dependency, both rejected.

## What each module holds (adopted from the Sovereign Nodes engine, package-renamed)

| Module | Contents | Tests |
| --- | --- | --- |
| `:core` | `Op`, `OpClass`, `OpClasses`, **`OpRegistry`** (one table: class, capability, merge kind per axis, arity, shapes, replicas, judge), `MergeKind`/`SplitAxis`/`Mergeability`, `TaskSpec`, `Dag`, `Profile` with `Link`/`LinkKind`, `ModelEntry`, `JobRequest`/`JobProgress`/`JobRunner`, `Transport` (endpoint-based, with `kind` and `measureLink`), `Signer`/`Verify`/`Hashing`, **`Canon`** (canonical JSON + sha256), `JsonSchema` (draft 2020-12 subset validator), `SqlDb` (+ `JdbcSqlDb` for the JVM), `KeyValuePrefs`, the fakes (`FakeTransport`, `FakeBus`, `FakeSigner`, `FakeLlmEngine`, …) | 18, incl. `CanonFuzzTest`: 1,000 fuzzed values agree with an independent reference writer and digest |
| `:mesh` | `Wire` envelope `{v, type, from, body, sig}`, `MeshRouter` (pinning, per-type sender rules, replay guard, plan-consent verification), `PeerStore`, `ReplayGuard`, every message body incl. v2 (`ROSTER`, `REVOKE`, `SIGNAL`, `MODEL_REQ/READY/FAIL`, `RECLAIM`, `RECLAIM_RES`, `JOURNAL`), `MeshRouterPort`, `PlanConsent`, Nearby and Bluetooth RFCOMM transports, **`SecureTransport`** (signature-authenticated XX handshake, AES-GCM records, rekey, link echo; salvaged, tested, not yet wired) | 54 |
| `:trust` | `KeystoreSigner`, `ApprovalKeys` (biometric approval key), `ApprovalStore` | 15 |
| `:runtime` | GenieX LLM engine, `ModelPoolImpl` (one big slot + warm small models), `ModelRegistryImpl`, LiteRT-QNN and ORT-QNN runtimes, `TensorIo`, `Warmup`, GBNF grammars, `ClosedChooserImpl`, intent planner (LLM → `IntentSet`) | 95 |
| `:engine` | `ProfileBeacon`, eligibility + `WorkerPolicyImpl` + `PresenceMonitor`, `TaskRunnerImpl` (priority queue, `yieldAll`), `SchedulerImpl`, `ReducerImpl`, `ComparatorImpl`, `Commander` (graph engine), `OwnerPlanHandler` + `AggExecutor` (owner side), `SwarmNode` (message dispatch, in-process delivery, `JobRunner`), split engine (`Splitters` for all nine input kinds, `ChunkPlanner`, `WorkQueue`, `Mergers` for all seven kinds, `SplitCommander`), `BlobStore`, recipes, seams: `Escalator`/`EscalationRequest`/`GateOutcome`/`SchemaValidator`, `JobJournal`/`ChunkMemo` row types, `SplitConstants` | 74 |
| `:planner` | `CueLexicon` (T0), `SwarmRouterImpl`, `Patterns` + `assets/swarm/patterns.json`, `PlannerImpl` (T1 closed choices), `PlanCompilerImpl`, `T2Planner` interface (no implementation) | 28 |
| `:node` | Executors: text (EMBED, TEXT_CLASSIFY, SUMMARIZE, EXTRACT, KNN, CLUSTER, GEN_TEXT), vision (OCR, DETECT, SEGMENT, TAG, IQA, SUPERRES, VLM_ASK), media (VAD_SPLIT, ASR, FRAME_SAMPLE, TRACK_AGG), generation (GEN_IMAGE, GEN_VARIANTS, UPSCALE, SCORE_IMAGE), devai (EVAL, BENCH); owner data: `DataStore`, `RuleIntentCompiler` (floors), `SqlQueryExecutor`, `SqlDifferencingGuard`, `TextAnswerRenderer`, doc store + extractors + BM25 | 276, incl. the 3-phone fake swarm (`SwarmHarness`, `SwarmNodeTest`, `CommanderTest`, `SplitMeshTest`, `MergePropertiesTest`) |
| `:ledger` | Hash-chain ledger, receipt exchange | 12 |
| `:cloud` | Empty (phase 8); build file and boundary rule in place | 0 |
| `:ui` | Compose shell: six screens (Ask, Jobs, Swarm, Models, Data, Settings) as placeholders behind a bottom bar; `ScreenRegistry` lets `:app` plug real screens in per flag | 0 |
| `:app` | `HiveCoreApp`, `MainActivity`, `Features` (flag object), `SwarmGraph` (wiring of the real executors over `SwarmNode.build`, compiled but not yet started) | 0 |
| `relayd/` | Go module with the relay protocol constants and frame types (`relay/protocol.go`); builds and vets; no server loop yet | 0 |

## Not wired yet

Nothing in `:engine`, `:planner`, `:node` or `:mesh` is started by `:app` on a phone: the app launches to the six placeholder screens. Every phase from 1 on lands behind its flag; see `docs/spec.md` section 19 for the gates.

## Dropped from the reference code

Voice (STT/TTS), the evening close, ingest/share sheet, the legacy direct-question pipeline (`ask/`: JOB/RESULT/CONSENT messages are still parsed by the router but nothing produces them), document-AI executors that depended on the evening close, `SYNTH_GEN`/`SYNTH_VALIDATE`, the department setup screen, and the reference Compose screens (to be re-ported per phase from the reference repo). `NodeRole {HR, SALES, ACCOUNTS}` remains in `:core` because the schema, seed data and planner tests key on it; it is a label, not a product feature.
