# glpred

[![Roadmap (Kanban Board)](https://img.shields.io/badge/Roadmap-GitHub_Projects-blue?style=flat-square&logo=github)](https://github.com/users/Friedrich-Mueller/projects/4)

Glpred is a small Android app for predicting blood glucose after a meal. Given CGM data and a meal,
it sweeps a range of insulin doses and predicts where each one would leave the user's glucose four
hours later, so the dose that lands in a normal range can be picked.

I had the idea and a proof of concept around 2020, then abandoned it. Calculating a dose the
traditional way is simpler than getting CGM data off a sensor and onto a phone, and the pipeline
demands a precision from the user that a wrong value would punish. The unfinished state bothered me
for years, so I finished it in 2026.

> **It is a personal experiment, not a medical device.** It does not replace on-the-fly correction
> dosing, and no insulin decision should rest on its output alone.

## Table of Contents

- [<ins>The App</ins>](#glpred)
- [<ins>Data and ML</ins>](#data-and-ml)
    - [<ins>Data requirements</ins>](#data-requirements)
    - [<ins>The model</ins>](#the-model)
- [<ins>Build and test</ins>](#build-and-test)
- [<ins>Privacy and legal</ins>](#privacy-and-legal)


## The problem

Part of a diabetic's insulin therapy is the rule of thumb to eat 3 meals a day, one at 8:00 AM, one at 12:00 PM, and one at 6:00 PM. As different times of the day come with different insulin sensitivities, a rule of thumb is to use a Insulin-to-Carbohydrates Ratio(ICR) of 2.0 in the morning, 1.0 around noon, and 1.5 in the evening. 

This rule of thumb is problematic for the following reasons:

- The exact ICR at a given time may vary from patient to patient
- A meal intake at those exact times is not always possible
- When eating a meal at, for example, 9:00 AM, it is unclear whether a linear interpolation between the ICR values of 8am and 12pm is realistic

Additionally, if a patient's blood glucose levels deviate from the healthy target value (usually 90mg/dL), immediately before a meal-related insulin injection, an additional corrective dose is required, which is based on a person's (yet again) individual Insulin Sensitivity Factor(ISF) which can range from 20 to 60mg/dL per insulin unit. A massive range. It can take a patient months to be able to make a rough estimate of their individual ICR and ISF.

Apart from the aforementioned complexity, a patient has to solve the following formula for each meal, which in itself can already be challenging, for example, for children, older adults, or people who simply lack the arithmetics affinity:

```
bolus = carbs / ICR + (glucose - target) / ISF
```

## The solution 

A machine-learning driven approach to not only learn the function of the bolus formula, but also learn the distribution of ICR factors w.r.t. the time of the day.

This approach swats two flies with one swat.

Ultimately, this approach would not just relieve the mathematical and cognitive burden on the patient, but ideally increase the accuracy of the calculated ICR as well as ISF, resulting in an actual net health gain due to the increased efficiency of the insulin therapy.

## How it works

1. **<ins>Record the meal</ins>**

   The user enters date, time, blood glucose, carbs and long-acting insulin. The short-acting dose
   is missing on purpose, because the app is about to propose it.
2. **<ins>A dose is proposed</ins>**

   Every half unit from 1 to 40 is run through the model, and the dose whose predicted four-hour
   glucose lands closest to 90 mg/dL is shown. If nothing lands in the 90 to 140 band, the screen
   says so instead of dressing up the closest miss as advice.

3. **<ins>The injected dose is entered</ins>**

   The app keeps both numbers, the proposed dose and the one actually injected, side by side.

4. **<ins>Import CGM readings and train the model</ins>**

   The LibreView CSV is the only route for glucose data into the app. Importing it pairs the
   recorded meals with what happened afterwards, and fits the model in the same step.

5. **<ins>_Optional_: fix the diary</ins>**

   Entries can be edited or deleted. The model then reports itself as out of date and wants
   retraining; no re-import is needed.

6. **<ins>_Optional_: read the analysis screen</ins>**

   How far each dose landed from 90 mg/dL - the suggested one against the injected one - over the
   whole history and again over the last twenty meals, plus the learned carb ratio across the day.

A meal only counts as training data once an import reaches four hours past it, which is why a
fresh install has nothing to suggest from and says as much. Three labelled meals are required for a 
model to exist. Every suggestion is shown with the model's validation error, because that
number is what says how much the suggestion is worth.

## Benchmarking Model vs User

The model is benchmarked against the user's performance. One graph shows the learned ICR values, and
another shows how the model performs against the user - how far each of the two doses landed from the
target of 90 mg/dL - which indicates when the model begins to outperform the user. That point is where
the model's predictions become more accurate than the user's would have been.
<p align="center">
<img src="images/analysis.png" width="270" alt="Analysis screen with four cards: Your data, Insulin-Carb-Ratio throughout the day, Benchmarking Model vs User with two running-average lines of distance from target over the whole history, and Last 20 with the same two lines rescaled to the recent window">
</p>

## Screenshots

<table>
  <tr>
    <td align="center" width="33%"><img src="images/main.png" width="230" alt="Main menu with New Entry, History, Fetch Data and Train Model and Model Performance Analysis buttons, plus a status line reporting the trained model"><br><sub><b>Main menu</b><br>Record a meal, import and train, look back, or read the evidence</sub></td>
    <td align="center" width="33%"><img src="images/enter-data.png" width="230" alt="Enter data screen with date, time, blood glucose, carbohydrates and long-acting insulin fields"><br><sub><b>Record a meal</b><br>Enter meal data. No short-acting dose yet, that is decided next</sub></td>
    <td align="center" width="33%"><img src="images/review.png" width="230" alt="Review screen: the entry as labelled values, then either a suggested dose in large type with its predicted glucose, or an amber warning that no dose reaches the range; below that the model's accuracy and the disclaimer, and a field for the dose actually injected"><br><sub><b>Review and prediction</b><br>The suggestion, its error, and why it may not be trustworthy</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="images/history.png" width="230" alt="History list of meal cards: date and time with the injected dose on the right, a coloured status dot for trainable or pending, the starting glucose and carbs as two labelled columns, and the predicted against the actual 4 h value"><br><sub><b>History</b><br>Which meals are trainable, what actually happened, and a way to fix a typo</sub></td>
    <td align="center" width="33%"><img src="images/fetch-data.png" width="230" alt="Fetch data and train screen: the file picker, a Stored CGM data card with the first and last reading and 280,142 rows, and a Model card marked out of date above a retrain button"><br><sub><b>Import and train</b><br>The import refits the model; the card says when the fit is out of date</sub></td>
    <td align="center" width="33%"><img src="images/analysis.png" width="230" alt="Analysis screen with four cards. Your data: meals recorded, meals processed and the average distance from target in mg/dL. Insulin-Carb-Ratio throughout the day: labelled axes from 00:00 to 24:00 with the user's doses as purple dots against the model's flat green line. Benchmarking Model vs User: two running-average lines, the model's distance from 90 mg/dL starting high and falling to meet the user's. Last 20: the same two lines rescaled to the recent window"><br><sub><b>Analysis</b><br>ICR by hour, and visual indicator/s for when the model begins to outperform the user</sub></td>
  </tr>
</table>

> Development build on a phone-sized emulator, with synthetic test meals rather than anyone's real
> data.

# Data and ML

A model can only be as good as the meals it learns from, so a few habits around the diary matter
more than anything the code does.

## Data requirements

- **Four clear hours after the meal.** A second meal inside that window mixes two meals into one
  label. Meals need at least four hours between them, and the app cannot detect a violation.
- **Variation in carbs and dose.** Identical meals with identical doses give the fit no contrast to
  attribute an outcome to.
- **Meals across the day.** The model only resolves how the carb ratio changes with the hour where
  entries exist at different hours. It does not require meals at fixed times.
- **Regular imports.** A label comes from imported CGM data, so an un-imported week is an
  untrainable week.
- **One fixed time zone.** Readings carry no UTC offset, so travelling or changing the zone manually 
  between a meal and the import that covers it makes the lookup aim at the wrong hour. Do not switch
  time zones between making an entry and importing its CGM data.

## The model

It is ridge regression, fitted inside the app on retrain: no ML framework, no downloaded model
file, no runtime dependency. Three labelled meals are enough for it to exist, and it starts
suggesting doses from that moment rather than waiting for a sample count it would consider
respectable. The error printed beside a suggestion is a leave-one-out figure - each meal predicted
by a model fitted without it - which is the honest one; the in-sample number shown next to it is
always optimistic.

The model reads a meal as glucose, carbs, bolus and long-acting insulin, plus where in the day the
meal happened. Time enters as a smooth position on the 24-hour circle rather than as
breakfast/lunch/dinner slots, both because 11:50 and 12:10 are the same kind of meal and because
slots would merge 08:00 with 11:50. Carbs and bolus are multiplied by that time signal, which is the
whole idea: the carb ratio is a curve over the day, not a number, so one carb unit may need more
insulin at breakfast than at dinner. Staying with a regression rather than a tree is deliberate too -
asking what would have happened at a dose never taken means extrapolating, and a tree answers that
with its nearest memorised case. A neural net would learn more, but wants hundreds of meals and
cannot explain why it produced its answer.

Linearity has a price: the dose curve is always straight, and a model fitted on thin data can come
out sloping the wrong way. Nothing hides that. The analysis chart draws the fitted curve exactly as
it is, and every suggestion carries the error figure that says how much to trust it. Predictions for
a time of day with no recorded meals are still made, but marked as extrapolation.

# Build and test

## Prerequisites

- **Android 8.0 (API 26) or newer.** Development targets API 35.
- **JDK 21 and the Android SDK.** The sources compile at Java 21. Android Studio bundles both.
- **A FreeStyle Libre 3 sensor and a LibreView account** for the CGM import. The loader reads the
  English CSV export only.
- Android only, no desktop or iOS client.

## Building and running

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The suite covers the CGM import, the preprocessing rules, label generation, the model maths and the
dose sweep. One test cross-checks the Kotlin pipeline against the numbers the original notebook
printed for the same export; it skips when the export is missing, so a fresh clone still passes.

## Project layout

```
app/src/main/java/com/example/glpred/
  data/           Room entities, DAOs and both databases (diary.db, cgm.db)
  preprocessing/  minute series from raw CGM, labels, training samples, history rows
  prediction/     dose contract, ridge model, training runner, dose sweep, LOO, benchmark math
  ui/             Compose screens and the ViewModel driving the entry flow
app/src/test/     JVM unit tests
images/           the screenshots above
IDEA.md           product case and its feasibility limits
PLAN.md           engineering plan, decisions, traps, session notes
AGENTS.md         working rules for this repository
glpred_prototype/ the original notebook and the export it reads
```

Meals are hand-entered into `diary.db`; imported CGM readings live in `cgm.db` and that table is
rebuilt wholesale on every import, which loses nothing because LibreView exports are cumulative.

## Development status

The whole loop works: record a meal, import, let the labels mature, train, predict, keep the
suggestion, then see what actually happened. Until a few dozen real meals exist, the app reports
itself as unvalidated, which is intended behaviour rather than a defect.

Two gaps are worth knowing about: a meal far larger than anything in the diary is extrapolated
without complaint, and the four-hour spacing rule is stated but not checked. Phase status, open
decisions and the measured facts behind the numbers live in [PLAN.md](PLAN.md).

# Privacy and legal

## Privacy

Everything stays on the phone. The app declares no `INTERNET` permission and has no analytics or
crash reporting; the data lives in two local SQLite files. The only thing that leaves the device is
whatever the user exports from LibreView in order to import it.

## Disclaimer

This repository contains no medical advice. The app's output is an experiment on one person's data,
its accuracy is unproven, and insulin errors are dangerous. The product case, including why the
original attempt was abandoned, is in [IDEA.md](IDEA.md).

A missing feature or a broken behaviour belongs in an issue on the [issue tracker](../../issues).
Reports from other people's data are the only way the parts listed as not yet built get built.

## License

MIT, see [LICENSE](LICENSE). The license covers the code, not the claim that the code is fit for
making insulin decisions with.
