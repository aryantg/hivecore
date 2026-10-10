# On-phone gates (spec section 19)

Everything below is reserved for the user. The JVM suite (982 tests, `tools/gradle.sh testDebugUnitTest`) covers the
logic; these gates need real iQOO 15 phones, a relay host, or an OpenRouter account. Build a gate APK with
`tools/gradle.sh :app:assembleDebug -PhiveFeatures=<flags>`; a flag defaults on only after its gate passed.

| Phase | Flags | Needs | Gate |
| --- | --- | --- | --- |
| 1 LAN mesh and trust | `mesh` | 3 phones on one Wi-Fi | Pair by QR (both see the same 6 digits, confirm on both), tiles appear, a packet capture shows ciphertext only, an unpaired phone is rejected |
| 2 Runtime | `runtime` | each phone with the LLM and VLM bundles in `Download/HiveCoreModels` (see `runtime/src/main/assets/models/catalog.json`; GenieX QAIRT path) | Load an LLM and a VLM, run BENCH, p50 and `big` advertised in the profile; picking the phone up yields within 1 s |
| 4 Split on phones | `mesh,runtime,split` | 3 phones, 120 released photos | OCR job with a measured speed-up and the same result hash as one phone; a job needing VLM on two phones and LLM on one places correctly |
| 5 Graph | `+graph` | 2 owner phones with seed tables | "Revenue per employee by region" answers with rows moved 0, one fingerprint on the confidential owner, plan hash in both ledgers (the owner approves with one fingerprint on the approval screen opened by the notification; debug auto-approve stays available) |
| 6 Remote | `+remote` | a relayd host behind Caddy TLS (`relayd/DEPLOY.md`, Dockerfile), coturn with the shared secret, a Firebase project for wake, relay address in Settings, one phone on home Wi-Fi and one on mobile data in another city | The 120-photo job completes; the relay is killed mid-job and the job finishes; LLM chunks go to the remote phone, photos stay home |
| 7 State | `split` (journal lands with it) | 3 phones | Force-stop the orchestrator mid-job, resume completes with zero re-runs of finished chunks; re-run after adding ten items runs ten chunks |
| 8 Cloud | `+cloud` | an OpenRouter account with a per-key spend limit and the ZDR guardrail, the key entered in Settings | A forced 3-way disagreement on a public step escalates and is badged; private lineage is refused; the cap stops spend; `CloudBoundaryTest` proves the key path is absent from `:node`; verify the slugs in `cloud/src/main/assets/cloud/models.json` against `GET /api/v1/models` |
| 9 Hardening | `hardening` | all of the above | 1-hour soak with random kills, sleeps, relay restarts and yields (the JVM `SoakTest` runs four seeded chaos sequences over the LAN and relay rigs, every job done or partial-with-reason and exact merges hash-equal; the on-phone hour is the gate) |

## First on-phone pass (2026-10-11, three iQOO 15 on one office Wi-Fi, all flags on)

What ran: the all-flags debug APK installed on the three phones; the catalog bundles pushed to `Download/HiveCoreModels`;
the Qwen3-4B NPU bundle loaded on every phone in 11 to 17 s; the three phones found each other, paired (invite link
opened on the joiner, the same 6 digits on both screens, confirmed on both), the roster reached v3 with the admin on
the inviting phone, HELLOs were exchanged and unpaired phones were refused until their pairing. Not yet done from
this pass: the packet capture of gate 1, BENCH, the 120-photo OCR job and everything from gate 4 on.

What the phones taught (each fixed in the code):

- **Scoped storage.** Android 16 refuses the shared root `Download/HiveCoreModels` ("file not found or inaccessible")
  until the app holds "All files access"; the manifest now declares `MANAGE_EXTERNAL_STORAGE`, the first launch opens
  the system page for it, and `adb shell appops set com.hivecore MANAGE_EXTERNAL_STORAGE allow` grants it from a PC.
- **Runtime permissions.** Nearby, Bluetooth, the camera and notifications were never requested; the first launch now
  asks for them (`adb shell pm grant com.hivecore android.permission.<NAME>` from a PC).
- **Discovery on an isolating access point.** This Wi-Fi forwards unicast between clients but drops multicast and
  broadcast, so NSD never finds a peer and a broadcast beacon is silent too. The LAN transport now also announces by
  unicast UDP beacon (`LanBeacon`, port 47474) to every host of the interface's prefix every 9 s; the phones found each
  other within seconds. The multicast lock is held from the moment the router starts, and NSD's service-type compare
  tolerates the trailing dot the phones' mdnsd leaves out.
- **Unpaired churn.** A neighbour that refuses the session (not paired yet) was redialled every second and each refusal
  closed every link to it, including the one a pairing handshake was crossing on, so joins timed out. A refusal now
  holds the redial for 30 s, and a join drops the half-open links and dials the inviter afresh before its handshake.
- **Roster members are pinned from the roster.** Two phones that each paired with the admin refused each other as "not paired": the session layer only knew keys pinned at pairing. The router now pins every member of an accepted roster (and of the stored roster at start-up), so the admin-signed roster is the membership, as 6.4 says.
- **Thermal.** The phone loading the NPU model while charging reached "critical" (45 °C) within minutes; the worker
  policy keeps such a phone ineligible as designed, but the orchestrator role is unaffected. The Swarm tiles show the
  battery sensor, which reads 31 to 39 °C while the skin sensor that sets the thermal status reads 42 to 43 °C
  (status 2, ineligible), so a tile can say "Cool" next to "the phone is hot".
- **Crossed handshakes.** Two phones dialling each other at once each started a handshake over the other's, the FIN
  found no responder and both stayed pending for good. A phone already answering the peer's INIT no longer starts its
  own, and a handshake that does not complete within the timeout is dropped and started again while the link lives (at
  most three times in a row; one timer per handshake, because an endless sweep loop never lets the JVM tests go idle).
- **Own chunks and BENCH.** The orchestrator's own chunks carry no PLAN and are no longer put through P10; the "Benchmark
  every model" recipe gets the installed models as items and a 60 s per-unit estimate, so its chunks keep their
  deadlines. It then ran across the swarm (10 chunks, done, ledger written), and a re-run was served from the chunk memo
  on the three phones.
- **Files never reached a job.** The Jobs screen's "Choose files" was never wired in the app, so no file or image recipe
  could start on a phone. It now opens the system document picker for the recipe's item kinds and copies the picks into
  the app's cache.
- **Chained stages over raw files.** A file recipe ran every stage on the picked files, including stages that read an
  earlier stage's output (LAYOUT over photos); those stages are now skipped and listed, as for item recipes.
- **OCR read one photo per chunk.** A shard chunk carries several photos, but OCR read only the first, so a split OCR
  job lost most of its pages. OCR now returns one item per photo (`{"items":[{"item","lines"}]}`) and the concat merge
  keeps all of them. The new "Photo text (OCR)" recipe (`photo_text`) is the gate 4 job: one OCR stage over picked images.

## Gate 4 OCR measurement (2026-10-11, same three phones, all flags, `-PhiveDemoThermal`)

120 synthetic photos of printed invoices (1600 x 2100 JPEG, ~240 KB each, slight rotation, shading and noise), picked
on phone 2 through the system picker and OCR'd with ML Kit's bundled Latin recogniser by the `photo_text` recipe.
Phone 2 commanded every run with its screen on; phones 1 and 3 slept (screen off, app in the background), as idle
workers do. Every chunk memo was cleared before each run.

| Run | Chunks | Elapsed | Result hash |
| --- | --- | --- | --- |
| One phone (Private on, phone 2) | 2 x 60, both on phone 2 | 15.6 s | `17b2f3a52aaf` |
| Three phones, best run (03:41) | 6 x 20, finished on phones 1 and 2; phone 3 hit the deadline | 21.4 s | (history lost to the memo clear) |
| Three phones, last runs (03:54, 04:07) | 6 x 20, remote chunks duplicated on phone 2 | 21.7 s, 30.9 s | `17b2f3a52aaf` |

The merged result was hash-identical to the one-phone run every time, including when remote chunks came back as blobs.
**There is no speed-up for OCR on this setup, so gate 4 is not passed.** Measured causes:

- **Sleeping workers OCR 2 to 7 times slower.** Median per photo: 129 ms on the awake orchestrator, 202 to 915 ms on
  phone 1 and 886 to 898 ms on phone 3 with the screen off, with and without a partial wake lock (the OS's screen-off
  CPU policy, not a missing lock).
- **Shipping a photo costs about as much as reading it.** Photos reach the workers at about 1.6 MB/s in total
  (~150 ms per photo against ~130 ms of OCR). Every envelope, every 40 KB blob frame included, carries a Keystore
  ECDSA signature (`Wire.kt`), about 24 ms per frame by the frame rate; not yet measured directly.
- A job that moves little data per unit of compute (LLM or VLM steps) is the next candidate for a measured speed-up.

What this run taught (each fixed in the code):

- **Thermal limits for a demo.** `-PhiveDemoThermal` builds keep a phone eligible up to thermal status 3 and a 45 °C
  battery, and stop heavy work at status 4 or 47 °C (`Eligibility.relaxForDemo`); default builds keep the spec limits.
- **Results over the envelope.** A 30-photo OCR result is ~74 KB, over the 64 KB envelope, so remote chunks could
  never answer. A TASK_RESULT over 48 KB now sends its output as a blob first (`outputBlob`), and the orchestrator
  restores it, checked against the signed hash.
- **The deputy stream stuck on a big row.** The same row failed every ~70 ms and held back every later row; a row over
  the batch limit is now skipped with one warning (the orchestrator's journal keeps it, RECLAIM restores it).
- **Chunks cut for one phone.** This phone's own MODEL_READY arrived first and the queue cut 2 chunks for itself while
  the others reported ready 20 to 200 ms later; placement now waits up to 1.5 s for the asked phones.
- **Eligible while yielding.** A charging phone with the app in the background counted as idle with its screen on and
  advertised eligible while its runner refused every task (OwnerYield needs 60 s of screen off); each refusal cost a
  full photo transfer. A yielding phone no longer advertises eligible, and the tile says why.
- **Yielded for good.** OwnerYield's 60 s resume ran only on a new presence value, so with the screen off a phone that
  yielded once never worked again; the policy heartbeat now drives it.
- **Killed in the background, never back on the mesh.** The system killed the app for low memory with the screen off
  and START_STICKY brought back only the worker service; the graph was built by the activity alone. It is now built at
  process start, and the phone rejoins on its own.
- **A low-memory kill loop.** Each restart loaded the 4 GB LLM again and the next kill came ~40 s later. After a
  low-memory exit the LLM loads on demand.
- **Transfers.** Blob frames over a LAN link are 40 KB (16 KB elsewhere, for Nearby's 32 KB cap), blobs for several
  remote chunks ship in parallel instead of one after the other, and a chunk's deadline starts when its TASK is sent,
  not when its photos start shipping (a 10 s deadline had run out on arrival). Transfers went from ~0.9 to ~1.6 MB/s.
- **Worker locks.** A worker holds a partial wake lock while tasks are queued or running and a low-latency Wi-Fi lock
  while it advertises eligible.
- **No reason on the tile.** The Jobs grid shows "failed on ?" without the cause, and the worker's task path logged
  nothing; the worker now logs each task's start, end and outcome.
