# HiveCore: what `main` does today

Updated 2026-10-11 (phases 1-8 merged and wired behind their flags; the integration wave on top of the six tracks, the closing wave: consent, CLOUD option, deputy, downloads, document executors, soak harness; then the UI redesign, demo mode and the deck plans).

## Build and test

- Multi-module Android project (AGP 8.13.2, Kotlin 2.4.20, Compose BOM 2026.06.01, compileSdk 36, minSdk 31, JDK 17) plus `relayd/` (Go).
- `tools/gradle.sh <args>` is the only way to run Gradle on this machine (one build at a time across every checkout).
- JVM unit tests: `tools/gradle.sh testDebugUnitTest` runs 1,080-odd tests, all green, in about two minutes warm (the four-seed soak harness `SoakTest` is among them); `relayd` has 18 Go tests.
- Debug APK: `tools/gradle.sh :app:assembleDebug` (every flag off) and `tools/gradle.sh :app:assembleDebug -PhiveFeatures=all` both build. `-PhiveDemoThermal` relaxes the thermal limits for demos (not for gate runs). Flags: `mesh,runtime,split,graph,remote,state,cloud,hardening`.

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
| `:ui` | the design system and kit (`ui/design`), the shell (`ui/shell`), the chat Home and the five capability pages with their view models over the ports, the result and activity sheets, `DemoPort`, the scripted demo (`ui/demo`); `DesignGuardTest` enforces `docs/ui-template.md` | 72 |
| `:app` | `AppWiring` (per-flag graph), `AppPorts` (the real ports), `HiveCoreGraph`, consent sheet, the plan approval screen and notifier, FCM wake, the demo tools (`app/demo`: `DemoPortImpl`, `DemoWiring`, sample invoices, QR scanner, reset, permission checklist, feed bridges); boundary, flag-combination, approval, feed and demo-tool tests | 13 |

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

## The UI and the demo

- **Look and template.** Honey amber on slate, light and dark, an adaptive hexagon icon and the platform splash. Every
  screen uses `HiveScreen` and the kit in `com.hivecore.ui.design`; `docs/ui-template.md` is the contract for new
  features and `DesignGuardTest` fails the build on raw colours, sizes, shapes, library icons or missing previews.
- **Home is a chat** (Ask): greeting and five suggestion cards, bubbles, the plan card with LOCAL and CLOUD side by
  side, live status, answers with provenance, a pill composer with the cloud policy pill. The other pages open with a
  capability header and a "Try it in chat" prompt.
- **Top bar**: phones chip (taps to Swarm), activity feed with an unread badge, a DEMO pill in demo mode; while a job
  runs, the newest activity line shows under it.
- **Results**: "Open result" shows the merged output (the OCR text of every photo, or a table) with Copy as CSV. The
  "identical to one phone" badge appears only after a one-phone run and a swarm run of the same request.
- **Decks** (spec amendment "decks"): "Generate a pitch deck showing the increase in sales FY2024-26" plans AGG on the
  owner, slide text (LLM_MAP), a picture (GEN_IMAGE) and RENDER deck on this phone; the answer carries the deck, drawn
  as slide thumbnails with Present (a pager sheet), Open .pptx and Share (FileProvider). The .pptx is written by
  `engine.deck.PptxWriter` into `files/decks` with editable shape charts; `PptxExtractor` reads it back in the tests.
  Text and picture steps are optional: a swarm without LLM_MAP or the diffusion model still gets a deck from the figures.
- **Pictures and the guide** (spec amendment "questions about the swarm, and pictures"): "Create an image of my team" plans
  GEN_IMAGE on a phone with the image model and RENDER picture here (pattern `make_image`, lexicon `cues-3`); the answer
  carries the PNG as a card with Open and Share. A question about the app itself ("what can you do", "what can you create
  an image of", "what data do you have") is answered at once by `SwarmGuide` from the catalog and the capability summary,
  with no plan and no model; a request for a picture of a chart of the data is pointed at the deck, since charts come from
  the real figures. Refusals are one plain sentence plus the guide's hint; the route reason sits in the details.
- **Swarm**: Scan QR uses the camera (CameraX + ML Kit's bundled barcode model); Copy, Share and the battery exemption
  are wired. **Ping nearby** (spec amendment "ping nearby") broadcasts a fresh invite to every phone on the Wi-Fi
  (`mesh.LanPairPing`, UDP 47475); each one shows "X wants to pair" with Approve / Deny on the Swarm screen and as a
  notification with the same two actions; Approve is the ordinary join, so both phones still compare the six digits,
  and the pinging phone sees who approved or denied on its card.
- **Compute points** (UI only, no engine behind it yet): `ui.vm.ComputeCredits` pays a point per second of compute this
  phone did for another phone, once per settled job from the worker timeline, persisted in prefs; each request from
  Home spends 5; a new phone starts with 50. A card on the Swarm page shows the balance, totals and the last awards,
  the Home greeting line carries the balance, and `app.credits.CreditsNotifier` posts "You earned N points" per award.
  The rule is one function so the real accounting can replace it without touching the screens.
- **Seed data** (spec amendment "Kavira data"): Kavira Pharmaceuticals, a fictional pharma company, is split across
  the three roles. Sales holds monthly sales, products, branches, the sales force, events and stockists. HR holds the staff
  directory, compensation, salary history and company phones. Accounts holds financials, receivables and budget vs
  actual. Picking a role removes the other roles' seed data, and an app update re-seeds a seeded phone
  once. Regenerate with `tools/kavira/make_kavira_data.py` then `tools/kavira/export_seed.py`; answer keys land in
  `tools/kavira/out/`.
- **Kavira jobs** (Jobs screen, and the "Letters to 48 stockists" card on Home): stockist letters, brand reviews,
  Monday review packs, launch messages in three languages, and a launch kit. The first four are table recipes: the
  phone holding the table turns its rows into items, and the LLM work shards across the phones. Demo mode scripts all five.
- **Settings > Demo**: demo mode, the phone's role (seeds its tables and documents), seed every role, 24 or 120 sample
  invoice photos drawn on the device, approval screen and consent sheet previews, debug auto-approve, keep screen on,
  large text, hide hashes, the permission checklist and reset-and-restart. `docs/gates.md` has the demo runbook.
- **Demo mode** runs the app over three scripted phones on one device (no mesh): the job splits with a straggler and
  lands at about 2.6×, the suggestion prompts plan and answer, the commission question uses the real approval screen,
  and the deck prompt writes a real .pptx (pharma revenue FY2024-26, an illustration drawn on the device).

## Still fake or not wired

- Model downloads run only for catalog entries that carry a `url`; the shipped catalog has none (copy the bundles into `Download/HiveCoreModels`, or install a `files/models/catalog.json` with urls).
- Plan consent, the CLOUD option, deputy streaming, VLM_ASK, the document executors are wired and JVM-tested but have not run on a phone; the deck plan (AGG, LLM_MAP, GEN_IMAGE, RENDER) has run on the three iQOO 15s end to end (docs/gates.md, 1 min 7 s) (biometric signing, the lock-screen intent, ML Kit and the GenieX VLM are exercised only through their seams).
- A split job started from a recipe has no CLOUD option (recipes run on the phones); the gate still re-runs a failed chunk in the cloud.
- Document ingest (`PdfExtractor` has its OCR now, but nothing feeds it) and voice remain out, as in phase 0; the share sheet exists for decks only.
- A real deck run needs: the owner's table with a `month` column and a measure the question names (the seed's `sales.revenue` answers to "sales"), an LLM phone for the slide text (LLM_MAP; the pattern drops the step when none registers it), and a phone on the charger with the SD 1.5 bundle for the picture (GEN_IMAGE is HEAVY; dropped likewise). The slide text runs unreplicated, so a deck answer is badged unverified.
- The hardening flag has no wiring of its own; the soak harness is the JVM `SoakTest` (four seeded chaos sequences over the LAN and relay rigs); the 1-hour on-phone soak has not run.
- First on-phone pass done (docs/gates.md): install, model load, discovery, pairing of three phones and the roster work on real iQOO 15s; BENCH, the OCR job and the gates from 4 on are still unrun.
