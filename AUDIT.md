# AUDIT — release readiness, glpred

Audit date: 05.11.2026 · HEAD `05c47b5` (this file is new and untracked) ·
Device: emulator-5554, Android 16 (API 37), `sdk_gphone64_x86_64`

Status legend: **REPRODUCED** (observed on the device, evidence stated) · **INSPECTION**
(established from source, not triggered at runtime) · **HYPOTHESIS** (plausible, unproven).

Yardstick for “correct”: behaviour was judged against the decisions in `PLAN.md` (4 h horizon,
49 evaluations over 1-25 units in 0.5 steps, 3 labelled meals minimum, no sample-count gate, no
settings screen). Those deliberately supersede the original sketch in `IDEA.md` (3-5 h lookahead,
about 20 inferences over 1-20 units). Measured against `IDEA.md` alone the dose sweep and the horizon
would look wrong; they match the decision record, so they are not findings.

Nothing in this file has been fixed. No source file, Gradle file, database or app data was modified by
the audit, and no diary entry was saved. The only write is this document.

---

## 1. Environment and what was actually executed

| Step | Command | Result |
|---|---|---|
| Build | `./gradlew --offline assembleDebug testDebugUnitTest` | **BUILD SUCCESSFUL in 4s**; both tasks executed |
| Install | `adb install -r app-debug.apk` | `Success`, existing app data preserved |
| Launch | `am start -n com.example.glpred/.MainActivity` | started, crash buffer empty |
| UI walk | `adb shell input` + `uiautomator dump` | main, enter, date picker, review (§3) |
| Crash buffer | `adb logcat -b crash -d` | **empty for the whole session** |
| Release build | `:app:assembleRelease` | `BUILD SUCCESSFUL` → **`app-release-unsigned.apk`**, 6.97 MB, **zero signature entries in `META-INF`** |
| Lint | `:app:lintDebug` | **0 errors, 29 warnings** |
| Lint (release gate) | `:app:lintVitalRelease` | `BUILD SUCCESSFUL` |
| Unit tests | `:app:testDebugUnitTest` | **10 suites, 46 tests, 0 failures, 0 errors, 0 skipped** |
| Not run | instrumentation tests | none exist in the project |

Tests, read from `app/build/test-results/testDebugUnitTest/*.xml` (not inferred from the task summary):
`DiaryCsvTest` 4, `DoseBenchmarkTest` 10, `LeaveOneOutTest` 4, `ModelVsHumanTest` 3, `PipelineTest` 3,
`PredictionCoverageTest` 5, `RidgeModelTest` 5, `TimeInteractedFeatureTest` 2, `MealHistoryTest` 6,
`PreprocessingParityTest` 4 — **46 tests, all green**, which independently confirms the figure
`PLAN.md` claims. Their *assertion quality* is still unassessed: the classes were not read line by line,
and two of them (`PipelineTest`, `PreprocessingParityTest`) are the ones `PLAN.md` itself flags as
partly self-confirming or export-dependent.

Lint breakdown of the 29 warnings: `GradleDependency` 17, `UseTomlInstead` 8,
`AndroidGradlePluginVersion` 3, `MissingApplicationIcon` 1. **No finding in the two files edited on
05.11.2026** except three pre-existing `AutoboxingStateCreation` information items
(`DiaryViewModel.kt:66, :69, :240`).

Build caveat, measured 05.11.2026: immediately after editing two Kotlin files, `:app:compileDebugKotlin`
reported **UP-TO-DATE** and `BUILD SUCCESSFUL` in 4 s - the incremental compile had not seen the new
sources (Gradle file-system watching missed writes made by another process). A rebuild with
`-Dorg.gradle.vfs.watch=false --rerun-tasks` took 14 s, and the four new symbols were then confirmed in
`classes.dex`. **Any green build claimed in this repository must be checked against the artifact, not the
task summary** - otherwise a stale APK passes as a verified fix.

Device state at audit time: model restored from storage, **65 labelled samples**, model reported
unvalidated. That is consistent with the design — leave-one-out error is not persisted
(`PLAN.md`, 31-10-2026) — and is not counted as a defect.

---

## 2. Findings

Severity follows the audit brief. Ordered by severity, not by discovery.

### H1 — The release build cannot be produced or distributed — INSPECTION, CONFIRMED BY BUILD

`app/build.gradle.kts` declares no `buildTypes`, `signingConfigs`, `isMinifyEnabled` or
`isShrinkResources`. Consequences: `assembleRelease` emits an **unsigned** APK (cannot be installed or
published), R8 is off (no shrinking or obfuscation, no keep-rules file), and `versionCode` /
`versionName` are frozen at `1` / `1.0`.

*Confirmed 05.11.2026:* `:app:assembleRelease` succeeds and writes `app-release-unsigned.apk`, whose
`META-INF` contains **no** certificate entries - the artifact exists but cannot be installed. R8 never
runs, so the debug-priority logging of L1 also ships.

*Fix:* a `release` build type with `isMinifyEnabled = true` plus Room keep-rules, and a signing config
(upload key or Play App Signing). Verify with `assembleRelease` and `lintVitalRelease`. Already in the
`PLAN.md` backlog; it is the hard blocker for any release verdict.

### H2 — Any future schema change silently deletes the diary — INSPECTION

`data/DiaryDatabase.kt:8` sets `exportSchema = false` (the database is now **version 3**) and `:26`
applies `.fallbackToDestructiveMigration()`. `diary_table` holds the only non-reproducible data in the
app: meals are hand-entered, whereas CGM re-imports in about 0.6 s. The next `version` bump destroys them
with no dialog, no backup and no path back. `model_table` lives in the same file, so the bump also costs
the trained model.

*Fix before real use:* `exportSchema = true` plus a `schemas/` directory (a build-config change, needing
approval), a real `Migration(3,4)`, and no `fallbackToDestructiveMigration()` on the release path.
Interim mitigation already shipped: CSV export from the History top bar.

### M1 — The entry screen silently replaces the meal's date and time — **REPRODUCED**

`ui/EnterDataScreen.kt:51-52`

```
var selectedDate by remember { mutableStateOf(LocalDate.now()) }
var selectedTime by remember { mutableStateOf(LocalTime.now()) }
```

`remember`, not `rememberSaveable`, and not seeded from the ViewModel — unlike the three text fields
at `:54-56`, which are seeded from `viewModel.tempGlucose` / `tempCarbs` / `tempBasis`.

**Reproduction, observed on emulator-5554:**

1. New Entry → the screen shows `05.11.2026`, `15:05`.
2. Date picker → `01.11.2026`; enter `120 / 4 / 10`.
3. Review Entry → the review screen correctly shows `Date 01.11.2026`, `Time 15:05`.
4. Back (or "Edit Entry") → the fields now read **`05.11.2026`** and **`15:06`**, while
   `120 / 4 / 10` survived.

*Impact:* correcting a typo in the dose silently moves the meal to *now*. The entry is then saved with a
different `timestamp` than the review screen displayed, and that timestamp decides which CGM value
becomes the 4 h label and where in the 24-hour cycle the meal sits. The phase is
`hour * 60 + minute`, so minutes count and seconds are truncated - a 20-minute drift is 5 degrees of
the cycle, fed into all four interaction columns. Silent, plausible output
— the failure class this repository treats as the dangerous one.

*Root cause:* two of the three draft fields are hoisted into `DiaryViewModel` and therefore survive the
destination change; the two date/time controls are not hoisted at all, so popping back to `enter_data`
re-runs `remember` and re-seeds them from `LocalDate.now()` / `LocalTime.now()`. The screen then shows a
“chosen” time the app never kept.

*Two coherent fixes, not interchangeable — this needs a decision, not a patch:*

- **(b) Record *now* only** — remove the date and time pickers from the entry screen (or make them
  read-only) so the row always carries the moment of entry. This is what `PLAN.md:62` already decided:
  “No backfilling: the app is used immediately at a meal, never to reconstruct yesterday. Entries are
  therefore always ‘now’.” It removes the control that lies and is the smaller diff. Cost: logging a
  meal five minutes late becomes impossible.
- **(a) Hoist the choice** — carry a nullable `LocalDateTime` in the draft state, seed the pickers from
  it, update it on every picker change, reset it in `clearDraft()`. This is what the current UI promises:
  the user picks a time and it is kept. Cost: more state to thread, and it contradicts the no-backfilling
  decision unless that decision is revised.

Either way the invariant to restore is: **the date and time shown on the review screen are the date and
time stored.** Storing formatted strings in the ViewModel (rather than a `LocalDateTime`) is what makes
fix (a) awkward — they would need re-parsing on every recomposition.

**Status 05.11.2026 - FIXED AND VERIFIED.** Decision (a) implemented in `EnterDataScreen.kt` and
`DiaryViewModel.kt`: the pickers seed from `tempDate` / `tempTime` and write back on every pick. Re-ran
the reproduction on the freshly built APK - date picker to `01.11.2026`, fill `120 / 4 / 10`, Review,
system back - and the form now returns with **`01.11.2026` / `15:33`** intact instead of today's clock.
Build: `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green; the new symbols
(`discardDraft`, `setDraftDate`, `setDraftTime`, `toDraftDateOrNull`) verified present in `classes.dex`.
Rotation is covered by the same write-back but was **not** exercised on the device.

### M2 — The extrapolation guard counts hours that never trained the model — INSPECTION

`prediction/TrainingRunner.kt:120` computes `coveredHours = PredictionCoverage.hoursFrom(samples)`,
where `samples` is *all* entries. It should be the labelled subset (`TrainingSamples.labelled(samples)`,
i.e. `RidgeModel.fit`'s `usable`). An entry with no 4 h label still marks its hour as covered, so
`DiaryViewModel.withCoverageWarning` can stay silent for an hour where the model has **zero** training
rows — the one situation the guard exists to flag. The error points in the unsafe direction (guard
too permissive) and is invisible: the warning simply does not appear.

*Fix:* pass the labelled list; add a test asserting that an unlabelled meal's hour is not "covered".

*Why it outranks the other one-line fixes:* `IDEA.md:15` names this guard as a product requirement —
“There might even have to be safety anchors in place, for unseen meal data which falls outside of the
observed data distribution.” M2 makes that anchor under-report, so the app can present an extrapolated
dose with no warning precisely where the product case says it must be flagged. The test should pin the
requirement (an unlabelled meal does not create coverage), not merely the call site.

### M3 — Entry values are parsed, never sanity-checked — INSPECTION

`ui/DiaryViewModel.kt:260-274` (new entry) and `:478-485` (history edit): glucose only has to parse and
be above 0 — **no upper bound**; carbs and basis "≥ 0" with no ceiling; bolus is capped only at
100.

`PLAN.md` (03-11-2026) records that `120111 mg/dL`, a basis of `105` units and a 25 U suggestion were
**accepted and stored**, and that the response was `clearDraft()` — that is a different fix for a
different bug. A fat-fingered value still reaches the diary, the training set and every later prediction.
The History editor is a second, independent door to the same hole.

*Fix* (deferred by decision, but the cheapest correctness win left): one shared bounds validator —
glucose e.g. 20-600, carbs 0-300, basis 0-200, bolus 0-50 — used by both paths.

### M4 — A late failure leaves the Review button dead with no message — INSPECTION

`ui/DiaryViewModel.kt:286-298`: `runCatching` wraps only `doseAdvisor.recommend`.
`withCoverageWarning(...)` and `onReady()` sit outside it, and `isPredicting` is cleared only on the
success path. An exception there leaves `isPredicting = true`, `recommendation = null`, the user still on
the entry screen, and the button silently inert — no Toast, no error state.

The same shape recurs in `refreshAnalysis()` (`:156-179`, where `isAnalysisLoading` is never reset on a
throw, leaving the screen permanently "loading") and in the `history` mapping (`:122-144`, where a throw
cancels the collection and `isHistoryLoading` stays true).

*Fix:* `try/finally` clearing the in-flight flags, and surface the failure as a message rather than a
dead control.

### M5 — Health data is included in Android cloud backup — INSPECTION

`app/src/main/AndroidManifest.xml:5` sets `android:allowBackup="true"` with no `fullBackupContent` and
no `dataExtractionRules`. Both `diary.db` and `cgm.db` are therefore eligible for Auto Backup to the
user's Google account. `README.md` states "Everything stays on the phone" — true about the network,
not about backup.

*Fix:* `allowBackup="false"`, or backup rules that exclude both databases.

### M6 — Double-tapping "Confirm & Save Entry" can insert two meals — INSPECTION (not reproduced)

`ui/FinalConfirmScreen.kt:103-115` calls `DiaryViewModel.confirmAndSaveEntry` (`:339-363`): there is no
in-flight flag, the insert runs in `viewModelScope`, and navigation happens only after the coroutine
runs. `PLAN.md` records one attempt where navigation won the race. Two inserts create two identical
meals, both becoming training rows with the same label — and afterwards indistinguishable from a
real repeat meal.

*Fix:* a save-once flag, or disable the button while a save is in flight. One line, and it also covers a
slow insert on a low-end device.

### M7 — The review screen is not scrollable; its buttons sit at the screen edge — INSPECTION

`ui/ConfirmDataScreen.kt:62-233` is a `fillMaxSize()` `Column` with **no** `verticalScroll`. Measured on
emulator-5554 (1080 px wide, roughly 2400 px tall): `Continue` at y ≈ 2038-2091 and `Edit Entry` at
y ≈ 2206-2259. On a shorter screen (1080x1920 is common) or at a larger system font, both buttons
fall below the fold and the user is trapped — no way forward and no way back except the system bar.
The same pattern appears in `EnterDataScreen` and `FinalConfirmScreen`.

*Fix:* `verticalScroll` on the content column, or — cleaner — a fixed bottom action bar outside
the scrolling region, since the `Spacer(weight(1f))` at `:211` fights a scroll modifier.

### M8 — An abandoned draft pre-fills the next entry — **REPRODUCED**

`ui/EnterDataScreen.kt:54-56` seeds the three text fields from the ViewModel, but
`DiaryViewModel.clearDraft()` runs only after a successful save (`:361`, now `:379-393`). Leaving the
form without saving therefore keeps the draft, and the next New Entry opens with the previous meal's
values already typed in.

**Reproduction, observed on emulator-5554:** with `120 / 4 / 10` entered, back to the main menu, then
New Entry again → `Glucose 120`, `Carbs 4`, `Basis 10` were pre-filled; only the date and time had
been reset.

*Impact:* the same class as the 03-11-2026 incident recorded in `PLAN.md`, where typing into a
pre-filled form produced `120111 mg/dL`. Combined with M1 it meant a stale **date** could also be
carried into the next meal, which relocates the 4 h label - the more damaging of the two.

*Fix:* `DiaryViewModel.discardDraft()` (a named way into `clearDraft()`), called from a single exit path
in the entry screen covering **both** the toolbar arrow and `BackHandler` - otherwise the system back
gesture, which is the usual one on modern Android, would still leak the draft.

**Status 05.11.2026 - FIXED AND VERIFIED.** System back from the form now reaches the main menu with
the draft discarded, and the next New Entry opened with **empty** glucose, carbs and basis at today's
date; repeating the cycle left it empty again. Crash buffer empty throughout.

### L1 — `Log.d` ships in release — INSPECTION

`ui/FetchDataViewModel.kt:141` logs import timing. The line carries no personal data (row count,
milliseconds), but it is debug output in a shipping build and disappears only once H1's R8 is enabled.

### L2 — The History editor reports success it has not observed — INSPECTION

`ui/HistoryScreen.kt:330-341` closes the dialog when `onSave` returns `null`, but
`DiaryViewModel.updateEntry` (`:459-507`) validates synchronously, then launches the write and **always**
returns `null` afterwards (`:506`). A failing `@Update` (disk full, cancelled scope) is invisible and the
row silently keeps its old values. Low impact today, but the UI states a fact it did not check.

### L3 — No launcher icon — INSPECTION

`app/src/main/res/` contains only `values/strings.xml`, and the manifest declares no `android:icon`. The
app therefore shows the platform default icon in the launcher and the app switcher. Corroborated
independently by `lintDebug`: `MissingApplicationIcon` is one of the 29 warnings.

### L4 — Long-acting insulin cannot be fractional — INSPECTION

`data/DiaryEntity.kt:17` declares `basis: Int` while `bolus: Double`. Defensible in practice, since basal
insulin is titrated in whole units, and listed only because the field label says "units" while `10.5` is
rejected with "whole number of units" rather than being explained.

### I1 — Informational, already known

- `ModelDoseAdvisor.kt:111` re-declares the bolus feature index as the literal `2` instead of
  `TrainingSample.BOLUS_INDEX` (already in the `PLAN.md` backlog).
- `DoseSlopes` has no test, and no removal path for deleted entries (`prediction/DoseSlopes.kt:26-29`,
  documented there).
- `:app` has **no `androidTest` source set**: the Compose flows, both Room DAOs, the import path, the
  SAF export and the SharedPreferences-backed stores have no automated coverage at all.
- `UnavailableDoseAdvisor` (`prediction/DoseAdvisor.kt:84`) is now dead code, and its doc comment still
  describes the app as being pre-model.

---

## 3. Verified as working (do not re-audit these)

- **Launch and main screen** — four buttons and one status line, no crash. The status read
  `Model: 65 samples, restored from storage - the validation error is recomputed at the next import`,
  which matches the documented design that leave-one-out error is not persisted.
- **Enter screen** — date and time pickers open; 24 h `HH:mm`; correct month length (November shown
  with 30 days); typed values land in their fields.
- **Validation** — blank glucose, carbs or basis is rejected with a Toast; `120 / 4 / 10` is
  accepted.
- **Prediction path** — `2.5 units suggested`, `Predicted after 4 h 100 mg/dL`, `Predicted after
  3 h 120 mg/dL`, in-band styling (no "no dose brings you into range" banner), and **the extrapolation
  warning fired correctly**: "No recorded meal within an hour of this time of day". The model-quality
  line rendered: `Model: 65 samples - accuracy not validated yet`.
- **Disclaimer** — present unconditionally on the review card, whatever the model claims.
- **Navigation** — main → enter → review → back to enter; back-stack correct, no
  duplicated destinations.
- **Crash buffer** — empty across every interaction above.
- **Timestamp boundary** — `TrainingSamples.toWallClockMillis` is the only epoch/wall-clock
  crossing; `MealHistory`, `TrainingRunner` and the analysis path all reuse it, and no second
  implementation exists.
- **Dose sweep** — 49 evaluations, band membership first then nearest to 90, closest-miss path with
  `inRange = false`, integer stepping. Read and found correct.
- **Import pipeline** — whole-table replace inside one transaction, index dropped and rebuilt around
  the insert, statements closed in `finally`, empty CSV rejected, and both parse and write failures
  surfaced.
- **`MealHistory`** consumes `TrainingSamples.build`, so "trainable" cannot drift from "labelled row".
- **Ridge solve** — centring, per-column penalties, partial pivoting, singular → `null` →
  `Failed`.
- **Security surface** — no `INTERNET` permission, no analytics, no exported component other than
  the launcher activity, SAF for both import and export (no storage permissions). `allowBackup` is the
  only exposure (M5).

---

## 4. Not verified — the honest gaps

| Area | Why |
|---|---|
| Import of a real LibreView CSV | Overwrites 280 000 stored readings — needs permission |
| "Retrain on stored data" | Overwrites the only model — needs permission |
| Saving an entry, double-tap (M6) | Would pollute the diary — the flow was stopped at the review screen |
| History screen, edit dialog, delete, CSV export | Not opened this session |
| Analysis screen, charts, empty and partial states | Not opened this session |
| Rotation, backgrounding, process death, back-stack under stress | Not exercised |
| Release build, R8, `lintDebug`, `lintVitalRelease` | Not run |
| Small-screen / large-font layout (M7) | Only one tall emulator was available |
| Instrumented tests | None exist in the project |

---

## 5. Ordered remediation plan

Blockers first. Each item is a separate approval.

1. ~~**M1** — decide (a) hoist the date/time into `DiaryViewModel`, or (b) record *now* only and
   drop the pickers; then make the review screen and the stored row agree.~~ **DONE 05.11.2026**
   — decision (a), with **M8** closed in the same change; verified on emulator-5554.
2. **H2** — `exportSchema = true`, schema directory, `Migration(3,4)`, drop the destructive
   migration.
3. **H1** — release build type: minify plus keep-rules plus signing; then `assembleRelease` and
   `lintVitalRelease`.
4. **M2** — pass the labelled samples to `PredictionCoverage.hoursFrom`; test the `IDEA.md:15`
5. **M3** — one shared bounds validator for the entry form and the History editor.
6. **M4** — `try/finally` around the in-flight flags in `validateAndStartReview`,
   `refreshAnalysis` and the history mapping.
7. **M5** — `allowBackup="false"`, or backup rules excluding both databases.
8. **M7** — scrollable content or a fixed bottom action bar on the three form screens.
9. **M6** — save-once guard. **L2** — return a result from `updateEntry` and report a failed
   write.
10. **L1** and **L3** — release logging and a launcher icon, as part of H1.

After items 1-3, re-run `assembleDebug`, `testDebugUnitTest`, `lintDebug` and `assembleRelease`, and
repeat the M1 reproduction on the emulator. After items 4-9, add the missing `androidTest` coverage for
the entry flow, the DAOs and the import, since none exists today.

---

## 6. Verdict

**NOT READY.**

Three independent reasons, each sufficient on its own:

1. The release artifact cannot be produced (H1).
2. The only irreplaceable data can be destroyed by an app update (H2).
3. A reproduced logic defect stores a different meal time than the app displays (M1), and no
   instrumentation test has ever exercised the UI.

The mathematics, the timestamp boundary and the dose sweep are in good shape. The weaknesses are in the
Android shell around them — release configuration, state hoisting, in-flight flags and input bounds
— which matches `PLAN.md`'s own deferral list.

That is also consistent with the product case's own admission (`IDEA.md:17`) that the project “lacks
robustness in comparison to conventional approaches”. This audit does not contradict the admission; it
localises it — the weak parts are the Android shell and the release wiring, not the arithmetic.
