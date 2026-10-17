package id.animasi.anima3d.gltf

import id.animasi.anima3d.anim.Numeric
import kotlinx.serialization.json.Json

/**
 * Berkas `.glb` yang sudah diurai.
 *
 * Kelas ini adalah sumber kebenaran tentang isi berkas: node mana yang ada, mana yang
 * berupa joint, dan bagaimana hierarkinya. Filament juga membaca berkas yang sama untuk
 * keperluan render, tetapi API-nya tidak mengungkap informasi tersebut, sehingga kedua
 * hasil baca perlu dipadankan (lihat `NodeBinding` di modul render).
 */
class GltfDocument(
    val root: GltfRoot,
    val bin: ByteArray?,
    val jsonText: String
) {

    val accessors: AccessorReader by lazy { AccessorReader(root, bin) }

    val nodeCount: Int get() = root.nodeCount

    /** `parent[i]` = induk node ke-i, atau -1 bila node itu akar. */
    val parent: IntArray by lazy { buildParentMap() }

    private val childrenCache: Array<IntArray> by lazy { buildChildren() }

    fun node(index: Int): GltfNode? = root.nodes?.getOrNull(index)

    fun nodeName(index: Int): String {
        val name = node(index)?.name
        return if (name.isNullOrBlank()) "node_$index" else name
    }

    fun childIndices(index: Int): IntArray =
        childrenCache.getOrElse(index) { IntArray(0) }

    /** Node yang tidak direferensikan sebagai anak oleh node mana pun. */
    fun rootNodes(): IntArray {
        val out = ArrayList<Int>()
        for (i in 0 until nodeCount) if (parent[i] < 0) out.add(i)
        return out.toIntArray()
    }

    /**
     * Node teratas dari scene yang dipakai. Bila `scene` tidak disebut, scene pertama
     * dipakai; bila tidak ada scene sama sekali, seluruh node akar dipakai.
     */
    fun sceneRootNodes(): IntArray {
        val scenes = root.scenes
        if (scenes.isNullOrEmpty()) return rootNodes()
        val sceneIndex = (root.scene ?: 0).coerceIn(0, scenes.size - 1)
        val nodes = scenes[sceneIndex].nodes
        return if (nodes.isNullOrEmpty()) rootNodes() else nodes.toIntArray()
    }

    private fun buildParentMap(): IntArray {
        val result = IntArray(nodeCount) { -1 }
        for (i in 0 until nodeCount) {
            val children = node(i)?.children ?: continue
            for (child in children) {
                if (child in 0 until nodeCount) result[child] = i
            }
        }
        return result
    }

    private fun buildChildren(): Array<IntArray> {
        val lists = Array(nodeCount) { mutableListOf<Int>() }
        for (i in 0 until nodeCount) {
            val children = node(i)?.children ?: continue
            for (child in children) {
                if (child in 0 until nodeCount) lists[i].add(child)
            }
        }
        return Array(nodeCount) { lists[it].toIntArray() }
    }

    /**
     * Transform lokal node, ditulis ke buffer yang disediakan pemanggil.
     *
     * Setiap buffer boleh null bila nilai itu tidak dibutuhkan.
     */
    fun nodeLocalTrs(
        index: Int,
        translationOut: FloatArray? = null,
        rotationOut: FloatArray? = null,
        scaleOut: FloatArray? = null
    ) {
        val node = node(index)

        val matrix = node?.matrix
        if (matrix != null && matrix.size == 16) {
            val m = FloatArray(16)
            for (i in 0 until 16) m[i] = matrix[i]
            Numeric.decomposeMatrix(
                m, 0,
                translationOut ?: FloatArray(3), 0,
                rotationOut ?: FloatArray(4), 0,
                scaleOut ?: FloatArray(3), 0
            )
            return
        }

        translationOut?.let { out ->
            val t = node?.translation
            out[0] = t?.getOrNull(0) ?: 0f
            out[1] = t?.getOrNull(1) ?: 0f
            out[2] = t?.getOrNull(2) ?: 0f
        }
        rotationOut?.let { out ->
            val r = node?.rotation
            out[0] = r?.getOrNull(0) ?: 0f
            out[1] = r?.getOrNull(1) ?: 0f
            out[2] = r?.getOrNull(2) ?: 0f
            out[3] = r?.getOrNull(3) ?: 1f
        }
        scaleOut?.let { out ->
            val s = node?.scale
            out[0] = s?.getOrNull(0) ?: 1f
            out[1] = s?.getOrNull(1) ?: 1f
            out[2] = s?.getOrNull(2) ?: 1f
        }
    }

    /** Node yang transformnya disimpan sebagai matriks, bukan TRS terpisah. */
    fun nodesUsingMatrix(): IntArray {
        val out = ArrayList<Int>()
        for (i in 0 until nodeCount) if (node(i)?.matrix != null) out.add(i)
        return out.toIntArray()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            allowSpecialFloatingPointValues = true
        }

        fun parse(bytes: ByteArray): GltfDocument {
            val glb = GlbContainer.read(bytes)
            return parseJson(glb.jsonText, glb.bin)
        }

        fun parseJson(jsonText: String, bin: ByteArray?): GltfDocument {
            val root = try {
                json.decodeFromString(GltfRoot.serializer(), jsonText)
            } catch (e: Exception) {
                throw GltfException(
                    "Bagian JSON dari berkas .glb tidak bisa dibaca: ${e.message}", e
                )
            }
            val version = root.asset?.version
            if (version != null && !version.startsWith("2")) {
                throw GltfException("Versi glTF $version tidak didukung; hanya versi 2.x")
            }
            return GltfDocument(root, bin, jsonText)
        }
    }
}
