package id.animasi.anima3d.gltf

import id.animasi.anima3d.anim.AnimPath
import id.animasi.anima3d.anim.ChannelEvaluator
import id.animasi.anima3d.anim.ChannelIndex
import id.animasi.anima3d.anim.Interpolation
import id.animasi.anima3d.anim.Numeric
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClipConverterTest {

    private val eps = 1e-5f

    @Test
    fun `animasi bawaan dikonversi menjadi clip milik editor`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        val result = ClipConverter.convert(doc)

        assertEquals(1, result.clips.size)
        assertEquals(0, result.unsupportedChannelCount)
        assertTrue(result.warnings.isEmpty(), "peringatan: ${result.warnings}")

        val clip = result.clips[0]
        assertEquals("Berjalan", clip.name)
        assertEquals(2, clip.channels.size)
        // Durasi diambil dari keyframe terakhir channel mana pun, jadi 1.0 dari translasi Hip.
        assertEquals(1.0f, clip.duration, eps)
    }

    @Test
    fun `channel translasi hasil konversi bernilai benar`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        val clip = ClipConverter.convert(doc).clips[0]

        val channel = clip.byIndex[ChannelIndex(Fixtures.NODE_HIP, AnimPath.TRANSLATION)]
            ?: error("channel translasi Hip tidak ditemukan")
        assertEquals(Interpolation.LINEAR, channel.interpolation)
        assertEquals(2, channel.keyCount)
        assertTrue(channel.times.contentEquals(floatArrayOf(0f, 1f)))
        assertTrue(channel.values.contentEquals(floatArrayOf(0f, 1f, 0f, 0f, 3f, 0f)))

        val out = FloatArray(3)
        ChannelEvaluator(channel).evaluate(0.5f, out)
        assertEquals(0f, out[0], eps)
        assertEquals(2f, out[1], eps)
        assertEquals(0f, out[2], eps)
    }

    @Test
    fun `channel rotasi hasil konversi memakai slerp`() {
        val doc = GltfDocument.parse(Fixtures.riggedModel())
        val clip = ClipConverter.convert(doc).clips[0]

        val channel = clip.byIndex[ChannelIndex(Fixtures.NODE_KNEE, AnimPath.ROTATION)]
            ?: error("channel rotasi Knee tidak ditemukan")

        val out = FloatArray(4)
        ChannelEvaluator(channel).evaluate(0.25f, out)

        // Setengah jalan menuju rotasi 90 derajat pada sumbu X adalah 45 derajat.
        val half = Math.toRadians(22.5)
        val expected = floatArrayOf(sin(half).toFloat(), 0f, 0f, cos(half).toFloat())
        assertTrue(
            Numeric.quaternionEquals(expected, 0, out, 0, 1e-5f),
            "hasil: ${out.toList()}"
        )
    }

    @Test
    fun `kuaternion yang tidak bernorma satu dinormalkan saat konversi`() {
        val builder = BinBuilder()
        val times = builder.addFloats("SCALAR", 1, 2, floatArrayOf(0f, 1f), inputForAnimation = true)
        // Panjang 2 pada kedua keyframe: harus dinormalkan.
        val values = builder.addFloats(
            "VEC4", 4, 2,
            floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)
        )
        val root = GltfRoot(
            asset = GltfAssetInfo(version = "2.0"),
            scene = 0,
            scenes = listOf(GltfScene(nodes = listOf(0))),
            nodes = listOf(GltfNode(name = "A")),
            animations = listOf(
                GltfAnimation(
                    name = "R",
                    channels = listOf(GltfAnimationChannel(0, GltfAnimationTarget(0, "rotation"))),
                    samplers = listOf(GltfAnimationSampler(times, values, "LINEAR"))
                )
            ),
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size))
        )

        val clip = ClipConverter.convert(GltfDocument.parse(TestGlb.buildGlb(root, builder.bytes()))).clips[0]
        val channel = clip.channels[0]
        assertEquals(1f, kotlin.math.abs(channel.values[3]), eps)
        assertEquals(1f, kotlin.math.abs(channel.values[7]), eps)
    }

    @Test
    fun `cubicspline dipisah menjadi nilai dan tangen`() {
        val builder = BinBuilder()
        val times = builder.addFloats("SCALAR", 1, 2, floatArrayOf(0f, 1f), inputForAnimation = true)
        // Urutan glTF per keyframe: in-tangent, value, out-tangent.
        val raw = floatArrayOf(
            0f, 0f, 0f,      // in-tangent key 0
            0f, 0f, 0f,      // value      key 0
            2f, 0f, 0f,      // out-tangent key 0
            0f, 0f, 0f,      // in-tangent key 1
            10f, 0f, 0f,     // value      key 1
            0f, 0f, 0f       // out-tangent key 1
        )
        val values = builder.addFloats("VEC3", 3, 6, raw)
        val root = GltfRoot(
            asset = GltfAssetInfo(version = "2.0"),
            scene = 0,
            scenes = listOf(GltfScene(nodes = listOf(0))),
            nodes = listOf(GltfNode(name = "A")),
            animations = listOf(
                GltfAnimation(
                    name = "C",
                    channels = listOf(GltfAnimationChannel(0, GltfAnimationTarget(0, "translation"))),
                    samplers = listOf(GltfAnimationSampler(times, values, "CUBICSPLINE"))
                )
            ),
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size))
        )

        val doc = GltfDocument.parse(TestGlb.buildGlb(root, builder.bytes()))
        val channel = ClipConverter.convert(doc).clips[0].channels[0]

        assertEquals(Interpolation.CUBICSPLINE, channel.interpolation)
        assertTrue(channel.values.contentEquals(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f)))
        assertTrue(channel.inTangents!!.contentEquals(FloatArray(6)))
        assertTrue(channel.outTangents!!.contentEquals(floatArrayOf(2f, 0f, 0f, 0f, 0f, 0f)))

        // Angka yang sama dengan yang dihitung tangan di tes sampler.
        val out = FloatArray(3)
        ChannelEvaluator(channel).evaluate(0.5f, out)
        assertEquals(5.25f, out[0], eps)
    }

    @Test
    fun `target weights dihitung sebagai channel yang belum didukung`() {
        val builder = BinBuilder()
        val times = builder.addFloats("SCALAR", 1, 2, floatArrayOf(0f, 1f), inputForAnimation = true)
        val values = builder.addFloats("SCALAR", 1, 2, floatArrayOf(0f, 1f))
        val root = GltfRoot(
            asset = GltfAssetInfo(version = "2.0"),
            scene = 0,
            scenes = listOf(GltfScene(nodes = listOf(0))),
            nodes = listOf(GltfNode(name = "A")),
            animations = listOf(
                GltfAnimation(
                    name = "Morph",
                    channels = listOf(GltfAnimationChannel(0, GltfAnimationTarget(0, "weights"))),
                    samplers = listOf(GltfAnimationSampler(times, values, "LINEAR"))
                )
            ),
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size))
        )

        val result = ClipConverter.convert(GltfDocument.parse(TestGlb.buildGlb(root, builder.bytes())))
        assertEquals(1, result.unsupportedChannelCount)
        assertTrue(result.clips.isEmpty())
    }

    @Test
    fun `waktu keyframe yang tidak menaik dilewati dengan peringatan`() {
        val builder = BinBuilder()
        // Terbalik dengan sengaja: pencarian biner akan salah kalau ini diterima.
        val times = builder.addFloats("SCALAR", 1, 2, floatArrayOf(1f, 0f), inputForAnimation = true)
        val values = builder.addFloats("VEC3", 3, 2, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f))
        val root = GltfRoot(
            asset = GltfAssetInfo(version = "2.0"),
            scene = 0,
            scenes = listOf(GltfScene(nodes = listOf(0))),
            nodes = listOf(GltfNode(name = "A")),
            animations = listOf(
                GltfAnimation(
                    name = "Rusak",
                    channels = listOf(GltfAnimationChannel(0, GltfAnimationTarget(0, "translation"))),
                    samplers = listOf(GltfAnimationSampler(times, values, "LINEAR"))
                )
            ),
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size))
        )

        val result = ClipConverter.convert(GltfDocument.parse(TestGlb.buildGlb(root, builder.bytes())))
        assertTrue(result.clips.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("tidak menaik"), "peringatan: ${result.warnings[0]}")
    }

    @Test
    fun `berkas tanpa animasi menghasilkan daftar kosong`() {
        val root = GltfRoot(
            asset = GltfAssetInfo(version = "2.0"),
            nodes = listOf(GltfNode(name = "A"))
        )
        val result = ClipConverter.convert(GltfDocument.parseJson(TestGlb.encode(root), null))
        assertTrue(result.clips.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }
}
