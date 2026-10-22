## glpred backbone prototype

This is the backbone prototype for an android application that utilizes machine learning to estimate for a given meal, the corresponding glucose levels after 3, 4 and 5 hours.

The user feeds their meal related data into an android application nested SQLite database.

Additionally, the user will have to download their FreeStyle Libre 3 continuous glucose monitor(CGM) data from the [libreview](https://www2.libreview.com/) website.

This backbone uses the historical data which was manually recorded with the android app, and scans the historical CGM data for corresponding glucose levels after e.g. 3 hours for each manually recorded data point. Combining the two, a sample with a corresponding (continuous) label is generated.

Now once data has been manually collected (over the span of e.g. a few weeks), the training data can be generated via the aforementioned combination with GCM data, and a regression model can be trained.

Since usually insulin injections are commonly counted in 'units', and are commonly amongst 1 and a relatively small maximum of e.g. 20, a user can input their meal data, and the app will run 20 inferences, with a different value for insulin units, and return the value which yields a regression target within an optimal range, e.g. 90, which is conventionally considered the healthy level of blood glucose.

To reiterate, the user will at the same time both create a new data sample as well as get a prediction on his current meal data. And obviously it will require some yet to be determined amount of data before reliable prediction would be possible.

There might even have to be safety anchors in place, for unseen meal data which falls outside of the observed data distribution.

<strong>There are a lot of things to take care of which is why this project is lacking robustness in comparison to conventional approaches to calculating required insulin units. 

Although, if this approach is optimized, it should yield far more accurate calculations than conventional methods, which operate under the assumptions of a bunch of 'rules of thumb', would.</strong>

For more understanding:

The GCM data holds, among others, records for datetime and glucose level, which are being recorded automatically every 5 minutes (or every 1 minutes if the app is active/opened).

The android app allows the input (and recording via a SQLite database) of 5 values, which together build the input vector in regards to machine learning. Those are date, time, glucose level at the time, consumed carb units at the time, injected insulin at the time,  and injected long term insulin (injected once, twice or in some cases three times a day). Using the input vectors date and time values allows association with a later in time glucose level from the GCM data, thereby creating input vector and (regression) label.

## Disclaimer

This software is an experimental engineering prototype and academic exploration of individualized ($N=1$) predictive modeling. It is not a certified medical device, has not been evaluated or approved by any regulatory agency such as the Food and Drug Administration or equivalent international bodies, and is not intended for clinical diagnosis, treatment, or automated medical decision-making.

The algorithms contained within this repository—including regression-based glucose forecasting and automated dosage estimation—are exploratory research artifacts. Relying on algorithmic outputs for real-world insulin dosing introduces severe clinical hazards, including life-threatening hypoglycemia, severe hyperglycemia, and associated metabolic complications. Data entry errors, continuous glucose monitor telemetry drift, or out-of-distribution predictions can cause the underlying model to output dangerously incorrect recommendations.

Users must exclusively rely on established, clinically validated protocols, traditional deterministic calculation methods, and professional medical oversight provided by licensed healthcare practitioners for all diabetes management and insulin dosing decisions. The author and contributors assume no legal responsibility, liability, or obligation for any direct or indirect health consequences, injuries, or adverse medical events resulting from the deployment, testing, modification, or use of this codebase.