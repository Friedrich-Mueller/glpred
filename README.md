# glpred

## Roadmap

[![Roadmap (Kanban Board)](https://img.shields.io/badge/Roadmap-GitHub_Projects-blue?style=flat-square&logo=github)](https://github.com/users/Friedrich-Mueller/projects/4)

Glpred is a small blood glucose prediction app for Android I quickly scrambled together. Given
continuous glucose monitor (CGM) data and a meal, it tries a range of insulin doses and predicts
where each one would leave your blood glucose three to five hours later, so you can pick the dose
that lands in a normal range.

I had the idea and a proof of concept around 2020 and then abandoned it. Calculating a dose the
traditional way is simpler than getting CGM data off a sensor and onto a phone, and the data
pipeline demands a precision from the user that a wrong value would punish. That combination made
it unfeasible at the time.

Being bothered by the unfinished state for years, I decided to finish it in 2026 regardless.

---

> **It is a personal experiment, not a medical device.** It does not replace on-the-fly correction
> dosing with a CGM, and nobody should make an insulin decision on its output alone.

## The problem it solves

Insulin need is not constant across a day. The ratio that covers breakfast is usually not the ratio
that covers lunch, and a clinic can record only a handful of values for you — typically three, one
for the morning, one for noon, one for the evening.

That leaves the hours between them undefined. What ratio applies at 10:00, halfway between the
morning and noon values? You can interpolate linearly, assume some curve, or reuse the nearer of the
two — but whichever you pick, it is your guess rather than a measurement, and you apply the same
guess every day until the next appointment adjusts the blocks.

The arithmetic itself is then trivial:

```
bolus = carbs / ICR + (glucose - target) / ISF
```

with those two block-assigned numbers, the insulin-to-carb ratio and the insulin sensitivity factor,
as its only personal terms.

Glpred estimates both as continuous functions of time of day, from your own meals and doses. The
shape is not assumed: between your morning and noon meals the model can produce a straight line, a
curve, or nothing at all — and with few meals it shrinks toward a single constant rather than
inventing a shape it cannot support. An hour with no meals near it produces a warning instead of a
number.

For a new meal it then sweeps candidate doses through the fitted prediction and suggests the one
whose predicted 4-hour glucose lands closest to the target.

So it differs from the arithmetic in two ways: where the factors come from, and how finely they are
described — three values per day with the gaps guessed, against an estimate for every hour that
knows which meals it was built from. That is why it can in principle dose better than the rule of
thumb, and why the analysis screen reports whether it actually has. At a few dozen meals the
estimates are still dominated by uncertainty and a well-tuned rule of thumb will win; the app prints
its error beside every suggestion rather than hiding it.

## Screenshots

<table>
  <tr>
    <td align="center" width="33%"><img src="images/main.png" width="230" alt="Main menu with New Entry, Fetch Data, History, Model Performance Analysis and Train Model buttons, plus a status line reporting the trained model"><br><sub><b>Main menu</b><br>Train, and see how much the model can be trusted</sub></td>
    <td align="center" width="33%"><img src="images/enter-data.png" width="230" alt="Enter data screen with date, time, blood glucose, carbohydrates and long-acting insulin fields"><br><sub><b>Record a meal</b><br>No short-acting dose yet — that is decided next</sub></td>
    <td align="center" width="33%"><img src="images/review.png" width="230" alt="Review screen: the entry as labelled values, then either a suggested dose in large type with its predicted glucose, or an amber warning that no dose reaches the range; below that the model's accuracy and the disclaimer, and a field for the dose actually injected"><br><sub><b>Review &amp; prediction</b><br>The suggestion, its error, and why it may not be trustworthy</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="images/history.png" width="230" alt="History list of meal cards: date and time with the injected dose on the right, a coloured status dot for trainable or pending, the starting glucose and carbs as two labelled columns, and the predicted against the actual 4 h value"><br><sub><b>History</b><br>Which meals are trainable, and what actually happened</sub></td>
    <td align="center" width="33%"><img src="images/fetch-data.png" width="230" alt="Fetch data screen showing the stored CGM span from first to last reading and the total row count"><br><sub><b>Import CGM data</b><br>File picker, reports the time span the import covers</sub></td>
    <td align="center" width="33%"><img src="images/analysis.png" width="230" alt="Analysis screen with three cards. Your data: 8 meals recorded, 5 carry a suggestion, 5 have a verified 4 h value, average misprediction 33 mg/dL. Insulin-Carb-Ratio throughout the day: a chart with labelled axes from 00:00 to 24:00, the user's doses as purple dots against the model's flat green line. Model vs User performance: a strip of coloured squares, green for the model, purple for the user, grey for undecided"><br><sub><b>Analysis</b><br>ICR by hour, and indicator for when the model begins to outperform the user</sub></td>
  </tr>
</table>

> These are screenshots of a development build on a phone-sized emulator, with synthetic test meals
> rather than anyone's real data. The model in them has five samples, so its error is large, no swept
> dose reaches the band, and the extrapolation warning is visible — that is what a model that young
> looks like.

## How it works

1. **Record a meal** — date, time, blood glucose, carb units, long-acting insulin. No short-acting
   dose yet.
2. **Re-import your CGM export** — download the CSV from LibreView and import it. That is the only
   way CGM data enters the app. Only English-language exports are parsed; an export from a German
   account is rejected.
3. **Labels mature** — an entry becomes a training row once the imported CGM covers the moment
   *4 hours after* it. Until then it is pending, which is normal rather than a failure.
4. **Train** — the app fits a small regression model on all labelled entries.
5. **Predict** — for a new meal it sweeps short-acting doses from 1 to 25 units in half-unit steps
   and suggests the one whose predicted 4-hour glucose is closest to 90 mg/dL. The acceptable band
   is 90–140. If no dose reaches the band it says so instead of pretending.
6. **Record what you actually injected** — the suggestion is stored next to it, so the model can
   later be judged against your own judgement.

The loop only advances when steps 1, 2 and 4 happen. A week of meals with no CGM import produces no
training data at all.

## What makes an entry useful

- **At least 4 hours between meals.** The 4-hour label is clean only if no second meal happened
  inside that window; closer meals mix two meals into one label.
- **Vary the amounts.** Entries that always use the same carbs and the same dose teach the model
  nothing — it needs to see what changed when the meal or the dose changed.
- **Eat at different times of day.** The model estimates how insulin need varies across the day, so
  it needs meals spread over the day. There is no requirement to eat at fixed times.
- **Import regularly.** Labels come from the CGM export, so an un-imported week is an untrainable
  week.

## The model

A **ridge regression**, solved in closed form inside the app — no framework, no model file, no
runtime dependency. Features: starting glucose, carbs, dose, long-acting insulin, time of day
encoded as a point on a 24-hour circle (`sin`/`cos`), and four interaction columns
(`carbs*sin`, `carbs*cos`, `bolus*sin`, `bolus*cos`) that let the carb factor and the insulin
sensitivity vary across the day rather than being constants.

Why not something bigger: with tens of rows a forest or a neural net fits noise and reports it as
insight, tree-based models answer counterfactual dose questions with a flat constant, and a linear
model is guaranteed to predict *less* glucose for *more* insulin across the whole sweep. Two error
figures are reported: **leave-one-out** (each entry predicted by a model fitted without it — the
closer estimate of how it will do on a new meal) and **in-sample** (always optimistic). The gap
between them is the honesty check.

The interaction columns are shrunk ten times harder than the main effects, so with little data the
model behaves like a single constant factor and only develops a time-varying one when the meals
support it. If your factors stay flat, that is a result rather than a bug — it means your ratio does
not vary enough to be resolved yet. Design notes are in [PLAN.md](PLAN.md).

## Status

| Area | State |
|---|---|
| Diary entry and persistence | done |
| CGM CSV import (≈280k rows in ≈0.6 s) | done |
| Stored-data summary (span from/to, row count) | done |
| Preprocessing: minute series, DST handling, interpolation | done |
| 3 h and 4 h labels | done |
| Ridge model with time-interacted factors, persistence, dose sweep | done |
| Entry flow: enter → review + prediction → final confirm | done |
| Train action, model status, model restored after restart | done |
| Honest accuracy figure (leave-one-out) | done |
| Time-coverage warning for unobserved hours + in-app disclaimer | done |
| History list with pending/labelled state, suggestion vs injected, prediction vs actual | done |
| Settings (target band, horizon) | not in v1 — the band is fixed at 90–140 mg/dL and the horizon at 4 h |
| Unit tests | 39, covering loader parity, preprocessing, model math, LOO, coverage, history states, time interactions, ratio derivation, counterfactual scoring, pipeline |

Everything described above is implemented. A meal is only a training row once the imported CGM
covers the moment four hours after it; the history screen shows exactly which meals have reached
that point and which are still waiting on an import.

## Project layout

```
app/src/main/java/com/example/glpred/
  data/           Room entities, DAOs and both databases (diary.db, cgm.db)
  preprocessing/  minute series from raw CGM, labels, training samples
  prediction/     dose contract, ridge model, training runner, dose sweep
  ui/             Compose screens and the ViewModel driving the entry flow
app/src/test/     JVM unit tests
IDEA.md           product case and its feasibility limits
PLAN.md           engineering plan, decisions, traps, session notes
AGENTS.md         working rules for this repository
glpred_prototype/ the original notebook and the export it reads
```

Two databases on purpose: `diary.db` holds hand-entered meals (schema v3), `cgm.db` holds the
imported CGM table and is rebuilt wholesale on every import.

## Prerequisites

- **Android 8.0 (API 26) or newer.** Development targets API 35.
- **A FreeStyle Libre 3 sensor and a LibreView account**, if you want to import CGM data. The loader
  reads LibreView's English CSV export; nothing else is supported yet.
- **A JDK 17 and the Android SDK** to build. Android Studio bundles both.
- Android only. There is no desktop or iOS client.

## Building and running

```bash
./gradlew assembleDebug
./gradlew installDebug
```

If `./gradlew` fails with *"JAVA_HOME is not set"*, point `JAVA_HOME` at a JDK first — on Windows,
the one inside Android Studio works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The parity test reads the export in `glpred_prototype/` (not in git) and
compares the Kotlin pipeline against the numbers the notebook printed for the same file: 280,142
glucose rows, 245,391 distinct minutes, 244,717 after cleaning, and the 3 h/4 h labels of its five
sample entries. It **skips** when the export is absent, so a fresh clone passes with that test
skipped.

## Time zone assumptions

This app assumes **one fixed time zone**, and it never stores an offset with a reading. If you fork
it, or travel with it, these three properties are what keep the numbers honest:

1. **Two encodings live side by side.** CGM timestamps are the export's wall clock, stored as if
   they were UTC (`CgmReadingEntity.timestampMs`); diary entries are real epoch instants resolved
   against the device zone (`DiaryEntity.timestamp`). The same moment therefore differs by the UTC
   offset between the two stores. They are reconciled in exactly one function,
   `TrainingSamples.toWallClockMillis`, and every cross-store read must go through it.
2. **The device zone is read at import and at training time.** If it changes in between — travel, a
   manual change, a fork hardcoding a different zone — past diary entries and past CGM labels no
   longer describe the same wall clock, and every label silently shifts by the offset difference.
3. **The daylight-saving fall-back hour is dropped, not guessed.** During it one wall-clock hour
   happens twice and the export carries no offset saying which is which, so those minutes are left
   out of the CGM series rather than assigned to the wrong hour.

The failure mode in all three cases is **not an exception**: it is a plausible glucose value
attributed to the wrong hour, which trains the model on garbage while every screen still renders.
If you change anything about timestamps, change it end to end and re-run
`PreprocessingParityTest` — it pins the cleaning result to an exact minute count.

## Warning

This repository contains no medical advice. The app's output is an experiment on one person's data,
its accuracy is unproven, and insulin errors are dangerous. The product case, including why the
original attempt was abandoned, is in [IDEA.md](IDEA.md).

## License

MIT — see [LICENSE](LICENSE). The license covers the code, not the claim that the code is fit for
making insulin decisions with.


