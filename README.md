# glpred

## 🗺️ Project Roadmap & Status :
#### [![Roadmap (Kanban Board)](https://img.shields.io/badge/Roadmap-GitHub_Projects-blue?style=flat-square&logo=github)](https://github.com/users/Friedrich-Mueller/projects/4)


Glpred is a small blood glucose prediction android app I quickly scrambled together. Its purpose is, given continuous glucose monitor data, as well as meal, to combine that data and extend it with a range of insulin amount values to predict the future blood glucose levels - allowing find the right insulin amount for the prediction to yield the desired (normal) levels of blood glucose after 3-5 hours.

I came up with its idea and proof of concept around 2020 already, but technical limitations and the way the training data ultimately is sourced by the users themselves, made it unfeasible to finish its development. The main reason being that the complexity of the traditional calculations for required insulin amount is smaller than the complexity of transferring continuous glucose monitor(GCM) data onto your phone, and the meticulosity that is required by the user to feed the data pipeline without mistakes (which would directly translate into medical risks).

Since I was bothered by the unfinished state for years, I decided to finish it in 2026, regardless.

---

> **It is a personal experiment, not a medical device.** It does not replace on-the-fly correction
> dosing with a CGM, and nobody should make an insulin decision on its output alone.

## How it works

1. **Record a meal** — date, time, blood glucose, carb units, long-acting insulin. No short-acting
   dose yet.
2. **Re-import your CGM export** — download the CSV from libreview and import it. That is the only
   way CGM data enters the app.
3. **Labels mature** — an entry becomes a training row once the imported CGM covers the moment
   *4 hours after* it. Until then it is pending, which is normal rather than a failure.
4. **Train** — the app fits a small regression model on all labelled entries.
5. **Predict** — for a new meal it sweeps short-acting doses from 1 to 25 units and suggests the one
   whose predicted 4-hour glucose is closest to 90 mg/dL. The acceptable band is 90–140. If no dose
   reaches the band it says so instead of pretending.
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
figures are reported: **leave-one-out** (each entry predicted by a model fitted without it - the
closer estimate of how it will do on a new meal) and **in-sample** (always optimistic). The gap
between them is the honesty check.

The interaction columns are shrunk ten times harder than the main effects, so with little data the
model behaves like a single constant factor and only develops a time-varying one when the meals
support it. Two unit tests pin both directions: a time-varying carb factor is reproducible, and a
relation with no time dependence stays constant. If your factors stay flat, that is a result rather
than a bug — it means your ratio does not vary enough to be resolved yet. Design notes are in
[PLAN.md](PLAN.md).

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
| Settings (target band, horizon) | deliberately dropped for v1 |
| Unit tests | 28, covering loader parity, preprocessing, model math, LOO, coverage, history states, time interactions, pipeline |

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

## Building and running

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # gradlew needs a JDK
./gradlew assembleDebug
./gradlew installDebug
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The parity test reads `glpred_prototype/MaxMustermann_glucose_20-10-2026.csv` (not in git) and
compares the Kotlin pipeline against the numbers the notebook printed for the same file: 280,142
glucose rows, 245,391 distinct minutes, 244,717 after cleaning, and the 3 h/4 h labels of its five
sample entries. It **skips** when the export is absent, so the suite passes on a fresh clone without
pretending it verified anything.

## Time zone assumptions

This app assumes **one fixed time zone**, and it never stores an offset with a reading. If you fork
it, or travel with it, these three properties are what keep the numbers honest:

1. **Two encodings live side by side.** CGM timestamps are the export's wall clock, stored as if
   they were UTC (`CgmReadingEntity.timestampMs`); diary entries are real epoch instants resolved
   against the device zone (`DiaryEntity.timestamp`). The same moment therefore differs by the UTC
   offset between the two stores. They are reconciled in exactly one function,
   `TrainingSamples.toWallClockMillis`, and every cross-store read must go through it.
2. **The device zone is read at import and at training time.** If it changes in between - travel, a
   manual change, a fork hardcoding a different zone - past diary entries and past CGM labels no
   longer describe the same wall clock, and every label silently shifts by the offset difference.
3. **The daylight-saving fall-back hour is dropped, not guessed.** During it one wall-clock hour
   happens twice and the export carries no offset saying which is which, so those minutes are left
   out of the CGM series rather than assigned to the wrong hour.

The failure mode in all three cases is **not an exception**: it is a plausible glucose value
attributed to the wrong hour, which trains the model on garbage while every screen still renders.
If you change anything about timestamps, change it end to end and re-run
`PreprocessingParityTest` - it pins the cleaning result to an exact minute count.

## Warning

This repository contains no medical advice. The app's output is an experiment on one person's data,
its accuracy is unproven, and insulin errors are dangerous. The product case, including why the
original attempt was abandoned, is in [IDEA.md](IDEA.md).


