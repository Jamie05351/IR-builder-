# J_DSP IR Builder

A Windows desktop program (Kotlin + Compose Desktop) that designs **phase-only Low/Mid/High
correction IRs** for [J_DSP](https://github.com/Jamie05351/SiphonDSP_J)'s per-band convolver,
from measurements taken at both front seats.

Each band's IR has unity magnitude: tone stays with J_DSP's PEQ. Near the Low/Mid and Mid/High
crossovers, each band's phase is adjusted so the software bands add coherently at the driver's and
passenger's seats. Outside those regions the IRs are a pure common delay.

## Why the bands are measured through the whole car

J_DSP sums its software bands back into two channels before the BMW amplifier splits them to the
physical speakers. Separate software IRs therefore do not create independent speaker outputs. The
program models what is really there: for each seat, mic position and channel, the acoustic response
of each software branch through the complete system, summed as complex responses. It never adds a
crossover and never assumes six independent outputs.

## Four tools

The app opens on a start screen with four independent tools. All begin with importing and checking
measurements; none runs another.

- **Band Align** (Measurements → Checks → Band Align): J_DSP's Band Align, extended. Per side, the
  Low delay/polarity against Mid and the High delay/polarity against Mid that make the bands add up
  best at both seats, scored by sum loss (below), never making a seat worse and only for at least
  0.2 dB less average loss; the polarity flips only if that wins by 0.5 dB. Result: a list of
  settings to enter on J_DSP's DELAY page. No added latency.
- **Phase IRs** (Measurements → Checks → Design → Preview → Export): phase-only Low/Mid/High FIRs for
  the convolver's Per-band mode, designed on the measurements as taken. Each crossover is corrected
  per channel where both of its bands were measured; a channel, crossover or band without data is
  left as a pure delay, so one channel or Low+Mid only works. All three WAVs are always exported.

- **Crossover Finder** (Measurements → Checks → Crossover Finder): which J_DSP crossover to use.
  For each crossover it searches the frequency and slope (BW1/BW2/BW3/LR4/BW4) of both sides, with
  each side's delay and polarity, for the bands to add up best at both seats. It reuses the normal
  band-solo sweeps: J_DSP's current filter is divided out exactly (the same filter maths as J_DSP's
  engine, no auto-pairing) and each candidate put in its place. A candidate may not lift a band
  where its sweep is more than 45 dB down or wasn't swept, beyond what stays 30 dB down, so it never
  leans on data the sweeps can't vouch for. Candidates are scored against a flat-sum target (the
  bands summed perfectly in phase) over the crossover region, dips counted double, per seat; none
  may make a seat worse, and the winner must gain at least 0.5 dB. Frequencies move in 1/12-octave
  steps up to an octave, inside per-filter limits and allowed slopes you set (the tool can't know
  what a speaker can safely take; by default half an octave, and a highpass no shallower than now).
  Result: crossover, delay and polarity settings to enter in J_DSP, with seat plots and the
  next-best options.

- **EQ Wizard** (Measurements → Checks → EQ Wizard): J_DSP Parametric EQ bands (peaking, J_DSP's
  own biquad) that bring each side's response, averaged over both seats, toward a house curve (a
  bass lift and a treble tilt). Cuts are free; boosts are capped and never exceed what the
  octave-wide response lacks, so narrow dips (cancellation) aren't filled. Per side or one set for
  both. Exports Equalizer APO text (`input_eq_wizard.txt`) that J_DSP's Parametric EQ imports into
  Pre EQ; append it, since it corrects the tune you measured.

### Shared measurement handling

- **Impulse responses with a frequency-dependent window** (REW › Export › Impulse response as text,
  or a WAV): each frequency is read through a window 8 cycles long (off/6/8/15) from the direct
  sound, so the cabin's reflections stay out above a few hundred hertz and the phase is the
  speakers'. REW frequency/SPL/phase text is still read, but can't be windowed.
- **Arrival time** is read from the direct sound (each octave counts the same, noise floors
  ignored), not dragged late by the loud bass.
- **Sum loss**, 20·log10(|ΣH| / Σ|H|): how far the bands' real sum falls below the same bands added in
  phase, taken per point and smoothed after, with noise floors gated out; read as average and
  worst dip.
- **Stereo IR phase** is unwrapped only where the measurements can vouch for it.

Several of these follow [Resonalyze](https://github.com/DIMOSUS/Resonalyze) (MIT) by dimosus:
the frequency-dependent window, sum loss and its gates, the reliability-gated unwrap, the
polarity margin, the driver band estimate behind the Finder's suggested limits, and the EQ Wizard
idea. Ideas are ported to Kotlin; no code is copied.

The Phase IRs tool has two convolver modes, chosen on its Design page, matching J_DSP's convolver:

- **Per-band (Low/Mid/High)**: three stereo IRs before the band sum that align the bands to each
  other at the crossovers (exported as `band-ir-package.zip`).
- **Stereo (after the band sum)**: one stereo IR (`stereo-phase-ir.wav`) for the Stereo slot. From
  one Combined sweep per channel per position, it removes the phase rotation of the summed response
  (crossovers and speakers) toward one arrival time at both seats, phase-only, between a chosen
  range (40 Hz–10 kHz by default). It acts on every band alike, so it can't fix cancellation between
  bands; Band Align or Per-band do that. Low-frequency linearisation needs a few ms of shift, so the
  maximum shift is raised to 5 ms when Stereo is chosen.

Recommended order: Crossover Finder (or Band Align if the crossovers stay) → enter the changes in
J_DSP → re-measure → Phase IRs from the fresh sweeps. Projects remember which tool they belong to.

## Phase IRs workflow

1. **Measurements**: import REW frequency/SPL/phase text exports (`.txt`, `.csv`, `.frd`) or
   calibrated mono impulse WAVs. Tag each file with seat, position, channel, software band
   (Low/Mid/High, or Combined for validation), timing reference, calibration and validation-only.
   Names like `driver_pos2_L_mid.txt` fill the tags automatically. A completeness grid shows
   what is missing. Projects save as `.jirb`.
2. **Checks**: choose the measurement smoothing (none, 1/48, 1/24 by default, or 1/12 octave).
   It is complex smoothing applied to every file as it's read, with each file's arrival time taken
   out first and put back after, so the checks, Band Align, the solver and the plots share the same
   data and phase stays exact. Then the checks: timing-reference consistency per channel, completeness for both seats and both
   channels, band-tag order, crossover level balance, level outliers between positions,
   combined-sweep vs band-sum agreement, impulse time-origin sanity, and whether positions agree
   on each crossover's band phase offset (if they don't, one fixed filter cannot suit them all).
3. **Design**: enter the existing J_DSP crossover frequencies, correction region and fade width,
   maximum group-delay shift, smoothing, regularisation, seat weighting, taps and common delay.
4. **Preview**: per seat average or per position, before vs predicted band summation, summed
   level, each band's phase correction and impulses, and a results table that flags any position
   predicted to get worse. Validation-only positions are reported but never used for the fit.
5. **Export**: `low.wav`, `mid.wav`, `high.wav` (stereo float32), `manifest.json`,
   `report.json`, `README.txt` and `band-ir-package.zip`, the package J_DSP's convolver page
   imports (format `siphondsp-band-ir`, version 1, `correction_only: true`, plus
   `correction_type: phase_only`).

The results are predictions from the measurements. Load the package, select Per-band at 0 dB band
gain, then **re-measure both seats** to verify the tune acoustically.

## Measuring

- Save the J_DSP configuration, bypass the convolver, keep crossovers / PEQ / delays / polarity /
  gains exactly as they will be used. Mono bass and cross-channel routing off (and kept off).
- Solo each software band per channel with all speakers connected. Mute bands only on the measured
  side, so an unchanged timing reference keeps playing.
- Use one unchanged timing reference for every sweep of a channel.
- Measure the centre and two nearby positions per seat (36 band-solo sweeps), plus a held-out
  position per seat marked validation-only. Optional Combined sweeps check the band-sum model.
- Keep the REW `.mdat` originals; the program reads exported text or impulse WAVs.

## Phase solver

For each channel and frequency inside the correction regions, it finds unit-magnitude band
corrections `K_b` maximising

    Σ_positions w_p · |Σ_b H_pb K_b|² / Σ_b |H_pb|²  +  2λ · Re Σ_b K_b

where `w_p` splits each seat's weight evenly across its positions and `λ` pulls toward no change.
It is solved by coordinate ascent warm-started from the neighbouring frequency, then unwrapped,
faded to zero outside the regions, smoothed in log frequency and scaled down if any band's
group-delay change exceeds the limit. FIRs are built from `exp(jφ)` with one common delay and a
Tukey window. One common gain (not per file) keeps the oversampled peak below unity. Predictions
use the exported float32 taps.

## Download

The **Build** workflow produces two artifacts on every push:

- `JDSP-IR-Builder-installer`: Windows `.exe` installer (Start-menu shortcut, bundled Java runtime).
- `JDSP-IR-Builder-portable`: ZIP; unzip and run `JDSP-IR-Builder.exe`.

## Building from source

Requires JDK 21.

    ./gradlew :core:test :desktop:test   # unit tests + offscreen render of every screen
    ./gradlew :desktop:run               # run the app
    ./gradlew :desktop:packageExe        # Windows installer (run on Windows)

`core/` holds import, checks, the solver and export (pure Kotlin). `desktop/` holds the Compose UI
in J_DSP's dark dashboard style.
