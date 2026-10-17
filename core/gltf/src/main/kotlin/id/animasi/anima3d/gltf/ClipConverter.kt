package id.animasi.anima3d.gltf

import id.animasi.anima3d.anim.AnimPath
import id.animasi.anima3d.anim.Channel
import id.animasi.anima3d.anim.Clip
import id.animasi.anima3d.anim.Interpolation
import id.animasi.anima3d.anim.Numeric

/** Hasil konversi, termasuk catatan tentang bagian berkas yang terpaksa dilewati. */
class ClipConversionResult(
    val clips: List<Clip>,
    val warnings: List<String>,
    val unsupportedChannelCount: Int
)

/**
 * Mengubah animasi di dalam berkas glTF menjadi model [Clip] milik editor.
 *
 * Langkah ini yang membuat klip bawaan bisa diedit seperti klip buatan sendiri:
 * setelah dikonversi, editor tidak lagi memakai `Animator` milik Filament, melainkan
 * mengevaluasi channel-nya sendiri. Karena data hasil konversi murni angka, seluruh
 * logikanya bisa diuji di JVM tanpa perangkat.
 */
object ClipConverter {

    fun convert(document: GltfDocument): ClipConversionResult {
        val animations = document.root.animations
        if (animations.isNullOrEmpty()) {
            return ClipConversionResult(emptyList(), emptyList(), 0)
        }

        val warnings = ArrayList<String>()
        val clips = ArrayList<Clip>(animations.size)
        var unsupported = 0

        for ((index, animation) in animations.withIndex()) {
            val name = animation.name?.takeIf { it.isNotBlank() } ?: "animasi_$index"
            val channels = ArrayList<Channel>(animation.channels.size)
            var duration = 0f

            for ((channelOrdinal, gltfChannel) in animation.channels.withIndex()) {
                val where = "$name channel #$channelOrdinal"

                val path = AnimPath.fromGltfPath(gltfChannel.target.path)
                if (path == null) {
                    // Biasanya "weights" (morph target) atau pointer KHR_animation_pointer.
                    unsupported++
                    continue
                }

                val nodeIndex = gltfChannel.target.node
                if (nodeIndex == null) {
                    // Target pointer tidak menunjuk node; hanya relevan untuk ekstensi.
                    unsupported++
                    continue
                }

                val sampler = animation.samplers.getOrNull(gltfChannel.sampler)
                if (sampler == null) {
                    warnings += "$where: sampler ${gltfChannel.sampler} tidak ada, dilewati"
                    continue
                }

                val components = path.components
                val interpolation = Interpolation.fromGltfName(sampler.interpolation)

                val times = try {
                    document.accessors.readFloats(sampler.input)
                } catch (e: GltfException) {
                    warnings += "$where: gagal membaca waktu (${e.message}), dilewati"
                    continue
                }
                val rawOutput = try {
                    document.accessors.readFloats(sampler.output)
                } catch (e: GltfException) {
                    warnings += "$where: gagal membaca nilai (${e.message}), dilewati"
                    continue
                }

                if (times.isEmpty()) {
                    warnings += "$where: tidak punya keyframe, dilewati"
                    continue
                }
                if (!isStrictlyNonDecreasing(times)) {
                    // Pencarian biner memerlukan waktu yang menaik. Berkas seperti ini
                    // melanggar spesifikasi, dan menebak urutannya berisiko diam-diam salah.
                    warnings += "$where: waktu keyframe tidak menaik, dilewati"
                    continue
                }

                val expected = when (interpolation) {
                    Interpolation.CUBICSPLINE -> times.size * components * 3
                    else -> times.size * components
                }
                if (rawOutput.size < expected) {
                    warnings += "$where: $interpolation butuh $expected float, tersedia ${rawOutput.size}, dilewati"
                    continue
                }

                val channel = when (interpolation) {
                    Interpolation.CUBICSPLINE -> buildCubicSpline(nodeIndex, path, times, rawOutput, components)
                    else -> buildSimple(nodeIndex, path, interpolation, times, rawOutput, components)
                }
                channels.add(channel)

                val last = times[times.size - 1]
                if (last > duration) duration = last
            }

            if (channels.isEmpty()) {
                warnings += "'$name' tidak punya channel yang bisa dipakai, dilewati"
                continue
            }

            clips.add(Clip(name = name, duration = duration, channels = channels))
        }

        return ClipConversionResult(clips, warnings, unsupported)
    }

    private fun buildSimple(
        nodeIndex: Int,
        path: AnimPath,
        interpolation: Interpolation,
        times: FloatArray,
        rawOutput: FloatArray,
        components: Int
    ): Channel {
        val values = rawOutput.copyOf(times.size * components)

        // Kuaternion di berkas idealnya sudah bernorma satu, tetapi tidak semua pengekspor
        // memenuhinya. Dinormalisasi sekali di sini supaya slerp tidak menghasilkan
        // kuaternion yang mengembang. Nilai CUBICSPLINE sengaja tidak disentuh karena
        // tangennya dihitung terhadap nilai mentah.
        if (path == AnimPath.ROTATION) {
            for (k in 0 until times.size) {
                Numeric.normalize(values, k * components, 4)
            }
        }

        return Channel(
            nodeIndex = nodeIndex,
            path = path,
            interpolation = interpolation,
            times = times.copyOf(times.size),
            values = values
        )
    }

    /**
     * Memisahkan accessor output CUBICSPLINE yang berisi tiga nilai per keyframe:
     * in-tangent, value, out-tangent — dalam urutan itu.
     */
    private fun buildCubicSpline(
        nodeIndex: Int,
        path: AnimPath,
        times: FloatArray,
        rawOutput: FloatArray,
        components: Int
    ): Channel {
        val count = times.size
        val values = FloatArray(count * components)
        val inTangents = FloatArray(count * components)
        val outTangents = FloatArray(count * components)

        for (k in 0 until count) {
            val src = k * components * 3
            val dst = k * components
            rawOutput.copyInto(inTangents, dst, src, src + components)
            rawOutput.copyInto(values, dst, src + components, src + components * 2)
            rawOutput.copyInto(outTangents, dst, src + components * 2, src + components * 3)
        }

        return Channel(
            nodeIndex = nodeIndex,
            path = path,
            interpolation = Interpolation.CUBICSPLINE,
            times = times.copyOf(count),
            values = values,
            inTangents = inTangents,
            outTangents = outTangents
        )
    }

    private fun isStrictlyNonDecreasing(times: FloatArray): Boolean {
        for (i in 1 until times.size) {
            if (times[i] < times[i - 1]) return false
        }
        return true
    }
}
