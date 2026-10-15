// Where Pulls' sounds part from Cuelume's. Both generators apply these over the package's own themes, so
// `recipes.json` and the reference renders that `CuelumeFidelityTest` holds the port to always agree, and
// regenerating from the package never brings a replaced recipe back.
//
// default.tap: Cuelume's tap rings glass (two sines at 1174.66 and 1180 Hz that beat, over a mode at 3242 Hz),
// and at the subtle emphasis most buttons play, the glass is all that is left. Tavi hates that ping and wants it
// used for nothing (feedback of 15 Oct 2026, J3 and J16). This tap is dry, in the family of the default select
// and toggle he likes: a switch's click over a small knock, a nail's tick and a wooden body added at normal, and
// a deeper knock with a thock at strong. Nothing in it rings: every layer is gone within about 70 ms.

/** The recipes Pulls replaces, built with Cuelume's own builders: `{ theme: { cue: recipe } }`. */
export function pullsRecipes({ click, knock }) {
  return {
    default: {
      tap: {
        masterGain: 0.3,
        layers: [
          ...click(2300, 0.13),
          knock(370, 0.024, 0.05),
          { from: 'normal', kind: 'noise', filterType: 'bandpass', filterFrequency: 4500, filterQ: 1.2, attack: 0.001, decay: 0.00242, peak: 0.03 },
          { from: 'normal', kind: 'noise', filterType: 'bandpass', filterFrequency: 620, filterQ: 2.5, attack: 0.001, decay: 0.016, peak: 0.12 },
          knock(185, 0.036, 0.025, { from: 'strong' }),
          { from: 'strong', kind: 'noise', filterType: 'bandpass', filterFrequency: 150, filterQ: 2, attack: 0.002, decay: 0.032, peak: 0.3 },
        ],
        vary: { pitch: 0.012, level: 0.12 },
      },
    },
  }
}

/** The package's themes with Pulls' recipes laid over them, and the ids replaced (`default.tap`). */
export function withPulls(themes, builders) {
  const merged = Object.fromEntries(Object.entries(themes).map(([theme, cues]) => [theme, { ...cues }]))
  const replaced = []
  for (const [theme, cues] of Object.entries(pullsRecipes(builders))) {
    for (const [cue, recipe] of Object.entries(cues)) {
      merged[theme][cue] = recipe
      replaced.push(`${theme}.${cue}`)
    }
  }
  return { themes: merged, replaced }
}
