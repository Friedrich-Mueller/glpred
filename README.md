# glpred

## 🗺️ Project Roadmap & Status :
#### [![Roadmap (Kanban Board)](https://img.shields.io/badge/Roadmap-GitHub_Projects-blue?style=flat-square&logo=github)](https://github.com/users/Friedrich-Mueller/projects/4)


Glpred is a small blood glucose prediction android app I quickly scrambled together. Its purpose is, given meal data and insulin amount data, to predict the future blood glucose levels - allowing to adjust the insulin amount until the prediction yields the desired (normal) levels of blood glucose after 3-5 hours.

I came up with its idea and proof of concept around 2020 already, but technical limitations and the way the training data ultimately is sourced by the users themselves, made it unfeasible to finish its development. The main reason being that the complexity of the traditional calculations for required insulin amount is outweighed by the complexity of transferring continuous glucose monitor(GCM) data onto your phone, and the meticulosity that is required by the user to feed the data pipeline without mistakes which would directly translate into medical risks.

Since I was bothered by the unfinished state for years, I decided to finish it, regardless.