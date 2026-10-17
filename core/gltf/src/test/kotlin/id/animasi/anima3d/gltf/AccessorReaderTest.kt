package id.animasi.anima3d.gltf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccessorReaderTest {

    private fun reader(root: GltfRoot, bin: ByteArray?): AccessorReader = AccessorReader(root, bin)

    @Test
    fun `membaca VEC3 float yang rapat`() {
        val builder = BinBuilder()
        val accessor = builder.addFloats("VEC3", 3, 2, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val root = GltfRoot(
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size))
        )

        val values = reader(root, builder.bytes()).readFloats(accessor)
        assertEquals(6, values.size)
        assertEquals(1f, values[0], 1e-6f)
        assertEquals(3f, values[2], 1e-6f)
        assertEquals(4f, values[3], 1e-6f)
        assertEquals(6f, values[5], 1e-6f)
    }

    @Test
    fun `membaca bufferView dengan byteStride`() {
        // Dua elemen VEC3 yang disisipkan dengan jarak 16 byte: tiga float pertama lalu
        // satu float pengisi yang harus dilewati.
        val raw = floatArrayOf(
            1f, 2f, 3f, 999f,
            4f, 5f, 6f, 999f
        )
        val bin = TestGlb.floatBytes(raw)
        val root = GltfRoot(
            accessors = listOf(
                GltfAccessor(bufferView = 0, componentType = GltfAccessor.COMPONENT_FLOAT, count = 2, type = "VEC3")
            ),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = bin.size, byteStride = 16)),
            buffers = listOf(GltfBuffer(uri = null, byteLength = bin.size))
        )

        val values = reader(root, bin).readFloats(0)
        assertEquals(6, values.size)
        assertEquals(3f, values[2], 1e-6f)
        assertEquals(4f, values[3], 1e-6f)
        assertEquals(6f, values[5], 1e-6f)
    }

    @Test
    fun `accessor byteOffset dan bufferView byteOffset dijumlahkan`() {
        val bin = TestGlb.floatBytes(111f, 222f, 1f, 2f)
        val root = GltfRoot(
            accessors = listOf(
                GltfAccessor(
                    bufferView = 0,
                    byteOffset = 8,
                    componentType = GltfAccessor.COMPONENT_FLOAT,
                    count = 2,
                    type = "SCALAR"
                )
            ),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = bin.size)),
            buffers = listOf(GltfBuffer(uri = null, byteLength = bin.size))
        )
        val values = reader(root, bin).readFloats(0)
        assertEquals(1f, values[0], 1e-6f)
        assertEquals(2f, values[1], 1e-6f)
    }

    @Test
    fun `membaca MAT4 sebagai enam belas float`() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val builder = BinBuilder()
        val accessor = builder.addFloats("MAT4", 16, 1, identity)
        val root = GltfRoot(
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size))
        )
        val values = reader(root, builder.bytes()).readFloats(accessor)
        assertEquals(16, values.size)
        assertEquals(1f, values[0], 1e-6f)
        assertEquals(1f, values[5], 1e-6f)
        assertEquals(1f, values[10], 1e-6f)
        assertEquals(1f, values[15], 1e-6f)
    }

    @Test
    fun `accessor tanpa bufferView menghasilkan nol`() {
        val root = GltfRoot(accessors = listOf(GltfAccessor(count = 3, type = "VEC3")))
        val values = reader(root, null).readFloats(0)
        assertEquals(9, values.size)
        assertTrue(values.all { it == 0f })
    }

    @Test
    fun `membaca di luar batas buffer ditolak`() {
        val bin = TestGlb.floatBytes(1f, 2f)
        val root = GltfRoot(
            accessors = listOf(
                GltfAccessor(bufferView = 0, componentType = GltfAccessor.COMPONENT_FLOAT, count = 100, type = "SCALAR")
            ),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = bin.size)),
            buffers = listOf(GltfBuffer(uri = null, byteLength = bin.size))
        )
        val e = assertFailsWith<GltfException> { reader(root, bin).readFloats(0) }
        assertTrue(e.message!!.contains("padahal buffer hanya"), "pesan: ${e.message}")
    }

    @Test
    fun `accessor sparse ditolak dengan pesan jelas`() {
        val bin = TestGlb.floatBytes(1f)
        val root = GltfRoot(
            accessors = listOf(
                GltfAccessor(
                    bufferView = 0,
                    componentType = GltfAccessor.COMPONENT_FLOAT,
                    count = 1,
                    type = "SCALAR",
                    sparse = kotlinx.serialization.json.JsonObject(emptyMap())
                )
            ),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = bin.size)),
            buffers = listOf(GltfBuffer(uri = null, byteLength = bin.size))
        )
        val e = assertFailsWith<GltfException> { reader(root, bin).readFloats(0) }
        assertTrue(e.message!!.contains("sparse"), "pesan: ${e.message}")
    }

    @Test
    fun `accessor yang menunjuk bufferView tidak ada ditolak`() {
        val root = GltfRoot(
            accessors = listOf(GltfAccessor(bufferView = 5, count = 1, type = "SCALAR")),
            bufferViews = emptyList(),
            buffers = listOf(GltfBuffer(uri = null, byteLength = 4))
        )
        assertFailsWith<GltfException> { reader(root, TestGlb.floatBytes(1f)).readFloats(0) }
    }

    @Test
    fun `buffer dengan data uri base64 terbaca`() {
        val payload = TestGlb.floatBytes(7f, 8f)
        val base64 = java.util.Base64.getEncoder().encodeToString(payload)
        val root = GltfRoot(
            accessors = listOf(
                GltfAccessor(bufferView = 0, componentType = GltfAccessor.COMPONENT_FLOAT, count = 2, type = "SCALAR")
            ),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = payload.size)),
            buffers = listOf(
                GltfBuffer(uri = "data:application/octet-stream;base64,$base64", byteLength = payload.size)
            )
        )
        val values = reader(root, null).readFloats(0)
        assertEquals(7f, values[0], 1e-6f)
        assertEquals(8f, values[1], 1e-6f)
    }

    @Test
    fun `buffer dengan berkas luar ditolak`() {
        val root = GltfRoot(
            accessors = listOf(GltfAccessor(bufferView = 0, count = 1, type = "SCALAR")),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = 4)),
            buffers = listOf(GltfBuffer(uri = "model.bin", byteLength = 4))
        )
        val e = assertFailsWith<GltfException> { reader(root, null).readFloats(0) }
        assertTrue(e.message!!.contains(".glb"), "pesan: ${e.message}")
    }

    @Test
    fun `komponen integer dibaca sebagai float`() {
        // Tiga UINT16 dengan nilai maksimum, dinormalkan menjadi 1.0.
        val bytes = ByteArray(6)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(3) { buffer.putShort(0xFFFF.toShort()) }

        val root = GltfRoot(
            accessors = listOf(
                GltfAccessor(
                    bufferView = 0,
                    componentType = GltfAccessor.COMPONENT_UINT16,
                    normalized = true,
                    count = 3,
                    type = "SCALAR"
                )
            ),
            bufferViews = listOf(GltfBufferView(buffer = 0, byteOffset = 0, byteLength = bytes.size)),
            buffers = listOf(GltfBuffer(uri = null, byteLength = bytes.size))
        )
        val values = reader(root, bytes).readFloats(0)
        assertEquals(1f, values[0], 1e-6f)
    }
}
