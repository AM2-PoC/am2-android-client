package com.am2.am2

/**
 * Rotation and mirroring for NV21 camera frames.
 *
 * Preview callbacks always arrive in sensor orientation: setDisplayOrientation
 * only turns the local SurfaceView and setRotation only applies to takePicture.
 * The frame therefore has to be turned before it is encoded.
 *
 * Doing it on the bytes costs a transpose of the Y plane and of the interleaved
 * VU plane. The alternative the capture path used before was to decode the frame
 * to a Bitmap, rotate it with a Matrix and encode it again, which cost roughly
 * ten times as much for every frame sent.
 */
object Nv21Transform {

    /** Width of the frame produced by [rotate] for a given input width/height. */
    fun rotatedWidth(width: Int, height: Int, degrees: Int): Int =
        if (degrees == 90 || degrees == 270) height else width

    /** Height of the frame produced by [rotate] for a given input width/height. */
    fun rotatedHeight(width: Int, height: Int, degrees: Int): Int =
        if (degrees == 90 || degrees == 270) width else height

    /**
     * Turns [source] by [degrees] clockwise, then mirrors it horizontally if
     * [mirror] is set.
     *
     * The mirror is applied in the destination frame, after the rotation, which
     * is the order the Matrix pipeline this replaced used. Applying it to the
     * source instead would leave a front-camera frame upside down, because a
     * mirror and a quarter turn do not commute.
     */
    fun rotate(source: ByteArray, width: Int, height: Int, degrees: Int, mirror: Boolean): ByteArray {
        if (degrees == 0 && !mirror) return source

        val output = ByteArray(source.size)
        val lumaSize = width * height
        val outWidth = rotatedWidth(width, height, degrees)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var dx: Int
                var dy: Int
                when (degrees) {
                    90 -> { dx = height - 1 - y; dy = x }
                    180 -> { dx = width - 1 - x; dy = height - 1 - y }
                    270 -> { dx = y; dy = width - 1 - x }
                    else -> { dx = x; dy = y }
                }
                if (mirror) dx = outWidth - 1 - dx
                output[dy * outWidth + dx] = source[y * width + x]
            }
        }

        // NV21 carries one interleaved VU pair per 2x2 luma block, so the chroma
        // plane is transposed the same way at half resolution, moving both bytes
        // of a pair together.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val outChromaWidth = if (degrees == 90 || degrees == 270) chromaHeight else chromaWidth

        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                var dx: Int
                var dy: Int
                when (degrees) {
                    90 -> { dx = chromaHeight - 1 - y; dy = x }
                    180 -> { dx = chromaWidth - 1 - x; dy = chromaHeight - 1 - y }
                    270 -> { dx = y; dy = chromaWidth - 1 - x }
                    else -> { dx = x; dy = y }
                }
                if (mirror) dx = outChromaWidth - 1 - dx
                val from = lumaSize + y * width + x * 2
                val to = lumaSize + (dy * outChromaWidth + dx) * 2
                output[to] = source[from]
                output[to + 1] = source[from + 1]
            }
        }
        return output
    }
}
