<!-- HiveCore specification. "Phone Swarm" below is the project placeholder name: it is HiveCore (application id com.hivecore). Amendments the build taught are collected at the end under "Amendments". -->

# Phone Swarm Core Engine — Specification

Oct 10, 2026

A new Android app that turns a group of iQOO 15 phones, in one room or in different cities, into one AI system: any phone can take a request, plan it, tell the other phones which local model to load, cut the work into chunks, spread them over the phones, track every chunk, recover from any phone failing, and merge a verified answer, with a cloud model used only by the requesting phone and only when the local models cannot deliver.

## 1. Purpose and the one-page model

There is no server in the hot path. Every phone runs the same app; roles are taken per request. The phone a request is made on is the **orchestrator** for that request; every other opted-in phone is a **node**; a phone whose private data a step reads is an **owner** and that step runs only there.

```
  1. Request on phone A (orchestrator)
           |
  2. Plan:   T0 rules -> T1 local LLM -> T2 cloud planner (A only, if T0/T1 not confident)
           |          produces a typed task graph: steps, data needs, capability per step
  3. Compile: validate ops, arity, privacy, budget; compute planHash
           |
  4. Place models: MODEL_REQ to each node -> which local model to load (LLM, VLM, ASR, OCR...)
           |
  5. Choose mode: plan shows LOCAL (N phones, est. time, nothing leaves) and CLOUD (A only, est. time, cost, what leaves)
           |
  6. Split: each step over a big input becomes chunks along one axis with a declared merge
           |
  7. Distribute: nodes pull chunks (1 running + 1 prefetched); private chunks stay on the owner
      |        |        |         |
   phone A  phone B  phone C   phone D (owner, private rows)
      |        |        |         |
  8. Track: journal every dispatch/result/fault on A; nodes keep memo of finished chunks
           |
  9. Verify: hash of every output; LLM steps run twice and must agree; owner aggregates checked
           |
 10. Fault: timeout / yield / crash -> requeue, duplicate, re-cut, reclaim; cloud fallback on A only if accuracy fails
           |
 11. Merge on A in chunk order -> same bytes as an unsplit run -> render -> ledger
```

The two halves of decentralisation:

- **Compute moves to the data** for private material: tables, documents and media never leave the phone that holds them unless the user releases them.
- **Data moves to idle compute** for released material: content-addressed blobs travel to idle phones, which delete them once the result is countersigned.

The handshake, the chunk routing table, the fallbacks and the cloud escalation are all parts of this one loop, specified section by section below. Reference design: the Sovereign Nodes engine (see the second tab), whose tested patterns this project adopts where they fit and replaces where they do not.

## 2. Principles and invariants

Twelve rules the code enforces; every later section is a consequence of one of them.

| # | Invariant | How the code enforces it |
| --- | --- | --- |
| P1 | No server in the hot path; the swarm works with zero internet | Planning tiers T0/T1, placement, split, distribution, verification and merge have no network dependency beyond the mesh |
| P2 | Private data never leaves its phone; released data travels as content-addressed blobs and is deleted after use | `privacy` flag on every input, inherited by every derived output; blob store keyed by sha256; `BLOB_DELETED` countersign |
| P3 | Cloud models run only in the orchestrator role, only for that phone's own request | The `:cloud` module is reachable only from the orchestrator's commander objects; the node module cannot depend on it (build rule); no message carries a cloud prompt, result or API key |
| P4 | Cloud is chosen either by the user from the plan's two options or automatically only when a local result fails a defined accuracy or capability test | Plan always carries both options; automatic use goes through the Escalation Gate's trigger list and nothing else |
| P5 | Trust comes from verification, not from the network | Every message signed by a pinned key; every output hashed over canonical JSON; LLM steps replicated and compared; owner aggregates checked against invariants |
| P6 | The same job on 1, 2 or N phones, local or remote, produces the same result bytes for exact merge kinds | Deterministic chunk ids, merge on the orchestrator in chunk-index order, canonical number formatting |
| P7 | The owner always wins | A phone being used by its owner yields all worker tasks; yielding is a reassignment, not a fault |
| P8 | Every job ends done or partial-with-reason; nothing hangs | Deadlines on every attempt, bounded attempts, a wake timer on the queue, journaled state |
| P9 | One writer of job state, persisted before it is acted on, reclaimable from nodes | SQLite journal on the orchestrator; persistent chunk memo on every node; `RECLAIM` |
| P10 | A node runs only work it was authorised for | A `TASK` is accepted only if the node already holds the signed `PLAN` naming it |
| P11 | Links are mutually authenticated and encrypted, including LAN and relay | Noise XX session before any envelope; pinned static keys |
| P12 | Models are named by capability in plans and resolved to hashed builds at placement | Plans never name files; replicas compare only outputs of the same model hash |

## 3. Roles and components

Four roles, taken per request by the same app, and eight components that every phone carries.

### 3.1 Roles

| Role | Taken by | Responsibilities | May call cloud |
| --- | --- | --- | --- |
| Orchestrator | The phone the request is made on | Plan, compile, place models, offer local/cloud mode, split, dispatch, journal, verify, merge, render, ledger | Yes: T2 planning and the Escalation Gate, for its own request only |
| Node | Any opted-in phone that is idle and eligible, co-located or remote | Load the model it is told to, run data-free chunks, return hashed outputs, keep a memo of finished chunks | Never |
| Owner | A phone whose private data a step reads | Run owner-class steps on its own data, approve plans that touch confidential data, keep private chunks local | Never |
| Deputy | One node the orchestrator picks for long jobs (optional) | Mirror the job journal; answer `RECLAIM`; never dispatch or merge | Never |

A phone can be orchestrator, owner and node in one job: its own chunks go through the same messages, delivered in-process.

### 3.2 Components on every phone

| Component | Does |
| --- | --- |
| Mesh | Discovery, transports (LAN, Nearby, Bluetooth, WAN relay, WebRTC), encrypted sessions, signed envelopes, replay guard |
| Trust | Node key in the Keystore, pairing, roster, revocation, plan-scoped consent |
| Profile beacon | Broadcasts what this phone can do right now (battery, thermal, loaded model, queue depth, link class) every 15 s on LAN, 30 s on WAN |
| Runtime | GenieX-backed model pool: load, warm, run, benchmark; one big model slot plus small warm models |
| Task runner | One task at a time, priority-ordered (own request, owner work, worker work, scheduled); executors per op |
| Planner | T0 rules, T1 local LLM closed choices, compiler; T2 cloud planner interface (implemented only in the orchestrator path) |
| Commander | Composite plans (typed task graph) and split jobs (chunks); placement; work queue; verification; merge; escalation gate |
| State | Journal, chunk memo, ledger, blob store |

### 3.3 Two engines, one substrate

- **Graph engine**: a question becomes a graph of typed steps (aggregate on owners, reduce on the orchestrator, extract/classify on nodes). Used for questions over distributed data.
- **Split engine**: one op over one big input or many items becomes chunks pulled by idle phones and merged on the orchestrator. Used for bulk work: OCR 200 photos, transcribe an hour of audio, summarise a 300-page document, classify 10,000 texts, upscale an image.

A graph step whose input is big is itself a split job, so the two compose: plan → graph → each big step split → merged → next step.

## 4. Devices and local runtime

Every phone is an iQOO 15, runs GenieX with the Qualcomm NPU runtime as the fast path, holds one big model at a time, and advertises what it can run right now.

### 4.1 Hardware baseline

[iQOO 15](https://www.91mobiles.com/iqoo-15-512gb-16gb-ram-price-in-india?ty=specs): Snapdragon 8 Elite Gen 5 (SM8850-AC), 12 or 16 GB LPDDR5X, 7000 mAh with 100 W charging and bypass charging, OriginOS on Android 16, 8K vapor chamber. One phone model means one model build per capability, identical hashes everywhere, one benchmark table shared by the swarm.

### 4.2 Inference runtime: GenieX, QAIRT and the alternatives

[GenieX](https://geniex.aihub.qualcomm.com/en/get-started/what-is-geniex) is Qualcomm's on-device Gen AI runtime with a Kotlin SDK; it dispatches to either llama.cpp (GGML kernels on CPU, GPU or Hexagon) or Qualcomm AI Engine Direct, the NPU-only runtime for pre-compiled AI Hub bundles, also known as QAIRT, and lists Snapdragon 8 Elite Gen 5 as an Android target.

QAIRT is an execution backend for one model on one phone. It is not a cross-device orchestrator; the orchestration layer is this project. Alternatives considered for execution:

| Option | Verdict |
| --- | --- |
| GenieX + QAIRT (AI Hub bundles) | Primary for LLM, VLM and HEAVY ops: highest NPU throughput, pre-quantised |
| GenieX + llama.cpp (GGUF, Q4\_0) | Secondary for any model not on AI Hub, and the always-works fallback; gives GBNF grammars for guaranteed-valid JSON |
| LiteRT / QNN delegate | Small vision and audio models (OCR, detection, embeddings, Whisper) that stay warm beside the big model |
| MediaPipe LLM Inference, MLC-LLM | Not used: no NPU path on this chipset comparable to Genie |

### 4.3 Capability catalog

Plans name a **capability**; the catalog (`models/catalog.json`, shipped with the app and updatable) maps it to concrete builds: `{modelId, capability, runtime, sha256, sizeBytes, loadMsP50, unitMsP50, maxContext, big}`.

| Capability | Model class | Runtime | Big slot |
| --- | --- | --- | --- |
| `llm.text`, `llm.json` | 3B–8B instruct (AI Hub bundle) | QAIRT | Yes |
| `vlm` | Gemma 3 4B / Gemma 3n class multimodal | llama.cpp or QAIRT | Yes |
| `asr` | Whisper base/small | QNN / LiteRT | No (warm) |
| `ocr`, `embed`, `detect`, `segment`, `classify`, `iqa` | Small vision/text models | QNN / LiteRT | No (warm) |
| `gen.image`, `superres`, `upscale` | Diffusion / super-resolution bundles | QAIRT | Yes (HEAVY) |

Rules: one big slot per phone (`big = llm | vlm | gen | none`); small models stay loaded; swapping the big model costs seconds and is a scheduling input (section 8). Video is handled as frame sampling on the owner followed by `vlm` per frame.

### 4.4 Op classes: where an op may run

| Class | Meaning | Examples |
| --- | --- | --- |
| OWNER | Reads the owner's rows, docs or media; exactly one executor; never moved or replicated | AGG, DOC, FRAME\_SAMPLE, VAD\_SPLIT |
| REDUCE | Pure Kotlin over JSON; always on the orchestrator | JOIN, RATIO, DIFF, RANK, THRESHOLD, RENDER |
| LLM | Needs the LLM in the big slot | EXTRACT, FIELDS, SUMMARIZE, TEXT\_CLASSIFY, LLM\_MAP |
| VLM | Needs the VLM in the big slot | VLM\_ASK |
| HEAVY | Long NPU work; charger and cool required | GEN\_IMAGE, UPSCALE, ASR, SUPERRES |
| LIGHT | Small or no model; any eligible phone | OCR, EMBED, DETECT, TAG, IQA, VERIFY |

### 4.5 Eligibility and worker mode

```
idle     = screen off >= 60 s  OR (charging AND app not in foreground)
eligible = optIn AND idle AND (battery >= 40 % OR charging) AND thermal <= 1
           AND batteryTemp < 38 C AND NOT inCall
heavyOk  = eligible AND charging AND thermal <= 1 AND batteryTemp < 40 C
           AND NOT in duty-cycle pause (2 min NPU work -> 30 s pause)
```

- Worker mode is off by default; the user opts in per phone. Opt-in starts a foreground service (OriginOS kills background work otherwise) and asks for the battery-optimisation exemption.
- A phone that is not eligible as a worker is still a full owner and orchestrator.
- Bypass charging is the preferred HEAVY state: on charger with the battery not heating.

### 4.6 Profile (what a phone advertises)

`{nodeId, name, ts, eligible, heavyOk, backend, big, warm[], capabilities[{cap, modelId, sha256, p50}], tokPerSec, queueDepth, link{kind, kbps, rttMs, metered}, cooldowns[], rosterVersion}`. Signed, sent every 15 s on LAN and 30 s on WAN and on any change; stale after three missed beats. The scheduler's only view of the swarm is the latest profile per phone.

## 5. Mesh and transport

Phones in one room talk directly; phones in different cities talk through a zero-trust relay for control and WebRTC data channels for bulk, and the scheduler treats each link's bandwidth and latency as inputs.

### 5.1 Transport ladder

| Rank | Transport | Discovery | Typical kbps | RTT | Blobs |
| --- | --- | --- | --- | --- | --- |
| 1 | LAN TCP (same Wi-Fi) | NSD/mDNS | 20,000–100,000 | < 10 ms | Yes |
| 2 | Wi-Fi Direct / Wi-Fi Aware via Nearby Connections | Nearby advertising | 10,000–50,000 | < 20 ms | Yes |
| 3 | Bluetooth RFCOMM | Nearby / BT scan | \~1,200 | \~50 ms | Text only (transfer rule) |
| 4 | WAN direct: WebRTC DataChannel with STUN | Relay presence | 2,000–20,000 | 30–150 ms | Yes, unless metered and not allowed |
| 5 | WAN relay: WebSocket through `relayd`, or TURN | Relay presence | 500–5,000 | 50–300 ms | Control only; blobs as last resort |

One active transport per peer, the highest rank that works, re-negotiated on loss. Envelopes are capped at 64 KB; blobs travel as 16 KB content-addressed pieces.

### 5.2 Envelope

Every message is `{v, type, from, body, sig}` where `sig` is ECDSA P-256 over the exact UTF-8 bytes of `body`, verified against the pinned key for `from`, then sealed inside the peer session (section 6). Per-type sender rules check that the body's named sender equals `from`. Bodies with a nonce (`PLAN`, `TASK`, `MODEL_REQ`, `RECLAIM`) pass a replay guard: a seen-set of `(sender, nonce)` and a ±10 min timestamp window.

### 5.3 Relay (`relayd`)

- Stateless WebSocket service, one Go binary. A phone connects, receives a 32-byte challenge, signs it with its node key, and sends `{nodeId, groupId, rosterVersion, sig}`. The relay verifies against the roster the group admin uploaded and forwards `{to, from, cipher}` only between members of one roster.
- It never sees plaintext; every envelope is sealed end-to-end before it reaches the relay.
- Store-and-forward: a message for an offline member is held up to 5 minutes, then dropped with a `drop` notice to the sender.
- Presence: `{nodeId, online, since}` for roster members is pushed to every connected member and feeds the profile beacon exactly like LAN discovery.
- Time: the auth response carries the relay clock; each phone keeps an offset so replay windows hold across time zones and drifted clocks.

### 5.4 Bulk transfer

- WebRTC DataChannel (SCTP, ordered, reliable), signalled through the relay with `SIGNAL` messages carried inside the sealed envelope; STUN first, a coturn TURN server as fallback. Library: [Stream's pre-compiled WebRTC for Android](https://github.com/getstream/webrtc-android), the maintained replacement for Google's abandoned artifact.
- A blob piece is one DataChannel message; the receiver reassembles in memory and writes the file only if the whole content hashes to the blob id.
- If no data channel can be opened within 15 s, pieces fall back to the relay at a capped rate; the transfer rule then usually keeps media at home.

### 5.5 Presence, sleep and wake

- Opted-in nodes hold the relay socket inside the worker foreground service.
- When a node's socket is gone and the orchestrator needs it, the orchestrator sends `wake {nodeId}` to the relay, which sends an FCM high-priority data message (UnifiedPush is the drop-in if nothing Google-side is wanted); the app reconnects and re-announces its profile. 20 s grace before the node is treated as absent.
- Heartbeats: 15 s / 45 s stale on LAN, 30 s / 90 s on WAN.

### 5.6 Link class

`Profile.link = {kind, kbps, rttMs, metered}`, measured by a 2 KB echo at session start and every 5 minutes. Used by the transfer rule (`bytes·8 / kbps + 2·rttMs`), by deadlines (`+ 2·rttMs`), by the candidate filter (HEAVY and blob-carrying chunks never over Bluetooth or the relay), and by the metered policy (no blobs over mobile data unless allowed for the swarm).

## 6. Identity, pairing and trust

Each phone is a P-256 key in its hardware Keystore; phones join a swarm by a human-verified handshake; every link is encrypted; membership is an admin-signed roster; work is authorised per plan.

### 6.1 Identity

- Node key: P-256 in the Android Keystore, non-exportable. `nodeId = sha256(DER public key)`.
- Approval key: a second Keystore key gated by biometrics, used only to sign plan consent (section 7.6).
- Pinning: a peer's public key is stored once, at pairing. Afterwards any envelope from an unpinned `nodeId`, or with a bad signature, or carrying a different key for a known `nodeId`, is dropped ("forget and re-pair").

### 6.2 Pairing handshake

| Situation | Flow | Keys are stored when |
| --- | --- | --- |
| Co-located | Inviter shows a QR with `{groupId, pubkey, token (10 min)}`; joiner scans and connects over LAN or Nearby; both run the Noise handshake; both phones show a 6-digit short authentication string (SAS) derived from the handshake hash | Both users confirm the SAS matches |
| Remote | Inviter sends an invite link `swarm://join?g=<groupId>&k=<pubkey>&t=<token>` by any channel; joiner opens it, connects through the relay; both show the SAS and compare it on a voice call | Both users confirm the SAS matches |
| Re-pair after key loss | Admin revokes the old `nodeId`; either flow above | After revocation is acknowledged |

A mismatched SAS or an expired token aborts before any key is stored. The SAS defeats a relay or network attacker substituting keys; the token stops uninvited joins.

### 6.3 Encrypted sessions

- Noise XX per peer link: ephemeral ECDH → HKDF → AES-256-GCM, with the pinned static keys authenticating both sides. Messages `SESSION_INIT`, `SESSION_RESP`, `SESSION_FIN`.
- Required on every transport. Nearby Connections' link encryption is not relied on; LAN and relay would otherwise be plaintext-signed.
- Rekey every 24 h or 1 GB. An authentication failure drops the transport and shows a warning naming the peer.

### 6.4 Roster and revocation

- A group (swarm) has one admin phone. The roster `{groupId, version, admin, members: [{nodeId, pubkey, name, addedTs}], revoked: [nodeId], adminSig}` is signed by the admin and gossiped as `ROSTER`; phones keep the highest validly signed version.
- The admin uploads each roster version to the relay, which forwards only within a roster and refuses revoked ids at auth.
- A revoked phone's key is deleted everywhere; its in-flight attempts are faulted and requeued; its already-verified outputs stay valid.
- Admin handover: the admin signs a roster naming a successor.

### 6.5 Task authorisation

- A node accepts a `TASK` only if it already holds a `PLAN` with that `planId` signed by the same orchestrator, and the task's spec hash appears in that plan. Split jobs send a `PLAN` too (one spec template per op plus the ordered chunk ids).
- Unauthorised tasks are dropped and counted; three in a minute from one peer raise a warning.

### 6.6 Canonical hashing

Every hash is `sha256(canonical(json))`: keys sorted, no whitespace, numbers as shortest exact decimals (`5`, `5.0`, `5.00` all print `5`). Arithmetic results are normalised: exact integers to Long, others rounded half-up to 4 decimals. This is what makes "same bytes on any number of phones" testable.

## 7. Planning: the task graph

A request becomes a typed state graph in the LangGraph style (nodes, typed state, conditional edges), produced by local rules first, a local LLM second, and a cloud model only when those are not confident; whatever produces it, one compiler validates it.

### 7.1 Why not run LangChain on the phone

LangChain and LangGraph are Python/JavaScript runtimes; on Android they would need an embedded Python or a WebView, both fragile and slow to start. The project keeps LangGraph's *model* (a graph of nodes over a typed state with conditional edges) and implements it in Kotlin. The graph is plain JSON, so the same graph can be loaded into LangGraph on a laptop for offline testing and visualisation.

### 7.2 Planner tiers (on the orchestrator)

| Tier | Engine | Fires when | Leaves the device |
| --- | --- | --- | --- |
| T0 | Cue lexicon: table and column names, phrases such as per / compare / trend / top / flag / summarise | Always first | Nothing |
| T1 | Pattern library (parameterised graphs) + local LLM answering closed questions only: which pattern, which slot value; 3 runs with reordered options, confidence = agreement | T0 found owners or cues | Nothing |
| T2 | Cloud planner (section 15) returning a graph in the schema below | T1 confidence < 0.5 after one clarification, or no pattern matched, or a capability no pattern covers | Question text; catalog schema (names and flags only) if the user enabled schema sharing; swarm capability summary; op registry |

A setting can move T2 first for users who prefer speed; the default is local first. Plans are cached by `sha256(question + catalogHash + capabilityHash)` with the tier recorded.

### 7.3 Graph schema

```json
{
  "goal": "extract vendor and total from invoice photos",
  "nodes": [
    {"id": "ocr",    "op": "OCR",    "cap": "ocr",      "input": "items", "axis": "shard"},
    {"id": "fields", "op": "FIELDS", "cap": "llm.json", "needs": ["ocr"], "axis": "shard", "replicas": 2,
     "schema": {"type": "object", "properties": {"vendor": {"type": "string"}, "total": {"type": "number"}},
                "required": ["vendor", "total"]}},
    {"id": "out",    "op": "RENDER", "needs": ["fields"]}
  ],
  "edges": [{"from": "fields", "to": "out", "when": "always"}],
  "privacy": {"items": "private"},
  "confidence": 0.86,
  "clarification": null
}
```

- `cap` names a capability (section 4.3); `axis` is `shard | chunk | fanout | none`; `replicas` defaults to 2 for LLM and VLM ops; `schema` is a JSON Schema the executor, the verifier and the escalation gate all validate against.
- There is no `exec` field: a node of the graph cannot ask for the cloud. Execution mode is chosen per plan (section 9), not per node.
- Conditional edges (`when`) support the common controls: `always`, `if_empty`, `if_confidence_below:<x>`, `if_count_above:<n>`.

### 7.4 Compiler checks

Before anything is sent: ids unique; output node exists; every input exists and precedes its consumer; arity per op; table ops only read table producers; a group-by key is among the producer's declared dimensions; owner ops carry a sub-question and a known owner; every op exists in the registry; every (op, axis) has a merge kind; every `cap` exists in the catalog; private lineage never reaches a non-owner node; a confidential column only in `avg`/`count`; every `schema` is valid JSON Schema 2020-12 without `$ref`; budget ≤ 12 tasks and ≤ 6 LLM calls per phone. `planHash = sha256(canonical(graph) + patternsVersion + modelHashes)` is computed even for rejected plans so refusals are logged against it. A T2 graph that fails gets one repair round with the error list; a second failure drops to T1's best candidate plus a clarification listing the top two readings.

### 7.5 Generic step: `LLM_MAP`

For tasks no pattern covers: `params = {template, schema, maxTokens}` applied per item, output validated against `schema`; SHARD merge = concatenate, FANOUT merge = majority vote. It gets replication and hash checks like every other LLM op, so T2 can express new tasks without the runtime learning a new op.

### 7.6 Owner side of a plan

An owner receiving a `PLAN` compiles every sub-question aimed at it through its own query compiler with the same floors as a direct question (`COUNT(*) ≥ 5` per group, only AVG/COUNT on confidential columns, no identifier filters), runs a differencing guard over all of them plus that requester's last 24 h of answered questions, and if any figure touches confidential data asks for **one** fingerprint for the whole plan and returns a signed `PLAN_CONSENT_RES`. A task for that plan runs only what was prepared under it; the orchestrator discards any figure that arrives while consent is pending.

## 8. Model placement

Before any chunk is sent, the orchestrator decides which phone loads which big model, tells it, and waits for confirmation; this is the step where the graph's capability requirements become concrete models on concrete phones.

### 8.1 Inputs

- The compiled graph's capability demand: `{capability → estimated units}` per step.
- The latest profile per phone: eligibility, `heavyOk`, currently loaded big model, warm small models, benchmark `p50` per model, queue depth, link class.
- The catalog: load time, unit time and size per model build.

### 8.2 Solver

`Placement.solve(graph, profiles, catalog, self)` is a pure function (same inputs → same assignment, so it is auditable):

1. For each capability, candidates = eligible phones (plus `heavyOk` for HEAVY) plus the orchestrator.
2. Cost of assigning a capability to a phone = `(loadMsP50 if its big slot holds a different model, else 0) + units × unitMs(model on that phone) + queueDepth × 3000 ms`.
3. Greedy list scheduling: capabilities by total demand, largest first; each capability to the phones that minimise the makespan, keeping one big model per phone per job; ties broken by `sha256(nodeId + capability)`.
4. LAN phones are preferred for capabilities whose steps carry blobs; WAN phones are preferred for prompt-in/JSON-out capabilities.
5. Output: `[{nodeId, capability, modelId, sha256, swap: bool}]` and the expected swap cost, shown in the plan panel.

### 8.3 Protocol

| Step | Message | Body |
| --- | --- | --- |
| 1 | `MODEL_REQ` orchestrator → node | `{planId, capability, modelId, sha256, deadlineMs, commander, ts, nonce}` |
| 2a | `MODEL_READY` node → orchestrator | `{planId, node, modelId, loadMs, warm}` |
| 2b | `MODEL_FAIL` node → orchestrator | \`{planId, node, modelId, class: oom |

- The node loads through GenieX: QAIRT bundle first; on failure, the llama.cpp build of the same capability once; then `MODEL_FAIL`.
- The work queue opens for a capability as soon as one phone is ready for it; later `MODEL_READY`s add phones.
- `MODEL_FAIL` applies a 10-minute cooldown to `(node, capability)` and re-runs the solver for the remaining demand.
- Deadline for a load: `max(10 s, 3 × loadMsP50)`; a missed deadline counts as `MODEL_FAIL {class: runtime}`.

### 8.4 After the job

Models stay loaded (warm) until the next placement or the owner's own use evicts them; the profile's `big` and `warm` fields advertise this so the next job's solver prefers phones that need no swap.

## 9. Execution mode: local vs cloud

Every plan carries two execution options with estimates; the user or a policy picks one; cloud execution only ever runs on the orchestrator, and automatic switching to cloud happens only when a local result fails a defined test.

### 9.1 The two options

| Option | Where it runs | What leaves the phones | Estimate shown |
| --- | --- | --- | --- |
| LOCAL | The swarm: nodes, owners, orchestrator | Released blobs to nodes (deleted after countersign); nothing to the internet | Time on N phones vs on one, which phones, which models load |
| CLOUD | The orchestrator only, calling OpenRouter for the LLM/VLM steps; owner and reduce steps still run locally | Prompt text and released inputs of the cloud-eligible steps; never private lineage, never catalog values | Time, cost in ₹, bytes that leave, which steps |

A mixed plan is the normal cloud option: OCR and aggregates local, extraction or summarisation on the cloud from the orchestrator. Steps with private lineage are never cloud-eligible, and if every LLM step is private the CLOUD option is shown disabled with the reason.

### 9.2 How the default is chosen

| Signal | Pushes toward |
| --- | --- |
| Any private or confidential input in the step | LOCAL only (CLOUD greyed out for that step) |
| Many items and ≥ 2 eligible phones | LOCAL |
| Long single-shot reasoning on small text | CLOUD |
| No internet on the orchestrator | LOCAL |
| Phones hot or low on battery, user wants it now | CLOUD for public steps |
| Required context or capability exceeds every local model | CLOUD for that step (flagged by the compiler) |

Policy settings: **ask every time** (default), **local unless impossible**, **fastest**. The policy picks the default; the user can always flip the choice before the job starts.

### 9.3 Where cloud is never used

- On a node. A node has no cloud client, no API key and no message that could deliver a cloud result to it (P3).
- For OWNER-class steps (aggregates, document reads, frame sampling on private media).
- For steps whose lineage is private, unless the user explicitly releases the specific items from the consent sheet.

### 9.4 Automatic switch after a local failure

If LOCAL was chosen and a step fails a verification test, the Escalation Gate (section 15) may re-run that one step on the orchestrator through the cloud, with the same inputs and the same output schema, and the result is badged as cloud-sourced. The gate fires only on the listed triggers: capability gap, replica disagreement, schema failure, low vote confidence, or the user marking a result as not accurate. Slowness, a lost phone or a partial job never trigger it.

## 10. Splitting

A split job is one op over one big input or many items, cut along one axis into chunks whose ids depend only on their content, with a declared way to merge the outputs back.

### 10.1 The job

`SplitJob(jobId, op, params, capability, input, merge?, owner?, chunks?, unitsPerChunk?, unitMs?)`. `owner` is the data holder (default the orchestrator); private chunks run only there. The sizing overrides pin an exact chunk count, units per chunk, or the expected ms per unit when no benchmark exists.

### 10.2 Input kinds and axes

| Input | Axis | Unit | How it is cut | What the chunk executor receives |
| --- | --- | --- | --- | --- |
| `Items(items)` | SHARD | one item | consecutive ranges | `{items: [a..b)}` |
| `Blobs(ids, sizes, private[])` | SHARD | one blob | consecutive ranges; boundaries also fall wherever `private` changes, so a chunk never mixes private and public items | `blobs: [ids]`, `bytes` for the transfer rule |
| `Text(text, doc, overlap=1)` | CHUNK | one sentence | sentence ranges; chunk *i* also sees `overlap` sentences before its range as context | `{units, ownFrom, ownTo, overlapBefore}` |
| `Audio(durationMs, blobId, segments?)` | CHUNK | one second | ≤ 28 s owned + 1 s overlap each side; or one chunk per voice-activity segment | `{startMs, endMs, ownFromMs, ownToMs}` |
| `Video(frames, blobId)` | CHUNK | one frame | frame ranges | `{frameFrom, frameTo}` |
| `Image(blobId, w, h, tile=128, overlap=16)` | CHUNK | one tile | tile groups | `{tiles: [idx]}` |
| `Table(table, rows)` | CHUNK | one row | row ranges for partial aggregates | `{table, rowFrom, rowTo}` |
| `Seeds(n, base)` | FANOUT | one seed | seed ranges | `{seeds}` |
| `FanOut(variants)` | FANOUT | one variant | one chunk per variant | the variant's params |

Every chunk records its owned range `[ownFrom, ownTo)`, its overlap context, and the bytes that would travel to a remote phone.

### 10.3 Chunk identity

```
chunkId = sha256( op + "@" + modelSha256 | canonical(job params) | canonical(slice params) | blobIds )
```

The same input cut the same way yields the same ids on every phone. A retry, a duplicate, and a re-run of the job after adding ten items are the same work for the chunks that did not change, so finished outputs are reused from the memo (section 13). Task ids inside a run are `c<chunk>.a<attempt>` under plan id `<jobId>#<run>`, so every attempt is distinct to a node while a late result of an old attempt is still recognised by the queue.

### 10.4 Which ops split, and how they merge

| Merge kind | Meaning | Equal to an unsplit run |
| --- | --- | --- |
| CONCAT | shard outputs concatenated in item order | yes |
| STITCH | ordered chunks of one input with the overlap removed (sentence ids, tile index, audio midpoint rule, transcript word-overlap stitch) | yes |
| TREE | pairwise extractive reduce that only picks sentence ids from its children | deterministic, labelled "tree summary of N chunks" |
| MONOID | count/sum/min/max added per group key; mean recomputed from sum and count | yes |
| TOPK | per-chunk top-k merged by score desc, id asc, first k | yes |
| VOTE | fan-out majority; confidence = agreeing / runs | n/a (ensemble) |
| RANK | fan-out candidates ordered by a deterministic score | n/a (ensemble) |

Across inputs (SHARD): CONCAT for OCR, FIELDS, classify, detect, embed, VLM\_ASK, LLM\_MAP, generation per seed; STITCH for ASR and super-resolution; TREE for SUMMARIZE and EXTRACT; MONOID for AGG; TOPK for KNN. Within one input (CHUNK): STITCH for ASR, super-resolution, sentence-level classify and embed; TREE for SUMMARIZE/EXTRACT; MONOID for AGG; CONCAT for per-frame vision ops. Ops that are one model generation (GEN\_TEXT, GEN\_IMAGE, VLM\_ASK) never split within an input. An op with no merge kind on the requested axis fails at planning with "cannot be split along that axis" and runs unsplit on the best single phone.

### 10.5 Chunk sizing

1. Axis from the input kind; merge kind from the job or the table above.
2. Eligible phones = those with `MODEL_READY` for the op's capability plus the orchestrator.
3. Expected ms per unit per phone = `job.unitMs`, else that phone's benchmark `p50`, else the catalog `unitMsP50`, else a default by kind (0.05 ms per row, 100 ms per audio second, 50 ms per tile, 4000 ms per LLM unit, 1500 ms per VLM unit, 3000 ms per HEAVY unit, 200 ms otherwise). The median across eligible phones drives sizing.
4. Units per chunk = `round(30 000 ms / medianUnitMs)` clamped to `[1, n]`, then lowered until there are at least `2 × eligible phones` chunks so fast phones can take more. TREE uses a fixed 40 sentences per chunk so the summary is identical on any phone count. Audio windows are capped at 28 s.
5. The single-phone baseline `singlePhoneMs = Σ expected ms of every chunk on the owner alone` is what the speed-up in the UI is measured against.

## 11. Distribution

Chunks sit in a pull queue on the orchestrator; each phone holds one running chunk and one prefetched; fast phones take more; slow chunks are duplicated; lost phones lose only their in-flight chunks.

### 11.1 Queue state

An ordered queue of chunk indices; the active phone set; live attempts per phone; outputs per chunk; per-chunk attempt counts and a duplicated flag; per-phone busy ms, chunks and units done; counters for bytes moved, duplicates, mismatches, reassignments; a human-readable notes list. The queue is a pure state machine; the commander does the messaging around it.

### 11.2 Fill, after every event

1. Order phones: warm first (model already loaded), then LAN before WAN, then fewest live attempts, then `nodeId`. Fill each to 2 slots (one running + one prefetched).
2. `pick(phone)`:
   - only chunks that are public or whose owner is this phone;
   - the owner takes first any chunk that is private or holds blobs it already has (locality);
   - a remote phone skips a blob-carrying chunk when `transfer + remote > local`, with `transfer = bytes·8 / link.kbps + 2·rttMs`, `remote = that phone's backlog + its expected ms`, `local = owner's backlog + owner's expected ms`. Inline text, prompts and ids always move; images and audio move only when it pays;
   - HEAVY chunks and blob-carrying chunks are never offered over Bluetooth or the relay; metered WAN phones get no blobs unless allowed.
3. `assign` records `expectedDoneAt = now + backlog + expected` and `deadlineAt = now + max(4 s, 3 × expected + 2·rttMs)`.
4. Blobs a remote phone lacks are sent before its first chunk that needs them; a failed transfer drops the phone for that chunk.
5. Stragglers: once the queue is empty, each idle phone duplicates the most overdue running attempt elsewhere (past `dispatched + 1.5 × expected`, not already duplicated, public or owned here), LAN phones preferred. First result wins; the second is compared.

### 11.3 Node side

A node runs one task at a time, by priority then FIFO: its owner's own request → owner-class work for a peer → worker work for a peer → scheduled work. Executors register per op; a task carries the chunk params, inline upstream outputs, the deadline and the model hash; the reply carries the output and its hash. `queueDepth` goes into the profile so the orchestrator sees backlog.

**The owner always wins.** Screen-on, unlock or app-to-foreground calls `yieldAll()`: the running task finishes, every queued worker task fails with `yielded`, new worker tasks are refused until the phone is idle again. A yield is reported as `TASK_YIELD` and is a reassignment, not a fault. A phone whose worker mode is off refuses all remote worker tasks the same way.

### 11.4 Orchestrator loop

```
place models -> queue(plan, eligible, warm, links) -> prefill memo hits -> planId = jobId#run
loop:
  error?         -> cancel running, emit error, stop
  fill()         -> for each assignment: send blobs if missing, then TASK{planId, c<i>.a<n>, params, deadline, modelHash}
  emit progress; wait for event or nextWake (earliest deadline, else earliest straggler threshold)
  timeout        -> TASK_CANCEL every overdue attempt; fault("no reply"); drop the phone after 3 misses
  TASK_YIELD     -> fault("yielded"), phone stays
  TASK_RESULT    -> error -> fault; hash mismatch -> fault; else accept (+ memo), cancel other runners of the chunk
  MODEL_FAIL     -> cooldown, re-place
  Cancel         -> TASK_CANCEL everything, emit cancelled
done -> TASK_CANCEL leftovers -> merge in chunk order -> result + resultHash -> journal + ledger
```

The orchestrator's own chunks go through the same `TASK` message, delivered in-process to its own runner at own-request priority.

## 12. Verification and merge

No output is trusted because of where it came from: every result is hashed, model steps are run twice and compared, owner aggregates are checked against invariants, and the merge is deterministic.

### 12.1 Per-result checks

| Check | Rule | On failure |
| --- | --- | --- |
| Output hash | `outputHash = sha256(canonical(output))` recomputed by the orchestrator | Failed attempt; the phone's mismatch counter +1; three in one job removes the phone |
| Schema | Output validates against the step's `schema` (and the op's built-in shape) | Failed attempt; after 3 failures across ≥ 2 phones the step is a verification failure |
| Owner aggregate invariants | Output decodes as an aggregate table, carries every declared dimension, per-group `n` sums to `rowsScanned` | Plan refuses, naming the owner |
| Replication | Every LLM/VLM step runs on two phones at once; outputs compared canonically (or by `cosine`/`iou`/`text` rules from the model manifest) | Mismatch → a third phone; no agreeing pair among three → verification failure |
| Model hash | Replicas compare only outputs from the same model build | Different builds are never compared; the solver avoids mixing them |
| Local judge | For ops that declare one, a `VERIFY`/`EVAL` step scores the output; threshold 0.7 by default | Verification failure |

A verification failure on a public-lineage step goes to the Escalation Gate (section 15); on a private-lineage step the job refuses that step with the disagreement note. If only one phone was available for a replicated step, the answer is given but marked "not verified" and never memoized.

### 12.2 Merge, kind by kind

Inputs are the chunk outputs in chunk-index order plus the chunks themselves (owned ranges). The merge runs on the orchestrator only.

- **CONCAT**: arrays flattened in order; objects keep every key from the first chunk and concatenate list-valued keys `items`, `segments`, `tileOutputs`, `frames`, `results`.
- **STITCH**: audio outputs filtered to items whose midpoint lies in the chunk's owned window; lists concatenated and de-duplicated by `id`/`index`/`item`, kept from the first chunk that produced them; tiles ordered by index; transcripts re-stitched across boundaries by the longest run of equal words (≥ 2 within a 12-word window, or 1 exactly at the boundary).
- **TREE**: leaves are each chunk's picks `[{id, doc, unit, text}]`; pairs merged level by level; candidates de-duplicated by id in document order; ≤ k kept as-is, else a deterministic BM25 extractive selector picks ≤ k; the merge asserts every picked id is among its candidates. Output `{method: "tree", chunks, levels, picks, citations}`.
- **MONOID**: aggregate partials with identical columns merged by group key; `n`, `count_*`, `sum_*` add; `min_*`/`max_*` take extremes; `avg_x` recomputed as `sum_x / n`; rows sorted by key; `rowsScanned` summed.
- **TOPK**: every chunk's `hits [{id, score}]` by score desc, id asc, one per id, first `k`.
- **VOTE**: each run's `value`/`label`/`class` (per item when `items` is present); majority wins, ties to the first seen; output carries `value`, `agreeing`, `confidence` and the tally.
- **RANK**: concatenated `items` sorted by the measure desc, nulls last, ties by id, with a 1-based `rank`.

### 12.3 Determinism

Chunk ids depend only on op, model hash, params, slice and blob ids; the merge order is the chunk index; numbers are canonicalised; adaptive re-cut (section 14) produces child chunks whose outputs are concatenated into the parent's slot before the merge. Result: for CONCAT, STITCH, MONOID and TOPK the result hash is identical on 1, 2 or N phones, local or remote, and that identity is a test, not a claim (P6).

### 12.4 Rendering

The final answer for a graph plan is rendered by deterministic code from the producing step's table: no model writes the sentence. The answer carries the plan summary (one line per step: op, phone, ms, verified or not), rows moved (always 0 for owner data), bytes moved, the per-step provenance, and a cloud badge on any step that escalated.

## 13. Job state and chunk tracking

The orchestrator is the single writer of "which chunk went where", persists it before acting, and can rebuild it from the nodes' memos after a crash; a deputy mirrors it for long jobs. This is the router service of the design, without a consensus protocol.

### 13.1 Journal (SQLite on the orchestrator)

| Table | Columns | Written when |
| --- | --- | --- |
| `jobs` | \`jobId, planId, planHash, plannerTier, mode (local | cloud), state, createdTs, updatedTs, privacy, capsSnapshot\` |
| `chunks` | `jobId, chunkId, index, params, owner?, state, attempts, recutOf?` | Cut; every state change |
| `attempts` | `jobId, chunkId, attempt, nodeId, dispatchedTs, deadlineTs, outcome, outputHash?, errorClass?` | Before the `TASK` is sent; on result or fault |
| `outputs` | `chunkId, outputHash, outputJson, source` (`node:<id>` or `cloud:<slug>`) | On accepted result |
| `placements` | `jobId, nodeId, capability, modelId, sha256, state, loadMs?` | On every placement message |
| `escalations` | `jobId, stepId, trigger, slug, bytesSent, tokensIn, tokensOut, costInr, ts` | On every gate decision, including refusals |

Write-ahead: the row commits before the message goes out or the merge runs. On app start, jobs in `placing`, `running` or `merging` are offered for resume.

### 13.2 Chunk memo (every phone)

- `memo(chunkId → outputHash, outputJson, ts)` in SQLite; 24-hour retention; 2 GB cap with LRU eviction.
- Covers chunks a phone ran for itself and for peers. A `TASK` whose `chunkId` is memoized is answered from memo without running; the orchestrator pre-fills memo hits before dispatch. This is how re-running a job after adding ten invoices runs only the new chunks.

### 13.3 `RECLAIM` (recover after an orchestrator restart)

1. Resume loads the journal; chunks in `done` with stored outputs are final.
2. For chunks in `running` or `queued`, the orchestrator sends `RECLAIM {planId, chunkIds}` to every phone in the plan's eligible set.
3. `RECLAIM_RES {planId, node, found: [{chunkId, outputHash}]}` comes back; each found output is fetched with a `TASK` carrying the same `chunkId` (served from memo), verified by hash, and marked done.
4. Everything still missing is requeued; placement is re-run for capabilities whose phones are gone.

### 13.4 Deputy (optional, for jobs longer than a few minutes)

- The orchestrator picks one node (highest uptime, LAN preferred) and streams `JOURNAL {jobId, fromRow, rows}` after each commit.
- The deputy stores the stream and answers `RECLAIM` for the orchestrator's own chunks too. It never dispatches, merges or escalates.
- When the orchestrator returns after a crash it asks the deputy for rows newer than its own journal before resuming.

### 13.5 Blob store and release

- Released items are stored under `files/blobs/<sha256>`; a receiving phone writes the file only when the whole content hashes to the id, so a corrupted or forged piece loses the blob rather than storing wrong bytes.
- After the orchestrator countersigns a result, the node deletes the blobs it received and sends `BLOB_DELETED {jobId, node, blobIds}`; the orchestrator records it.
- Private items are never blobs for anyone but their owner; `privacy = private` on a job marks every input private.

### 13.6 Ledger

Each phone keeps an append-only hash chain of receipts, signed by its node key: one `plan` entry per job (plan hash, planner tier, mode, assignment, profile snapshot, graph) and one `task` entry per executed step or chunk (executors, output hash, who verified, memo hit, duplicates, re-cuts, cloud source with slug, bytes, tokens, cost). Receipts are exchanged with every phone that executed work, so each side holds the other's entry hash and any answer can be replayed.

## 14. Fault handling

Every job ends done or partial-with-reason; a failed chunk is requeued, duplicated, re-cut or reclaimed, and the cloud is a fallback for accuracy only, on the orchestrator only.

| Failure | Detected by | Response |
| --- | --- | --- |
| Slow chunk | Past `1.5 × expected` once the queue is empty | Duplicate on an idle phone (LAN preferred); first result wins, second compared |
| Chunk timeout | `max(4 s, 3 × expected + 2·rtt)` | `TASK_CANCEL`; requeue at the front; exclude that phone for that chunk |
| Node vanishes (LAN or WAN) | 3 missed heartbeats or transport drop | Remove; requeue its in-flight chunks; keep its finished verified outputs |
| Node asleep (WAN) | Socket gone, roster says member | `wake` through the relay; 20 s grace, then treated as vanished |
| Relay down | Socket error on the orchestrator | Reconnect with backoff 1 s → 30 s; LAN peers continue; WAN attempts wait up to 60 s, then fault |
| Data channel fails | ICE failure or 15 s stall | Blob pieces via relay; transfer rule re-evaluated for later chunks |
| Owner picks the phone up | `TASK_YIELD` | Requeue; the phone rejoins when idle; not counted as a fault |
| Model won't load, OOM, thermal shutdown | `MODEL_FAIL`, `TASK_RESULT.error.class` | 10-minute cooldown for `(node, capability)`; requeue elsewhere; re-run placement |
| Same chunk fails on two different phones | Attempt counter | Re-cut once into two child chunks at a unit boundary; then `maxAttempts = 6`; then the chunk is failed and the job ends partial with a note |
| Output hash mismatch | Recomputed hash | Failed attempt; phone mismatch counter +1; three in a job removes the phone |
| Replica disagreement (no agreeing pair among three) | Comparator | Escalation Gate if lineage is public; refuse the step with the note if private |
| Schema validation fails 3× on ≥ 2 phones | Validators | Escalation Gate if public; failed step if private |
| Orchestrator app restarts | On-disk journal | Resume: reload; `RECLAIM`; requeue the rest; no finished chunk re-runs |
| Orchestrator phone dies | Deputy's journal copy | Job paused; resumes when the orchestrator returns; the deputy never escalates |
| Cloud call fails or spend cap hit | HTTP / gate | Step marked "unverified" if a local result exists, else job partial; never retried on a node because the cloud failed |
| Private owner leaves | Node removal | Every unfinished private chunk fails; partial result with the reason |
| Roster revocation mid-job | `ROSTER` with a higher version | Revoked phone's live attempts faulted and requeued; its verified outputs kept |
| Clock skew > 10 min | Replay guard rejects | Re-sync the relay time offset; re-send once |
| Forged or unauthorised `TASK` | Plan-store lookup (P10) | Dropped and counted; warning after 3 in a minute |
| Blob corrupted in transit | Hash-before-write | Blob dropped, re-sent once, then that phone is skipped for chunks needing it |

### Classes of error a node reports

`TASK_RESULT.error = {class, detail}` with `class ∈ {oom, model_load, thermal, schema, runtime, yielded, blobs_missing}`. The class decides the response above; `detail` is human-readable and goes into the job notes.

## 15. Escalation gate and cloud models

One gate on the orchestrator is the only path to a cloud model, whether the user chose the CLOUD option up front or a local step failed verification; it checks privacy, spend and consent, calls OpenRouter with zero-data-retention and strict-schema routing, and labels the result.

### 15.1 Automatic triggers

| Trigger | Evidence | Default |
| --- | --- | --- |
| Capability gap | No local model for the required capability (context beyond every local `maxContext`, unsupported language, a capability absent from every profile), flagged at compile time | On |
| Verification failure | No agreeing pair among three replicas; or schema validation fails 3 times across ≥ 2 phones; or the local judge scores below the op's threshold (0.7) | On |
| Low confidence | `VOTE` confidence < 0.6 on a fan-out step | On |
| User verdict | "Not accurate" on a result → re-run that step only | On |
| Time budget | A user-set deadline the local estimate exceeds by 3× | Off |

### 15.2 Pre-flight checks, in order

1. `plan.commander == selfId` (P3).
2. The step's op is not OWNER-class.
3. Privacy lineage of every input is public or user-released; derived outputs inherit the strictest input privacy, so OCR text from a private invoice is private.
4. The request carries only the step's prompt, inputs and `schema`; no catalog values.
5. Spend: `jobSpent + estimate ≤ jobCap` and `monthSpent + estimate ≤ monthCap`; estimate = the local run's token count × the slug's list price.
6. Consent: the first escalation in a job shows a sheet ("Send 12 KB of OCR text from 3 released items to \<model>? Nothing private is included."); remembered per job, optionally per month.

A failed check returns a partial result with the reason; the user can release specific items from the sheet and retry.

### 15.3 Procedure

1. Same inputs and `schema` as the local run; `temperature 0`; `response_format json_schema strict`; fallback chain below.
2. Validate against `schema`; compute `outputHash`; run the same invariant checks a node result gets.
3. Ledger `task` entry with `source = cloud:<slug>`, trigger, bytes sent, tokens, cost, and the local attempts it replaced.
4. Feed the output into the merge where the node result would have gone; the answer carries a cloud badge and the provenance shows the slug.
5. Memoize under `cloud:<slug>:<chunkId>`, never under the local chunk id, so a later local re-run is still attempted first.

### 15.4 Why OpenRouter

[Provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) accepts a `zdr` flag that restricts a request to endpoints that do not retain prompts; [structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs) use `response_format: json_schema` with `strict: true`, and `require_parameters: true` routes only to providers that support it; a `models` list gives a fallback chain with automatic routing around outages.

### 15.5 Model choices

Prices per million tokens (input / output) as listed by pricing aggregators on 2026-10-10 ([CloudZero](https://www.cloudzero.com/blog/llm-api-pricing-comparison/), [Spheron](https://www.spheron.network/blog/llm-api-pricing-comparison-gpt-claude-gemini-deepseek-2026/), [BenchLM](https://benchlm.ai/google/api-pricing)); verify against `GET /api/v1/models` when wiring.

| Role | Primary | Fallbacks | Why |
| --- | --- | --- | --- |
| T2 planner | GPT-5.6 Luna ($0.20 / $1.20) | Gemini 3.5 Flash-Lite ($0.30 / $2.50) → Claude Haiku 4.5 ($1 / $5) | Cheapest current-generation model after OpenAI's July 2026 cut; planning is a small-output JSON task and the compiler carries correctness |
| Escalation: text, JSON, reasoning | Claude Sonnet 5 ($2 / $10) | GPT-5.4 ($2.50 / $15) | Same mid-tier price band; escalation volume is low and accuracy is the point |
| Escalation: image, audio | Gemini 3.1 Pro ($2 / $12) | Gemini 3.8 Flash ($0.75 / $3.75 introductory through 2026-12-31) | Native multimodal input with a 1M-token context; a released image or recording is sent once |
| CLOUD option chosen up front | Same roles as escalation, by step type |  | The up-front CLOUD option uses the escalation slugs, so one config serves both paths |

None of these has been benchmarked on this app's prompts. An eval recipe runs the same test set against each slug; the ledger records which slug answered each call; `cloud/models.json` holds `{role, slugs[], maxTokens, jobCapInr, monthCapInr}` so a swap is a config edit.

### 15.6 Request configuration

```json
{
  "models": ["<primary>", "<fallback1>", "<fallback2>"],
  "temperature": 0,
  "max_tokens": 1500,
  "response_format": {"type": "json_schema", "json_schema": {"name": "step_output", "strict": true, "schema": {}}},
  "provider": {"zdr": true, "require_parameters": true, "data_collection": "deny", "sort": "latency"},
  "messages": []
}
```

- Timeout 30 s per attempt; one retry on 5xx; then the next model; total budget 90 s.
- If no endpoint satisfies `zdr` and `require_parameters`, the request fails with "no compliant endpoint"; there is no fallback to a non-ZDR endpoint.

### 15.7 Key handling

The OpenRouter key is entered once in Settings, stored in `EncryptedSharedPreferences` backed by the Keystore, and read only by the cloud client in the orchestrator path. Use a key with a per-key spend limit and the ZDR guardrail enabled on the account. A node never receives the key: no message, setting sync or backup path carries it.

## 16. Message catalogue

Every message is one signed JSON envelope `{v: 1, type, from, body, sig}` sealed inside a Noise session; the router checks the sender rule per type before any handler sees the body.

### 16.1 Session and membership

| Type | Direction | Body | Sender rule |
| --- | --- | --- | --- |
| `HELLO` | peer ↔ peer at pairing and reconnect | `{nodeId, name, pubkey, approvalPubkey, groupId, rosterVersion, vocab, catalog, modelHashes}` | Key must match the pinned key for `nodeId` |
| `SESSION_INIT` / `SESSION_RESP` / `SESSION_FIN` | peer ↔ peer | Noise XX handshake payloads | Plaintext framing only |
| `ROSTER` | admin → everyone (gossiped) | `{groupId, version, admin, members[], revoked[], adminSig}` | `adminSig` over the canonical body; higher valid version wins |
| `REVOKE` | admin → everyone | `{groupId, version, nodeId, adminSig}` | As `ROSTER` |
| `SIGNAL` | peer ↔ peer via relay | \`{sessionId, kind: offer | answer |

### 16.2 Capability and placement

| Type | Direction | Body | Sender rule |
| --- | --- | --- | --- |
| `PROFILE` | everyone, every 15 s LAN / 30 s WAN | section 4.6 | `profile.nodeId == from` |
| `MODEL_REQ` | orchestrator → node | `{planId, capability, modelId, sha256, deadlineMs, commander, ts, nonce}` | `commander == from`; replay-guarded |
| `MODEL_READY` | node → orchestrator | `{planId, node, modelId, loadMs, warm}` | `node == from` |
| `MODEL_FAIL` | node → orchestrator | `{planId, node, modelId, class, detail}` | `node == from` |

### 16.3 Plans and consent

| Type | Direction | Body | Sender rule |
| --- | --- | --- | --- |
| `PLAN` | orchestrator → participants | `{planId, planHash, commander, question, graph, assignment, chunkIds?, mode, ts, nonce}` | `commander == from`; replay-guarded |
| `PLAN_CONSENT_REQ` | owner → orchestrator | `{planId, planHash, node, intentHashes}` | `node == from`; extends that owner's deadline to 65 s |
| `PLAN_CONSENT_RES` | owner → orchestrator | `{planId, planHash, node, approved, signedBy, approvalSig?, refusal?}` | `approvalSig` by the biometric key over \`planId |

### 16.4 Work

| Type | Direction | Body | Sender rule |
| --- | --- | --- | --- |
| `TASK` | orchestrator → executor | `{planId, task: {id, op, cap, params, blobs?, owner?}, inputs, deadlineMs, modelHash, commander, ts, nonce}` | `commander == from`; accepted only against a held `PLAN` (P10) |
| `TASK_RESULT` | executor → orchestrator | `{planId, taskId, node, output?, outputHash?, error?: {class, detail}, ms, backend}` | `node == from`; hash recomputed |
| `TASK_YIELD` | executor → orchestrator | `{planId, taskId, node, reason}` | `node == from` |
| `TASK_CANCEL` | orchestrator → executor | `{planId, taskId}` | `from` is the plan's commander |
| `BLOB` | owner → node | `{blobId, seq, total, bytesB64, owner, jobId}` | `owner == from` |
| `BLOB_DELETED` | node → owner | `{jobId, node, blobIds}` | `node == from` |
| `RELEASE` | owner → orchestrator | \`{jobId, blobIds, scope: job | month}\` |

### 16.5 State and recovery

| Type | Direction | Body | Sender rule |
| --- | --- | --- | --- |
| `RECLAIM` | orchestrator → node | `{planId, chunkIds[], commander, ts, nonce}` | `commander == from` |
| `RECLAIM_RES` | node → orchestrator | `{planId, node, found: [{chunkId, outputHash}]}` | `node == from` |
| `JOURNAL` | orchestrator → deputy | `{jobId, fromRow, rows[]}` | `from` is the job's commander |
| `RECEIPT` | both ways | `{jobId, entryHash, kind, sig}` | Ledger receipt exchange |

### 16.6 Relay-level frames (outside the envelope)

`challenge`, `auth`, `auth_ok`/`auth_fail`, `roster`, `msg {to, from, cipher, ttlS?}`, `presence`, `wake`, `drop`, `ping`/`pong` every 25 s. Rate limits per node: 50 msg/s and 2 MB/s through the relay path.

### 16.7 Deliberate absences

There is no message that carries a cloud prompt, a cloud result or the API key to a node. Cloud output reaches other phones only inside the final merged answer, which nodes never receive.

### 16.8 In-process delivery

Messages a phone sends to itself (the orchestrator is often also an owner or a node) never touch a transport: they are dispatched in-process, synchronously and in order, through the same handlers, so the single-phone case runs the identical code path.

## 17. User interface

Six screens cover the core engine; everything else layers on later. Jetpack Compose, one activity, a bottom bar.

| Screen | Shows | Actions |
| --- | --- | --- |
| Ask | Request input; the plan panel (one line per step: op, phone, estimate); the LOCAL / CLOUD choice with estimates, cost and "what leaves"; clarification prompts when confidence < 0.5; the answer with provenance and badges (verified, unverified, cloud) | Submit; pick mode; answer clarification; "not accurate" on a result (triggers a cloud re-run of that step through the gate) |
| Jobs | Live chunk grid (queued / running / done / duplicated / failed; which phone; ms; a label like `items 41–60` or `audio 02:30–03:00`); per-phone load (chunks, busy ms, items/s, link kind); bytes moved; elapsed vs single-phone estimate; speed-up when done; result summary and hash; a cloud badge on escalated steps; resumable jobs after a restart | Cancel; resume; open result; re-run (memo makes unchanged chunks free) |
| Swarm | Members as tiles: name, online via LAN / Nearby / Bluetooth / direct / relay, battery, thermal, loaded model, queue depth, cooldowns; the admin's roster version; pairing entry points | Pair by QR, show QR, send invite link; confirm SAS; opt in or out of worker mode (requests the battery exemption); admin: approve joins, revoke, hand over |
| Models | Capability catalog with installed, available and loaded state per phone; hashes; benchmark p50 per model; last BENCH time | Download, load, unload, run BENCH; pin the default build per capability |
| Data | The phone's tables, documents and media visible to the swarm catalog; per-item private / released state; confidential column flags | Mark private; release for a job or for a month; set confidential columns; hide a table |
| Settings | Cloud policy (ask every time / local unless impossible / fastest); OpenRouter key; per-job and monthly caps; schema sharing for the cloud planner; mobile data for blobs; relay address; heartbeat and deadline tuning for advanced users | Edit; export ledger; forget a peer |

### Flows that must feel immediate

- **Pair in the same room**: Swarm → Show QR → other phone scans → both see the same 6 digits → tap Confirm on both → tile appears. Under 30 s.
- **Pair across cities**: Swarm → Invite link → send it → recipient opens it → both see the 6 digits → read them aloud → Confirm. Under 2 min including the call.
- **Run a job**: Ask → plan panel appears in under 2 s for T0/T1 → pick LOCAL or CLOUD → Jobs shows the grid filling → result with speed-up.
- **Approve a plan on an owner phone**: notification → one fingerprint for the whole plan → done.
- **A phone goes away mid-job**: its tiles fade; its chunks go back to queued; no user action.
- **Escalation**: consent sheet names the model, the bytes and that nothing private is included → Allow once / Allow this month / Keep local.

### Refusals in plain words

Every refusal and every partial result shows one sentence a non-developer understands ("HR's phone left before its private rows were processed", "two phones disagreed on the extraction and the data is private, so no cloud re-run"), plus a "details" expander with the plan hash and the step.

## 18. Code architecture

A Kotlin multi-module Android project whose dependency graph makes "no cloud on nodes" a build failure, with every engine component testable on the JVM against fake transports before it touches a phone.

### 18.1 Modules

| Module | Contents | May depend on |
| --- | --- | --- |
| `:core` | Types: `Profile`, `OpClass`, `TaskSpec`, `SplitJob`, `JobProgress`, `Canon` (canonical JSON + hashing), `Transport` interface | nothing |
| `:mesh` | Envelope, router, peer store, replay guard; LAN, Nearby, Bluetooth, relay and WebRTC transports; Noise `SessionManager` | `:core` |
| `:trust` | Keystore signer, pairing (QR, invite link, SAS), roster store, revocation, plan consent | `:core`, `:mesh` |
| `:runtime` | GenieX wrapper, model pool, catalog loader, BENCH | `:core` |
| `:engine` | Profile beacon, worker policy, task runner, splitters, mergers, work queue, chunk planner, placement solver, graph commander, split commander, journal, chunk memo, blob store | `:core`, `:mesh`, `:runtime` |
| `:planner` | Cue lexicon, pattern library, closed-choice chooser, compiler, `CloudPlanner` interface (no implementation) | `:core`, `:engine` |
| `:node` | Op executors (aggregate, text, vision, media, generation, `LLM_MAP`), owner plan handler | `:core`, `:runtime`, `:engine` |
| `:cloud` | OpenRouter client, escalation gate, cloud planner implementation, spend caps, `models.json` loader | `:core`, `:engine`, `:planner` |
| `:ledger` | Hash chain, receipts | `:core` |
| `:ui` | Compose screens and view models | `:core`, `:engine`, interfaces exposed by `:app` |
| `:app` | Dependency wiring, foreground services, FCM receiver, settings | all |
| `relayd/` | Go service, Dockerfile, deploy scripts | — |

### 18.2 Build-enforced rules

- `:node` → `:cloud` is forbidden; a Konsist or dependency-analysis check fails the build if any class in `:node` references the cloud package.
- `CloudClient` has an `internal` constructor; only `:cloud`'s `EscalationGate` and `CloudPlannerImpl` build it, and `:app` creates those only inside the orchestrator's commander factories.
- A unit test asserts the task runner's executor registry contains no class from `:cloud`.
- `cloud/models.json` and the API key never ship in `:node` resources; a lint rule checks the merged asset manifest.
- Every module ships JVM tests against `FakeTransport`, `FakeRelay`, `FakeDataChannel` and `FakeRuntime`; a 3-phone fake swarm runs every engine scenario on the JVM.

### 18.3 Key interfaces

```kotlin
// :core
enum class LinkKind { LAN, P2P, BT, WAN_DIRECT, WAN_RELAY }
data class Link(val kind: LinkKind, val kbps: Int, val rttMs: Int, val metered: Boolean)
interface Transport {
    val kind: LinkKind
    fun send(to: NodeId, bytes: ByteArray)
    fun onReceive(handler: (NodeId, ByteArray) -> Unit)
    fun measureLink(to: NodeId): Link
}

// :mesh
interface SessionManager { suspend fun open(peer: NodeId, t: Transport): Session; fun session(peer: NodeId): Session? }
interface Session { fun seal(plain: ByteArray): ByteArray; fun open(cipher: ByteArray): ByteArray; val link: Link }
class RelayTransport(url: String, signer: Signer, roster: RosterStore) : Transport { fun onPresence(h: (NodeId, Boolean) -> Unit); fun wake(peer: NodeId) }
class WebRtcBlobTransport(signal: (NodeId, SignalBody) -> Unit, turn: TurnConfig) : Transport

// :trust
interface Pairing { fun startInvite(groupId: String): Invite; suspend fun join(i: Invite): SasPrompt; suspend fun confirm(p: SasPrompt, matches: Boolean) }
interface RosterStore { fun current(groupId: String): Roster; fun apply(r: Roster): Boolean; fun isMember(id: NodeId): Boolean }

// :runtime
interface ModelPool { suspend fun ensure(e: ModelEntry): Result<LoadInfo>; fun warm(): Set<String>; fun big(): String? }

// :engine
object Placement { fun solve(plan: CompiledPlan, profiles: List<Profile>, catalog: List<ModelEntry>, self: NodeId): List<Assignment> }
interface TaskRunner { fun submit(task: TaskSpec, prio: Prio): Deferred<TaskOutput>; fun yieldAll(); fun resumeWorkers(); val queueDepth: Int }
class WorkQueue(plan: SplitPlan, eligible: Set<NodeId>, warm: Map<NodeId, Set<String>>, links: Map<NodeId, Link>)
interface JobJournal { fun begin(j: JobRow); fun chunk(c: ChunkRow); fun attempt(a: AttemptRow); fun output(o: OutputRow); fun placement(p: PlacementRow); fun escalation(e: EscalationRow); fun resumable(): List<JobRow> }
interface ChunkMemo { fun get(chunkId: String): MemoHit?; fun put(chunkId: String, hash: String, json: JsonElement) }
interface JobRunner { fun run(req: JobRequest): Flow<JobProgress>; fun cancel(jobId: String); fun resume(jobId: String): Flow<JobProgress> }

// :planner
interface Planner { suspend fun plan(question: String, catalogs: List<Catalog>, caps: CapabilitySummary): PlanResult }
interface CloudPlanner { suspend fun propose(payload: PlannerPayload): Result<Graph> }   // implemented only in :cloud

// :cloud
enum class CloudRole { PLANNER, ESCALATE_TEXT, ESCALATE_MULTIMODAL }
interface CloudClient { suspend fun complete(role: CloudRole, messages: List<Msg>, schema: JsonSchema, maxTokens: Int): Result<CloudReply> }
class EscalationGate(cloud: CloudClient, caps: SpendCaps, consent: ConsentSheet, ledger: Ledger) {
    suspend fun tryEscalate(plan: CompiledPlan, step: TaskSpec, inputs: Map<String, JsonElement>, lineage: Privacy, trigger: EscalationTrigger): GateOutcome
}
sealed class GateOutcome { data class Ran(val output: JsonElement, val outputHash: String, val slug: String) : GateOutcome(); data class Refused(val reason: String) : GateOutcome() }
```

### 18.4 Op registry

One table drives the compiler, the splitter, the merger and the executors: `{op, class, capability?, axes: {shard?: merge, chunk?: merge, fanout?: merge}, arity, inputShape, outputShape, replicas, judge?}`. Adding an op means adding a row, a splitter rule if it needs one, and an executor; nothing else changes.

## 19. Build plan from an empty repository

Ten phases; each ends with a gate on real iQOO 15 phones (or the JVM where stated), and each lands behind a feature flag so the app always runs. Durations assume one engineer per track; phases 2 and 3 can run in parallel, as can 5 and 6.

| Phase | Weeks | Build | Exit gate |
| --- | --- | --- | --- |
| 0 Skeleton | 1 | Multi-module project; `:core` types; canonical JSON and hashing; op registry; `FakeTransport`; CI running JVM tests; Compose shell with the six screens as placeholders | `canonical()` and `sha256` agree with a reference implementation on 1,000 fuzzed JSON values; build fails on a deliberate `:node` → `:cloud` import |
| 1 LAN mesh and trust | 2–3 | Keystore identity; envelope, router, replay guard; LAN (NSD) and Nearby transports; Noise sessions; QR + SAS pairing; roster and revocation; profile beacon; `HELLO`, `PROFILE`, `ROSTER` | Three phones on one Wi-Fi pair by QR, see each other's tiles, and exchange signed, sealed profiles; a packet capture shows ciphertext only; an unpaired phone is rejected |
| 2 Runtime | 2 | GenieX integration; model pool with QAIRT-then-llama.cpp fallback; capability catalog; BENCH; worker foreground service; eligibility rules | Each phone loads an LLM and a VLM bundle, runs BENCH, advertises p50 and `big`; an owner picking up the phone yields within 1 s |
| 3 Split engine on the JVM | 3 | Splitters for all nine input kinds; mergers for all seven kinds; chunk ids; chunk planner; work queue with prefetch, locality, transfer rule, stragglers; node task runner with priorities; in-process delivery | On a 3-node fake swarm: same result hash on 1, 2 and 3 nodes for CONCAT/STITCH/MONOID/TOPK; a straggler is duplicated and the first wins; a killed node loses only in-flight chunks; private chunks never leave the owner |
| 4 Split engine on phones | 2 | Wire phase 3 over phase 1 and 2; blobs over LAN; Jobs screen with the live chunk grid; placement solver and `MODEL_REQ/READY/FAIL` | 120 released photos OCR'd across three phones with a measured speed-up and identical result hash to one phone; a job needing VLM on two phones and LLM on one places correctly |
| 5 Graph engine | 3 | Cue lexicon; pattern library; closed-choice local planner; compiler; owner plan handler with floors, differencing guard and one-fingerprint consent; reduce ops; deterministic renderer; Ask screen with plan panel | "Revenue per employee by region" across two owner phones answers with rows moved = 0, one fingerprint on the confidential owner, and a plan hash in both ledgers |
| 6 Remote mesh | 3–4 | `relayd` + Dockerfile; relay transport; presence; FCM wake; WebRTC data channels + coturn; link class; RTT-aware deadlines; metered policy; invite-link pairing; `FakeRelay`/`FakeDataChannel` on the JVM | A phone on home Wi-Fi in one city and one on mobile data in another complete the 120-photo job; the relay is killed mid-job and the job finishes; LLM chunks go to the remote phone, photos stay home |
| 7 State and recovery | 2 | SQLite journal; persistent memo; `RECLAIM`; resume UI; ledger entries for split jobs; receipt exchange | Force-stop the orchestrator app mid-job → resume completes with zero re-runs of finished chunks; re-running after adding ten items runs only ten chunks |
| 8 Cloud | 2–3 | `:cloud` module; OpenRouter client with ZDR, strict schema, fallback chains; LOCAL/CLOUD options in the plan panel with estimates; escalation gate with all triggers; cloud planner T2 with repair round; consent sheet; spend caps; `LLM_MAP` | A forced 3-way disagreement on a public step escalates and is badged; private lineage is refused; the cap stops spend; the key is provably absent from `:node`; an eval recipe sweeps the three roles' slugs and records results |
| 9 Hardening and UI polish | 3 | Adaptive re-cut; cooldowns; deputy; relay store-and-forward; clock offset; Swarm, Models, Data and Settings screens complete; plain-language refusals; a 1-hour soak harness | Soak with random phone kills, sleeps, relay restarts and yields: every job ends done or partial-with-reason, none hung; result hashes unchanged for exact merge kinds; a non-developer pairs, runs a job and handles an escalation without help |

### Definition of done, every phase

- JVM tests cover the phase's behaviour against the fake swarm, including WAN fakes from phase 6 on.
- The feature flag defaults on only after the phone gate passed.
- `docs/spec.md` (this document) is amended where the build taught something; `docs/status.md` describes what `main` does today.

### Tracks that can start on day one without phones

`relayd` and its protocol tests; the placement solver against recorded profiles; the escalation gate against a fake cloud client; the journal schema and `RECLAIM` on the fake transport; the op registry and compiler.

## 20. Infrastructure, constants, open questions, sources

### 20.1 Infrastructure

| Piece | What | Where | Sees |
| --- | --- | --- | --- |
| `relayd` | Go WebSocket relay: challenge auth, roster-scoped forwarding, 5-minute hold, presence, wake | One small VPS or Fly.io machine; TLS via Caddy; a Cloudflare Durable Objects port is the no-server alternative | Node ids, group ids, ciphertext, online/offline |
| coturn | STUN + TURN for data channels | Same host or a second small VPS with a static IP; credentials issued per session by `relayd`, rotated weekly | Encrypted DTLS/SCTP streams |
| Push | FCM project (or a self-hosted UnifiedPush distributor) for wake | Firebase console; token registered with `relayd` at auth | Node id, device token |
| OpenRouter | One account; per-key spend limit; ZDR guardrail on the key | openrouter.ai | T2 planner and escalation payloads only |

Cost at hobby scale: one 2 vCPU VPS for `relayd` and coturn, under ₹1,500 per month; TURN bandwidth is the only variable and the transfer rule keeps media off it unless the direct channel fails. Two `relayd` instances behind one hostname give redundancy; no backups are needed, the admin phone re-uploads the roster on reconnect.

### 20.2 Constants

| Constant | Value |
| --- | --- |
| Profile heartbeat / stale: LAN, WAN | 15 s / 45 s, 30 s / 90 s |
| Idle after screen off | 60 s |
| Worker battery floor / thermal cap / temperature cap | 40 % / ≤ 1 / < 38 °C |
| HEAVY stop | thermal ≥ 2 or ≥ 40 °C; duty cycle 2 min on / 30 s pause |
| Model load deadline | `max(10 s, 3 × loadMsP50)` |
| Split deadline | `max(4 s, 3 × expected + 2 × rtt)` |
| Graph step deadline | `max(4 s, 2 × estimate + 2 × rtt)`; 65 s while consent is pending |
| Straggler threshold | 1.5 × expected, only once the queue is empty |
| Slots per phone | 2 (1 running + 1 prefetched) |
| Max attempts per chunk | 6, with one re-cut after 2 failures on different phones |
| Target chunk work | 30 s; at least 2 × eligible phones chunks |
| TREE chunk / picks | 40 sentences / k = 5 |
| Audio window / overlap | ≤ 28 s owned + 1 s each side |
| Image tile / overlap | 128 px / 16 px |
| Capability cooldown | 10 min |
| Relay hold / reconnect backoff / outage wait | 5 min / 1 s → 30 s / 60 s |
| WAN wake grace / data channel stall | 20 s / 15 s |
| Link measurement | 2 KB echo at session start, every 5 min |
| Noise rekey | 24 h or 1 GB |
| Pairing token / SAS | 10 min / 6 digits |
| Cloud timeout / retries / total | 30 s / 1 on 5xx then next model / 90 s |
| Verification thresholds | judge < 0.7; VOTE < 0.6; schema fail 3× on ≥ 2 phones |
| Chunk memo | 24 h, 2 GB LRU |
| Envelope / blob piece / blob max | 64 KB / 16 KB / 64 MB |
| Replay window | ±10 min with relay time offset, 1000 nonces |
| Plan budget | ≤ 12 steps, ≤ 6 LLM calls per phone |
| Group floor | 5 |
| Default per-unit costs | 0.05 ms/row, 100 ms/s audio, 50 ms/tile, 4000 ms LLM, 1500 ms VLM, 3000 ms HEAVY, 200 ms otherwise; 2000 ms warm-up; 3000 ms per queued task |

### 20.3 Open questions

- [ ] Project name (this document uses "Phone Swarm" as a placeholder).
- [ ] Schema sharing for the cloud planner: on with per-table opt-out, or off until enabled?
- [ ] Default cloud policy: "ask every time" (proposed) or "local unless impossible"?
- [ ] Monthly cloud cap default: ₹500 proposed.
- [ ] Keep the off-by-default "time budget" trigger, or drop it?
- [ ] Deputy in v1, or defer until a real crash-mid-job report justifies it?
- [ ] TURN host region: India only, or one per continent where members live?
- [ ] Which 3B–8B AI Hub bundle is the default `llm.json` model after the BENCH sweep?

### 20.4 Risks

| Risk | Mitigation |
| --- | --- |
| Remote phones rarely help with media because blobs are expensive to move | By design; the UI shows per-link contribution so users see remote phones taking LLM text chunks |
| FCM wake unreliable under OriginOS battery management | The worker foreground service is the real mechanism; wake is a bonus |
| GenieX is in developer preview | Pin the SDK version; llama.cpp GGUF is the always-works path; the catalog carries the runtime per build |
| Cloud slugs and prices drift | `models.json` + the ledger's slug record; re-run the eval recipe on change |
| Relay as a single point of failure for WAN | Two instances; LAN unaffected; jobs pause 60 s rather than fail |
| Escalation rate higher than expected | Caps; tunable trigger thresholds; the rate is published after the soak |

### 20.5 Sources

- [iQOO 15 specifications](https://www.91mobiles.com/iqoo-15-512gb-16gb-ram-price-in-india?ty=specs)
- [GenieX: what it is](https://geniex.aihub.qualcomm.com/en/get-started/what-is-geniex) and [GenieX on GitHub](https://github.com/qualcomm/geniex)
- [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) and [structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs)
- [Stream WebRTC Android](https://github.com/getstream/webrtc-android)
- Pricing aggregators, as of 2026-10-10: [CloudZero](https://www.cloudzero.com/blog/llm-api-pricing-comparison/), [Spheron](https://www.spheron.network/blog/llm-api-pricing-comparison-gpt-claude-gemini-deepseek-2026/), [BenchLM](https://benchlm.ai/google/api-pricing)
- Reference design: the Sovereign Nodes engine description (second tab of this doc)

---

## Amendments

What the build taught, phase by phase. Each entry names the section it amends.

### Phase 0 (2026-10-10)

- **Name (20.3).** The project is HiveCore; application id and root package `com.hivecore`; modules are `com.hivecore.<module>`.
- **Transport (18.3).** `:core`'s `Transport` is the endpoint-based interface the adopted mesh code already implements (`start/stop`, `connected`, `inbound`, `send(endpoint, bytes)`, pairing mode) extended with `kind: LinkKind` and `measureLink(endpoint): Link`. The spec's `send(to: NodeId, …)` shape is provided by `MeshRouterPort` on top of it. Both keep the existing tests.
- **Owner plan handler (18.1).** `OwnerPlanHandler` and `AggExecutor` live in `:engine`, not `:node`: they depend only on `:core` and `:mesh`, and keeping them beside `SwarmNode` lets the 3-phone fake swarm stay in one place. `:node` holds the op executors and the owner's data store.
- **`PlanConsent` (18.1).** Lives in `:mesh` because `MeshRouter` verifies `PLAN_CONSENT_RES` signatures; `:trust` holds the Keystore signer, approval keys and approval store.
- **`:ledger` (18.1).** Depends on `:core` and `:mesh` (receipt exchange rides on the router), not `:core` alone.
- **Shared text helpers.** Sentence splitting, the extractive (BM25) selector, the transcript stitcher, the pixel tiler and the relevance thresholds sit in `:engine` (`engine.text`, `engine.pixels`) because the splitters and mergers need them; `:node` imports them.
- **Op registry (18.4).** `core.OpRegistry` is the table; `OpClasses` and `Mergeability` are the legacy views the adopted code reads. The compiler still carries its own arity table; a later phase makes it read the registry.
- **Message bodies (16).** `TASK_RESULT.error` is still the free-text string of the reference code plus a sibling `errorClass`; the `{class, detail}` object lands with phase 3/4 when the queue consumes it.
- **Feature flags (19).** `-PhiveFeatures=` keys: `mesh` (phase 1), `runtime` (2), `split` (3–4), `graph` (5), `remote` (6), `state` (7), `cloud` (8), `hardening` (9).
- **Build rule (18.2).** `checkCloudBoundary` in the root build covers every module below `:app` (not only `:node`): dependency on `:cloud`, any mention of `com.hivecore.cloud`, and a `models.json` asset outside `:cloud` all fail the build.
- **Toolchain.** One Gradle build at a time on this machine; `tools/gradle.sh` holds the lock. Tests that read assets look across every module's `src/main/assets` because the APK merges them into one tree.

### Phases 1-8 tracks and the integration wave (2026-10-10)

- **Roster store (18.1, 18.3).** `RosterStore` and `RosterStoreImpl` live in `:mesh` (`MeshRouter` applies `ROSTER` / `REVOKE` itself); `:trust` keeps a `typealias RosterStore` so the spec's name still resolves there. `PlanAuthoriser` (task authorisation, 6.5 / P10) lives in `:trust` and is wired to `SwarmNode.onPlanSeen` / `taskAuthoriser` by `:app`.
- **SAS (6.2).** The six digits are `SHA256("SN-SAS\0" || h4) mod 10^6`, where `h4` is the session handshake hash; both phones derive them from the same handshake, so they match only when no one sat in between.
- **LAN endpoint id and frame cap (5.1).** A LAN peer's endpoint id is `<nodeId prefix>@<ip>` (the mDNS service name is `<name>|<prefix>`), so both directions of one pair collapse into one endpoint; frames are 4-byte length + bytes, at most 64 KB plus the session record overhead (`Session.OVERHEAD`).
- **Capability pool (18.3).** `core.ModelPool` is frozen; the spec's `ensure / warm() / big()` live on `runtime.CapabilityPool`, which `ModelPoolImpl` implements (`ensure` tries the `qairt` build, then the `llama_cpp` build once, then fails with a class).
- **Catalog runtimes (4.3).** Besides `qairt`, `llama_cpp`, `litert_qnn` and `ort_qnn`, entries may carry `mlkit` or `kotlin`: built-in ops (ML Kit OCR, pure-Kotlin ops) have a catalog row so plans can name them, but nothing to load.
- **Placement deadline (8.3).** A `MODEL_REQ` whose deadline passes with no reply counts as `MODEL_FAIL {class: runtime}` *and* starts the 10-minute `(node, capability)` cooldown, like any other failed load.
- **Solver (8.2).** A capability goes to a further phone only when that phone would finish at least one unit of it (units shared in proportion to speed); otherwise the phone is left free for other capabilities.
- **Journal rows (13.1).** `EscalationRow` carries `stepId` (the graph step or the split chunk id) and `costInr`; the journal tables keep the spec's bare names (`jobs`, `chunks`, `attempts`, `outputs`, `placements`, `escalations`) in their own SQLite file `files/journal.db`, beside the data and peer database. `:ledger` depends on `:engine` because the receipt replay re-runs the merge over the journaled outputs.
- **Re-cut and misses (14).** The adaptive re-cut applies only to exact merge kinds (CONCAT, STITCH, MONOID, TOPK); TREE, VOTE and RANK chunks go straight to the six attempts. A phone leaves a job after three missed deadlines; a phone that yielded is offered nothing for 15 s (one missed heartbeat) before it can take work again.
- **Cloud planner schema (7.3, 15.6).** The strict `response_format` schema carries a node's `schema` and `params` as JSON strings and `privacy` as `[name, lineage]` pairs (the strict schema allows no free-form objects); `GraphJson` accepts both that form and the plain objects of section 7.3.
- **Private replicas (7.4).** A private-lineage LLM/VLM step must declare `replicas: 1` (it runs on its owner only); the compiler rejects anything else, the scheduler pins it there, and it is never replicated or escalated.
- **INR_PER_USD (15.2).** Estimates convert list prices at the constant `ModelsConfig.INR_PER_USD = 88` (reference rate of the build date) so they are reproducible; the ledger records the cost OpenRouter reports.
- **Planner naming (18.3).** `TieredPlanner.plan(question, catalogs, caps)` is the spec's `Planner.plan` (`core.Planner` was already the T1 interface); `TieredPlannerImpl` runs T0 → T1 → T2 with the 7.2 rules and the plan cache.
- **UI (17).** The QR encoder is pure Kotlin (`ui/qr`); view models are plain classes with an injected scope (no AndroidX ViewModel), so every screen is driven by JVM tests over the port fakes.
- **Relay protocol (5.3, 6.2).** `relayd` adds `invite` / `invite_ok` / `invite_fail` frames: the inviter opens a token (its SHA-256) for ten minutes and a guest authenticates with `invite` + `tokenHash` instead of roster membership, admitted only for the inviter's node. Auth signs `relayd-auth-v1 || 0x00 || nonce` with the node key. A handover roster is verified under the *outgoing* admin's key. The relay keeps rosters and push tokens in memory only (the admin phone re-uploads on reconnect); nothing is persisted.
- **Transports in the router (5.1).** `MeshRouter` runs the selected transport plus a set of always-on ones (the relay and the WebRTC channel); it keeps one endpoint per transport for every node and sends over the best-ranked connected one, so LAN neighbours are never routed through the relay while remote members are.
- **Escalation in the graph engine (15.1).** The `Commander` applies the thresholds of `EscalationThresholds` (judge 0.7, VOTE 0.6, schema 3 × on ≥ 2 phones, 3 disagreeing replicas); a cloud-answered step carries executor `cloud:<slug>` in the plan summary, the provenance and the ledger `task` entry and is never memoized. A refused low-confidence vote keeps its local answer, marked not verified; every other refusal ends the plan with both reasons.
- **Split PLAN (6.5, 16.3).** The split commander sends a `PLAN` whose dag holds the job's one template task and whose `chunkIds` list every task id the run may use (chunk × attempt) before a phone's first chunk, and again after a re-cut adds chunks; chunk TASKs carry `params.chunkId`, which is the memo key on every phone (13.2) and what `RECLAIM` answers from (13.3).
- **Resume (13.3).** A resumed job re-runs its journaled `SplitJob` with every held output prefilled under the next `jobId#run`, so the resumed attempts are new task ids on every phone and only the missing chunks run.
- **CLOUD option (9.1).** The plan panel's CLOUD option is estimated and offered, but both options run the plan locally first: the gate re-runs a step in the cloud on a trigger. Pre-dispatching eligible steps to the cloud is open work for the phase 8 phone gate.

### Closing the integration wave: consent, CLOUD option, deputy, downloads, document AI (2026-10-10)

- **CLOUD option (9.1, 15.1).** Picking CLOUD in the plan panel now sends every cloud-eligible LLM / VLM step to the cloud from the orchestrator *before* any phone is asked: the `Commander` takes the chosen step ids and routes each through the gate under a new trigger `EscalationTrigger.UserChoice` (`user_choice`), which is not a 15.1 failure trigger but the user's decision. The gate's pre-flight still runs in full (orchestrator only, no OWNER step, public or released lineage, schema, caps); the choice counts as the consent for that plan, so the sheet is not shown again for it. A refusal or a cloud failure leaves a note and the step runs on the phones, so a cloud outage never loses an answer. Private-lineage steps never reach the gate, whatever was picked.
- **Plan consent on the owner (7.6, 17).** `ApprovalNotifier` posts the high-importance notification whose full-screen intent opens `ApprovalActivity` over the lock screen; the activity shows the plan's header, numbered figures and countdown, and one `BiometricPrompt` with a `CryptoObject` signs the plan bytes with the approval key (`signedBy: approval`). The in-app banner on every screen is the fallback when the notification does not surface. The approval key is created at start-up once a fingerprint is enrolled (a warning names the gap otherwise), so HELLO carries `approvalPubkey`; a key invalidated by a fingerprint re-enrolment is re-created and HELLO re-broadcast.
- **Deputy (13.4).** Behind the `state` flag the orchestrator's journal is the `StreamingJournal` decorator: every commit streams to the deputy `DeputySelector` picks (LAN first, then the phone this orchestrator has seen the longest; uptime is measured as time since first seen, since profiles carry none). The split commander records the deputy in the job row (`capsSnapshot.deputy`) so a resume pulls its newer rows first (`JobResume`). A phone that goes absent on the relay is forgotten and re-ranked when it returns.
- **Model downloads (4.3, 17).** `catalog.json` entries may carry `url` and, when the download is an archive, `downloadSha256`; the shipped catalog has none (the bundles are copied into `Download/HiveCoreModels` as before), and a newer `files/models/catalog.json` with urls turns the Models screen's Download on. `ModelDownloader` resumes with `Range`, streams the digest, refuses an unpinned digest, extracts zips into `<root>/<modelId>/` (zip-slip checked) and writes a minimal manifest for single-file builds; built-in runtimes (`mlkit`, `kotlin`) have nothing to download.
- **VLM_ASK and GEN_IMAGE seams (4.3).** `VLM_ASK` runs the catalog's preferred `vlm` build through the pool (`GenieXVlmRuntime`) as a closed choice voted three times with rotated option orders; `GEN_IMAGE` / `GEN_VARIANTS` run the SD 1.5 bundle on the ORT QNN runtime (`SdPipeline`). Both register only when the runtime phase is on and the runtime exists, as every other seam.
- **Document executors (18.1, 4.3).** OCR, LAYOUT, DOC_CLASSIFY, METADATA, REDACT and FIELDS live in `:node` `docai` as one `DocAiExecutor` over the `Ocr` seam (`MlKitOcr`, the catalog's `mlkit_text_latin`), the LLM and the closed chooser; the eight class schemas ship as node resources. OCR answers `{"lines":[{text, left, top, right, bottom}…]}`; FIELDS answers the invoice fields with `by` (regex or llm per field), `missing` and `review`. The executor registers wherever the runtime phase is on.
- **Soak harness (19, phase 9).** `SoakTest` is the JVM soak: four seeded sequences of split jobs over the 3-phone fake swarm (two on the relay rig with a fourth, relay-only phone) while a chaos driver kills and relaunches workers, cuts and restores their transport, makes them yield and restarts or downs the relay; the gate is every job done or partial-with-reason, no hang inside a 15-minute virtual budget, exact merge kinds hash-equal to the undisturbed one-phone run, the journal consistent with the reported progress, and every killed phone back in the orchestrator's view. Two things the harness taught: a test that leaves the relay pinger and beacons running when `runTest` ends never goes idle (the harness closes inside the test body); and a phone that reappears on the fake bus before its relaunched process listens loses the orchestrator's handshake, so the harness brings a phone back on the network only once it listens, which is what a real LAN link does (the endpoint exists only after the app accepts the socket). Nearby and Bluetooth, which can surface a peer before its app runs, should be checked for the same ordering on the phone gate.

### Decks: a plan whose output is a slide deck (2026-10-11)

- **The deck step (4.4, 7.3).** `RENDER` with `params.kind = "deck"` is the deck step: a reduce, so it runs on the orchestrator after every input it names is done. `engine.deck.DeckAssembler` turns the inputs into a `Deck` (title, subtitle, slides of kind TITLE / CHART / BULLETS / IMAGE, a one-sentence `summary`) and, when the node was built with a `deckDir`, writes `deck-<hash12>.pptx` there with `PptxWriter`. The step's output is the deck as JSON with `"kind":"deck"`; `AskOutcome.Answer.artifact` carries it beside the sentence, which is the summary plus the slide count (R2: the figures in every sentence and on every slide are written by this code from the owner's table, never by a model).
- **Inputs.** The owner's table (`AGG`, or a table reduce over it) becomes the chart: rows summed per calendar year, or per fiscal year (April to March, named by the year it ends in) when the step's `period` phrase says "FY"; a table without a month column is charted by its first text column. The slide-text step is `LLM_MAP` (any LLM backend, the reply validated against the schema in `DeckAssembler.TEXT_SCHEMA`: title, subtitle, headline, 2-5 bullets, 1-4 closing points) or `GEN_TEXT`; the pattern marks it `replicate: none`, so a deck answer is badged unverified while its figures stay checked. The picture step is `GEN_IMAGE`. When the words or the picture are missing, deterministic sentences from the figures and a placeholder take their place, so a deck always comes out of the numbers alone.
- **The .pptx (18.1).** `PptxWriter` builds the package with the JDK's zip and string building only: one 16:9 blank layout, one master, one theme (slate, amber, light text), slides from text boxes, rectangles and pictures; a chart is rectangles and labels, so it opens and stays editable in PowerPoint, Keynote, Google Slides and LibreOffice without a chart part. Entries are stored with a fixed timestamp, so the same deck gives the same bytes and the file name is its content hash. `PptxExtractor` reads the result back (the round-trip test).
- **Pictures travel as blobs (13.5).** An executor that writes a file puts it in the node's blob store (`GenExecutor.publish`); `SwarmNode.reply` sends every blob named by an output's `items[].sha256` to the commander before the `TASK_RESULT`, and the deck step finds it by hash in the orchestrator's store. Nothing else about blobs changed: content-addressed, hash-checked chunks, the same frame sizes.
- **Planner (7.2, 5.1).** Lexicon `cues-2` adds the TREND words of a growth story (increase, rise, fell, ...) and the RENDER words that ask for a deck (deck, slides, presentation). `Periods` reads year spans: "FY2024-26", "FY24-26", "2024 to 2026" and a lone "FY2025" (a hyphen is a word break, so the grounded phrase is "FY2024 26"). Pattern `deck_growth` (patterns v2) binds a measure, an optional period and grouping into AGG, LLM_MAP, GEN_IMAGE and RENDER deck; its text and picture nodes carry `optional_op: true`, which `Patterns.bind` drops when no phone of the swarm registers the op (`PlannerImpl.availableOps`), trimming the consumers' inputs, so the plan compiles on a swarm without a diffusion model. The seed's `sales.revenue` column gained the alias "sales" so "the increase in sales" binds the measure.
- **UI (17).** `AskAnswer.deck` draws the deck in the chat: a card with slide thumbnails and Present / Open .pptx / Share, and a presenter sheet that pages through the slides; the same `Deck` the file was written from, drawn with the kit on the inverse surface. Open and Share go through the app's `FileProvider` (`files/decks`, `cache/demo`) as content URIs. The first suggestion card asks for the deck; Demo mode scripts it (AGG on Sales, slide text on Accounts, the picture on HR, the deck here) and writes a real .pptx with an illustration drawn on the device.

### Ping nearby and compute points (2026-10-11)

- **Ping nearby (6.2, 17).** A third way to pair, for phones on one Wi-Fi: the inviter mints an ordinary invite (group, public key, single-use 10-minute token) and `mesh.LanPairPing` broadcasts it as `HCP1 REQ <id> <name> <invite>` on UDP 47475 to the limited and directed broadcasts and, as the LAN beacon does, to every host of the interface's prefix, three times 400 ms apart (receivers dedupe on the id). Every phone that hears it shows "`<name>` wants to pair" with Approve / Deny (Swarm screen, and a notification with the same actions); Approve is `Pairing.join` on the carried invite, so the six-digit check still guards the keys and nothing is pinned before both confirm; either answer goes back unicast as `HCP1 REP <id> <name> YES|NO` and the inviter's card lists who approved or denied. A request nobody answers leaves the screen after two minutes; a ping from a phone whose key is already pinned is dropped. The ping carries no secret beyond the invite token, exactly like the QR.
- **Compute points (17, UI only).** The Swarm page and the Home greeting show a points balance: a point per second this phone computed for another phone, paid once per settled job (the worker timeline's `RESULT_SENT.ms` per chunk; settled eight seconds after the last ending event or on the countersigned receipt), spent five per request from Home, fifty to start, persisted in prefs, with a low-importance notification per award. `ui.vm.ComputeCredits` is the whole rule; no engine, ledger or mesh message knows about points yet, and the real accounting (receipts, per-capability rates, transfer between phones) is open work that replaces that object.

### Kavira data: the demo company's tables split across the phones (2026-10-11)

- **The seed (7, 17).** The seed is Kavira Pharmaceuticals Pvt Ltd, a fictional pharma company with 280 employees, three branches and FY2023 to FY2026 sales. `tools/kavira/make_kavira_data.py` generates it deterministically and `tools/kavira/export_seed.py` turns it into `assets/seed/kavira/*.csv`, two markdown documents and `seed_manifest.json` v2. Money is in rupees: the generator's lakh figures are multiplied by 100,000. Each role is one phone. **Sales** holds `sales_monthly`, `products`, `branch_master`, `sales_force`, `events` and `stockists`, so every Kavira job starts from the stage phone. **HR** holds `staff` (no pay), `compensation` and `salary_history` (their pay columns are confidential), and `devices`. **Accounts** holds `financials`, `receivables` and `budget_vs_actual`. `sales_monthly.net_sales` is the only column that answers to "sales" and "revenue". The old tour tables stay in the manifest for the planner tests, but no role loads them.
- **One share per phone.** `DataStore.seedNow(role)` first removes the seed tables and seeded documents that are not this role's. Data the owner imported, and documents shared in live, are never removed. "Seed all three roles" passes `exclusive = false`. At start, `reseedIfManifestChanged` re-seeds an already-seeded phone once when the bundled manifest changes, so an app update moves each phone to its new share. Demo mode's scripted phones read the same manifest (`SeedManifestSchema`), so their Data screen matches the real seed.
- **Owner prompts.** The few-shot examples of all three roles use the Kavira tables. Template `K1` caches the deck's owner sub-question ("sales by month FY2024 26": net sales per month, April 2023 to March 2026), so the headline deck never depends on the model's intent.
- **Table recipes (10, 17).** A recipe may take `input: "table"` with item kind `row` and a `source`: a table on the phone that starts the job, key columns, optional `by` columns, summed or averaged measures, a `where` and a `limit` of 64 or fewer (LLM_MAP's cap). `node.data.RecipeItems` groups the rows into one item per key, `{item, text}`, and refuses confidential columns, private tables and tables that are not on this phone. The items then shard across the phones through `RecipeJobs.itemJobs`. The new recipes are `stockist_letters` (48 letters), `brand_reviews` (19 reviews, from Sales), `branch_review_packs` (3 packs), `launch_messages` (Kannada, Tamil and Malayalam), all started from the Sales phone, and `launch_kit`. `launch_kit` is a prompt recipe: four GEN_IMAGE seeds, then an LLM_MAP for the detail-aid words, run one stage after the other. Each runs its text unreplicated (`replicate: none`).

### Questions about the swarm, and pictures from a description (2026-10-11)

- **The guide (7.2, 17).** `engine.guide.SwarmGuide` answers a question about the app itself ("what can you do", "what can you create an image of", "which tables do you hold", "can you make a deck") from `SwarmFacts` (the catalogs, whether any phone registers GEN_IMAGE, the pattern ids, the recipe titles, the Home suggestions) with no plan and no model. `AskPort.plan` returns `PlanOptions.Answered`, which the chat records as an answer at once, badged verified because it is read from the catalog. A request ("create an image of my team") is never intercepted; the one exception is a request for a picture *of a chart of the data*, which is pointed at the deck, because charts are drawn from the real figures (R2) and never imagined. Demo mode answers from its scripted facts.
- **Pictures (4.4, 5.1, 7.2).** Lexicon `cues-3` adds the GEN_IMAGE words (draw, image of, picture of, illustration, poster, logo, ...). GEN_IMAGE is an owner-free op (`CueLexicon.OWNER_FREE_OPS`): the router makes such a question composite with no owner, since the picture is drawn from the words. Pattern `make_image` (patterns v3) has one slot of the new type `text`: the request's own words after its asking phrase ("please create an image of my team" becomes "my team"), grounded like every other slot and bound into the GEN_IMAGE prompt and into `RENDER` with `kind: picture`. The picture step (`engine.picture.PictureAssembler`) runs on the orchestrator like the deck step: it finds the PNG by sha256 in the blob store, copies it beside the decks as `picture-<hash12>.png` so the file provider can hand it out, and writes the one sentence of the answer (what was drawn, the size and the seed, and that no photo or table of the user's went into it). The output carries `kind: picture`; `AskAnswer.picture` draws it in the chat with Open and Share. A pattern's non-optional heavy step that no phone runs right now fails the plan in plain words ("No phone of the swarm can draw right now ...") before any model is asked. `CapabilityCues.named` no longer counts a drawing request's "image" as the VLM capability, so such a request stays at T1 instead of asking the cloud planner.
- **Refusals (17).** The T0 refusal is one sentence that says whether the question was about a phone's data at all, followed by the guide's hint (what the phones hold, that a deck or an illustration can be asked for); the route reason and a failed cloud planner's note go to the details expander, never into the sentence.

### Open paths: documents, charts, the on-device graph planner and the model's own knowledge (2026-10-11)

The pattern library stays the first answer, but no longer the only one. A question now falls through four paths before anything is refused, and every path keeps R2: figures come from the owner's guarded query, never from a model.

- **One pattern, no veto (7.2).** When exactly one pattern is compatible and the question's own cue words supply every op it requires ("create an image of a penguin" → GEN_IMAGE → `make_image`; "revenue per rep" → RATIO → `per_unit`), the pattern is taken without the closed choice. The model's "none of these fits" is only asked when there is a real choice.
- **T1G, the on-device graph planner (7.2, 7.3).** `planner.LocalGraphPlanner` implements the `T2Planner` seam with the phone's own language model: a compact prompt lists the analysis ops in one sentence each (AGG, DOC, RATIO, DIFF, SHARE, TREND, RANK, THRESHOLD, RECONCILE, RENDER with `kind: chart`), one worked example, and the catalogs by name and flag; the reply is a spec 7.3 graph that the same compiler checks, with one repair round. It runs when a question carries cues (it composes) but no pattern binds, before the cloud planner. Tier `T1G` is recorded in the plan and the cache.
- **The direct plan (7.2).** `planner.DirectPlanner` is pure Kotlin: a question that names a phone's table gets one `AGG` on that phone with the question as its sub-question (the owner's intent model does the understanding under its own rules); a question that names a document, or asks about documents, gets one `DOC` on the owner whose document names fit best; several answers sit under a plain `RENDER`, which now says each in turn. A chart word ("as a bar chart", "plot", "graph", "visualise") adds `RENDER kind: chart` over the table, and the chart words are stripped from the owner's question.
- **Charts (12.4, 17).** `engine.chart.ChartAssembler` turns the first table of a `RENDER kind: chart` step into one series (`measure` or the last measure column; the other columns as the category; `style` bar, or line for a month column; at most 36 points) with the deck's Indian-scale labels, deltas and a one-sentence summary. The output carries `kind: chart`; `AskAnswer.chart` draws it in the chat as `ChartCard` (bars, or a line with the ends and the peak labelled).
- **The model's own knowledge (17).** When no path plans a question, the port returns `PlanOptions.Offer`: one sentence saying why the phones' data does not answer and asking whether the language model may answer from its own knowledge. Only on yes does `AskPort.knowledge` plan `planner.KnowledgePlan`: one unreplicated `GEN_TEXT` step (instruction = the question, schema `{answer}`) on a phone with the model, compiled and estimated like any plan, run locally, badged unverified with the note that it is the model's prose, checked against no data. A no ends the exchange as a plain refusal. A picture that no phone can draw stays a refusal and is not offered.
- **GEN_TEXT answers (12.4).** `PlanRenderer` says a `GEN_TEXT` output's value (a string, or an object's string fields in order), so a knowledge or narration step reads as prose.
