package id.animasi.anima3d.anim

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChannelEvaluatorTest {

    private val eps = 1e-5f

    private fun translationChannel(
        times: FloatArray,
        values: FloatArray,
        interpolation: Interpolation = Interpolation.LINEAR,
        inTangents: FloatArray? = null,
        outTangents: FloatArray? = null
    ) = Channel(0, AnimPath.TRANSLATION, interpolation, times, values, inTangents, outTangents)

    // --- dasar ---------------------------------------------------------------

    @Test
    fun `nilai sebelum keyframe pertama memakai keyframe pertama`() {
        val channel = translationChannel(floatArrayOf(1f, 2f), floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f))
        val out = FloatArray(3)
        ChannelEvaluator(channel).evaluate(0.0f, out)
        assertEquals(0f, out[0], eps)
    }

    @Test
    fun `nilai setelah keyframe terakhir konstan di keyframe terakhir`() {
        val channel = translationChannel(floatArrayOf(1f, 2f), floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f))
        val out = FloatArray(3)
        val evaluator = ChannelEvaluator(channel)
        evaluator.evaluate(50f, out)
        assertEquals(10f, out[0], eps)
        evaluator.evaluate(2f, out)
        assertEquals(10f, out[0], eps)
    }

    @Test
    fun `satu keyframe berarti nilai konstan`() {
        val channel = translationChannel(floatArrayOf(5f), floatArrayOf(3f, 4f, 5f))
        val out = FloatArray(3)
        val evaluator = ChannelEvaluator(channel)
        evaluator.evaluate(0f, out)
        assertEquals(3f, out[0], eps)
        evaluator.evaluate(999f, out)
        assertEquals(5f, out[2], eps)
    }

    @Test
    fun `interpolasi linear translasi di titik tengah`() {
        val channel = translationChannel(floatArrayOf(0f, 2f), floatArrayOf(0f, 0f, 0f, 8f, 4f, -2f))
        val out = FloatArray(3)
        ChannelEvaluator(channel).evaluate(1f, out)
        assertEquals(4f, out[0], eps)
        assertEquals(2f, out[1], eps)
        assertEquals(-1f, out[2], eps)
    }

    @Test
    fun `STEP menahan nilai sampai keyframe berikutnya`() {
        val channel = translationChannel(
            floatArrayOf(0f, 1f), floatArrayOf(1f, 0f, 0f, 9f, 0f, 0f),
            interpolation = Interpolation.STEP
        )
        val out = FloatArray(3)
        val evaluator = ChannelEvaluator(channel)
        evaluator.evaluate(0.99f, out)
        assertEquals(1f, out[0], eps)
        evaluator.evaluate(1f, out)
        assertEquals(9f, out[0], eps)
    }

    // --- pencarian segmen ----------------------------------------------------

    @Test
    fun `indexOfKey memilih segmen yang benar`() {
        val channel = translationChannel(
            floatArrayOf(0f, 1f, 2f, 5f),
            FloatArray(4 * 3)
        )
        val evaluator = ChannelEvaluator(channel)
        assertEquals(0, evaluator.indexOfKey(-1f))
        assertEquals(0, evaluator.indexOfKey(0f))
        assertEquals(0, evaluator.indexOfKey(0.5f))
        assertEquals(1, evaluator.indexOfKey(1f))
        assertEquals(2, evaluator.indexOfKey(3f))
        assertEquals(3, evaluator.indexOfKey(5f))
        assertEquals(3, evaluator.indexOfKey(100f))
    }

    // --- rotasi: slerp -------------------------------------------------------

    private fun rotZ(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble() / 2.0)
        return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    @Test
    fun `rotasi linear memakai slerp bukan lerp`() {
        val q0 = rotZ(0f)
        val q1 = rotZ(90f)
        val channel = Channel(
            0, AnimPath.ROTATION, Interpolation.LINEAR,
            floatArrayOf(0f, 1f),
            floatArrayOf(q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3])
        )
        val out = FloatArray(4)
        ChannelEvaluator(channel).evaluate(0.5f, out)

        // Setengah jalan antara 0 dan 90 derajat adalah 45 derajat, bukan hasil lerp
        // komponen demi komponen yang akan menghasilkan kuaternion tidak ternormalisasi.
        val expected = rotZ(45f)
        assertTrue(
            Numeric.quaternionEquals(expected, 0, out, 0, 1e-5f),
            "diharapkan 45 derajat di sumbu Z, hasil: ${out.toList()}"
        )
        // Normanya harus tetap 1; lerp mentah akan menyimpang dari 1.
        val norm = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        assertEquals(1f, norm, 1e-5f)
    }

    @Test
    fun `slerp memilih jalur pendek ketika tanda kuaternion terbalik`() {
        val q0 = rotZ(0f)
        val q1 = rotZ(90f)
        // -q menyatakan rotasi yang sama persis. Tanpa penanganan tanda, slerp akan
        // memutar 270 derajat ke arah sebaliknya.
        val negated = floatArrayOf(-q1[0], -q1[1], -q1[2], -q1[3])
        val channel = Channel(
            0, AnimPath.ROTATION, Interpolation.LINEAR,
            floatArrayOf(0f, 1f),
            floatArrayOf(q0[0], q0[1], q0[2], q0[3], negated[0], negated[1], negated[2], negated[3])
        )
        val out = FloatArray(4)
        ChannelEvaluator(channel).evaluate(0.5f, out)
        assertTrue(
            Numeric.quaternionEquals(rotZ(45f), 0, out, 0, 1e-5f),
            "diharapkan tetap 45 derajat, hasil: ${out.toList()}"
        )
    }

    // --- cubic spline --------------------------------------------------------

    @Test
    fun `cubicspline tanpa tangen sama dengan interpolasi halus antara dua nilai`() {
        val zeros = FloatArray(6)
        val channel = translationChannel(
            floatArrayOf(0f, 1f),
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f),
            interpolation = Interpolation.CUBICSPLINE,
            inTangents = zeros,
            outTangents = FloatArray(6)
        )
        val out = FloatArray(3)
        val evaluator = ChannelEvaluator(channel)
        evaluator.evaluate(0.5f, out)
        assertEquals(5f, out[0], eps)
        evaluator.evaluate(0f, out)
        assertEquals(0f, out[0], eps)
        evaluator.evaluate(1f, out)
        assertEquals(10f, out[0], eps)
    }

    @Test
    fun `cubicspline memakai tangen keluar pada suku h10`() {
        // Nilai yang dihitung tangan untuk u = 0.5, span = 1:
        //   h00 = 0.5, h10 = 0.125, h01 = 0.5, h11 = -0.125
        //   hasil = 0.5*0 + 0.125*1*2 + 0.5*10 + (-0.125)*1*0 = 5.25
        val inT = FloatArray(6)
        val outT = FloatArray(6)
        outT[0] = 2f // tangen keluar keyframe ke-0 pada sumbu X
        val channel = translationChannel(
            floatArrayOf(0f, 1f),
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f),
            interpolation = Interpolation.CUBICSPLINE,
            inTangents = inT,
            outTangents = outT
        )
        val out = FloatArray(3)
        ChannelEvaluator(channel).evaluate(0.5f, out)
        assertEquals(5.25f, out[0], eps)
    }

    @Test
    fun `cubicspline rotasi menghasilkan kuaternion ternormalisasi`() {
        val q = rotZ(90f)
        val inT = FloatArray(8)
        val outT = FloatArray(8)
        // Tangen keluar keyframe pertama dibuat besar dengan sengaja, supaya hasil
        // mentah rumus Hermite jelas-jelas tidak bernorma satu. Kalau normalisasi di
        // akhir lupa dikerjakan, tes ini akan gagal.
        for (i in 0 until 4) { outT[i] = 5f }
        val channel = Channel(
            0, AnimPath.ROTATION, Interpolation.CUBICSPLINE,
            floatArrayOf(0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f, q[0], q[1], q[2], q[3]),
            inT,
            outT
        )
        val out = FloatArray(4)
        ChannelEvaluator(channel).evaluate(0.5f, out)
        val norm = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        assertEquals(1f, norm, 1e-5f)
    }

    // --- validasi ------------------------------------------------------------

    @Test
    fun `cubicspline tanpa tangen ditolak saat pembuatan channel`() {
        assertFailsWith<IllegalArgumentException> {
            Channel(
                0, AnimPath.TRANSLATION, Interpolation.CUBICSPLINE,
                floatArrayOf(0f, 1f), FloatArray(6)
            )
        }
    }

    @Test
    fun `jumlah nilai yang tidak cocok dengan jumlah keyframe ditolak`() {
        assertFailsWith<IllegalArgumentException> {
            Channel(0, AnimPath.TRANSLATION, Interpolation.LINEAR, floatArrayOf(0f, 1f), FloatArray(3))
        }
    }

    // --- ClipEvaluator -------------------------------------------------------

    @Test
    fun `ClipEvaluator mengunjungi setiap channel`() {
        val clip = Clip(
            "jalan",
            1f,
            listOf(
                Channel(
                    3, AnimPath.TRANSLATION, Interpolation.LINEAR,
                    floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)
                ),
                Channel(
                    4, AnimPath.ROTATION, Interpolation.LINEAR,
                    floatArrayOf(0f, 1f),
                    floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                )
            )
        )
        val seen = mutableListOf<String>()
        ClipEvaluator(clip).evaluate(0.5f) { node, path, _ -> seen += "$node/${path.name}" }
        assertEquals(listOf("3/TRANSLATION", "4/ROTATION"), seen)
    }

    @Test
    fun `byIndex Clip memetakan translasi dan rotasi node yang sama`() {
        val t = Channel(
            7, AnimPath.TRANSLATION, Interpolation.LINEAR,
            floatArrayOf(0f), floatArrayOf(0f, 0f, 0f)
        )
        val r = Channel(
            7, AnimPath.ROTATION, Interpolation.LINEAR,
            floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f)
        )
        val clip = Clip("x", 1f, listOf(t, r))
        assertEquals(2, clip.byIndex.size)
        assertTrue(clip.byIndex.containsKey(ChannelIndex(7, AnimPath.ROTATION)))
    }
}
