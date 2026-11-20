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

## Workflow

1. **Measurements**: import REW frequency/SPL/phase text exports (`.txt`, `.csv`, `.frd`) or
   calibrated mono impulse WAVs. Tag each file with seat, position, channel, software band
   (Low/Mid/High, or Combined for validation), timing reference, calibration and validation-only.
   Names like `driver_pos2_L_mid.txt` fill the tags automatically. A completeness grid shows
   what is missing. Projects save as `.jirb`.
2. **Checks**: timing-reference consistency per channel, completeness for both seats and both
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
