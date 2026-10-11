# Decision-driven chunking: a System One model on the phones

Plan of record for the `decide` flag (2026-10-11). Phase 1 is implemented behind the flag on branch `track/decide`;
Phase 2 is a spike on its own branch. Sections name the spec sections they touch.

## 1. Goal and constraints

A **System One decision model** answers typed closed questions in one pass and returns calibrated probabilities:
Choice (one of N), Score (a position on an ordered rubric, reported in [0, 1]), Noul (probability a statement is
true). It never generates text, never plans, never calls tools. It is used at the chunking stage of the split engine
(spec 10) and in the work queue's verification (spec 12.1).

| # | Constraint | How this plan meets it |
| --- | --- | --- |
| 1 | No cloud AI, no network code outside `mesh/` | The model is the local GenieX LLM (Phase 1) or Clef-flash on device (Phase 2). `checkNetworkBoundary` in the root build fails on any network dependency or JDK socket / HTTP import in `core, trust, runtime, engine, planner, node, ledger, ui`, with one pinned allowlist entry (`runtime/.../ModelDownloader.kt`, model downloads). `cloud/` (OpenRouter, P3) and `app/` (FCM wake) are pre-existing exceptions outside the checked set. |
| 2 | Chunk identity stays content-derived and includes the decision build | `SplitJob.opKey` gains `|decide:<sha256>:<calibrationVersion>` when a decision cut the job (10.3 below). |
| 3 | Determinism; decisions on the orchestrator once per job, journaled | Decisions run inside `SplitCommander.run` before the first dispatch; greedy decoding and deterministic option permutations give identical votes for identical requests; probabilities are rounded to 4 dp; every decision is journaled (write-ahead) with its request hash, answers and build. |
| 4 | Merge equality; TREE identity across phone counts | Only boundaries move: CONCAT / STITCH / MONOID / TOPK merges are exact for any boundary set (tests). TREE keeps a fixed band (30–40 sentences) whose cut depends on the text only. |
| 5 | Fallback | Flag off, model unavailable, build unpinned, over budget, or request failed ⇒ today's cutters run unchanged and chunk ids are byte-identical to today's. |
| 6 | Privacy | Decisions over private inputs run only when the orchestrator is the owner of the data; private inputs of another owner are never decided on in this phase (their chunks are cut by the deterministic cutter). |

## 2. Why Phase 1 votes instead of reading logits

The pinned `geniex-android-0.8.0` exposes `LlmWrapper.generateStreamFlow(prompt, GenerationConfig)` with text
tokens only and a JNI `Llm.generate(handle, prompt, config, callback): LlmGenerateResult(fullText, profileData)`.
There are no logits, logprobs, token ids or tokenizer calls on either the qairt or the llama.cpp path (verified
against the SDK's `classes.jar`). `GenParams` (frozen `core/LlmEngine.kt`) carries max tokens, stop words and a GBNF
grammar; `GenieXEngine` pins `topK = 1, temperature = 0.01` and applies the grammar only off the NPU (qairt throws on
any grammar).

Phase 1 therefore reproduces the *interface technique* with what exists: for each question the allowed options are
listed, the model answers with exactly one option number (GBNF-constrained on llama.cpp; validate-and-reject on
qairt, never repaired), the question is asked `k = 3` times with deterministic permutations of the option order
(`ClosedChooserImpl.permutation`, seeded from sha256(question + labels)), and the vote fraction per option goes
through a per-build calibration table to a probability. The `OptionScorer` seam lets a runtime that exposes logits
replace the votes with `softmax(logit / temperature)` without touching callers.

## 3. Interfaces (`:core`, `core/src/main/java/com/hivecore/core/DecisionModel.kt`)

Module direction is `engine → runtime → core`, so the seam lives in `:core`, the voting implementation in `:runtime`,
the chunking logic in `:engine`. `core.Decision<T>` already names the closed-choice result, so the journaled record
is `Decided`.

```kotlin
@Serializable sealed interface Question {
    val id: String; val instructions: String
    @Serializable @SerialName("choice") data class Choice(override val id: String, override val instructions: String, val criteria: Map<String, String>) : Question
    @Serializable @SerialName("score")  data class Score(override val id: String, override val instructions: String, val criteria: List<String>) : Question
    @Serializable @SerialName("noul")   data class Noul(override val id: String, override val instructions: String, val group: String? = null) : Question
}
@Serializable data class DecisionRequest(val model: String, val state: String, val questions: List<Question>)
@Serializable sealed interface Answer {
    @Serializable @SerialName("choice") data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>) : Answer
    @Serializable @SerialName("score")  data class Score(val score: Double, val confidence: Double, val legend: List<String>, val probabilities: List<Double>) : Answer
    @Serializable @SerialName("noul")   data class Noul(val probability: Double) : Answer
}
@Serializable data class DecisionUsage(val questions: Int, val llmCalls: Int, val ms: Long)
@Serializable data class DecisionResponse(val model: String, val answers: Map<String, Answer>, val usage: DecisionUsage)
@Serializable data class DecisionBuild(val modelId: String, val sha256: String, val calibrationVersion: String) { val tag: String get() = "$sha256:$calibrationVersion" }
interface DecisionModel {
    fun build(): DecisionBuild?                       // null = unavailable now (no LLM loaded, build unpinned)
    suspend fun decide(request: DecisionRequest): Result<DecisionResponse>
}
interface OptionScorer { suspend fun scoreOptions(messages: List<ChatMsg>, options: List<String>): Result<List<Double>> }
@Serializable data class Decided(val point: String, val requestHash: String, val response: DecisionResponse, val build: DecisionBuild, val hash: String)
```

The request and response mirror the Clef / SystemOne API (`POST /v1/systemone`: `{model, state, questions}` →
`{model, answers, usage}`; a `choice` answer carries `choice, confidence, probabilities`; a `score` answer the
expected `score, confidence, legend, probabilities`; a `noul` answer the probability of true), so the journaled
requests replay against Clef-flash on a laptop unchanged. The one difference is that questions are a list with `id`
rather than an object keyed by id, so canonical hashing has a fixed order; `tools/decide/replay_clef.py` converts.

`DecisionBuild` for Phase 1: `modelId` = the loaded LLM's catalog id, `sha256` = that catalog entry's sha256,
`calibrationVersion` = `<catalog calibration version>+t<TEMPLATES_VERSION>`. A missing or `unpinned` sha makes the
model report `build() == null`, so decisions never run under an identity the memo could confuse.

### Calibration (catalog)

`ModelEntry.calibration: Calibration? = null` with `Calibration(version, bins: List<List<Double>>)`: a monotone
piecewise-linear map from vote fraction (or softmax probability) to a calibrated probability, validated by
`ModelCatalog.validate`. Absent ⇒ identity, version `none`. The shipped catalog carries `cal-1` identity tables for
the two LLM builds until the phone eval (section 9) produces fitted bins.

## 4. Question templates

`engine/.../decide/Templates.kt`, `TEMPLATES_VERSION = "t1"`. The state is always plain text the orchestrator holds.

| Point | Type | id | Instructions (verbatim) | State | Fallback |
| --- | --- | --- | --- | --- | --- |
| A boundary | Noul, `group = "w<start>"` | `b<i>` | "The topic changes between sentence s<i> and sentence s<i+1>." | The window's sentences, one per line as `s<i>: text` | fixed-size cut (`ranges(n, per)`) |
| B difficulty | Score, levels `trivial, easy, moderate, hard, very hard` | `d<index>` | "How demanding is this chunk for the `<op>` step?" | The chunk's inline text or items (≤ 1 500 chars) or its blob names and sizes | `difficulty = null`, `costFactor = 1`, registry replicas |
| C axis (planned) | Choice over the registry's axes for the input kind | `axis` | "Along which axis should this input be split for `<op>`?" | Input kind, size, op | registry's axis |
| D capability (planned) | Choice over `OpInfo.capabilities` | `cap<index>` | "Which capability should run this chunk?" | Chunk summary, candidate capabilities with descriptions | registry capability |
| E verdict | Noul | `v<index>.<node>` | "This output is consistent with its input and with the required schema." | Chunk params (truncated), output (truncated), schema when present | hash-and-compare only |
| F personal data (planned, off) | Noul | `pd<item>` | "This item contains personal data." | Item preview | no suggestion |

Phase 1 batch rule: Noul questions that share a `group` over one state are answered by **one** Choice vote per group
("Which boundary is the strongest topic change?", options = the candidates plus `none`); each Noul receives its
calibrated vote share. This is an approximation of per-boundary Noul that keeps the cost at ~3 LLM calls per window;
Clef-flash answers the same request natively, which is what the evaluation harness compares.

## 5. Decision points at chunking

### A. Semantic boundaries (`BoundaryDecider`)
Runs when a model is injected, `build() != null`, the input is `SplitInput.Text`, the merge kind is STITCH or TREE,
the job has no `chunks` / `unitsPerChunk` override, `job.cuts == null` (a resumed job re-plans without the model), and
the text is public or the orchestrator is its owner. Windows of candidates `[s + min, s + max]` are asked; a
deterministic dynamic programme maximises Σ p(boundary) − λ·|size − target| subject to `min ≤ units ≤ max` (the last
chunk may be shorter), ties to the earlier boundary. STITCH: `min = ceil(per / 2)`, `max = per` from the speed-based
`per` of spec 10.5 (sizes may follow the phones, equality never does). TREE: `min = 30`, `max = 40`
(`TREE_SENTENCES − 10 .. TREE_SENTENCES`), independent of phone count. The result is `job.copy(cuts, decider)`;
`ChunkPlanner.plan` then cuts without the model.

### B. Difficulty (`DifficultyDecider`)
One Score per chunk after the plan. `Chunk.difficulty`, `Chunk.costFactor = 0.75 + 0.5·difficulty` (expected ms stays
within ±25 %), `Chunk.replicas`: difficulty ≥ 0.6 on a public chunk with ≥ 2 eligible phones ⇒ `min(registry.replicas
+ 1, 3)`; difficulty ≤ 0.3 ⇒ `OpInfo.minReplicas` (2 for LLM / VLM, 1 otherwise, so no LLM op drops to one run until
its registry row says so). A chunk settled on fewer runs than the registry's replicas accepts `verified = false` and
is never memoized (12.1). Re-cut children inherit the three fields. The small-vs-large build choice is **recorded**
(`Chunk.build`) but not applied in Phase 1: applying it needs a per-chunk op key through every splitter and placement
demand per build.

### E. Verdict (`VerdictDecider`)
Never before the hash and schema checks, never for inputs the orchestrator may not see, never inline in the
commander loop. `WorkQueue.settle` stays a pure state machine: in the two terminal no-agreement branches (replicas
disagree and no further phone can run; stranded with held outputs) it returns `Outcome.NeedsVerdict(index,
candidates)`; the commander asks one Noul per candidate and calls `q.verdict(index, winner)`: the candidate with the
highest probability wins if ≥ 0.7 (the spec's judge threshold), accepted `verified = false` with the note
"verdict-picked", never memoized; otherwise the existing verification-failure path. Within the budget, accepted
outputs of single-run or unverified chunks are judged off-loop and annotated (`ChunkCell.verdict`, `ChunkView.verdict`,
note "low-confidence output" under 0.5); annotation never changes acceptance.

## 6. Chunk identity (spec 10.3)

```
opKey   = op + "@" + modelSha256 [+ "|decide:" + decisionSha256 + ":" + calibrationVersion]
chunkId = sha256( opKey | canonical(job params) | canonical(slice params) | blobIds )
```

The suffix is present only when a decision cut the job, so an undecided run (flag off, model unavailable, fallback)
produces today's ids and today's memo hits. A different decision build or calibration yields different ids, so the
memo never serves a cut made by another build. `SplitJob.cuts` and `SplitJob.decider` are fields of the journaled
spec, not params, so the PLAN template and P10 authorisation are unchanged.

## 7. Journal (spec 13.1)

| Table | Columns | Written when |
| --- | --- | --- |
| `decisions` | `seq, jobId, point, questionId, requestHash, answerJson, probabilitiesJson, modelSha256, calibrationVersion, llmCalls, ts` | Before the decided job is acted on (A, B) and before a verdict is applied (E); append-only, one transaction per batch |

`JobJournal.decisions(rows)` has a default no-op; `SqliteJobJournal` adds the table to `SCHEMA`, `PRIMARY_KEYS`
(`seq`) and `JournalRows.TABLES` so it streams to the deputy (`StreamingJournal` overrides it and signals) and
imports back; `JobSnapshot.decisions` exposes it to the Jobs screen and the ledger. `SCHEMA_VERSION` stays 1: the
forward migration creates the table.

Resume: the commander journals the decided job (`cuts`, `decider`, per-chunk replicas in `ChunkRow`) as the job
row's `spec`, so `JobResume` reproduces the identical chunk ids with no model call and the prefilled outputs match.

## 8. Feature flag

`-PhiveFeatures=decide` → `BuildConfig.FEATURE_DECIDE` → `Features.decide`. `AppWiring` builds
`VotingDecisionModel(chooser, llm, catalog)` only when `runtime` and `decide` are on and passes it through
`SwarmGraph.Deps.decisionModel` → `SwarmNode.build(decisionModel)` → `SplitCommander(decisions)`. Modules below
`:app` never read flags: a null decider is "off". Default off until the phone gate (section 11) passes.

## 9. Evaluation harness

- Gold sets (held out, committed): `engine/src/test/resources/decide/boundaries.jsonl` and `verdicts.jsonl`, each
  line a Clef-shaped request plus `gold`. Boundaries come from concatenations of distinct fixture documents (gold =
  document and paragraph seams, negatives = mid-paragraph positions); verdicts from the invoice fixtures (positives =
  the expected fields, negatives = swapped vendor / total or a schema violation).
- `DecisionEvalTest` reports accuracy, Brier score and ECE (10 equal-width bins) for the current heuristics
  (boundary = every `per`-th sentence or paragraph break; verdict = hash + schema pass ⇒ 1.0) and for any
  `DecisionModel`: the scripted fake on the JVM, `VotingDecisionModel` on a phone (the `decide_eval` recipe writes
  `files/decide_eval.json`), and Clef-flash through `tools/decide/replay_clef.py` against a laptop SGLang endpoint,
  whose answers file the test reads from the `decide.answers` system property.
- Metrics live in `engine/.../decide/Metrics.kt` (pure) and are shared by the test and the recipe.

## 10. Phase 2 spike (branch `track/decide-clef`)

1. Quantise Qwen3.5-9B (int4 weights) and `joint_head.safetensors` (int8); keep the head's `JointSchemaHead`
   semantics (evidence routing over `last_hidden_state`, one logit per option, softmax per question).
2. Try ONNX Runtime with the QNN execution provider first (`onnxruntime-android-qnn` is already a `:runtime`
   dependency). **Unverified:** decoder-LLM support on the QNN EP for this chipset; a 2026 ORT issue reports a silent
   CPU fallback on SM8750. Then llama.cpp with a hand-ported head (the `qwen35` GGUF architecture exists in community
   conversions; the minimum llama.cpp version is unverified).
3. Measure on a 12 GB iQOO 15: resident memory, ms per 2 k-token state carrying 40 Noul questions, and the swap cost
   from the 4 B LLM (not co-resident: ≈ 5.5 GB int4 + 3.2 GB).
4. Go/no-go: p50 ≤ 1 500 ms per window state, resident ≤ 6 GB, and swap + all decisions ≤ 10 % of the 300-page
   reference job's single-phone estimate. Justification: ~70 windows for 4 000 sentences keeps decisions under two
   minutes against a multi-minute job; 6 GB leaves the OS and the app headroom on 12 GB.
5. On go: a `decide` capability in the catalog, `ClefDecisionModel` under the same interface and calibration path,
   replacing `VotingDecisionModel`.

## 11. Tests (JVM, fakes only)

Determinism (same input twice ⇒ identical chunks and bytes), merge equality for CONCAT / STITCH / MONOID / TOPK over
decided cuts, TREE identity on 1 / 2 / 3 phones, fallback when the model is unavailable (ids identical to today),
chunkId change when the decision sha changes, per-chunk replicas and the verdict tie-break in `WorkQueue`, journal
rows and streaming, resume of a decided job without the model, the 3-node fake transport with a scripted model
(STITCH hash equal on 1 / 2 / 3 phones and to the unsplit run; private job of another owner never decided on), the
voting model over a scripted engine (grammar only off the NPU, reject on qairt, calibration), the flag enumeration,
and the network boundary. Phone gate for the flag: boundary and verdict eval accuracy / ECE better than the
heuristics on the gold sets, decisions within the 10 % budget on the 120-photo and long-document jobs.

## 12. Proposed `docs/` edits

- `spec.md` 10.3: after the chunkId formula, "When decision-driven chunking cut the job, the op key also carries
  `|decide:<sha256>:<calibration>`."
- `spec.md` 10.5 step 4: after "TREE uses a fixed 40 sentences per chunk", "(with the `decide` flag a band of
  30–40 chosen by the boundary model, still independent of phone count)".
- `spec.md` 12.1, Local judge row: "a split chunk's verdict is the decision model's Noul; it annotates and breaks
  terminal ties only".
- `spec.md` 13.1: a `decisions` row; Amendments: a dated entry "Decision model (4.3, 10.3, 10.5, 12.1, 13.1, 18.2)".
- `status.md`: flags line adds `decide`; `:runtime` row adds `VotingDecisionModel`; "Still fake" adds the identity
  calibration and the Phase 2 spike.
- `gates.md`: a `decide` gate row (eval accuracy / ECE vs heuristics; decision latency within budget).
- `seams.md`: ownership of `core/DecisionModel.kt`, `engine/decide/**`, `runtime/decide/**`.

## 13. Sources

- Cloudflare/clef-flash model card and `joint_schema_model.py` (Hugging Face, read 2026-10-11): backbone
  Qwen/Qwen3.5-9B, Apache-2.0, `joint_head.safetensors`, `/v1/systemone` request and response fields, softmax per
  question, 4-dp rounding, tested on one H200. The 38.8 ms median latency is the vendor's number (unverified).
- OpenJev weights: CC BY-NC 4.0 per the openjev Hugging Face page (not used; the option-letter logit technique is
  reproduced without its weights).
- GenieX 0.8.0 API surface: `geniex-android-0.8.0.aar` classes (inspected locally).
- llama.cpp `qwen35` GGUF conversions (Hugging Face, several repos): architecture support exists; minimum version
  unverified. ORT QNN EP on Snapdragon 8 Elite: GitHub issue reporting CPU fallback (status unverified).
