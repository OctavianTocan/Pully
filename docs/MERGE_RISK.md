# Merge risk in AI reviews

Enable Merge risk under Experimental in mobile Settings, then run Find problems
to get an experimental merge-risk assessment alongside inline findings. Every lens assesses the whole
change using the same rubric. A review can have no inline defects and still carry
high merge risk. The setting defaults off. Saved older reviews have no assessment
until reviewed again; disabling the setting hides saved assessments as well.

The assessment shows alignment with the description and issue, impact, verification,
rollback cost and error detection. Each category includes a verdict and short linked
evidence or gaps. Missing screenshots or recordings for a user experience change,
or missing profiling for a meaningful performance change, counts as poor verification.
Unavailable context is named as a gap; the reviewer never claims to have run tests.

The rubric is adapted from [Warp's merge-risk skill](https://github.com/warpdotdev/common-skills/blob/main/.agents/skills/merge-risk/SKILL.md),
blob `c8a545a237d7be29c0302d4aba3f1a8b7cfe9851`, credited there to Matt Pocock's
[PR skill](https://github.com/mattpocock/skills/blob/main/skills/engineering/pr/SKILL.md).
It is application code, with no installed skill, new provider or extra AI pass.
The shared review adapter and Rust server read the same `merge-risk-rubric.json`.

Internally, favorable verdicts score 1, intermediate verdicts 2 and unfavorable
verdicts 3. Impact and verification have weight 2, rollback cost 1.5, alignment and
error detection 1. Error detection N/A scores 1. The weighted average is low below
1.5, medium from 1.5 to below 2.25, and high from 2.25. The app computes the level;
the model supplies only the categories. Scores and calculations are not shown in
reviews. Shared fixtures cover both sides of the thresholds in TypeScript and Rust.

The assessment stays with its reviewed head and appears in saved reviews and the
default client-generated GitHub review body only while opted in. The mobile Overview
links a current assessment to its full review. GitHub checks, conflicts and merge permissions keep their own existing
behavior; this assessment does not enable or perform merging.

See [Experimental features](EXPERIMENTS.md) for the opt-in boundaries, usage analytics
and removal procedure.
