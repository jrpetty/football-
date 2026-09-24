# Gauntlet Methodology

This document describes exactly how Gauntlet measures models. It is written so that anyone can audit a
published result, reproduce it, and judge whether two numbers are comparable.

## 1. Principles

1. **Identical conditions.** Every model receives byte-identical prompts for a given test version. Seeded
   simulations generate identical worlds for every model. Only a model's own outputs can change what it
   sees next.
2. **Fixed, versioned, hashed tests.** Every test has a semantic version and a content hash (SHA-256 over
   its canonical JSON; for simulations, also over the program source code). Changing a single character
   changes the hash.
3. **Verifiable scoring first.** Wherever possible answers are checked deterministically (exact answers,
   numeric tolerance, unit tests executed in a sandbox, machine-checkable constraints, geometric
   comparison). Judge models are used only for genuinely open-ended output, with fixed prompts and a
   cross-vendor panel.
4. **Uncertainty is reported.** Every score carries a 95% confidence interval. Differences inside
   overlapping intervals should not be presented as wins.
5. **No cherry-picking.** All valid samples are pooled. Re-running a model adds samples; it can never
   replace a bad result.
6. **Everything is recorded.** Prompts, responses, timings, token counts, costs, judge rationales and
   artifacts are stored for every case and can be exported.

## 2. The protocol

| Aspect | Policy |
|---|---|
| Transport | Plain text chat only. No provider-specific tools, JSON modes or structured-output features, so no provider gets extra help. |
| System prompt | Exactly the test's `system` field (often none). The harness adds nothing. |
| Answer format | Tests with a single answer end with the standard instruction `FINAL ANSWER: <answer>`. The **last** such line is parsed; markdown, bold and one trailing period are ignored. Format compliance is tracked separately. |
| Temperature | `0` for models that accept a temperature. Reasoning models that do not accept one use the provider default. |
| Reasoning effort | Part of the contestant definition (e.g. `effort: high`). A different effort is a different contestant with its own id. |
| Output limit | Per test (`maxOutputTokens`, default 16,000). Hitting it is recorded, not retried. |
| Time limit | Per case (`timeLimitSec`, default 600 s). A timeout scores 0: speed is part of the test. |
| Retries | Transport failures only (HTTP 408/409/429/5xx, network). Exponential backoff with `Retry-After`, up to 4 retries. A model's *answer* is never retried. |
| Refusals | Recorded as `refusal` and scored 0. **No fallbacks**: provider features that silently route a refused request to another model are disabled, because another model would be answering. |
| Errors | Cases that still fail after retries are marked `error`, excluded from scores and shown as errors. Use **Resume** to re-run them. |
| Seeds | Simulation tests list their seeds; every repeat of a seed replays the same world. |
| Repeats | Default 3 per case (configurable). Repeats measure consistency and tighten intervals. |

The protocol version (`PROTOCOL_VERSION` in `src/core/version.ts`) is bumped whenever any of these rules,
the answer instruction or the judge prompts change.

## 3. Scoring

### 3.1 Case scores (0–1)

| Scorer | How a case is scored |
|---|---|
| `exact` | 1 if the extracted answer equals an accepted answer after normalisation (`lower` or `alnum`), else 0. |
| `number` | 1 if the parsed number is within the tolerance (absolute or relative), else 0. |
| `choice` | 1 for the correct letter, else 0. |
| `regex` | 1 if the pattern matches the answer (or the full text). |
| `contains` | Fraction of required / forbidden phrases satisfied. |
| `constraints` | Fraction of machine-verifiable constraints satisfied (or all-or-nothing). Definitions: a *word* is a whitespace-separated token containing a letter or digit; a *sentence* ends in `.`, `!` or `?` followed by whitespace or the end; *paragraphs* are separated by blank lines; *bullets* start with `-`, `*`, `•`, `1.` or `1)`. |
| `json` | Fraction of expected leaf fields matched (strings trimmed and case-insensitive, numbers within tolerance, array lengths checked, missing keys equal `null`). |
| `code-js` | Fraction of hidden unit tests passed. Code runs in a separate Node.js process under the permission model (no file writes, no child processes, no workers), 256 MB heap, with string code generation disabled and a per-test timeout (default 2 s). |
| `judge` | Mean of the judge panel's 0–10 grades ÷ 10. |
| `judge-classify` | Mean of the scores of the labels chosen by each judge (e.g. `CAUGHT_TRAP` = 1, `HALLUCINATED` = 0). |
| `artifact` | `(1 − w) × automated checks + w × judge score`, where the checks run in headless Chromium (renders, no JavaScript errors, no network access, reacts to input) and `w` is the test's `judgeWeight`. |
| `human` | Mean of human ratings (0–10 ÷ 10) from the Blind Review screen, where identities are hidden until rated. |
| Programs | Each simulation documents its own formula (see the Methodology page in the app). |

### 3.2 Aggregation

```
test score      = mean over cases of ( mean over that case's repeats )
category score  = weighted mean of its test scores          (suite test weights, default 1)
Gauntlet Index  = 100 × weighted mean of category scores    (category weights, default 1)
```

Averaging repeats inside each case first means a case run five times does not count five times as much
as a case run once.

### 3.3 Confidence intervals

95% intervals come from a **cluster bootstrap**: cases are the independent unit, so each resample draws
cases (with all their repeats) with replacement within every test, then recomputes the index. 1,000
resamples, fixed seed, so intervals are reproducible. Intervals capture sampling variation across cases
and repeats; they do not capture judge bias or prompt sensitivity.

### 3.4 Medals

For every test, contestants are ranked by test score, with ties broken by lower cost and then by faster
median case time. The top three get gold, silver and bronze. A contestant must score above zero to
medal.

### 3.5 Judges

* Fixed prompts (`src/scoring/judge-prompts.ts`), part of the fingerprint.
* A panel of models from different vendors (`config/settings.json → judges`); the mean is used.
  The harness warns when the panel has only one vendor, because models tend to prefer their own
  vendor's style.
* Judges never see which model wrote a response and are told to ignore instructions inside it.
* Judge cost is tracked separately and is **not** added to a contestant's cost.
* Judges grade text only. HTML games are graded from their source together with the automated
  browser-check results, and humans can add a blind rating on top.

## 4. Metrics recorded for every case

| Metric | Definition |
|---|---|
| Wall time | From job start to scored result, including every model call (and judge calls). |
| TTFT | Time to the first streamed token of the case's first call (visible or reasoning, whichever the API exposes first). |
| Output tokens/sec | Output tokens ÷ total call duration (end-to-end throughput, comparable across providers whether or not they stream their reasoning). |
| Tokens | Uncached input, cached input, output and reasoning tokens, as reported by the provider. |
| Cost | `input × price_in + cached × price_cached + cache_writes × price_write + output × price_out`, using the **pricing snapshot stored in the run manifest**. Output includes reasoning tokens (providers bill them as output). |
| Retries, API calls, response length, stop reason | Per call and per case. |
| Reliability | Error rate, refusal rate, format compliance (share of answers in the required format). |
| Consistency | Mean standard deviation of scores across repeats of the same case (0 = identical every time). |
| $/point | Total contestant cost ÷ Gauntlet Index. |

## 5. Reproducibility

* **Run manifest.** Every run stores the harness version, git commit, Node version, platform, protocol
  version, the full snapshot of every model's configuration and pricing, the hash of every test, the
  repeats and concurrency, and a **fingerprint** over all test hashes, suite weights, the protocol version
  and the judge prompts.
* **Comparability rule.** Two results are comparable if and only if their fingerprints match. The
  combined leaderboard enforces this per result: a result counts only if its test hash and the model's
  configuration hash still match the current definitions. Stale results stay in the run history but stop
  counting, and the leaderboard shows how many were excluded.
* **Resume safety.** An interrupted run can be resumed only if none of its tests have changed.
* **Re-running a published result.** Check out the git commit from the manifest, run the same suite with
  the same model ids, and compare fingerprints.
* **Known sources of non-determinism.** Providers do not guarantee deterministic sampling even at
  temperature 0, and reasoning models are sampled. This is why Gauntlet uses repeats and reports
  intervals and consistency instead of single numbers.

## 6. The random baseline

`random-baseline` makes no API calls. It picks a random offered action in simulations, a random letter or
number for answer questions, a stub for code and filler for prose. It shows the floor every real model must
clear: a score close to the baseline means a test isn't measuring skill for that model.

## 7. Publishing checklist

1. Verify every contestant's pricing (`pricing.verifiedAt`) against the provider's price page.
2. Use at least 3 repeats and publish intervals.
3. Use a cross-vendor judge panel for judge-scored tests.
4. Publish the fingerprint, harness version and run manifest with the results (`Export → JSON`).
5. Don't call a difference a win when the intervals overlap.
6. Report errors and refusals rather than silently re-running until they disappear.
