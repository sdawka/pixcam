package com.example.pixcam.lut

/** Parses the Adobe/Resolve .cube 3D LUT text format into a [CubeLut]. */
object CubeParser {

    fun parse(name: String, text: String): CubeLut {
        var title: String? = null
        var size = -1
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val rows = ArrayList<FloatArray>()

        for (rawLine in text.split("\r\n", "\r", "\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            when {
                line.startsWith("TITLE") -> {
                    val start = line.indexOf('"')
                    val end = line.lastIndexOf('"')
                    if (start >= 0 && end > start) title = line.substring(start + 1, end)
                }
                line.startsWith("LUT_1D_SIZE") -> {
                    throw IllegalArgumentException("1D LUTs are not supported")
                }
                line.startsWith("LUT_3D_SIZE") -> {
                    val token = line.substringAfter("LUT_3D_SIZE").trim()
                    size = token.toIntOrNull()
                        ?: throw IllegalArgumentException("invalid LUT_3D_SIZE value: $token")
                }
                line.startsWith("DOMAIN_MIN") -> {
                    domainMin = parseTriple(line.substringAfter("DOMAIN_MIN"), "DOMAIN_MIN")
                }
                line.startsWith("DOMAIN_MAX") -> {
                    domainMax = parseTriple(line.substringAfter("DOMAIN_MAX"), "DOMAIN_MAX")
                }
                else -> {
                    rows += parseTriple(line, "data row")
                }
            }
        }

        if (size < 0) throw IllegalArgumentException("missing LUT_3D_SIZE")

        val expected = size * size * size
        if (rows.size != expected) {
            throw IllegalArgumentException(
                "expected $expected data rows for LUT_3D_SIZE $size, found ${rows.size}"
            )
        }

        val data = FloatArray(expected * 3)
        for (i in rows.indices) {
            val row = rows[i]
            for (c in 0..2) {
                val range = domainMax[c] - domainMin[c]
                val normalized = if (range != 0f) (row[c] - domainMin[c]) / range else row[c]
                data[i * 3 + c] = normalized.coerceIn(0f, 1f)
            }
        }

        return CubeLut(name = title ?: name, size = size, data = data)
    }

    private fun parseTriple(text: String, label: String): FloatArray {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != 3) {
            throw IllegalArgumentException("expected 3 values for $label, found: $text")
        }
        return FloatArray(3) { i ->
            parts[i].toFloatOrNull()
                ?: throw IllegalArgumentException("unparseable float in $label: ${parts[i]}")
        }
    }
}
