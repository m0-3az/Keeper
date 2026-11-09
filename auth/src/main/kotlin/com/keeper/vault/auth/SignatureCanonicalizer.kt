package com.keeper.vault.auth

import kotlin.math.roundToInt

/**
 * A single sampled signature point in screen coordinates.
 *
 * [strokeStart] marks the first point of a new stroke. The UI currently records one stroke, but
 * the canonical form supports multiple strokes without ambiguity.
 */
data class SignaturePoint(
    val x: Float,
    val y: Float,
    val strokeStart: Boolean = false,
)

/**
 * Canonicalizes a behavioral signature drawing into a `CharArray` for PBKDF2.
 *
 * The drawing is translated and scaled into the unit box, quantized to a 32x32 grid, and encoded
 * as fixed-width base-36 characters. Position and scale therefore do not change the secret; the
 * shape and the order of the strokes do. The first two characters version the format.
 *
 * A signature is a behavioral secret, not biometrics. It must never be converted to a `String`,
 * logged, or persisted outside the encrypted key envelope.
 */
object SignatureCanonicalizer {
    const val MIN_POINTS: Int = 8
    const val MAX_POINTS: Int = 64
    const val GRID: Int = 32
    const val MIN_CANONICAL_LENGTH: Int = 2 + (MIN_POINTS * 2)

    private const val VERSION = "S1"
    private const val STROKE_SEPARATOR = '|'
    private const val EPSILON = 1e-6f

    fun canonicalize(points: List<SignaturePoint>): CharArray {
        val finite = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (finite.size < MIN_POINTS) return CharArray(0)

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (point in finite) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }
        val spanX = maxX - minX
        val spanY = maxY - minY
        if (spanX <= EPSILON && spanY <= EPSILON) return CharArray(0)

        val cells = ArrayList<Cell>(finite.size)
        for (point in finite) {
            val cell = Cell(
                x = quantize(point.x, minX, spanX),
                y = quantize(point.y, minY, spanY),
                strokeStart = point.strokeStart,
            )
            val previous = cells.lastOrNull()
            if (previous != null && !cell.strokeStart && cell.x == previous.x && cell.y == previous.y) {
                continue
            }
            cells += cell
        }
        if (cells.size < MIN_POINTS) return CharArray(0)

        val sampled = if (cells.size > MAX_POINTS) {
            ArrayList<Cell>(MAX_POINTS).also { out ->
                for (index in 0 until MAX_POINTS) {
                    val source = (index.toLong() * (cells.size - 1) / (MAX_POINTS - 1)).toInt()
                    out += cells[source]
                }
            }
        } else {
            cells
        }

        val separators = sampled.drop(1).count { it.strokeStart }
        val out = CharArray(VERSION.length + sampled.size * 2 + separators)
        VERSION.toCharArray().copyInto(out)
        var cursor = VERSION.length
        sampled.forEachIndexed { index, cell ->
            if (cell.strokeStart && index > 0) {
                out[cursor++] = STROKE_SEPARATOR
            }
            out[cursor++] = base36(cell.x)
            out[cursor++] = base36(cell.y)
        }
        return out
    }

    private fun quantize(value: Float, min: Float, span: Float): Int {
        if (span <= EPSILON) return 0
        val normalized = (value - min) / span
        return (normalized * (GRID - 1)).roundToInt().coerceIn(0, GRID - 1)
    }

    private fun base36(value: Int): Char {
        val clamped = value.coerceIn(0, GRID - 1)
        return if (clamped < 10) '0' + clamped else 'a' + (clamped - 10)
    }

    private data class Cell(
        val x: Int,
        val y: Int,
        val strokeStart: Boolean,
    )
}
