# PLAN

Execution plan for glpred. Last revised 01-11-2026.

This file is mutable: phase status, phase order and open decisions change as work
proceeds. The product case and its feasibility limits live in [IDEA.md](IDEA.md);
measured facts and lessons learned live in the "Session notes" section at the end of
this file. Re-derive this plan before trusting it - a stale phase list is
worse than none.

## Where the app stands

| Piece | State |
|---|---|
| Diary entry (date, time, glucose, carbs, basis) + persistence | done: `DiaryEntity`, `DiaryDao`, `DiaryViewModel`, Enter screen |
| CGM import from the LibreView CSV | done: replaces the table, ~280k rows in ~0.6 s; the stored span is reported on the fetch screen |
| Navigation / main menu | done: `main`, `enter_data`, `confirm_data`, `final_confirm`, `fetch_data` |
| Entry flow: no insulin on input, review + prediction, then the dose actually injected | done, running against `ModelDoseAdvisor` |
| Dose contract (`prediction/DoseAdvisor.kt`) | done: sweep 1..25 in 0.5 steps, `Available` / `Unavailable` |
| Suggestion persistence (`suggestedBolus`, `predictedGlucose` on the entry) | done, diary schema v2 |
| CGM -> 1-minute grid (dedupe, DST, interpolation) | done: `preprocessing/CgmSeries.kt`, parity-verified |
| Label generation (3 h + 4 h lookahead) | done: `LabelGenerator`; 5 h dropped, 4 h is primary |
| Feature vector + scaling | done: 10 features with time interactions, `TrainingSample.featuresOf` |
| Model training + persistence (the implementation behind `DoseAdvisor`) | done: `RidgeModel` + `model_table` |
| Honest accuracy figure | done: `LeaveOneOut`, shown on the main screen next to the in-sample error |
| Time-coverage guard, persistent disclaimer | done (Phase 5, 31-10-2026) |
| History list with pending/labelled state | done (Phase 4, 31-10-2026) |
| History edit/delete any of the six fields, with staleness flag | done (01-11-2026): `HistoryScreen` dialog, `DataVersion` |
| Analysis screen: carb ratio by hour, model-vs-user verdicts | done (01-11-2026), see the session note - three scorings were tried and two rejected |
| Settings (target band, horizon) | deliberately dropped for v1: the band 90-140 and the 4 h primary horizon are settled |
| Unit tests | 39 in `app/src/test`, all passing |

## The constraint that shapes the whole design

A label is the CGM value N hours after a diary entry, but CGM data enters the app
only through a manual CSV import. So an entry cannot be trained on until both
(a) N hours have passed and (b) a later CSV covering that time has been imported.

The real user loop is therefore: record meals -> days later re-import the export ->
labels mature -> retrain -> predict. The app needs an explicit pending/labelled
distinction per diary entry, or it will silently train on nothing.

## Decisions taken (31-10-2026)

- Target: land as close to 90 mg/dL as possible; the acceptable band is 90-140.
- Dose selection: among swept doses whose predicted 3 h glucose is in the band, pick the
  one closest to 90 (equivalently, the lowest dose that qualifies - minimum effective
  dose is the safe insulin convention). If none qualifies, show the closest miss plus an
  explicit "no dose brings you into range" warning and do not present it as advice.
- Sweep 1..25 units in **0.5-unit steps** (49 evaluations), constants in `DoseAdvisor.kt`.
  25 because nobody injects more per meal than that; half units because doses are fractional.
- Insulin is a fractional value (`Double`) everywhere - injected amount, suggestion, and
  the stored column - so 0.5-unit pens need no rebuild.
- No backfilling: the app is used immediately at a meal, never to reconstruct yesterday.
  Entries are therefore always "now" and prediction always applies.
- The suggestion and its predicted glucose are stored on the entry, not just shown.
- **No sample-count gate.** The advisor predicts as soon as a model exists, however few
  labelled samples it was trained on and however wrong the number is. A withheld prediction
  teaches nothing; a wrong one is a data point for the model-vs-user comparison. The UI's job
  is to show the model's validation error and sample count next to the suggestion, not to hide
  it.

## Model design (decided 31-10-2026)

**Model class: ridge regression, solved in closed form.** Not because it is fashionable, but
because at tens of rows it is the only family that behaves sanely, because the dose sweep is a
*counterfactual* query (it asks what a dose would do when that dose was never taken, i.e.
extrapolation), and because the coefficients are readable numbers the user can be shown.

- Trees/forests are rejected: piecewise-constant output goes flat outside the observed dose range,
  so the sweep becomes arbitrary exactly where it matters. They also memorise at this sample size
  (the prototype's tree predicted the same constant for every input, RMSE 128.5).
- Neural nets are rejected for now: they would learn the interactions implicitly, but they need
  hundreds of rows, extrapolate unpredictably, and their factors are not inspectable. Revisit only
  under the switch criteria below.
- Linearity also guarantees what physics requires: prediction falls monotonically as the dose rises
  across the whole 1..25 sweep. `PipelineTest` asserts that.

**Feature vector (10):** `glucose, carbs, bolus, basis, time_sin, time_cos` plus the four
interactions `carbs*sin, carbs*cos, bolus*sin, bolus*cos`. The 6-feature additive vector that
shipped first was a stepping stone and is superseded: it can express "glucose tends to be higher at
breakfast" but not "a carb unit needs more insulin at breakfast", which is the product thesis.

**Time dependence is continuous, never meal slots (decided 31-10-2026).** Meals happen at
arbitrary times by design (flexible intensified insulin therapy), so slot dummies
(breakfast/lunch/dinner) are wrong: they split 11:50 from 12:10 and merge 08:00 with 11:50. Instead
interact the dose terms with the existing cyclical features:

    glucose_4h ~ ... + carbs*(c0 + c1*sin t + c2*cos t) + bolus*(b0 + b1*sin t + b2*cos t) + ...

i.e. the carb factor and insulin sensitivity are smooth curves over the 24-hour circle. This is
better than slots for arbitrary meal times because neighbouring hours borrow strength from each
other, and it stays linear, monotone and interpretable (the factor curve can be plotted and shown).

**No A/B model gate (decided 31-10-2026).** The interactions are the model, not an upgrade that has
to earn its place: a constant carb factor is known to be wrong, so shipping the additive form and
waiting for a comparison would mean shipping a model the user already knows is misspecified. The
variance risk is handled inside the fit instead - interaction columns carry
`lambda * INTERACTION_PENALTY_FACTOR` (10x), so the solve starts close to additive and only bends
toward a time-varying factor when the rows support it. `TimeInteractedFeatureTest` pins both
directions.

LOO keeps a job, but a different one: it reports honest accuracy in the UI and shows when the time
dependence has become resolvable (interaction coefficients leaving ~0).

**Two constraints the upgrade creates:**

- *Time coverage.* Entries will cluster where the user eats; night hours will be unconstrained and
  the model will extrapolate into them happily. If an entry's time of day has no training support
  within a couple of hours, warn or refuse rather than print a number.
- *Meal spacing precondition.* The 4 h label is clean only if no second meal was eaten inside that
  window. A spacing discipline of at least four hours between meals is what makes the primary
  horizon sound; entries that violate it should be flagged or excluded, because their labels mix
  two meals.

## Known traps (fail silently, no exception, plausible-looking output)

- **Two timestamp encodings.** `CgmReadingEntity.timestampMs` holds the export's wall clock read
  *as if it were UTC*; `DiaryEntity.timestamp` holds a real epoch instant resolved against the
  device zone. The same moment therefore differs by the UTC offset (+2 h in summer, +1 h in
  winter) between the two stores:

      "15-01-2026 23:54"  CGM store    -> 1768521240000  (wall clock as UTC)
      15-01-2026 23:54 CET diary      -> 1768517640000  (true instant, 1 h earlier in winter)

  **Query the CGM series only through `TrainingSamples.toWallClockMillis(entryTimestamp)`.** A
  direct `series.valueAt(entry.timestamp + 4h)` compiles, runs, and returns a plausible glucose
  value from the *wrong hour* - silently corrupting every training row and every label. Any new
  code that reads both stores must reuse that one conversion function; it is the only place the
  boundary is crossed. The offset shifts with DST, so bugs of this kind change behaviour twice a
  year rather than failing outright.
- Related: wall-clock encoding is deliberate on the CGM side (the export has no offset, and the
  DST fall-back hour is only detectable while the ambiguity is preserved), so do not "simplify"
  it into epoch millis.

## Judging the model against the user (settled 01-11-2026)

The saved `suggestedBolus` / `predictedGlucose` exist for one purpose: to compare the model's
advice with the dose actually taken. Three implementations were tried; the first two were
deleted rather than kept alongside.

1. **Followed-vs-not in-band tallies** (31-10-2026, removed). Splits meals by whether the injected
   dose matched the suggestion, counts band hits per group. Rejected: the groups are self-selected,
   since the user decides when to follow, so it measures confidence as much as accuracy - and the
   two denominators are not comparable (in-band over all meals vs over failures only).
2. **Direction test** (01-11-2026, removed with its 10 tests). Assumption-free:
   `sign(actual-90) == sign(suggested-injected)`. Rejected for two reasons. It decays exactly as the
   user complies - agreement leaves nothing to disagree about - and "no correction offered" sat in
   the denominator, so following the model drove its score toward 0 % while the model was working.
   It also scored only direction, never magnitude, and a model that always says "more" scores well
   in someone who under-doses.
3. **Projected counterfactual** (shipped). Mealtime dosing reduces to
   `G4 = G0 + ISF*f*carbs - ISF*B`, so changing only the dose moves the outcome by `ISF * delta` and
   the carb side cancels: ISF alone is needed. Per meal it comes from refitting on the other meals
   and reading `d(prediction)/d(bolus)` by finite difference, so time-of-day arrives through the
   interaction columns. The verdict is `P(model's dose landed closer)`, integrated over the belief
   in ISF rather than evaluated at one value.

Two constraints on that third design, both deliberate:

- **No clinical constant is supplied, anywhere.** A clinician-supplied rule of thumb was offered
  as a prior and refused: the app's purpose is to make rules of thumb redundant, so it starts from
  nothing and measures. The consequence is visible and intended - five seeded meals yield a
  sensitivity estimate whose error bar swamps its mean, and the card says so instead of printing a
  verdict.
- **Scoring is gated on `mean > sd`.** While the yardstick is smaller than its own error bar,
  comparing two doses compares noise; every meal then reads undecided and no streak can grow. A
  metric that manufactures confidence from noise is worse than no metric.

Still unsolved, and structural: the ISF used to judge a meal comes from the model, so a systematic
bias in the bolus coefficient biases every projection the same way. Only an externally measured
value would break that, and taking one is exactly what this app declines to do.

## Phases

0. Hygiene: verify pushes, add `app/src/test` with JUnit (dependency change, needs
   approval), decide on `deprecated/`.
1. Preprocessing + labels + parity harness (highest risk, do first):
   `CgmPreprocessor` (1-min grid), `LabelGenerator` (nearest within +/-10 min at
   +N h), `TrainingSampleRepository` (pending vs labelled). Verify the Kotlin port
   against the notebook on the prototype CSV: 280,142 glucose rows, 245,391 anchors,
   and the five known label values of the notebook's cell 8.
2. Features + model: `glucose, carbs, bolus, basis, time_sin, time_cos` exactly as in
   `pp_ml`; standardize + ridge regression solved in closed form in Kotlin (no TFLite
   needed at this size); persist coefficients, scaler stats, RMSE and sample count.
3. Real advisor: implement `DoseAdvisor` over the trained model, replacing
   `UnavailableDoseAdvisor` in `DiaryViewModel`. It answers whenever a model exists - no
   sample-count gate - and reports `Available(inRange = false)` when no swept dose reaches
   the band. `Unavailable` is reserved for "no model at all".
4. Remaining UI: history list with pending/labelled badges, settings for the target band,
   horizon and data status; show validation RMSE and sample count beside any suggestion.
5. Safety and honesty: out-of-distribution guard, persistent disclaimer, accuracy shown
   rather than implied, never present a dose as medical advice.
6. Verification and docs: unit tests for loader, preprocessing rules, label parity,
   model math and the dose sweep; update README status.

## Next step

No phase is open. The app implements the whole loop: record -> import -> labels mature -> train ->
predict -> store the suggestion -> review what happened.

What remains is not code:

- Data. Four labelled meals produce a first validation figure; roughly forty spread across the day
  make the time-varying factors resolvable at all - and, since 01-11-2026, also make the learned
  insulin sensitivity larger than its own error bar, which is the precondition for the model-vs-user
  card to decide anything. Until then the app reports itself as unvalidated, which is the intended
  behaviour rather than a defect.
- The carb factor curve is now displayed (01-11-2026); there is nothing to read on it until real
  meals exist.
- Open, unrelated: whether `IDEA.md` / `PLAN.md` / `AGENTS.md` should stay gitignored. Still
  undecided, still untouched.

## Deferred to the final stage

Behaviour first, appearance later. Recorded here so these do not get lost and do not get started
half-way:

**Visual / layout**

- History cards: three to four 13.sp lines each is dense; needs grouping, spacing and hierarchy.
- The status "badge" is a coloured `Text`, not a chip. Measured on the emulator (01.11.2026):
  "Pending - re-import your CGM export" renders in `error` red and is clearly legible, but
  "Pending - 4 h not elapsed yet" uses `onSurfaceVariant`, i.e. grey on the grey card, and reads as
  a caption rather than a state. All three states should read as badges at a glance.
- App-wide pass: typography scale, spacing rhythm, colour semantics, dark-theme check.
- The analysis screens (factor curve, model vs user) should be designed as one surface, not two.

**Functional**

- Model staleness covers edits and deletes only (via `DataVersion`, 01-11-2026). Recording a new
  meal or importing more CGM leaves the stored model in use, deliberately for now: the model is not
  wrong, only not yet retrained, and flagging every entry would teach the user to ignore the flag.
  Revisit once retraining is cheap enough to run automatically.
- `diary.db` uses destructive migration, so the first schema change after real use begins would
  silently delete the meals. A written migration must exist before then, not after.

---

# Session notes (append-only; every entry states the artifact a number came from)

## 31-10-2026 - CGM import, timestamp encoding, dataset statistics

Dataset facts, measured on the prototype export in `glpred_prototype/`
(~30 MB, about 13 months of readings, 21 columns):

- 312,981 data rows; 280,142 carry a glucose value (111,111 historic, 169,031 scan);
  32,839 are LibreView record type 6 and carry none. This matches the notebook's
  `Filtered shape: (280142, 2)` exactly.
- 245,391 distinct minute-timestamps; 34,383 carry more than one row. 33,472 are a
  historic plus a scan reading in the same minute, and 31,422 of those differ in value
  (median gap 3 mg/dL, p90 10, p99 18; max 90 outside the DST hour). The notebook's
  comment "no row has both filled, so this is a relabel" is true per row but false per
  timestamp - the per-minute mean is what actually blends the two streams, as the
  notebook's own next comment admits.
- DST fall-back on 26-10-2025: hour 02 holds 84 rows against 12-24 on normal days, and
  the per-minute values split into two interleaved series ~100 mg/dL apart. That hour is
  two real hours under one set of wall-clock labels and is unrecoverable from the file.
  Gaps of ~100 mg/dL there are not measurement disagreement.
- The export writes `dd-MM-yyyy HH:mm` with no UTC offset. Sorting that text with SQL
  `ORDER BY` sorts by day-of-month first and silently scrambles history. Fixed by
  storing wall-clock millis (wall clock read as if UTC), which stays decodable so the
  ambiguous hour can still be detected later.
- Exports are cumulative: every export checked (three, months apart) starts at the oldest
  reading the sensor ever recorded. Replacing the whole table on import
  therefore loses nothing - which is why the import clears the table instead of merging.
- English and German exports both exist (`Gerätezeitstempel`, `Glukosewert-Verlauf
  mg/dL`). `CgmCsvLoader` matches English header names only, so a German export is
  rejected with "not a LibreView glucose export".

Preprocessing rules agreed for the label pipeline:

- drop minute-buckets whose spread exceeds 15 mg/dL (658 minutes; no coverage cost),
  average the remainder;
- drop the DST-overlap hour detected via `ZoneRules.getValidOffsets(lt).size > 1`
  rather than a hardcoded date list;
- build a 1-minute grid, interpolate linearly across gaps of at most 14 minutes, never
  extrapolate beyond the first/last real value;
- labels by nearest value within +/-10 minutes at +3/4/5 h (`merge_asof` equivalent).

Performance, same artifact:

- Import of 280k rows went from 14 s to 0.6 s. The old cost was not data volume but
  ~50 us/row of per-call overhead (one `executeInsert()` each: JNI hop, statement reset,
  bind, step). Now 562 multi-row INSERTs of 499 rows inside one transaction, integer
  timestamps instead of per-row strings, and the timestamp index dropped and rebuilt
  around the insert.
- The app's own `PERF` logcat line still splits parse from write; read it before
  optimising further.

Model reality check (notebook, 5 samples): linear regression RMSE 79.1, ridge 78.7,
decision tree 128.5 mg/dL against labels 112 / 151 / 173.6. Nothing predictive yet -
sample count is the binding constraint, hence the minimum-sample gate before any dose
recommendation is shown.

Naming and schema decisions: `DiaryDatabase` / `diary.db` / version 1;
`CgmDatabase` / `cgm.db` / version 5 with destructive migration; `CgmReadingEntity`
(name kept - "Reading" is the domain information, `CgmEntity` would not be);
`timestampMs` deliberately not unique. `CgmDao` exposes `getAllReadings()` and
`getReadingsBetween(fromMs, toMs)`.

Environment: `JAVA_HOME` is unset on the host, so `./gradlew` needs
`export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`; adb lives at
`AppData/Local/Android/Sdk/platform-tools/adb.exe`; `emulator-5554` is usually running.
`AGENTS.md` governs this repo: per-file approval, no build or dependency changes
without explicit approval.

## 31-10-2026 - Pipeline complete: labels, model, interactions, LOO, guards

Artifacts: `app/src/test` (18 tests, all passing) via `./gradlew :app:testDebugUnitTest
assembleDebug`.

- Feature vector is now 10 columns. The four `carbs*sin, carbs*cos, bolus*sin, bolus*cos`
  interactions make the carb factor a function of time of day - continuous time, never meal slots.
  They carry `lambda * INTERACTION_PENALTY_FACTOR` (10x), so the fit degrades to additive rather
  than fitting noise. `TimeInteractedFeatureTest` proves both directions: a breakfast meal of 8
  carb units predicts >5 mg/dL above the same meal at dinner, and a relation with no time
  dependence keeps the two within 3 mg/dL.
- Accuracy is reported as leave-one-out: each row predicted by a model fitted on the others. This
  is what makes comparing variants possible at all, since in-sample error always improves as
  parameters are added. `LeaveOneOutTest` pins that a deliberately overfitted fit (5 rows, 6
  features, lambda ~0) shows a tiny in-sample error and a LOO error an order of magnitude larger.
- LOO needs 4 labelled rows before a number exists (every fold must still leave MIN_SAMPLES = 3);
  below that the UI says "validation needs one more labelled entry" rather than inventing one.
- The model row is read back at startup, so the status line no longer claims "not trained yet"
  after a restart while the advisor is busy using the stored model. LOO is not persisted, so a
  restored model reports in-sample only until the next Train press.
- Time-coverage guard: training records which hours of the day have support, in SharedPreferences
  rather than as a new Room column - a schema bump would destroy `diary.db` through the configured
  destructive migration. An entry more than an hour away from every recorded meal is still
  predicted (a withheld prediction teaches nothing) but is flagged as an extrapolation.
- The fetch screen reports the stored span (first reading, last reading, row count) from one
  aggregate query. CGM values are wall-clock-as-UTC, so that summary formats in UTC deliberately -
  using the device zone would shift the reported span by the current UTC offset.
- Remaining: the history list (Phase 4). Settings were dropped for v1: band 90-140 and the 4 h
  primary horizon are settled.

## 31-10-2026 - History list, closing Phase 4

Artifacts: `app/src/test` (28 tests, all passing) via `./gradlew :app:testDebugUnitTest
assembleDebug`.

- `preprocessing/MealHistory.kt` classifies each entry into TRAINABLE / TOO_EARLY / NEEDS_IMPORT.
  The two pending reasons are kept apart because "wait four hours" and "re-import your export" are
  different instructions, and conflating them is how a working app reads as broken.
- The classification goes through `TrainingSamples.toWallClockMillis` and
  `LabelGenerator.labelsFor` - the same two functions training uses. A history screen that computed
  the 4 h value its own way would eventually disagree with training about which meals are labelled,
  and the disagreement would be silent. `MealHistoryTest` pins the three states.
- The screen shows, per meal: label state, starting glucose/carbs/basis, injected dose beside the
  suggested one, and the 4 h prediction beside the actual value with the absolute error.
- Cost: the history flow re-reads all CGM rows on every diary change (a few hundred ms at 280k
  rows, on Dispatchers.IO) instead of caching a series that a new import would silently invalidate.
  If that becomes noticeable the cache needs invalidation on import, not a shorter timeout.
- One collision worth remembering: `formatUnits` already exists as `internal` in
  `FinalConfirmScreen.kt`, so a second private copy in the same package is an overload ambiguity.
  The history screen now reuses the existing one.

## 31-10-2026 - One join for labels, prediction shown for the injected dose

Artifacts: `app/src/test` (29 tests, all passing) via `./gradlew :app:testDebugUnitTest
assembleDebug`.

- `MealHistory.build` now consumes `TrainingSamples.build` instead of calling
  `toWallClockMillis` and `LabelGenerator.labelsFor` itself. There is exactly one diary-to-CGM join
  in the codebase, so "trainable" means by construction "present in the training set with a 4 h
  label". `trainableMealsAreExactlyTheLabelledTrainingRows` asserts the two counts agree, which
  fails if anyone forks the lookup again.
- The prediction displayed beside the actual 4 h value is recomputed for the **injected** dose. The
  stored `predictedGlucose` was produced for the *suggested* dose, so comparing it with the outcome
  of a different dose compared two different counterfactuals - the number looked plausible and
  measured the wrong thing.
- No error figure, no signed direction, no verdict on the history screen. Judging the model belongs
  in one analysis place, with the factor curve and the "followed the suggestion vs not" statistics,
  not sprinkled through the log.
- The predict function is passed into `MealHistory.build` as a lambda, so `preprocessing` keeps no
  dependency on `prediction` (which already depends on `preprocessing`).
- Caching the CGM series stays out of scope: a sub-second rebuild when the history screen opens is
  fine for one user and a few dozen entries, and a cache would need invalidation on import and
  would be actively harmful if stale. If it ever feels slow, measure it before optimising it.

## 01-11-2026 - Analysis screen: two curves, and two scorings thrown away

Artifacts: `app/src/test` (39 tests, all passing) via `./gradlew :app:testDebugUnitTest
assembleDebug`; `images/analysis.png` at 540 px captured from `emulator-5554`.

- The carb-ratio card is two curves over 24 h on shared, labelled axes. The model's line is
  continuous because it is the model's own answer for a reference meal at each hour - the median of
  the median of the user's meals - not the stored suggestions, which only exist where the user ate,
  and not
  coefficient arithmetic, which would mean dividing standardised coefficients by feature scales.
- With five seeded meals that line is flat at 0.22 u/CU, sitting on the axis. Not a rendering bug:
  no swept dose reaches the band, so the advisor returns the smallest dose at every hour. Expected
  behaviour of an unvalidated model, and the reason the axes were added - without them the line
  reads as a baseline rather than a value.
- Two scorings were written, shipped, and deleted within the same day (reasons in the section
  above, "Judging the model against the user"). What replaced them projects the counterfactual
  through ISF alone. Measured on the seeded data: `2 +/- 5 mg/dL per unit from one fold`, which the
  card prints verbatim - the honest output of five meals, not a malfunction.
- A test forced the hypo floor. With a plain quadratic loss, a suggestion projecting to 0 mg/dL
  scored 24,300 against 25,600 for landing at 250, so a severe overdose won the meal. Loss is now
  squared distance from 90, tripled below 90, plus a fixed penalty below 70.
- Test count moved 36 -> 39: the direction test's 10 removed, 7 counterfactual tests added, the
  four outcome-bucket tests removed with `OutcomeStats`.
- Colours carry one meaning across the screen: green is the model, purple is the user, grey is a
  meal that decided nothing. Red was removed - it collided with the chart, where green already meant
  the model.
- Two defects the screenshots caught, both fixed before publishing: the main-screen status line ran
  edge to edge and printed `in-sample RMSE 0` (which is what an in-sample fit reports when rows
  equal parameters, i.e. nothing), and the analysis card announced "not the model's meal" when in
  fact every meal was undecided.
