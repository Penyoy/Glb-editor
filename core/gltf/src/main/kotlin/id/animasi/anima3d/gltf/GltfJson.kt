package id.animasi.anima3d.gltf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Subset skema glTF 2.0 yang dibutuhkan editor.
 *
 * Hanya bagian yang kita baca yang dimodelkan. Sisanya (mesh, material, tekstur)
 * ditampung sebagai [JsonObject] mentah supaya tetap bisa dihitung jumlahnya tanpa
 * harus menyalin seluruh skema. Penguraian memakai `ignoreUnknownKeys`, jadi file
 * dengan ekstensi apa pun tetap bisa dibuka.
 */
@Serializable
data class GltfRoot(
    val asset: GltfAssetInfo? = null,
    val scene: Int? = null,
    val scenes: List<GltfScene>? = null,
    val nodes: List<GltfNode>? = null,
    val skins: List<GltfSkin>? = null,
    val animations: List<GltfAnimation>? = null,
    val accessors: List<GltfAccessor>? = null,
    val bufferViews: List<GltfBufferView>? = null,
    val buffers: List<GltfBuffer>? = null,
    // Hanya untuk pelaporan; isinya tidak diurai.
    val meshes: List<JsonObject>? = null,
    val materials: List<JsonObject>? = null,
    val textures: List<JsonObject>? = null,
    val images: List<JsonObject>? = null,
    val extensionsUsed: List<String>? = null,
    val extensionsRequired: List<String>? = null
) {
    val nodeCount: Int get() = nodes?.size ?: 0
    val skinCount: Int get() = skins?.size ?: 0
    val animationCount: Int get() = animations?.size ?: 0
    val meshCount: Int get() = meshes?.size ?: 0
    val materialCount: Int get() = materials?.size ?: 0
}

@Serializable
data class GltfAssetInfo(
    val version: String? = null,
    val generator: String? = null,
    val copyright: String? = null
)

@Serializable
data class GltfScene(
    val name: String? = null,
    val nodes: List<Int>? = null
)

/**
 * Satu node di hierarki adegan.
 *
 * Transform boleh berbentuk [matrix] (16 float, kolom-mayor) atau TRS terpisah
 * ([translation] + [rotation] + [scale]). Spesifikasi melarang keduanya sekaligus.
 */
@Serializable
data class GltfNode(
    val name: String? = null,
    val children: List<Int>? = null,
    val mesh: Int? = null,
    val skin: Int? = null,
    val camera: Int? = null,
    val matrix: List<Float>? = null,
    val translation: List<Float>? = null,
    val rotation: List<Float>? = null,
    val scale: List<Float>? = null,
    val weights: List<Float>? = null
)

@Serializable
data class GltfSkin(
    val name: String? = null,
    val joints: List<Int> = emptyList(),
    val skeleton: Int? = null,
    val inverseBindMatrices: Int? = null
)

@Serializable
data class GltfAnimation(
    val name: String? = null,
    val channels: List<GltfAnimationChannel> = emptyList(),
    val samplers: List<GltfAnimationSampler> = emptyList()
)

@Serializable
data class GltfAnimationChannel(
    val sampler: Int,
    val target: GltfAnimationTarget
)

@Serializable
data class GltfAnimationTarget(
    val node: Int? = null,
    /** "translation" | "rotation" | "scale" | "weights" | "pointer". */
    val path: String,
    val extensions: JsonElement? = null
)

@Serializable
data class GltfAnimationSampler(
    val input: Int,
    val output: Int,
    /** "LINEAR" (default) | "STEP" | "CUBICSPLINE". */
    val interpolation: String? = null
)

@Serializable
data class GltfAccessor(
    val bufferView: Int? = null,
    val byteOffset: Int = 0,
    val componentType: Int = 5126,
    val normalized: Boolean = false,
    val count: Int = 0,
    /** "SCALAR" | "VEC2" | "VEC3" | "VEC4" | "MAT2" | "MAT3" | "MAT4". */
    val type: String = "SCALAR",
    val max: List<Float>? = null,
    val min: List<Float>? = null,
    val name: String? = null,
    /** Hanya dipakai untuk mendeteksi dan melaporkan accessor sparse yang belum didukung. */
    val sparse: JsonElement? = null
) {
    val componentCount: Int get() = componentCountOf(type)

    companion object {
        const val COMPONENT_INT8 = 5120
        const val COMPONENT_UINT8 = 5121
        const val COMPONENT_INT16 = 5122
        const val COMPONENT_UINT16 = 5123
        const val COMPONENT_UINT32 = 5125
        const val COMPONENT_FLOAT = 5126

        fun componentSize(componentType: Int): Int = when (componentType) {
            COMPONENT_INT8, COMPONENT_UINT8 -> 1
            COMPONENT_INT16, COMPONENT_UINT16 -> 2
            COMPONENT_UINT32, COMPONENT_FLOAT -> 4
            else -> throw GltfException("componentType $componentType tidak dikenal")
        }

        fun componentCountOf(type: String): Int = when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else -> throw GltfException("type accessor '$type' tidak dikenal")
        }
    }
}

@Serializable
data class GltfBufferView(
    val buffer: Int = 0,
    val byteOffset: Int = 0,
    val byteLength: Int = 0,
    /**
     * Jarak antar elemen bila data disisipkan (interleaved). Bila null, elemen
     * dianggap rapat (tightly packed).
     */
    val byteStride: Int? = null,
    val target: Int? = null,
    val name: String? = null
)

@Serializable
data class GltfBuffer(
    /** null berarti data ada di chunk BIN pada file .glb. */
    val uri: String? = null,
    val byteLength: Int = 0,
    val name: String? = null
)

/** Kesalahan yang bisa ditampilkan ke pengguna saat file .glb tidak sah. */
class GltfException(message: String, cause: Throwable? = null) : Exception(message, cause)
