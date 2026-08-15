package com.am2.am2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong index here corrupts every frame sent, and it is invisible in source
 * review, so the transform is checked against frames whose every sample is
 * distinct.
 */
class Nv21TransformTest {

    private val width = 4
    private val height = 6

    /** Luma sample carrying its own coordinates, so a misplaced byte is traceable. */
    private fun luma(x: Int, y: Int): Byte = (y * width + x + 1).toByte()

    private fun frame(): ByteArray {
        val lumaSize = width * height
        val data = ByteArray(lumaSize + lumaSize / 2)
        for (y in 0 until height) {
            for (x in 0 until width) data[y * width + x] = luma(x, y)
        }
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val at = lumaSize + y * width + x * 2
                data[at] = (100 + y * (width / 2) + x).toByte()      // V
                data[at + 1] = (-100 + y * (width / 2) + x).toByte() // U
            }
        }
        return data
    }

    private fun lumaAt(data: ByteArray, w: Int, x: Int, y: Int): Byte = data[y * w + x]

    @Test
    fun `a quarter turn moves every sample to its rotated position`() {
        val rotated = Nv21Transform.rotate(frame(), width, height, 90, mirror = false)
        val outWidth = Nv21Transform.rotatedWidth(width, height, 90)
        assertEquals(height, outWidth)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // 90 degrees clockwise sends (x, y) to (height - 1 - y, x).
                assertEquals(luma(x, y), lumaAt(rotated, outWidth, height - 1 - y, x))
            }
        }
    }

    @Test
    fun `mirroring is applied after the rotation, not before`() {
        val rotated = Nv21Transform.rotate(frame(), width, height, 90, mirror = true)
        val outWidth = Nv21Transform.rotatedWidth(width, height, 90)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // The mirror flips the rotated column, which is what the Matrix
                // pipeline did. Mirroring the source first would leave a front
                // camera frame upside down.
                assertEquals(luma(x, y), lumaAt(rotated, outWidth, outWidth - 1 - (height - 1 - y), x))
            }
        }
    }

    @Test
    fun `every rotation writes each byte exactly once`() {
        for (degrees in intArrayOf(90, 180, 270)) {
            for (mirror in booleanArrayOf(false, true)) {
                val rotated = Nv21Transform.rotate(frame(), width, height, degrees, mirror)
                assertEquals(frame().size, rotated.size)
                // Nothing left untouched means no gap, and a preserved multiset
                // means no sample was written twice over another.
                assertArrayEquals(
                    frame().sorted().toByteArray(),
                    rotated.sorted().toByteArray(),
                )
            }
        }
    }

    @Test
    fun `chroma pairs stay paired`() {
        val rotated = Nv21Transform.rotate(frame(), width, height, 90, mirror = false)
        val lumaSize = width * height
        var index = lumaSize
        while (index < rotated.size) {
            val v = rotated[index].toInt()
            val u = rotated[index + 1].toInt()
            // The fixture gives every V sample a positive value and every U
            // sample a negative one, so a pair that was split, reordered or
            // offset by a single byte shows up as a sign that is on the wrong
            // side of the pair.
            assertTrue("expected a V sample at $index, got $v", v > 0)
            assertTrue("expected a U sample at ${index + 1}, got $u", u < 0)
            index += 2
        }
    }

    @Test
    fun `an untouched frame is returned as is`() {
        val original = frame()
        assertTrue(original === Nv21Transform.rotate(original, width, height, 0, mirror = false))
    }

    @Test
    fun `a half turn is its own inverse`() {
        val once = Nv21Transform.rotate(frame(), width, height, 180, mirror = false)
        val twice = Nv21Transform.rotate(once, width, height, 180, mirror = false)
        assertArrayEquals(frame(), twice)
    }
}
