package id.animasi.anima3d.anim

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Operasi kuaternion dan vektor yang dibutuhkan sampler.
 *
 * Semua fungsi menulis ke array yang disediakan pemanggil (tidak mengembalikan objek baru).
 * Ini disengaja: evaluasi animasi berjalan setiap frame dan tidak boleh menghasilkan
 * sampah memori.
 */
object Numeric {

    fun identityQuaternion(out: FloatArray) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f
    }

    fun normalize(v: FloatArray, offset: Int, size: Int) {
        var lenSq = 0f
        for (i in 0 until size) lenSq += v[offset + i] * v[offset + i]
        if (lenSq <= 1e-20f) {
            // Kuaternion nol tidak punya arah; identitas adalah pilihan paling aman.
            if (size == 4) {
                identityQuaternionInto(v, offset)
            } else {
                for (i in 0 until size) v[offset + i] = 0f
            }
            return
        }
        val inv = 1f / sqrt(lenSq)
        for (i in 0 until size) v[offset + i] *= inv
    }

    private fun identityQuaternionInto(out: FloatArray, offset: Int) {
        out[offset] = 0f; out[offset + 1] = 0f; out[offset + 2] = 0f; out[offset + 3] = 1f
    }

    fun lerp(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int, t: Float, out: FloatArray, outOffset: Int, size: Int) {
        for (i in 0 until size) {
            val x = a[aOffset + i]
            out[outOffset + i] = x + (b[bOffset + i] - x) * t
        }
    }

    /**
     * Interpolasi bola untuk rotasi.
     *
     * glTF mensyaratkan slerp untuk interpolasi LINEAR pada kuaternion. Lerp biasa akan
     * membuat kecepatan sudut tidak rata saat antar-keyframe berbeda jauh, dan tanpa
     * penanganan tanda (q dan -q adalah rotasi yang sama) model bisa berputar balik
     * melewati jalur yang panjang.
     */
    fun slerp(
        a: FloatArray, aOffset: Int,
        b: FloatArray, bOffset: Int,
        t: Float,
        out: FloatArray, outOffset: Int
    ) {
        var bx = b[bOffset]
        var by = b[bOffset + 1]
        var bz = b[bOffset + 2]
        var bw = b[bOffset + 3]

        var dot = a[aOffset] * bx + a[aOffset + 1] * by + a[aOffset + 2] * bz + a[aOffset + 3] * bw

        // q dan -q menyatakan rotasi yang sama. Ambil jalur terpendek.
        if (dot < 0f) {
            dot = -dot
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }

        if (dot > 0.9995f) {
            // Terlalu berdekatan: slerp jadi tidak stabil secara numerik, lerp lalu
            // dinormalisasi memberi hasil yang praktis sama.
            out[outOffset] = a[aOffset] + (bx - a[aOffset]) * t
            out[outOffset + 1] = a[aOffset + 1] + (by - a[aOffset + 1]) * t
            out[outOffset + 2] = a[aOffset + 2] + (bz - a[aOffset + 2]) * t
            out[outOffset + 3] = a[aOffset + 3] + (bw - a[aOffset + 3]) * t
            normalize(out, outOffset, 4)
            return
        }

        val theta0 = acos(dot.coerceIn(-1f, 1f))
        val theta = theta0 * t
        val sinTheta0 = sin(theta0)
        val sinTheta = sin(theta)

        val s0 = cos(theta) - dot * sinTheta / sinTheta0
        val s1 = sinTheta / sinTheta0

        out[outOffset] = a[aOffset] * s0 + bx * s1
        out[outOffset + 1] = a[aOffset + 1] * s0 + by * s1
        out[outOffset + 2] = a[aOffset + 2] * s0 + bz * s1
        out[outOffset + 3] = a[aOffset + 3] * s0 + bw * s1
    }

    /** Kuaternion → matriks 4x4 kolom-mayor, disimpan ke [out] (16 float). */
    fun quaternionToMatrix(q: FloatArray, qOffset: Int, out: FloatArray, outOffset: Int) {
        val x = q[qOffset]; val y = q[qOffset + 1]; val z = q[qOffset + 2]; val w = q[qOffset + 3]
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2

        out[outOffset] = 1f - (yy + zz); out[outOffset + 1] = xy + wz; out[outOffset + 2] = xz - wy; out[outOffset + 3] = 0f
        out[outOffset + 4] = xy - wz; out[outOffset + 5] = 1f - (xx + zz); out[outOffset + 6] = yz + wx; out[outOffset + 7] = 0f
        out[outOffset + 8] = xz + wy; out[outOffset + 9] = yz - wx; out[outOffset + 10] = 1f - (xx + yy); out[outOffset + 11] = 0f
        out[outOffset + 12] = 0f; out[outOffset + 13] = 0f; out[outOffset + 14] = 0f; out[outOffset + 15] = 1f
    }

    /**
     * Menyusun matriks TRS kolom-mayor: `M = T * R * S`, hasil di [out] (16 float).
     *
     * Filament menerima transform sebagai matriks 4x4 lewat `TransformManager.setTransform`,
     * jadi kita perlu langkah ini setiap kali menulis pose ke engine.
     */
    fun composeTrs(
        translation: FloatArray, tOffset: Int,
        rotation: FloatArray, rOffset: Int,
        scale: FloatArray, sOffset: Int,
        out: FloatArray, outOffset: Int
    ) {
        quaternionToMatrix(rotation, rOffset, out, outOffset)
        val sx = scale[sOffset]; val sy = scale[sOffset + 1]; val sz = scale[sOffset + 2]
        for (col in 0 until 3) {
            out[outOffset + col * 4] *= sx
            out[outOffset + col * 4 + 1] *= sy
            out[outOffset + col * 4 + 2] *= sz
        }
        out[outOffset + 12] = translation[tOffset]
        out[outOffset + 13] = translation[tOffset + 1]
        out[outOffset + 14] = translation[tOffset + 2]
        out[outOffset + 15] = 1f
    }

    /** Membandingkan dua kuaternion tanpa memperdulikan tanda (rotasi yang sama). */    fun quaternionEquals(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int, epsilon: Float = 1e-4f): Boolean {
        val same = abs(a[aOffset] - b[bOffset]) < epsilon &&
            abs(a[aOffset + 1] - b[bOffset + 1]) < epsilon &&
            abs(a[aOffset + 2] - b[bOffset + 2]) < epsilon &&
            abs(a[aOffset + 3] - b[bOffset + 3]) < epsilon
        if (same) return true
        return abs(a[aOffset] + b[bOffset]) < epsilon &&
            abs(a[aOffset + 1] + b[bOffset + 1]) < epsilon &&
            abs(a[aOffset + 2] + b[bOffset + 2]) < epsilon &&
            abs(a[aOffset + 3] + b[bOffset + 3]) < epsilon
    }

    /**
     * Memecah matriks 4x4 kolom-mayor menjadi translasi, rotasi, dan skala.
     *
     * Node di glTF boleh menyimpan transform sebagai TRS terpisah atau sebagai satu
     * matriks. Kalau berbentuk matriks, nilai TRS harus direkonstruksi sebelum bisa
     * diedit atau disamakan dengan keyframe animasi.
     *
     * Asumsi: matriks tidak mengandung shear. Shear tidak bisa diwakili oleh TRS, jadi
     * pemeriksaannya ada di [hasShear].
     */
    fun decomposeMatrix(
        m: FloatArray, mOffset: Int,
        translationOut: FloatArray, tOffset: Int,
        rotationOut: FloatArray, rOffset: Int,
        scaleOut: FloatArray, sOffset: Int
    ) {
        fun at(row: Int, col: Int): Float = m[mOffset + col * 4 + row]

        translationOut[tOffset] = at(0, 3)
        translationOut[tOffset + 1] = at(1, 3)
        translationOut[tOffset + 2] = at(2, 3)

        // Panjang tiap kolom basis adalah faktor skala per sumbu.
        val sx = sqrt(at(0, 0) * at(0, 0) + at(1, 0) * at(1, 0) + at(2, 0) * at(2, 0))
        val sy = sqrt(at(0, 1) * at(0, 1) + at(1, 1) * at(1, 1) + at(2, 1) * at(2, 1))
        val sz = sqrt(at(0, 2) * at(0, 2) + at(1, 2) * at(1, 2) + at(2, 2) * at(2, 2))

        scaleOut[sOffset] = if (sx > 1e-12f) sx else 1f
        scaleOut[sOffset + 1] = if (sy > 1e-12f) sy else 1f
        scaleOut[sOffset + 2] = if (sz > 1e-12f) sz else 1f

        val invX = 1f / scaleOut[sOffset]
        val invY = 1f / scaleOut[sOffset + 1]
        val invZ = 1f / scaleOut[sOffset + 2]

        val r00 = at(0, 0) * invX; val r01 = at(0, 1) * invY; val r02 = at(0, 2) * invZ
        val r10 = at(1, 0) * invX; val r11 = at(1, 1) * invY; val r12 = at(1, 2) * invZ
        val r20 = at(2, 0) * invX; val r21 = at(2, 1) * invY; val r22 = at(2, 2) * invZ

        quaternionFromRotationMatrix(r00, r01, r02, r10, r11, r12, r20, r21, r22, rotationOut, rOffset)
    }

    /**
     * Kuaternion dari matriks rotasi 3x3, memakai metode Shepperd.
     *
     * Tiga cabang dipilih berdasarkan elemen diagonal terbesar agar pembaginya tidak
     * pernah mendekati nol — versi sederhana yang selalu memakai trace akan kehilangan
     * presisi untuk rotasi mendekati 180 derajat.
     */
    fun quaternionFromRotationMatrix(
        r00: Float, r01: Float, r02: Float,
        r10: Float, r11: Float, r12: Float,
        r20: Float, r21: Float, r22: Float,
        out: FloatArray, offset: Int
    ) {
        val trace = r00 + r11 + r22
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            out[offset + 3] = 0.25f * s
            out[offset] = (r21 - r12) / s
            out[offset + 1] = (r02 - r20) / s
            out[offset + 2] = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            out[offset + 3] = (r21 - r12) / s
            out[offset] = 0.25f * s
            out[offset + 1] = (r01 + r10) / s
            out[offset + 2] = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            out[offset + 3] = (r02 - r20) / s
            out[offset] = (r01 + r10) / s
            out[offset + 1] = 0.25f * s
            out[offset + 2] = (r12 + r21) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            out[offset + 3] = (r10 - r01) / s
            out[offset] = (r02 + r20) / s
            out[offset + 1] = (r12 + r21) / s
            out[offset + 2] = 0.25f * s
        }
        normalize(out, offset, 4)
    }

    /**
     * Mendeteksi shear pada matriks 4x4 kolom-mayor.
     *
     * Shear tidak bisa dinyatakan sebagai TRS, jadi node seperti ini tidak boleh
     * dijadikan target keyframe tanpa peringatan.
     */
    fun hasShear(m: FloatArray, mOffset: Int, tolerance: Float = 1e-3f): Boolean {
        fun at(row: Int, col: Int): Float = m[mOffset + col * 4 + row]
        fun dotCol(a: Int, b: Int): Float =
            at(0, a) * at(0, b) + at(1, a) * at(1, b) + at(2, a) * at(2, b)

        val c0c1 = dotCol(0, 1)
        val c0c2 = dotCol(0, 2)
        val c1c2 = dotCol(1, 2)

        fun norm(c: Int): Float = sqrt(dotCol(c, c))
        val n0 = norm(0); val n1 = norm(1); val n2 = norm(2)
        if (n0 <= 1e-12f || n1 <= 1e-12f || n2 <= 1e-12f) return true

        return abs(c0c1 / (n0 * n1)) > tolerance ||
            abs(c0c2 / (n0 * n2)) > tolerance ||
            abs(c1c2 / (n1 * n2)) > tolerance
    }
}
