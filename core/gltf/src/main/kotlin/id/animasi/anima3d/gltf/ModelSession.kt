package id.animasi.anima3d.gltf

import id.animasi.anima3d.anim.Clip

/**
 * Hasil lengkap pembacaan satu berkas `.glb`: dokumen mentah, kerangka, dan klip
 * animasi dalam bentuk yang bisa diedit.
 *
 * Pemisahan ini disengaja: semuanya murni data dan bisa dibentuk di JVM biasa, sehingga
 * jalan cerita "buka berkas → dapat klip yang bisa diedit" sudah teruji sebelum
 * menyentuh perangkat. Yang tersisa untuk modul render hanyalah memadankan node glTF
 * dengan entity Filament.
 */
class ModelSession(
    val document: GltfDocument,
    val skeleton: Skeleton,
    val clips: List<Clip>,
    val warnings: List<String>,
    val unsupportedChannelCount: Int,
    val byteSize: Int
) {
    val generator: String? get() = document.root.asset?.generator
    val gltfVersion: String? get() = document.root.asset?.version

    val nodeCount: Int get() = document.nodeCount
    val meshCount: Int get() = document.root.meshCount
    val materialCount: Int get() = document.root.materialCount
    val skinCount: Int get() = skeleton.skins.size
    val jointCount: Int get() = skeleton.totalJoints
    val hasAnimation: Boolean get() = clips.isNotEmpty()

    /** Ringkasan untuk panel diagnostik, satu baris per fakta. */
    fun summaryLines(): List<String> {
        val lines = ArrayList<String>(12)
        lines += "Ukuran berkas: ${byteSize / 1024} KB"
        lines += "Versi glTF: ${gltfVersion ?: "tidak disebut"}"
        generator?.let { lines += "Pembuat: $it" }
        lines += "Node: $nodeCount"
        lines += "Mesh: $meshCount"
        lines += "Material: $materialCount"
        lines += "Skin: $skinCount"
        lines += "Joint: $jointCount"
        lines += "Klip animasi: ${clips.size}"

        if (document.root.extensionsRequired?.isNotEmpty() == true) {
            lines += "Ekstensi wajib: ${document.root.extensionsRequired.joinToString(", ")}"
        }
        if (skeleton.matrixNodes.isNotEmpty()) {
            lines += "Node berbentuk matriks: ${skeleton.matrixNodes.size}"
        }
        if (skeleton.shearNodes.isNotEmpty()) {
            lines += "Node mengandung shear: ${skeleton.shearNodes.size} (tidak bisa dianimasikan sebagai TRS)"
        }
        if (unsupportedChannelCount > 0) {
            lines += "Channel dilewati (mis. morph target): $unsupportedChannelCount"
        }
        return lines
    }

    companion object {
        fun parse(bytes: ByteArray): ModelSession {
            val document = GltfDocument.parse(bytes)
            val skeleton = Skeleton(document)
            val conversion = ClipConverter.convert(document)

            val warnings = ArrayList<String>(skeleton.warnings.size + conversion.warnings.size + 1)
            warnings += skeleton.warnings
            warnings += conversion.warnings
            if (!skeleton.hasSkeleton) {
                warnings += "Model tidak punya kerangka, jadi tidak ada bone yang bisa dianimasikan."
            }
            if (conversion.clips.isEmpty()) {
                warnings += "Model tidak punya klip animasi. Anda masih bisa membuat klip baru."
            }

            return ModelSession(
                document = document,
                skeleton = skeleton,
                clips = conversion.clips,
                warnings = warnings,
                unsupportedChannelCount = conversion.unsupportedChannelCount,
                byteSize = bytes.size
            )
        }
    }
}
