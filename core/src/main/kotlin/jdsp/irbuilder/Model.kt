package jdsp.irbuilder

import kotlinx.serialization.Serializable

/** Listening seat a measurement belongs to. Both seats are always part of the design set. */
@Serializable
enum class Seat(val label: String) {
    DRIVER("Driver"),
    PASSENGER("Passenger"),
}

/** Logical J_DSP output channel (the IR's left channel is the car's left). */
@Serializable
enum class Channel(val label: String) {
    L("Left"),
    R("Right"),
}

/**
 * The J_DSP software band that was soloed for the sweep. A band is a software branch summed back to
 * stereo before the BMW amplifier's own speaker split, not an independent physical speaker output.
 * [COMBINED] is all three bands playing together: validation of the branch-sum model only.
 */
@Serializable
enum class Band(val label: String, val fileName: String) {
    LOW("Low", "low"),
    MID("Mid", "mid"),
    HIGH("High", "high"),
    COMBINED("Combined", "combined");

    companion object {
        /** The three software branches the designer produces IRs for, in package order. */
        val BRANCHES = listOf(LOW, MID, HIGH)
    }
}

/** One imported measurement file and the user's assertions about how it was captured. */
@Serializable
data class Measurement(
    val path: String,
    val seat: Seat,
    val position: String,
    val channel: Channel,
    val band: Band,
    val timingReference: String,
    val validationOnly: Boolean = false,
    val calibrationDb: Double = 0.0,
)

/** Identifies one mic position for one channel: it needs a Low/Mid/High triplet. */
data class GroupKey(val seat: Seat, val position: String, val channel: Channel) : Comparable<GroupKey> {
    val label: String get() = "${seat.label} · $position · ${channel.label}"

    override fun compareTo(other: GroupKey): Int =
        compareValuesBy(this, other, { it.channel }, { it.seat }, { it.position })
}

/**
 * Phase-only design settings. The IRs never change any band's magnitude on purpose; only each
 * band's phase near the two crossovers is adjusted so the software branches sum coherently.
 */
@Serializable
data class DesignOptions(
    val sampleRate: Int = 48000,
    val taps: Int = 16384,
    val latencyFrames: Int = 8192,
    /** Existing J_DSP Low/Mid crossover. Also defines where the first correction region sits. */
    val lowMidHz: Double = 150.0,
    /** Existing J_DSP Mid/High crossover. Also defines where the second correction region sits. */
    val midHighHz: Double = 3500.0,
    /** Correction is fully active within this many octaves either side of each crossover. */
    val regionOctaves: Double = 1.0,
    /** Correction fades to zero over this many further octaves. */
    val fadeOctaves: Double = 0.5,
    /** Largest group-delay change a correction may apply to any band, in ms. */
    val maxShiftMs: Double = 2.0,
    /** Phase smoothing width, in octaves. */
    val smoothingOctaves: Double = 1.0 / 6.0,
    /** Pull toward "no change". Larger is more conservative. */
    val regularization: Double = 0.05,
    val driverWeight: Double = 1.0,
    val passengerWeight: Double = 1.0,
) {
    val latencyMs: Double get() = latencyFrames * 1000.0 / sampleRate

    /** Approximate end-to-end latency in J_DSP: common IR delay + the 1024-frame partition. */
    val jdspLatencyMs: Double get() = (latencyFrames + 1024) * 1000.0 / sampleRate

    fun seatWeight(seat: Seat) = if (seat == Seat.DRIVER) driverWeight else passengerWeight

    /** Lowest frequency measurements must cover. */
    val analysisMinHz: Double get() = maxOf(10.0, lowMidHz / pow2(regionOctaves + fadeOctaves + 0.25))

    /** Highest frequency measurements must cover. */
    val analysisMaxHz: Double get() = minOf(sampleRate * 0.45, midHighHz * pow2(regionOctaves + fadeOctaves + 0.25))

    fun validate() {
        val numbers = listOf(lowMidHz, midHighHz, regionOctaves, fadeOctaves, maxShiftMs, smoothingOctaves, regularization, driverWeight, passengerWeight)
        require(numbers.all { it.isFinite() }) { "All settings must be finite numbers" }
        require(sampleRate == 44100 || sampleRate == 48000) { "Sample rate must be 44100 or 48000 Hz" }
        require(taps in 1024..65536 && taps and (taps - 1) == 0) { "Taps must be a power of two from 1024 to 65536" }
        require(!(sampleRate == 44100 && taps > 32768)) {
            "44100 Hz packages are limited to 32768 taps so they still fit J_DSP's limit after resampling to 48000 Hz"
        }
        require(latencyFrames in taps / 4..3 * taps / 4) { "Common delay must be between a quarter and three quarters of the tap count" }
        require(lowMidHz >= 20) { "Low/Mid crossover must be at least 20 Hz (J_DSP package rule)" }
        require(midHighHz > lowMidHz * 2) { "Mid/High crossover must be more than an octave above Low/Mid" }
        require(midHighHz < sampleRate * 0.4) { "Mid/High crossover is too close to Nyquist" }
        require(regionOctaves in 0.25..2.0) { "Correction region must be 0.25 to 2 octaves" }
        require(fadeOctaves in 0.1..1.5) { "Fade width must be 0.1 to 1.5 octaves" }
        require(smoothingOctaves in 0.0..1.0) { "Smoothing must be 0 to 1 octave" }
        require(regularization in 0.0..10.0) { "Regularization must be 0 to 10" }
        require(maxShiftMs > 0 && maxShiftMs <= 20) { "Maximum shift must be above 0 and at most 20 ms" }
        require(maxShiftMs * sampleRate / 1000.0 < minOf(latencyFrames, taps - latencyFrames) / 2.0) {
            "Maximum shift needs more room: increase taps/common delay or reduce the maximum shift"
        }
        require(driverWeight > 0 && passengerWeight > 0) { "Both seat weights must be positive" }
    }
}

internal fun pow2(x: Double) = Math.pow(2.0, x)
