# On-phone gates (spec section 19)

Everything below is reserved for the user. The JVM suite (901 tests, `tools/gradle.sh testDebugUnitTest`) covers the
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
  policy keeps such a phone ineligible as designed, but the orchestrator role is unaffected.
