# Muse Integrator

Captures the Muse session results screen automatically, recovers the Mind graph trace by pixel
analysis, and scores it as an **Integrated Calm Score** — a 0–100 number where **lower is
calmer**, normalised so a 10-minute and a 20-minute session are comparable.

Nothing leaves the device. The app declares no `INTERNET` permission.

---

## Build

The project has no `gradle-wrapper.jar`; CI supplies Gradle instead.

**On GitHub (recommended).** Push this repo; `.github/workflows/build.yml` builds on every push
to `main` and on manual dispatch. Open the run, download the **museIntegrator-debug-apk**
artifact, unzip, and install `app-debug.apk` on the phone. You will need to allow installing
unknown apps for whatever you download it with.

**Locally.** Open in Android Studio (it will offer to generate the wrapper), or:

```
gradle assembleDebug     # needs JDK 17 and an Android SDK with platform 35
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Signing

CI signs with a fixed key restored from the `SIGNING_KEYSTORE_BASE64` repository secret
(Settings → Secrets and variables → Actions). Without that secret each runner generates a
throwaway debug keystore, build N+1 refuses to install over build N, and the only way past the
signature mismatch is an uninstall — which deletes the database and every preserved
`source.png`. The build warns in the log when the secret is missing, and prints the signing
certificate fingerprint on every run so a key change is visible before it becomes a failed
install.

Changing the key later costs one uninstall and the whole history, so it is worth settling
before that history is worth keeping.

## What the app actually does

### Permissions

Nothing is requested at install time. No network, no storage, no camera. You grant exactly two
things by hand, both optional-by-default:

- **Accessibility service** — required for automatic capture. Without it the app still opens
  and shows history, but nothing new is captured.
- **A folder** (Storage Access Framework) — only if you want the external ZIP archive.

### The automatic path, step by step

1. The service receives accessibility events and discards everything whose package is not the
   configured Muse package. It records the last package it saw, so a wrong package name is
   visible in Settings instead of failing silently.
2. 700 ms after the last event it reads the window's accessibility tree and tests a signature:
   some text containing "session", plus "muse points" or both "birds" and "recoveries". If
   that does not match, it stops and reads nothing further.
3. It parses the displayed text: session date and time, duration, Calm %, muse points,
   recoveries, birds, and the three band times.
4. If a session with that exact date-time label is already stored, it stops.
5. If the Mind card is collapsed — detected by the band legend being absent — it finds the
   "Mind" node, walks up to the nearest clickable ancestor, taps it, waits a second and
   re-checks. Three attempts, then it gives up.
6. It computes the graph region: full screen width, from below the "Mind" header to below the
   band legend. If that region is clipped by the screen edge it asks Android to scroll the card
   into view and retries, rather than scoring a truncated graph.
7. It takes a screenshot through `AccessibilityService.takeScreenshot`, rate-limited to one per
   1.2 s, and copies it off the hardware buffer into a software bitmap.
8. **Raw files are written first**, before any analysis: `source.png`, `viewTree.json`,
   `hash.txt`, `metadata.json`.
9. `graph-v1` runs on the bitmap and writes `analysis-graph-v1.json`, `trace-graph-v1.csv`
   and `bounds-graph-v1.json`.
10. Rows go into SQLite; if an archive folder is set, the session's ZIP is written there.
11. A toast shows the result: `Calm Score 20.5  (P25 13.3 / P75 24.2)`.
12. If Powerbands capture is on, it then taps "Brainwave Powerbands" and takes a second
    screenshot, saved unparsed for a future algorithm.

If step 9 throws on an unfamiliar layout, steps 1–8 have already happened, so the session is
recoverable later by a new algorithm rather than lost.

### The manual path

The **Capture Muse** quick-settings tile runs the same sequence on whatever is on screen,
skipping the duplicate check and the auto-capture toggle. It exists because screen detection
against someone else's UI will break eventually.

### Screens

- **Main** — a warning banner if the service is off; the trend chart (score over time, shaded
  interquartile band, tap a point to open that session); then sessions newest first, each
  showing score, date, duration, Calm %, points, birds and P25/P75.
- **Session detail** — the score, the AUC, the reconstructed within-session trace with band
  rules and percentile lines, then a monospace dump of everything Muse reported, every analysis
  version held for that session, and the raw file listing with sizes. An export button shares
  that session's ZIP.
- **Settings** — accessibility shortcut, auto-capture and Powerbands toggles, archive folder and
  re-archive, algorithm status and **Reprocess all history**, and the Muse package field with
  its "last app seen" diagnostic.
- **Import** — pick old screenshots, run the same analyzer, stored tagged as imports.

### What it never does

No network access of any kind. It does not modify the Muse app or its data. It never rewrites
`source.png` once written.

## First run

1. Install and open the app. It will warn that capture is off.
2. **Settings → Open accessibility settings → Muse Integrator → On.**
   On Android 13+ the toggle is greyed out for sideloaded apps. Open App info for Muse
   Integrator, tap the three-dot menu, choose **Allow restricted settings**, then go back.
3. Optionally **Settings → Choose archive folder** and pick a folder. From then on every
   session is written there as its own ZIP.
4. Finish a Muse session. The results screen is detected, the Mind card expanded, the screen
   captured and scored. A toast shows the score.

If Muse ever changes its layout and auto-detection stops firing, add the **Capture Muse**
quick-settings tile and tap it while the results screen is showing.

---

## What gets stored

One directory per session, under the app's private storage:

```
sessions/20260916T050400/
    source.png              lossless, uncropped, never modified
    sourcePowerbands.png    optional second capture, unparsed by v1
    viewTree.json           full accessibility tree
    metadata.json           every number Muse displayed, plus the region of interest
    hash.txt                sha256 + perceptual dHash
    analysis-graph-v1.json  scores, percentiles, warnings
    trace-graph-v1.csv      the recovered trace, with a stroke envelope
    bounds-graph-v1.json    the detected plot geometry
```

`source.png` is written **before** analysis runs, so a session survives even if the analyzer
throws on an unfamiliar layout.

### Adding graph-v2

Implement `GraphAnalyzer` and append it to `GraphAnalyzers.all`. Then **Settings → Reprocess
all history**: every preserved `source.png` is re-scored, new `analysis-graph-v2.*` files are
written beside the v1 ones, and nothing is overwritten or deleted. Both versions show in the
session detail screen so you can compare them.

---

## How the score is computed

The Mind graph is canvas-drawn, so the accessibility tree holds the numbers but not one sample
of the curve. The trace is recovered from pixels:

1. **Calibration.** A three-segment colour bar runs down the left edge of the plot. Its
   boundaries give all four y anchors (top of Active, Active/Neutral, Neutral/Calm, bottom of
   Calm), so calibration never depends on the gridlines rendering. Detection is structural —
   the leftmost narrow column holding three adjacent, mutually distinct, near-equal colour
   plateaus — so it survives a theme change or a different screen resolution.
2. **Gridline masking.** The band gridlines are near-white, close enough to the trace colour
   that a naive luminance threshold picks up two phantom samples in *every* column. Rows that
   span the full plot width are masked.
3. **Trace extraction.** Per column: the centroid of the trace pixels, which linearly
   interpolates a steep transition, plus the min/max as a stroke envelope. Bird glyphs are
   excluded by blue dominance rather than by row position.
4. **Scoring.** Pixel row maps to 0–100 two ways — linear over the plot height, and piecewise
   through the band boundaries. Both are stored. On real screenshots they agree to about 0.2
   points because the bands render as near-equal thirds.

### The normalisation, and why it isn't session duration

Muse plots only the **classified** part of a session. The headband calibration period at the
start is absent from the graph — on the reference session, a 10:00 header covers 543s of
plotted trace. Normalising by the header duration would penalise sessions with a slow setup.

The band legend (`Active 0m`, `Neutral 8s`, `Calm 8m 55s`) sums to exactly the plotted span, so
that sum is the denominator. It is read as text, not estimated from pixels. A useful side
effect: Muse's own "% Calm" is also over classified time — 535/543, not 535/600 — so the
Integrated Calm Score sits on the same time base and the two are directly comparable.

`areaUnderCurve` is the unnormalised integral in score·seconds. `integratedCalmScore` is that
divided by classified time, which makes it a duration-independent mean.

### Known limits

- **Percentile tails drift below 1.0 px/s; the mean does not.** The plot is a fixed pixel width
  for a given screen, so px/second falls as sessions get longer. Decimating a high-resolution
  capture and watching the statistics move gives the real answer:

  | trace columns | px/s | mean error | P95 error |
  |---|---|---|---|
  | 1784 (native) | 2.12 | — | — |
  | 1200 | 1.42 | −0.002 | +0.08 |
  | 981 | 1.16 | −0.003 | +0.12 |
  | 800 | 0.95 | −0.003 | **−0.36** |
  | 600 | 0.71 | −0.007 | **−0.71** |
  | 300 | 0.36 | −0.010 | **−0.99** |
  | 120 | 0.14 | −0.068 | **−1.72** |

  The Integrated Calm Score survives decimation to one column per seven seconds with a
  0.07-point error — it is not resolution-limited in any realistic case. The percentile tails
  start degrading right at 1.0 px/s, exactly where the fixed-width arithmetic predicts. v1
  warns below that. Practically: **capture folded for sessions under 16 minutes, unfolded for
  longer ones** — see the screen-size note below.
- **The centroid slightly flattens extremes.** Reconstructed band membership comes out about
  0.5–1.0 percentage points calmer than Muse's own split (99.5% vs 98.5% on the 10-minute
  session; 89.1% vs 88.4% on the 15-minute one), consistent with mild shrinkage at a boundary
  crossing. The `scoreEnvelopeLow`/`High` columns in the trace CSV preserve what the centroid
  discards, for a v2 algorithm to use.
- **Screen size changes the resolution, barely changes the score.** On a folding phone the same
  session renders at 981 trace columns folded and 1784 unfolded (1.82× horizontally, 1.99×
  vertically, so score quantisation improves from 0.39 to 0.20 points per pixel). Scored side
  by side, the statistics move by 0.56% on the mean and 0.68% at P95. Decimating the unfolded
  trace down to the folded width does **not** reproduce the folded numbers, so that residual
  gap is sub-pixel anchor rounding, not lost information. Capture width is recorded in every
  analysis (`pxPerSecond`, and `plotWidth` in the bounds file) so a change of habit is
  auditable after the fact.

  The corollary: **pinch-to-zoom stitching is not worth building.** The decimation table shows
  the statistics are already flat above about 1.2 px/s, so extra pixels there carry no extra
  information. It would be a lot of fragile UI automation for a sub-0.1-point effect.
- **Imported screenshots are weaker data.** No accessibility tree means no Muse numbers and no
  band-legend denominator, so the AUC scaling is approximate. They are tagged `import`.

---

## Checking the analyzer without building

`tools/validateAnalyzer.py` is a transliteration of `GraphAnalyzerV1.kt` that runs on a
screenshot directly:

```
python3 tools/validateAnalyzer.py screenshot.png 543
```

It prints the detected geometry and the full score set. Two reference sessions, both at
1080×2520:

It prints the detected geometry and the full score set. Three reference captures — two screen
sizes, and the 15-minute session appears in both:

| | 10-min folded | 15-min folded | 15-min unfolded |
|---|---|---|---|
| image | 1080×2520 | 1080×2520 | 1968×2184 |
| classified time | 543 s | 843 s | 843 s |
| bar x | `41..55` | `41..55` | `77..104` |
| anchors | `920/1005/1092/1176` | `1192/1277/1364/1448` | `564/733/906/1073` |
| band heights | 85 / 87 / 84 px | 85 / 87 / 84 px | 169 / 173 / 167 px |
| plot x | `57..1038` | `57..1038` | `106..1890` |
| px/second | 1.807 | 1.164 | 2.116 |
| mean (linear) | **12.774** | **20.546** | **20.661** |
| P5 / P25 / P50 / P75 / P95 | 4.10 / 7.62 / 11.72 / 17.19 / 24.61 | 9.18 / 13.28 / 17.77 / 24.22 / 41.60 | 9.43 / 13.44 / 17.88 / 24.32 / 41.89 |

Nothing is hardcoded to any of this. The card sits 272 px lower on the second capture, and the
third is a different screen entirely at roughly double the pixel density; the structural
detector handles all three unchanged. The 10-minute figures are stable to under 0.01 points
across luminance thresholds from 185 to 230.

Two cross-checks that the metadata parse is sound, both holding on both sessions: Muse's
displayed Calm % is over classified time, not session duration (535/543 → 98%, 745/843 → 88%),
and `duration − classified` is exactly 57 s each time — a fixed headband calibration period
that the graph never plots.

Keep it in step with the Kotlin. If you change one, change the other and re-run it against a
known screenshot before trusting a new build.
