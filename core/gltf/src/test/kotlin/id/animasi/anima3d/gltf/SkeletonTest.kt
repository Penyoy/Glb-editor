package id.animasi.anima3d.gltf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkeletonTest {

    @Test
    fun `joint dan hierarkinya dikenali dari skins`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        val skeleton = Skeleton(doc)

        assertTrue(skeleton.hasSkeleton)
        assertEquals(2, skeleton.totalJoints)
        assertTrue(skeleton.isJoint(Fixtures.NODE_HIP))
        assertTrue(skeleton.isJoint(Fixtures.NODE_KNEE))

        // Root dan Body bukan joint meskipun ada di hierarki.
        assertFalse(skeleton.isJoint(Fixtures.NODE_ROOT))
        assertFalse(skeleton.isJoint(Fixtures.NODE_MESH))
    }

    @Test
    fun `peta induk mencakup node non-joint`() {
        val skeleton = Skeleton(GltfDocument.parse(Fixtures.riggedModel()))
        assertEquals(-1, skeleton.parent[Fixtures.NODE_ROOT])
        assertEquals(Fixtures.NODE_ROOT, skeleton.parent[Fixtures.NODE_HIP])
        assertEquals(Fixtures.NODE_HIP, skeleton.parent[Fixtures.NODE_KNEE])
    }

    @Test
    fun `restTrs memuat sepuluh nilai per node dengan default yang benar`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        val skeleton = Skeleton(doc)

        val base = Fixtures.NODE_HIP * 10
        assertEquals(0f, skeleton.restTrs[base], 1e-6f)
        assertEquals(1f, skeleton.restTrs[base + 1], 1e-6f)
        assertEquals(0f, skeleton.restTrs[base + 2], 1e-6f)
        // Rotasi identitas.
        assertEquals(1f, skeleton.restTrs[base + 6], 1e-6f)
        // Skala satu.
        assertEquals(1f, skeleton.restTrs[base + 7], 1e-6f)
        assertEquals(1f, skeleton.restTrs[base + 9], 1e-6f)
    }

    @Test
    fun `inverse bind matrices terbaca`() {
        val skeleton = Skeleton(GltfDocument.parse(Fixtures.riggedModel()))
        assertEquals(1, skeleton.skins.size)

        val skin = skeleton.skins[0]
        assertEquals("Rig", skin.name)
        assertEquals(2, skin.jointCount)
        assertTrue(skin.joints.contentEquals(intArrayOf(Fixtures.NODE_HIP, Fixtures.NODE_KNEE)))

        val ibm = skin.inverseBindMatrices
            ?: error("inverseBindMatrices tidak terbaca dari berkas uji")
        assertEquals(32, ibm.size)
        assertEquals(1f, ibm[0], 1e-6f)
        assertEquals(1f, ibm[16 + 5], 1e-6f)
    }

    @Test
    fun `daftar joint untuk UI mengikuti urutan hierarki`() {
        val skeleton = Skeleton(GltfDocument.parse(Fixtures.riggedModel()))
        val tree = skeleton.jointTree()

        assertEquals(2, tree.size)
        assertEquals("Hip", tree[0].name)
        assertEquals(0, tree[0].depth)
        assertEquals(1, tree[0].childJointCount)

        assertEquals("Knee", tree[1].name)
        assertEquals(1, tree[1].depth)
        assertEquals(0, tree[1].childJointCount)
    }

    @Test
    fun `skinsFor melaporkan kulit yang memakai joint`() {
        val skeleton = Skeleton(GltfDocument.parse(Fixtures.riggedModel()))
        assertEquals(listOf(0), skeleton.skinsFor(Fixtures.NODE_HIP))
        assertTrue(skeleton.skinsFor(Fixtures.NODE_ROOT).isEmpty())
    }
}
