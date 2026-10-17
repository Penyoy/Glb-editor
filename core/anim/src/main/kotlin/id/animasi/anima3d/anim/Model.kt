package id.animasi.anima3d.anim

/** Properti node yang dianimasikan. Nilainya sama dengan `target.path` di spesifikasi glTF. */
enum class AnimPath(val components: Int) {
    TRANSLATION(3),
    ROTATION(4),
    SCALE(3);

    companion object {
        fun fromGltfPath(path: String): AnimPath? = when (path) {
            "translation" -> TRANSLATION
            "rotation" -> ROTATION
            "scale" -> SCALE
            else -> null // mis. "weights" atau pointer KHR_animation_pointer, belum didukung
        }
    }
}

enum class Interpolation {
    STEP,
    LINEAR,
    CUBICSPLINE;

    companion object {
        /** glTF: `interpolation` boleh tidak ada, dan defaultnya LINEAR. */
        fun fromGltfName(name: String?): Interpolation = when (name?.uppercase()) {
            null, "LINEAR" -> LINEAR
            "STEP" -> STEP
            "CUBICSPLINE" -> CUBICSPLINE
            else -> LINEAR
        }
    }
}

/**
 * Satu jalur animasi untuk satu node.
 *
 * Data disimpan sebagai array rata (structure-of-arrays), bukan `List<Keyframe>`.
 * Alasannya performa: evaluasi berjalan setiap frame untuk setiap channel, dan
 * `List<Keyframe>` berarti ribuan objek kecil yang harus dialokasikan lalu
 * dikumpulkan GC. Dengan array rata, nol alokasi saat playback.
 *
 * Layout untuk [values]: `keyCount * components`, terurut berdasarkan waktu.
 *
 * Untuk [Interpolation.CUBICSPLINE], [inTangents] dan [outTangents] wajib ada dan
 * panjangnya sama dengan [values]. glTF menyimpan keduanya di dalam accessor output
 * dengan urutan per-keys: in-tangent, value, out-tangent. Kelas ini memisahkannya
 * supaya sampler tidak perlu tahu soal layout itu.
 */
class Channel(
    val nodeIndex: Int,
    val path: AnimPath,
    val interpolation: Interpolation,
    /** Waktu keyframe dalam detik, menaik. Panjang = jumlah keyframe. */
    val times: FloatArray,
    val values: FloatArray,
    val inTangents: FloatArray? = null,
    val outTangents: FloatArray? = null
) {
    val components: Int get() = path.components
    val keyCount: Int get() = times.size

    init {
        require(times.size * components == values.size) {
            "values punya ${values.size} float, seharusnya ${times.size * components} " +
                "(keyCount=${times.size}, components=$components) untuk node $nodeIndex ${path.name}"
        }
        if (interpolation == Interpolation.CUBICSPLINE) {
            require(inTangents != null && outTangents != null) {
                "CUBICSPLINE wajib punya inTangents dan outTangents (node $nodeIndex ${path.name})"
            }
            require(inTangents!!.size == values.size && outTangents!!.size == values.size) {
                "tangen CUBICSPLINE harus sepanjang values (node $nodeIndex ${path.name})"
            }
        }
    }

    fun timeAt(index: Int): Float = times[index]

    /** Menyalin nilai keyframe [index] ke [out] (panjang [components]). */
    fun valueAt(index: Int, out: FloatArray) {
        val base = index * components
        values.copyInto(out, 0, base, base + components)
    }

    override fun toString(): String =
        "Channel(node=$nodeIndex, ${path.name}, $interpolation, $keyCount keyframe)"
}

/** Sekumpulan channel yang membentuk satu animasi yang bisa diputar bersama. */
class Clip(
    val name: String,
    /** Durasi dalam detik. Diambil dari waktu keyframe terakhir, atau 0 bila kosong. */
    val duration: Float,
    val channels: List<Channel>
) {
    /** Indeks channel berdasarkan node+path, supaya UI bisa menanyakan "ada key di bone ini?". */
    val byIndex: Map<ChannelIndex, Channel> = channels.associateBy { ChannelIndex(it.nodeIndex, it.path) }

    val channelsSharingNodes: Set<Int> = channels.mapTo(mutableSetOf<Int>()) { it.nodeIndex }

    val isEmpty: Boolean get() = channels.isEmpty()

    override fun toString(): String = "Clip('$name', ${duration}s, ${channels.size} channel)"
}

/** Kunci pencarian channel: satu node bisa punya channel translasi, rotasi, dan skala sekaligus. */
data class ChannelIndex(val nodeIndex: Int, val path: AnimPath)
