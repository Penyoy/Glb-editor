package id.animasi.anima3d.gltf

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GltfDocumentTest {

    @Test
    fun `mengurai berkas rig dari fixture`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        assertEquals(4, doc.nodeCount)
        assertEquals(1, doc.root.skinCount)
        assertEquals(1, doc.root.animationCount)
        assertEquals("2.0", doc.root.asset?.version)
    }

    @Test
    fun `peta induk dan anak sesuai hierarki`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        assertEquals(-1, doc.parent[Fixtures.NODE_ROOT])
        assertEquals(Fixtures.NODE_ROOT, doc.parent[Fixtures.NODE_HIP])
        assertEquals(Fixtures.NODE_HIP, doc.parent[Fixtures.NODE_KNEE])
        // "Body" sengaja tidak dicantumkan sebagai anak oleh node mana pun, jadi ia
        // adalah akar tersendiri di luar hierarki Root.
        assertEquals(-1, doc.parent[Fixtures.NODE_MESH])

        assertTrue(doc.childIndices(Fixtures.NODE_HIP).contentEquals(intArrayOf(Fixtures.NODE_KNEE)))
        assertTrue(doc.rootNodes().contentEquals(intArrayOf(Fixtures.NODE_ROOT)))
        assertTrue(doc.sceneRootNodes().contentEquals(intArrayOf(Fixtures.NODE_ROOT)))
    }

    @Test
    fun `TRS node dibaca apa adanya`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        val t = FloatArray(3)
        val r = FloatArray(4)
        val s = FloatArray(3)
        doc.nodeLocalTrs(Fixtures.NODE_HIP, t, r, s)

        assertEquals(0f, t[0], 1e-6f)
        assertEquals(1f, t[1], 1e-6f)
        assertEquals(0f, t[2], 1e-6f)

        // Node tanpa rotasi dan skala harus memakai nilai identitas, bukan nol.
        assertEquals(1f, r[3], 1e-6f)
        assertEquals(1f, s[0], 1e-6f)
        assertEquals(1f, s[1], 1e-6f)
        assertEquals(1f, s[2], 1e-6f)
    }

    @Test
    fun `node berbentuk matriks dipecah menjadi TRS`() {
        val matrix = FloatArray(16)
        val rotation = floatArrayOf(0f, 0f, sin(Math.toRadians(45.0)).toFloat(), cos(Math.toRadians(45.0)).toFloat())
        id.animasi.anima3d.anim.Numeric.composeTrs(
            floatArrayOf(1f, 2f, 3f), 0,
            rotation, 0,
            floatArrayOf(2f, 2f, 2f), 0,
            matrix, 0
        )
        val root = GltfRoot(
            nodes = listOf(GltfNode(name = "M", matrix = matrix.toList())),
            scenes = listOf(GltfScene(nodes = listOf(0)))
        )
        val doc = GltfDocument.parseJson(TestGlb.encode(root), null)

        val t = FloatArray(3)
        val r = FloatArray(4)
        val s = FloatArray(3)
        doc.nodeLocalTrs(0, t, r, s)

        assertEquals(1f, t[0], 1e-5f)
        assertEquals(2f, t[1], 1e-5f)
        assertEquals(3f, t[2], 1e-5f)
        assertEquals(2f, s[0], 1e-5f)
        assertTrue(id.animasi.anima3d.anim.Numeric.quaternionEquals(rotation, 0, r, 0, 1e-3f))
        assertTrue(doc.nodesUsingMatrix().contentEquals(intArrayOf(0)))
    }

    @Test
    fun `node tanpa nama mendapat nama cadangan`() {
        val root = GltfRoot(nodes = listOf(GltfNode()))
        val doc = GltfDocument.parseJson(TestGlb.encode(root), null)
        assertEquals("node_0", doc.nodeName(0))
    }

    @Test
    fun `tanpa scenes, semua node akar dipakai`() {
        val root = GltfRoot(
            nodes = listOf(
                GltfNode(name = "A", children = listOf(1)),
                GltfNode(name = "B")
            )
        )
        val doc = GltfDocument.parseJson(TestGlb.encode(root), null)
        assertTrue(doc.sceneRootNodes().contentEquals(intArrayOf(0)))
    }

    @Test
    fun `versi selain dua pada JSON ditolak`() {
        val root = GltfRoot(asset = GltfAssetInfo(version = "1.0"))
        val e = runCatching { GltfDocument.parseJson(TestGlb.encode(root), null) }.exceptionOrNull()
            ?: error("berkas dengan versi glTF 1.0 seharusnya ditolak")
        assertTrue(e is GltfException, "jenis: ${e::class}")
        assertTrue(e.message!!.contains("Versi"), "pesan: ${e.message}")
    }

    @Test
    fun `JSON rusak ditolak dengan pesan jelas`() {
        val e = runCatching { GltfDocument.parseJson("{ ini bukan json", null) }.exceptionOrNull()
            ?: error("JSON rusak seharusnya ditolak")
        assertTrue(e is GltfException)
        assertTrue(e.message!!.contains("JSON"), "pesan: ${e.message}")
    }

    @Test
    fun `kunci asing diabaikan`() {
        val json = """
            {"asset":{"version":"2.0"},"nodes":[{"name":"A","extras":{"apa pun":1}}],
             "scenes":[{"nodes":[0]}],"extensionsUsed":["KHR_materials_emissive_strength"],
             "sesuatuYangTidakDikenal":{"x":1}}
        """.trimIndent()
        val doc = GltfDocument.parseJson(json, null)
        assertEquals(1, doc.nodeCount)
        assertEquals("A", doc.nodeName(0))
    }

    @Test
    fun `hierarki melingkar tidak membuat penguraian menggantung`() {
        // Berkas seperti ini tidak sah, tetapi editor tidak boleh mati karenanya.
        val root = GltfRoot(
            nodes = listOf(
                GltfNode(name = "A", children = listOf(1)),
                GltfNode(name = "B", children = listOf(0))
            ),
            scenes = listOf(GltfScene(nodes = listOf(0)))
        )
        val doc = GltfDocument.parseJson(TestGlb.encode(root), null)
        val skeleton = Skeleton(doc)
        // Tidak ada joint, jadi daftarnya kosong; yang penting tidak menggantung.
        assertFalse(skeleton.hasSkeleton)
        assertEquals(0, skeleton.jointTree().size)
    }
}
