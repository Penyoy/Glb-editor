package id.animasi.anima3d.anim

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumericTest {

    private val eps = 1e-4f

    private fun rotZ(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble() / 2.0)
        return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    private fun rotY(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble() / 2.0)
        return floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat())
    }

    @Test
    fun `composeTrs lalu decomposeMatrix mengembalikan nilai semula`() {
        val translation = floatArrayOf(5f, 6f, -7f)
        val rotation = rotZ(90f)
        val scale = floatArrayOf(2f, 3f, 4f)

        val matrix = FloatArray(16)
        Numeric.composeTrs(translation, 0, rotation, 0, scale, 0, matrix, 0)

        val tOut = FloatArray(3)
        val rOut = FloatArray(4)
        val sOut = FloatArray(3)
        Numeric.decomposeMatrix(matrix, 0, tOut, 0, rOut, 0, sOut, 0)

        assertEquals(5f, tOut[0], eps)
        assertEquals(6f, tOut[1], eps)
        assertEquals(-7f, tOut[2], eps)

        assertEquals(2f, sOut[0], eps)
        assertEquals(3f, sOut[1], eps)
        assertEquals(4f, sOut[2], eps)

        assertTrue(
            Numeric.quaternionEquals(rotation, 0, rOut, 0, 1e-3f),
            "rotasi Z 90 derajat harus kembali, hasil: ${rOut.toList()}"
        )
    }

    @Test
    fun `decomposeMatrix menangani rotasi di sumbu Y`() {
        val translation = floatArrayOf(1f, 2f, 3f)
        val rotation = rotY(120f)
        val scale = floatArrayOf(1f, 1f, 1f)

        val matrix = FloatArray(16)
        Numeric.composeTrs(translation, 0, rotation, 0, scale, 0, matrix, 0)

        val rOut = FloatArray(4)
        Numeric.decomposeMatrix(matrix, 0, FloatArray(3), 0, rOut, 0, FloatArray(3), 0)
        assertTrue(
            Numeric.quaternionEquals(rotation, 0, rOut, 0, 1e-3f),
            "rotasi Y 120 derajat harus kembali, hasil: ${rOut.toList()}"
        )
    }

    @Test
    fun `decomposeMatrix menangani identitas`() {
        val matrix = FloatArray(16)
        Numeric.composeTrs(
            floatArrayOf(0f, 0f, 0f), 0,
            floatArrayOf(0f, 0f, 0f, 1f), 0,
            floatArrayOf(1f, 1f, 1f), 0,
            matrix, 0
        )
        val sOut = FloatArray(3)
        val rOut = FloatArray(4)
        Numeric.decomposeMatrix(matrix, 0, FloatArray(3), 0, rOut, 0, sOut, 0)
        assertEquals(1f, sOut[0], eps)
        assertTrue(Numeric.quaternionEquals(floatArrayOf(0f, 0f, 0f, 1f), 0, rOut, 0, eps))
    }

    @Test
    fun `matriks tanpa shear tidak dilaporkan mengandung shear`() {
        val matrix = FloatArray(16)
        Numeric.composeTrs(
            floatArrayOf(9f, 9f, 9f), 0,
            rotZ(33f), 0,
            floatArrayOf(2f, 5f, 0.5f), 0,
            matrix, 0
        )
        assertFalse(Numeric.hasShear(matrix, 0))
    }

    @Test
    fun `matriks dengan shear terdeteksi`() {
        val matrix = FloatArray(16)
        Numeric.composeTrs(
            floatArrayOf(0f, 0f, 0f), 0,
            floatArrayOf(0f, 0f, 0f, 1f), 0,
            floatArrayOf(1f, 1f, 1f), 0,
            matrix, 0
        )
        // Kolom 1 (indeks 4..6) digeser sepanjang sumbu X sehingga kolom tidak lagi ortogonal.
        matrix[4] = 1f
        matrix[0] = 1f
        matrix[5] = 1f
        matrix[1] = 0.8f
        assertTrue(Numeric.hasShear(matrix, 0))
    }

    @Test
    fun `kuaternion ternormalisasi setelah composeTrs`() {
        val length = 3f
        val rotation = floatArrayOf(0f, 0f, 0f, length) // sengaja tidak bernorma 1
        val matrix = FloatArray(16)
        Numeric.composeTrs(
            floatArrayOf(0f, 0f, 0f), 0,
            rotation, 0,
            floatArrayOf(1f, 1f, 1f), 0,
            matrix, 0
        )
        // Panjang kuaternion diabaikan saat dikonversi ke matriks, jadi hasilnya identitas.
        val rOut = FloatArray(4)
        Numeric.decomposeMatrix(matrix, 0, FloatArray(3), 0, rOut, 0, FloatArray(3), 0)
        assertTrue(Numeric.quaternionEquals(floatArrayOf(0f, 0f, 0f, 1f), 0, rOut, 0, eps))
    }

    @Test
    fun `normalize menjaga arah dan menetapkan panjang satu`() {
        val v = floatArrayOf(0f, 0f, 0f, 2f)
        Numeric.normalize(v, 0, 4)
        assertEquals(0f, v[2], eps)
        assertEquals(1f, v[3], eps)
    }

    @Test
    fun `normalize kuaternion nol menghasilkan identitas`() {
        val v = FloatArray(4)
        Numeric.normalize(v, 0, 4)
        assertEquals(1f, v[3], eps)
    }
}
