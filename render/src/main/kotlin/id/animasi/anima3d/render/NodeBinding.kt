package id.animasi.anima3d.render

import com.google.android.filament.gltfio.FilamentAsset
import id.animasi.anima3d.gltf.GltfDocument
import id.animasi.anima3d.gltf.Skeleton

/**
 * Seberapa jauh pemetaan node glTF ke entity Filament bisa dipercaya.
 *
 * Ini bukan detail internal: seluruh editor bergantung pada kemampuan menulis
 * transform ke node yang benar. Kalau pemetaannya salah, menggeser satu bone akan
 * menggerakkan bone lain, dan tidak ada cara bagi pengguna untuk menyadarinya.
 */
enum class BindingConfidence {
    /** Diverifikasi: urutan `getEntities()` sepadan dengan `nodes[]`, dan joint cocok. */
    VERIFIED_BY_INDEX,

    /** Urutan tidak sepadan, tetapi nama node unik sehingga bisa dipakai sebagai kunci. */
    FALLBACK_BY_NAME,

    /** Tidak ada dasar yang bisa dipercaya. Editor harus menolak masuk mode edit. */
    UNRELIABLE
}

class BindingReport(
    val gltfNodeCount: Int,
    val fixtureEntityCount: Int,
    val namesChecked: Int,
    val namesMatched: Int,
    val skinsCompared: Int,
    val expectedJointEntities: Int,
    val matchedJointEntities: Int,
    val confidence: BindingConfidence,
    val notes: List<String>
) {
    val isVerified: Boolean get() = confidence == BindingConfidence.VERIFIED_BY_INDEX

    fun toMultilineString(): String = buildString {
        appendLine("Node di glTF: $gltfNodeCount")
        appendLine("Entity dari Filament: $fixtureEntityCount")
        appendLine("Nama dicocokkan: $namesMatched/$namesChecked")
        if (skinsCompared > 0) {
            appendLine("Joint cocok: $matchedJointEntities/$expectedJointEntities ($skinsCompared skin)")
        } else {
            appendLine("Joint cocok: tidak ada skin")
        }
        appendLine("Keyakinan: $confidence")
        if (notes.isNotEmpty()) {
            appendLine("Catatan:")
            notes.forEach { appendLine("  - $it") }
        }
    }.trimEnd()
}

/**
 * Pemetaan dua arah antara indeks node di berkas glTF dan entity Filament.
 *
 * ## Kenapa kelas ini ada
 *
 * Filament membaca `.glb` dengan baik, tetapi API-nya tidak memberi tahu:
 * node glTF ke-`i` menjadi entity yang mana, dan node mana yang berupa joint.
 * `FilamentInstance.getJointsAt` mengembalikan entity joint, namun tanpa indeks
 * node, nama, maupun hierarkinya. Tanpa pemetaan ini, editor tidak punya cara
 * mengaitkan keyframe dengan bone di mesin render.
 *
 * ## Cara pemetaan diverifikasi
 *
 * Dua pemeriksaan independen, keduanya dijalankan saat model dimuat:
 *
 * 1. **Nama.** Untuk setiap node yang punya nama di glTF, nama entity pada posisi
 *    yang sama harus identik. Ini menangkap pengurutan ulang.
 * 2. **Joint.** Untuk setiap skin, himpunan entity yang dilaporkan Filament lewat
 *    `getJointsAt` harus sama dengan himpunan entity yang dihitung dari daftar
 *    `skins[].joints` di berkas. Ini memeriksa sisi yang paling penting bagi editor.
 *
 * Hasilnya dilaporkan apa adanya ke panel diagnostik, bukan diasumsikan benar.
 */
class NodeBinding private constructor(
    private val nodeToEntity: IntArray,
    private val entityToNode: HashMap<Int, Int>,
    val report: BindingReport
) {

    val confidence: BindingConfidence get() = report.confidence

    /** Entity untuk indeks node glTF, atau -1 bila tidak terpetakan. */
    fun entityForNode(nodeIndex: Int): Int =
        if (nodeIndex in nodeToEntity.indices) nodeToEntity[nodeIndex] else -1

    /** Indeks node glTF untuk sebuah entity, atau -1 bila tidak dikenal. */
    fun nodeForEntity(entity: Int): Int = entityToNode[entity] ?: -1

    /** Apakah editor boleh masuk mode edit (menulis keyframe ke bone). */
    val canEdit: Boolean get() = confidence != BindingConfidence.UNRELIABLE

    companion object {

        fun build(asset: FilamentAsset, document: GltfDocument, skeleton: Skeleton): NodeBinding {
            val nodes = document.root.nodes ?: emptyList()
            val entities = asset.entities
            val notes = ArrayList<String>()

            val countMatches = entities.size == nodes.size
            if (!countMatches) {
                notes += "Jumlah entity (${entities.size}) berbeda dari jumlah node glTF (${nodes.size}). " +
                    "Kemungkinan Filament membuang node tanpa mesh, atau menambah entity sendiri."
            }

            // --- Pemeriksaan 1: nama pada posisi yang sama ---------------------
            var namesChecked = 0
            var namesMatched = 0
            var firstMismatch: String? = null
            val overlapping = minOf(entities.size, nodes.size)
            for (i in 0 until overlapping) {
                val expected = nodes[i].name ?: continue
                if (expected.isEmpty()) continue
                namesChecked++
                val actual = asset.getName(entities[i])
                if (actual == expected) {
                    namesMatched++
                } else if (firstMismatch == null) {
                    firstMismatch = "node $i: glTF '$expected' vs Filament '${actual ?: "(tanpa nama)"}'"
                }
            }
            if (namesChecked == 0) {
                notes += "Berkas tidak memberi nama pada node mana pun, jadi urutan tidak bisa diperiksa lewat nama."
            }
            firstMismatch?.let { notes += "Ketidakcocokan nama pertama: $it" }

            // --- Pemeriksaan 2: himpunan joint per skin ------------------------
            val instance = asset.instance
            var skinsCompared = 0
            var expectedJoints = 0
            var matchedJoints = 0
            for (skin in skeleton.skins) {
                if (skin.index >= instance.skinCount) {
                    notes += "Skin ${skin.index} tidak ada di Filament (skinCount=${instance.skinCount})."
                    continue
                }
                val filamentJoints = instance.getJointsAt(skin.index).toHashSet()
                val expected = skin.joints
                    .filter { it in entities.indices }
                    .map { entities[it] }
                    .toHashSet()

                skinsCompared++
                expectedJoints += expected.size
                matchedJoints += expected.count { filamentJoints.contains(it) }

                val missing = expected.count { !filamentJoints.contains(it) }
                val extra = filamentJoints.count { !expected.contains(it) }
                if (missing > 0 || extra > 0) {
                    notes += "Skin ${skin.index} (${skin.name}): $missing joint tidak dilaporkan Filament, " +
                        "$extra joint tak terduga dari Filament."
                }
            }

            val orderingVerified = countMatches && namesChecked > 0 && namesMatched == namesChecked
            val jointsVerified = skinsCompared == 0 || matchedJoints == expectedJoints

            val confidence: BindingConfidence
            var nodeToEntity = IntArray(nodes.size) { -1 }
            val entityToNode = HashMap<Int, Int>(nodes.size * 2)

            if (orderingVerified && jointsVerified) {
                confidence = BindingConfidence.VERIFIED_BY_INDEX
                for (i in 0 until overlapping) {
                    nodeToEntity[i] = entities[i]
                    entityToNode[entities[i]] = i
                }
            } else {
                // Cadangan: cocokkan lewat nama, tetapi hanya bila setiap nama unik.
                val nameToNode = HashMap<String, Int>()
                var duplicateNames = 0
                for ((index, node) in nodes.withIndex()) {
                    val name = node.name
                    if (name.isNullOrEmpty()) continue
                    if (nameToNode.put(name, index) != null) duplicateNames++
                }

                if (duplicateNames == 0 && nameToNode.isNotEmpty()) {
                    confidence = BindingConfidence.FALLBACK_BY_NAME
                    notes += "Urutan tidak terverifikasi; pemetaan jatuh ke pencocokan nama. " +
                        "Node tanpa nama tidak bisa dipetakan."
                    for ((name, nodeIndex) in nameToNode) {
                        val entity = asset.getFirstEntityByName(name)
                        if (entity != 0) {
                            nodeToEntity[nodeIndex] = entity
                            entityToNode[entity] = nodeIndex
                        }
                    }
                } else {
                    confidence = BindingConfidence.UNRELIABLE
                    if (duplicateNames > 0) {
                        notes += "Ada $duplicateNames nama node yang duplikat, sehingga pencocokan nama tidak aman."
                    }
                    notes += "Editor tidak akan mengizinkan penyuntingan bone pada model ini."
                    nodeToEntity = IntArray(0)
                }
            }

            val report = BindingReport(
                gltfNodeCount = nodes.size,
                fixtureEntityCount = entities.size,
                namesChecked = namesChecked,
                namesMatched = namesMatched,
                skinsCompared = skinsCompared,
                expectedJointEntities = expectedJoints,
                matchedJointEntities = matchedJoints,
                confidence = confidence,
                notes = notes
            )

            return NodeBinding(nodeToEntity, entityToNode, report)
        }
    }
}
