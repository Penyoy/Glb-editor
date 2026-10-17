package id.animasi.anima3d.gltf

import id.animasi.anima3d.anim.Numeric

/**
 * Satu skin: daftar joint dan matriks inverse bind-nya.
 *
 * [inverseBindMatrices] berisi `joints.size * 16` float kolom-mayor, atau null bila
 * berkas tidak menyediakannya (berarti matriks identitas).
 */
class SkinInfo(
    val index: Int,
    val name: String,
    val joints: IntArray,
    val skeletonRoot: Int?,
    val inverseBindMatrices: FloatArray?
) {
    val jointCount: Int get() = joints.size
}

/** Satu joint siap ditampilkan di daftar hierarki UI. */
data class JointEntry(
    val nodeIndex: Int,
    val name: String,
    /** Kedalaman relatif terhadap joint lain; node non-joint di antaranya tidak menambah kedalaman. */
    val depth: Int,
    /** Jumlah anak langsung yang juga joint. */
    val childJointCount: Int,
    val skinIndices: List<Int>
)

/**
 * Ketebalan (rig) dari sebuah berkas glTF.
 *
 * Filament tahu mana yang joint lewat `FilamentInstance.getJointsAt`, tetapi tidak
 * mengungkap nama, hierarki, maupun indeks node-nya. Kelas ini melengkapinya dari
 * hasil parse berkas.
 */
class Skeleton(val document: GltfDocument) {

    val nodeCount: Int = document.nodeCount

    /** `parent[i]` = induk node ke-i di hierarki penuh (termasuk node non-joint), atau -1. */
    val parent: IntArray = document.parent

    /**
     * Rest pose setiap node: 10 float per node dengan urutan
     * `[tx, ty, tz, qx, qy, qz, qw, sx, sy, sz]`.
     *
     * Flat array dipilih agar penerapannya ke engine bisa menyalin blok memori tanpa
     * membuat objek per node.
     */
    val restTrs: FloatArray = FloatArray(nodeCount * 10)

    /** Node yang transformnya berasal dari matriks (bukan TRS), untuk ditampilkan sebagai catatan. */
    val matrixNodes: IntArray = document.nodesUsingMatrix()

    /** Node yang matriksnya mengandung shear, sehingga tidak bisa dianimasikan sebagai TRS. */
    val shearNodes: IntArray

    val skins: List<SkinInfo>

    private val jointSet = HashSet<Int>()
    private val nodeToSkins = HashMap<Int, MutableList<Int>>()
    private val warningList = ArrayList<String>()

    /**
     * Masalah yang ditemukan saat membaca kerangka.
     *
     * Pembacaan kerangka sengaja tidak melempar pengecualian: model yang rangkanya
     * rusak tetap bisa ditampilkan sebagai objek statis, dan pengguna lebih terbantu
     * oleh peringatan yang jelas daripada aplikasi yang menolak membuka berkas.
     */
    val warnings: List<String> get() = warningList

    /** Indeks node yang merupakan joint, menaik dan tanpa duplikat. */
    val jointNodes: IntArray

    init {
        val t = FloatArray(3)
        val r = FloatArray(4)
        val s = FloatArray(3)
        val m = FloatArray(16)
        val sheared = ArrayList<Int>()
        for (i in 0 until nodeCount) {
            document.nodeLocalTrs(i, t, r, s)
            val base = i * 10
            restTrs[base] = t[0]; restTrs[base + 1] = t[1]; restTrs[base + 2] = t[2]
            restTrs[base + 3] = r[0]; restTrs[base + 4] = r[1]
            restTrs[base + 5] = r[2]; restTrs[base + 6] = r[3]
            restTrs[base + 7] = s[0]; restTrs[base + 8] = s[1]; restTrs[base + 9] = s[2]

            val matrix = document.node(i)?.matrix
            if (matrix != null && matrix.size == 16) {
                for (k in 0 until 16) m[k] = matrix[k]
                if (Numeric.hasShear(m, 0)) sheared.add(i)
            }
        }
        shearNodes = sheared.toIntArray()

        skins = buildSkins()
        for (skin in skins) {
            for (joint in skin.joints) {
                if (joint in 0 until nodeCount) {
                    jointSet.add(joint)
                    nodeToSkins.getOrPut(joint) { mutableListOf() }.add(skin.index)
                }
            }
        }
        jointNodes = jointSet.toIntArray().apply { sort() }
    }

    private fun buildSkins(): List<SkinInfo> {
        val declared = document.root.skins ?: return emptyList()
        return declared.mapIndexed { index, skin ->
            val declaredJointCount = skin.joints.size
            val joints = skin.joints.filter { it in 0 until nodeCount }.toIntArray()
            if (joints.size != declaredJointCount) {
                warningList.add(
                    "Skin $index menyebut $declaredJointCount joint, tetapi " +
                        "${declaredJointCount - joints.size} di antaranya menunjuk node yang tidak ada."
                )
            }

            val ibm = skin.inverseBindMatrices?.let { accessorIndex ->
                try {
                    val data = document.accessors.readFloats(accessorIndex)
                    if (data.size < joints.size * 16) {
                        warningList.add(
                            "Skin $index: inverseBindMatrices hanya ${data.size} float, " +
                                "seharusnya ${joints.size * 16}. Posisi rest dianggap identitas."
                        )
                        null
                    } else {
                        data
                    }
                } catch (e: GltfException) {
                    warningList.add(
                        "Skin $index: inverseBindMatrices tidak bisa dibaca (${e.message}). " +
                            "Posisi rest dianggap identitas."
                    )
                    null
                }
            }

            SkinInfo(
                index = index,
                name = skin.name ?: "skin_$index",
                joints = joints,
                skeletonRoot = skin.skeleton,
                inverseBindMatrices = ibm
            )
        }
    }

    fun isJoint(nodeIndex: Int): Boolean = jointSet.contains(nodeIndex)

    fun skinsFor(nodeIndex: Int): List<Int> = nodeToSkins[nodeIndex] ?: emptyList()

    val hasSkeleton: Boolean get() = skins.isNotEmpty() && jointNodes.isNotEmpty()

    val totalJoints: Int get() = jointNodes.size

    /**
     * Daftar joint dalam urutan hierarki, siap dipakai UI.
     *
     * Memakai tumpukan eksplisit dan penanda "sudah dikunjungi" karena berkas dari luar
     * tidak bisa dipercaya: hierarki melingkar atau sangat dalam tidak boleh membuat
     * aplikasi mati dengan StackOverflowError atau menggantung.
     */
    fun jointTree(): List<JointEntry> {
        val out = ArrayList<JointEntry>(jointNodes.size)
        val visited = BooleanArray(nodeCount)

        // Pasangan (node, kedalaman-joint).
        val stack = ArrayDeque<Pair<Int, Int>>()
        val roots = document.sceneRootNodes()
        for (i in roots.indices.reversed()) {
            val node = roots[i]
            if (node in 0 until nodeCount) stack.addLast(node to 0)
        }

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (node !in 0 until nodeCount || visited[node]) continue
            visited[node] = true

            // Variabel lokal ini sengaja diberi nama berbeda dari fungsi isJoint();
            // memberi nama yang sama akan menutupi fungsi itu di dalam blok ini.
            val selfIsJoint = isJoint(node)
            if (selfIsJoint) {
                var childJoints = 0
                for (child in document.childIndices(node)) {
                    if (isJoint(child)) childJoints++
                }
                out.add(
                    JointEntry(
                        nodeIndex = node,
                        name = document.nodeName(node),
                        depth = depth,
                        childJointCount = childJoints,
                        skinIndices = skinsFor(node)
                    )
                )
            }

            val childDepth = if (selfIsJoint) depth + 1 else depth
            val children = document.childIndices(node)
            for (i in children.indices.reversed()) {
                stack.addLast(children[i] to childDepth)
            }
        }

        // Node yang terlewat karena tidak tercapai dari scene (mis. dilampirkan ke scene
        // lain) tetap dimasukkan supaya daftar bone tidak tampak tidak lengkap.
        for (joint in jointNodes) {
            if (!visited[joint]) {
                out.add(
                    JointEntry(
                        nodeIndex = joint,
                        name = document.nodeName(joint),
                        depth = 0,
                        childJointCount = 0,
                        skinIndices = skinsFor(joint)
                    )
                )
            }
        }
        return out
    }
}
