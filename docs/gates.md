# On-phone gates (spec section 19)

Everything below is reserved for the user. The JVM suite (901 tests, `tools/gradle.sh testDebugUnitTest`) covers the
logic; these gates need real iQOO 15 phones, a relay host, or an OpenRouter account. Build a gate APK with
`tools/gradle.sh :app:assembleDebug -PhiveFeatures=<flags>`; a flag defaults on only after its gate passed.

| Phase | Flags | Needs | Gate |
| --- | --- | --- | --- |
| 1 LAN mesh and trust | `mesh` | 3 phones on one Wi-Fi | Pair by QR (both see the same 6 digits, confirm on both), tiles appear, a packet capture shows ciphertext only, an unpaired phone is rejected |
| 2 Runtime | `runtime` | each phone with the LLM and VLM bundles in `Download/HiveCoreModels` (see `runtime/src/main/assets/models/catalog.json`; GenieX QAIRT path) | Load an LLM and a VLM, run BENCH, p50 and `big` advertised in the profile; picking the phone up yields within 1 s |
| 4 Split on phones | `mesh,runtime,split` | 3 phones, 120 released photos | OCR job with a measured speed-up and the same result hash as one phone; a job needing VLM on two phones and LLM on one places correctly |
| 5 Graph | `+graph` | 2 owner phones with seed tables | "Revenue per employee by region" answers with rows moved 0, one fingerprint on the confidential owner, plan hash in both ledgers (the owner consent sheet is still the debug auto-approve; see status.md) |
| 6 Remote | `+remote` | a relayd host behind Caddy TLS (`relayd/DEPLOY.md`, Dockerfile), coturn with the shared secret, a Firebase project for wake, relay address in Settings, one phone on home Wi-Fi and one on mobile data in another city | The 120-photo job completes; the relay is killed mid-job and the job finishes; LLM chunks go to the remote phone, photos stay home |
| 7 State | `split` (journal lands with it) | 3 phones | Force-stop the orchestrator mid-job, resume completes with zero re-runs of finished chunks; re-run after adding ten items runs ten chunks |
| 8 Cloud | `+cloud` | an OpenRouter account with a per-key spend limit and the ZDR guardrail, the key entered in Settings | A forced 3-way disagreement on a public step escalates and is badged; private lineage is refused; the cap stops spend; `CloudBoundaryTest` proves the key path is absent from `:node`; verify the slugs in `cloud/src/main/assets/cloud/models.json` against `GET /api/v1/models` |
| 9 Hardening | `hardening` | all of the above | 1-hour soak with random kills, sleeps, relay restarts and yields (harness not built yet) |
