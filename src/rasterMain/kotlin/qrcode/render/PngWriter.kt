package qrcode.render

/** Minimal PNG encoder: 8-bit RGBA, zlib stream of stored (uncompressed) deflate blocks. */
internal object PngWriter {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val MAX_STORED_BLOCK = 65_535

    fun write(width: Int, height: Int, argb: IntArray): ByteArray {
        val raw = ByteArray(height * (width * 4 + 1))
        var offset = 0
        for (y in 0 until height) {
            raw[offset++] = 0
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                raw[offset++] = (pixel ushr 16).toByte()
                raw[offset++] = (pixel ushr 8).toByte()
                raw[offset++] = pixel.toByte()
                raw[offset++] = (pixel ushr 24).toByte()
            }
        }
        val out = Bytes()
        out.add(SIGNATURE)
        val header = Bytes().apply {
            int(width)
            int(height)
            add(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", zlibStored(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun zlibStored(data: ByteArray): ByteArray {
        val out = Bytes()
        out.add(byteArrayOf(0x78, 0x01))
        var position = 0
        do {
            val length = minOf(MAX_STORED_BLOCK, data.size - position)
            val last = position + length >= data.size
            out.add(byteArrayOf(if (last) 1 else 0))
            out.add(byteArrayOf(length.toByte(), (length ushr 8).toByte()))
            val inverse = length.inv()
            out.add(byteArrayOf(inverse.toByte(), (inverse ushr 8).toByte()))
            out.add(data, position, length)
            position += length
        } while (!last)
        out.int(adler32(data))
        return out.toByteArray()
    }

    private fun chunk(out: Bytes, type: String, data: ByteArray) {
        val typeBytes = type.encodeToByteArray()
        out.int(data.size)
        out.add(typeBytes)
        out.add(data)
        out.int(crc32(typeBytes + data))
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1L
        var b = 0L
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65_521
            b = (b + a) % 65_521
        }
        return ((b shl 16) or a).toInt()
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 else c ushr 1 }
        c
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (byte in data) crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }

    private class Bytes {
        private var buffer = ByteArray(1024)
        private var size = 0

        fun add(bytes: ByteArray, from: Int = 0, length: Int = bytes.size) {
            ensure(length)
            bytes.copyInto(buffer, size, from, from + length)
            size += length
        }

        fun int(value: Int) = add(
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
        )

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }
    }
}
