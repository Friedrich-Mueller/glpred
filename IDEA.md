Glpred is a small blood glucose prediction android app I quickly scrambled together. Its purpose is, given continuous glucose monitor data, as well as meal, to combine that data and extend it with a range of insulin amount values to predict the future blood glucose levels - allowing find the right insulin amount for the prediction to yield the desired (normal) levels of blood glucose after 3-5 hours.

The user feeds their meal related data into an android application nested SQLite database.

Additionally, the user will have to download their FreeStyle Libre 3 continuous glucose monitor(CGM) data from the libreview website.

The app uses the historical data which was manually recorded with the android app, and scans the historical CGM data for corresponding glucose levels after e.g. 3 hours for each manually recorded data point. Combining the two, a sample with a corresponding (continuous) label is generated.

Now once data has been manually collected (over the span of e.g. a few weeks), the training data can be generated via the aforementioned combination with GCM data, and a regression model can be trained.

Since usually insulin injections are commonly counted in 'units', and are commonly amongst 1 and a relatively small maximum of e.g. 20, a user can input their meal data, and the app will run 20 inferences, with a different value for insulin units, and return the value which yields a regression target within an optimal range, e.g. 90, which is conventionally considered the healthy level of blood glucose.

To reiterate, the user will at the same time both create a new data sample as well as get a prediction on his current meal data. And obviously it will require some yet to be determined amount of data before reliable prediction would be possible.

There might even have to be safety anchors in place, for unseen meal data which falls outside of the observed data distribution.

There are a lot of things to take care of which is why this project is lacking robustness in comparison to conventional approaches to calculating required insulin units.

Although, if this approach is optimized, it should yield far more accurate calculations than conventional methods, which operate under the assumptions of a bunch of 'rules of thumb', would.

For more understanding:

The GCM data holds, among others, records for datetime and glucose level, which are being recorded automatically every 5 minutes (or every 1 minutes if the app is active/opened).

The android app allows the input (and recording via a SQLite database) of 5 values, which together build the input vector in regards to machine learning. Those are date, time, glucose level at the time, consumed carb units at the time, injected insulin at the time, and injected long term insulin (injected once, twice or in some cases three times a day). Using the input vectors date and time values allows association with a later in time glucose level from the GCM data, thereby creating input vector and (regression) label.

For further details look at glpred_feasibility_prototype.ipynb.

The folder 'deprecated' holds and earlier unfinished attempt of implementing this project but did not go further than setting up the diary and diary data input activity and the file picker to import the cgm data from a freestyle libre monitoring device.
