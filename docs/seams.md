# Seams and file ownership for the parallel tracks

Fixed before the fan-out. A track edits only the files it owns; anything else it needs changed is reported in its
final summary and the integration wave applies it. `:app` and `:ui` are owned by the integration wave only.

## Seam interfaces (already in `main`)

| Seam | Where | Used by |
| --- | --- | --- |
| `Transport` (+ `kind`, `measureLink`), `Link`, `LinkKind`, `Profile` (+ `link`, `capabilities`, `cooldowns`), `ModelEntry`, `CapabilityState`, `Cooldown` | `:core` `Transport.kt`, `Swarm.kt` | every track |
| `MeshRouterPort` / `MeshRouterAdapter` | `:mesh` `Ports.kt` | engine handlers, tests |
| `Session`, `SessionManager`, `SecureTransport` (handshake, AES-GCM records, rekey, link echo, SAS from the handshake hash) | `:mesh` `SecureTransport.kt` | mesh+trust owns; remote track's transports sit below it |
| Message bodies incl. `ROSTER`, `REVOKE`, `SIGNAL`, `MODEL_REQ/READY/FAIL`, `RECLAIM/_RES`, `JOURNAL`; `TaskResultBody.errorClass` | `:mesh` `Messages.kt` | placement, state, remote, split |
| `Pairing`, `Invite`, `SasPrompt`, `RosterStore`, `Roster` | `:trust` `Pairing.kt` | mesh+trust implements; remote track parses invite links |
| `Escalator`, `EscalationRequest`, `EscalationTrigger`, `GateOutcome`, `SchemaValidator`, `Lineage` | `:engine` `Escalation.kt` | cloud implements `Escalator`; split/graph call it |
| `JobJournal`, `ChunkMemo`, `JobRow`/`ChunkRow`/`AttemptRow`/`OutputRow`/`PlacementRow`/`EscalationRow`, `JobState` | `:engine` `journal/JobJournal.kt` | state implements; split writes rows through the interface |
| `SplitConstants` | `:engine` `split/SplitConstants.kt` | split, placement, state |
| `T2Planner`, `T2Params` | `:planner` `T2Planner.kt` | cloud implements; planner calls |
| `OpRegistry`, `Canon`, `JsonSchema` | `:core` | all |
| `JobRunner.resume(jobId)` | `:core` `Jobs.kt` | state implements on `SwarmNode` in the integration wave |

## Ownership

| Track | Branch | Owns (may create and edit) | Reads only |
| --- | --- | --- | --- |
| mesh+trust | `track/mesh-trust` | `mesh/**` except `RelayTransport*`, `WebRtc*`, `Signaling*`, `FakeRelay*`, `FakeDataChannel*`; `trust/**`; `mesh` and `trust` tests | `core` |
| runtime+placement | `track/runtime-placement` | `runtime/**`; `engine/src/main/java/com/hivecore/engine/placement/**`; `engine/src/main/java/com/hivecore/engine/device/**`; `runtime/src/main/assets/models/**`; their tests | `core`, `mesh`, `engine` rest |
| split engine | `track/split` | `engine/src/main/java/com/hivecore/engine/split/**`, `engine/.../TaskRunnerImpl.kt`, `engine/.../ComparatorImpl.kt`; `engine` split tests; `node/src/test/.../SplitMeshTest.kt`, `MergePropertiesTest.kt` | `core`, `mesh` |
| state & recovery | `track/state` | `engine/src/main/java/com/hivecore/engine/journal/**`, `engine/.../BlobStore.kt`; `ledger/**`; their tests | `core`, `mesh`, `engine` rest |
| cloud + planner | `track/cloud-planner` | `cloud/**`; `planner/**`; `core/.../JsonSchema.kt`; `engine/.../Escalation.kt`; `node/src/main/java/com/hivecore/node/textai/LlmMapExecutor.kt`; their tests | everything else |
| remote mesh | `track/remote` | `relayd/**`; `mesh/.../RelayTransport.kt`, `WebRtcBlobTransport.kt`, `Signaling.kt`, `RelayClock.kt`; `core/.../fakes/FakeRelay.kt`, `FakeDataChannel.kt`; their tests | `core`, `mesh` rest |

Shared files no track edits: `core/**` (except `JsonSchema.kt`), `engine/SwarmNode.kt`, `engine/Commander.kt`,
`engine/OwnerPlanHandler.kt`, `engine/AggExecutor.kt`, `engine/ProfileBeacon.kt`, `app/**`, `ui/**`, `docs/**`
(each track adds its notes to its final summary instead; the integration wave updates `docs/`).

## Rules every track follows

- Build only through `tools/gradle.sh`; use focused `--tests` while iterating; run the module's full test task before each commit.
- JVM tests over the fakes (`FakeTransport`, `FakeBus`, `FakeSigner`, `FakeLlmEngine`, MockWebServer) are required; no real network, no real keys, no real mailbox.
- No attribution strings and no legacy product or vendor names appear in code, docs or commits; commit messages read as the author's own.
- No dependency from any module below `:app` on `:cloud` (the build enforces it).
- Commit on the track branch; never touch `main`.
- Screens and UI features follow `docs/ui-template.md`; `DesignGuardTest` enforces it.
