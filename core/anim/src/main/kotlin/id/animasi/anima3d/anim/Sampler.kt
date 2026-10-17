package id.animasi.anima3d.anim

/**
 * Evaluator untuk satu [Channel].
 *
 * Satu instance dibuat per channel dan dipakai berulang kali setiap frame. Buffer
 * sementara dialokasikan di konstruktor, jadi [evaluate] tidak mengalokasikan apa pun.
 */
class ChannelEvaluator(val channel: Channel) {

    private val scratchA = FloatArray(channel.components)
    private val scratchB = FloatArray(channel.components)

    /**
     * Menghitung nilai channel pada [time] detik dan menuliskannya ke [out].
     * [out] harus punya panjang minimal `channel.components`.
     */
    fun evaluate(time: Float, out: FloatArray) {
        val count = channel.keyCount

        if (count == 0) {
            // Tidak ada keyframe: pakai nilai netral supaya pose tidak rusak.
            neutralValue(out)
            return
        }
        if (count == 1) {
            channel.valueAt(0, out)
            return
        }

        val i = indexOfKey(time)

        // Setelah keyframe terakhir (atau tepat di atasnya) nilainya konstan.
        if (i >= count - 1) {
            channel.valueAt(count - 1, out)
            return
        }

        val t0 = channel.times[i]
        val t1 = channel.times[i + 1]
        val span = t1 - t0
        val u = if (span <= 0f) 0f else ((time - t0) / span).coerceIn(0f, 1f)

        when (channel.interpolation) {
            Interpolation.STEP -> channel.valueAt(i, out)

            Interpolation.LINEAR -> {
                channel.valueAt(i, scratchA)
                channel.valueAt(i + 1, scratchB)
                if (channel.path == AnimPath.ROTATION) {
                    Numeric.slerp(scratchA, 0, scratchB, 0, u, out, 0)
                } else {
                    Numeric.lerp(scratchA, 0, scratchB, 0, u, out, 0, channel.components)
                }
            }

            Interpolation.CUBICSPLINE -> evaluateCubicSpline(i, u, span, out)
        }
    }

    /**
     * Indeks keyframe kiri dari segmen yang memuat [time].
     *
     * Mengembalikan `i` sehingga `times[i] <= time`, dan `i == keyCount - 1` bila [time]
     * berada di ujung akhir (atau di luar) rentang.
     */
    fun indexOfKey(time: Float): Int {
        val times = channel.times
        val last = times.size - 1
        if (last <= 0) return 0
        if (time <= times[0]) return 0
        if (time >= times[last]) return last

        var lo = 0
        var hi = last
        // Invarian: times[lo] <= time < times[hi]
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= time) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * Interpolasi Hermite untuk CUBICSPLINE.
     *
     * Rumus sesuai spesifikasi glTF:
     * `p(u) = h00*v0 + h10*span*b0 + h01*v1 + h11*span*a1`
     * dengan `b0` = tangen keluar keyframe kiri, `a1` = tangen masuk keyframe kanan.
     */
    private fun evaluateCubicSpline(i: Int, u: Float, span: Float, out: FloatArray) {
        val components = channel.components
        val values = channel.values
        val inTangents = channel.inTangents ?: return
        val outTangents = channel.outTangents ?: return

        val baseA = i * components
        val baseB = (i + 1) * components

        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2f * u3 - 3f * u2 + 1f
        val h10 = u3 - 2f * u2 + u
        val h01 = -2f * u3 + 3f * u2
        val h11 = u3 - u2

        for (k in 0 until components) {
            out[k] = h00 * values[baseA + k] +
                h10 * span * outTangents[baseA + k] +
                h01 * values[baseB + k] +
                h11 * span * inTangents[baseB + k]
        }

        // Spesifikasi glTF: hasil cubic spline untuk rotasi harus dinormalisasi.
        if (channel.path == AnimPath.ROTATION) {
            Numeric.normalize(out, 0, 4)
        }
    }

    private fun neutralValue(out: FloatArray) {
        if (channel.path == AnimPath.ROTATION) {
            Numeric.identityQuaternion(out)
        } else if (channel.path == AnimPath.SCALE) {
            out[0] = 1f; out[1] = 1f; out[2] = 1f
        } else {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
        }
    }
}

/**
 * Kumpulan evaluator untuk seluruh channel dalam satu [Clip].
 *
 * Dibuat sekali, lalu [evaluate] dipanggil setiap frame. Pemanggil menyediakan callback
 * supaya tiap node langsung menerima nilainya tanpa objek perantara.
 */
class ClipEvaluator(val clip: Clip) {

    private val evaluators: List<ChannelEvaluator> = clip.channels.map { ChannelEvaluator(it) }

    // 4 float cukup untuk translasi/skala (3) maupun rotasi (4).
    private val scratch = FloatArray(4)

    val channelCount: Int get() = evaluators.size

    /**
     * Mengecek apakah [time] sudah melewati durasi clip.
     * Peredaran waktu (loop) adalah keputusan pemanggil, bukan clip.
     */
    fun isFinished(time: Float): Boolean = time >= clip.duration

    /**
     * Menghitung seluruh channel pada [time] dan mengirim hasilnya ke [onSample].
     *
     * [onSample] menerima indeks node, path, dan array nilai. Array yang dikirim
     * dipakai ulang antar pemanggilan — salin bila perlu disimpan.
     */
    fun evaluate(time: Float, onSample: (nodeIndex: Int, path: AnimPath, values: FloatArray) -> Unit) {
        for (evaluator in evaluators) {
            val channel = evaluator.channel
            evaluator.evaluate(time, scratch)
            onSample(channel.nodeIndex, channel.path, scratch)
        }
    }
}
