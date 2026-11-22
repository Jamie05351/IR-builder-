package jdsp.irbuilder

import java.io.File

/** All bands measured at one seat/position/channel. */
class LoadedGroup(
    val key: GroupKey,
    val validationOnly: Boolean,
    val responses: Map<Band, LoadedResponse>,
    val measurements: Map<Band, Measurement>,
) {
    val isComplete get() = Band.BRANCHES.all { it in responses }
    fun branch(b: Band): CVec = responses.getValue(b).response

    /** Complex sum of the three measured branches: the baseline the designer corrects. */
    fun branchSum(): CVec {
        val n = branch(Band.LOW).size
        val out = CVec(n)
        for (b in Band.BRANCHES) {
            val h = branch(b)
            for (i in 0 until n) { out.re[i] += h.re[i]; out.im[i] += h.im[i] }
        }
        return out
    }
}

/** A measurement that could not be read, with the reason. */
data class LoadError(val measurement: Measurement, val message: String)

/** Every measurement resampled onto one design grid and grouped by seat/position/channel. */
class MeasurementSet(
    val options: DesignOptions,
    val freqs: DoubleArray,
    val groups: List<LoadedGroup>,
    val loadErrors: List<LoadError>,
    val measurements: List<Measurement>,
) {
    fun group(key: GroupKey) = groups.firstOrNull { it.key == key }

    companion object {
        const val GRID_POINTS = 768

        fun grid(o: DesignOptions) = geomspace(o.analysisMinHz, o.analysisMaxHz, GRID_POINTS)

        /** Reads every file. Unreadable files are reported, not thrown, so the checks page can list them. */
        fun load(measurements: List<Measurement>, o: DesignOptions): MeasurementSet {
            val freqs = grid(o)
            val errors = ArrayList<LoadError>()
            val loaded = LinkedHashMap<GroupKey, MutableMap<Band, Pair<Measurement, LoadedResponse>>>()
            for (m in measurements) {
                val key = GroupKey(m.seat, m.position.trim(), m.channel)
                val r = try {
                    Importer.read(m, freqs, o.sampleRate, o.measurementSmoothing)
                } catch (e: Exception) {
                    errors += LoadError(m, e.message ?: e.toString())
                    continue
                }
                val bands = loaded.getOrPut(key) { LinkedHashMap() }
                if (m.band in bands) {
                    errors += LoadError(m, "Duplicate ${m.band.label} measurement for ${key.label}")
                    continue
                }
                bands[m.band] = m to r
            }
            val groups = loaded.map { (key, bands) ->
                LoadedGroup(
                    key = key,
                    validationOnly = bands.values.any { it.first.validationOnly },
                    responses = bands.mapValues { it.value.second },
                    measurements = bands.mapValues { it.value.first },
                )
            }.sortedBy { it.key }
            return MeasurementSet(o, freqs, groups, errors, measurements)
        }

        fun fileName(m: Measurement) = File(m.path).name
    }
}
