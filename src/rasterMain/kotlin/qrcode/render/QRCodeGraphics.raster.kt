package qrcode.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin raster backend for targets without a platform graphics API in this library (macOS,
 * Linux and Windows native). Pixels are ARGB in an [IntArray]; [getBytes] writes PNG.
 * [drawImage] is not supported because decoding images needs an inflate implementation.
 */
actual class QRCodeGraphics actual constructor(
    private val width: Int,
    private val height: Int,
) {
    private var pixels = IntArray(width * height)
    private var changed = false

    actual fun changed(): Boolean = changed

    actual fun reset() {
        pixels = IntArray(width * height)
        changed = false
    }

    actual fun dimensions(): Array<Int> = arrayOf(width, height)

    actual fun getBytes(): ByteArray = PngWriter.write(width, height, pixels)

    actual fun getBytes(format: String): ByteArray {
        require(format.equals("PNG", ignoreCase = true)) { "Unsupported format: $format" }
        return getBytes()
    }

    actual fun availableFormats(): Array<String> = arrayOf("PNG")

    actual fun nativeImage(): Any = pixels

    actual fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, color: Int, thickness: Double) {
        val half = max(0, (thickness / 2).roundToInt())
        val steps = max(abs(x2 - x1), abs(y2 - y1))
        for (i in 0..steps) {
            val t = if (steps == 0) 0.0 else i.toDouble() / steps
            val x = (x1 + (x2 - x1) * t).roundToInt()
            val y = (y1 + (y2 - y1) * t).roundToInt()
            fillRect(x - half, y - half, half * 2 + 1, half * 2 + 1, color)
        }
    }

    actual fun drawRect(x: Int, y: Int, width: Int, height: Int, color: Int, thickness: Double) {
        val t = max(1, thickness.roundToInt())
        fillRect(x, y, width, t, color)
        fillRect(x, y + height - t, width, t, color)
        fillRect(x, y, t, height, color)
        fillRect(x + width - t, y, t, height, color)
    }

    actual fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        for (py in max(0, y) until min(this.height, y + height)) {
            for (px in max(0, x) until min(this.width, x + width)) plot(px, py, color)
        }
    }

    actual fun fill(color: Int) = fillRect(0, 0, width, height, color)

    actual fun drawRoundRect(
        x: Int, y: Int, width: Int, height: Int, borderRadius: Int, color: Int, thickness: Double,
    ) {
        val t = max(1, thickness.roundToInt())
        forEachPixel(x, y, width, height) { px, py ->
            insideRoundRect(px, py, x, y, width, height, borderRadius) &&
                !insideRoundRect(px, py, x + t, y + t, width - 2 * t, height - 2 * t, max(0, borderRadius - t))
        }.forEach { (px, py) -> plot(px, py, color) }
    }

    actual fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, borderRadius: Int, color: Int) {
        forEachPixel(x, y, width, height) { px, py -> insideRoundRect(px, py, x, y, width, height, borderRadius) }
            .forEach { (px, py) -> plot(px, py, color) }
    }

    actual fun drawEllipse(x: Int, y: Int, width: Int, height: Int, color: Int, thickness: Double) {
        val t = max(1, thickness.roundToInt())
        forEachPixel(x, y, width, height) { px, py ->
            insideEllipse(px, py, x, y, width, height) &&
                !insideEllipse(px, py, x + t, y + t, width - 2 * t, height - 2 * t)
        }.forEach { (px, py) -> plot(px, py, color) }
    }

    actual fun fillEllipse(x: Int, y: Int, width: Int, height: Int, color: Int) {
        forEachPixel(x, y, width, height) { px, py -> insideEllipse(px, py, x, y, width, height) }
            .forEach { (px, py) -> plot(px, py, color) }
    }

    actual fun drawImage(rawData: ByteArray?, x: Int, y: Int) = Unit

    private inline fun forEachPixel(
        x: Int, y: Int, width: Int, height: Int, predicate: (Int, Int) -> Boolean,
    ): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        for (py in max(0, y) until min(this.height, y + height)) {
            for (px in max(0, x) until min(this.width, x + width)) {
                if (predicate(px, py)) result += px to py
            }
        }
        return result
    }

    private fun insideEllipse(px: Int, py: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val rx = width / 2.0
        val ry = height / 2.0
        val dx = (px + 0.5 - (x + rx)) / rx
        val dy = (py + 0.5 - (y + ry)) / ry
        return dx * dx + dy * dy <= 1.0
    }

    private fun insideRoundRect(px: Int, py: Int, x: Int, y: Int, width: Int, height: Int, radius: Int): Boolean {
        if (width <= 0 || height <= 0 || px < x || py < y || px >= x + width || py >= y + height) return false
        val r = min(radius, min(width, height) / 2).toDouble()
        if (r <= 0) return true
        val cx = (px + 0.5).coerceIn(x + r, x + width - r)
        val cy = (py + 0.5).coerceIn(y + r, y + height - r)
        val dx = px + 0.5 - cx
        val dy = py + 0.5 - cy
        return dx * dx + dy * dy <= r * r
    }

    /** Source-over blending of an ARGB [color] onto the pixel. */
    private fun plot(x: Int, y: Int, color: Int) {
        val index = y * width + x
        val alpha = (color ushr 24) and 0xFF
        pixels[index] = when (alpha) {
            0xFF -> color
            0 -> return
            else -> blend(pixels[index], color, alpha)
        }
        changed = true
    }

    private fun blend(dst: Int, src: Int, srcAlpha: Int): Int {
        val dstAlpha = (dst ushr 24) and 0xFF
        val outAlpha = srcAlpha + dstAlpha * (255 - srcAlpha) / 255
        if (outAlpha == 0) return 0
        fun channel(shift: Int): Int {
            val s = (src ushr shift) and 0xFF
            val d = (dst ushr shift) and 0xFF
            return (s * srcAlpha + d * dstAlpha * (255 - srcAlpha) / 255) / outAlpha
        }
        return (outAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
