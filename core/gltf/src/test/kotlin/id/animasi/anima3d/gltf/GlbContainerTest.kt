package id.animasi.anima3d.gltf

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlbContainerTest {

    private val simpleJson = """{"asset":{"version":"2.0"}}"""

    @Test
    fun `bolak-balik dengan chunk BIN`() {
        val bin = TestGlb.floatBytes(1f, 2f, 3f)
        val bytes = GlbContainer.write(simpleJson, bin)

        val glb = GlbContainer.read(bytes)
        assertEquals(simpleJson, glb.jsonText)
        assertNotNull(glb.bin)
        assertEquals(3, glb.bin!!.size / 4)
    }

    @Test
    fun `bolak-balik tanpa chunk BIN`() {
        val bytes = GlbContainer.write(simpleJson, null)
        val glb = GlbContainer.read(bytes)
        assertEquals(simpleJson, glb.jsonText)
        assertNull(glb.bin)
    }

    @Test
    fun `JSON yang panjangnya bukan kelipatan empat tetap terbaca`() {
        // Padding chunk JSON memakai spasi dan wajib dibuang sebelum diurai.
        val odd = """{"asset":{"version":"2.0"},"scene":0}"""
        assertTrue(odd.toByteArray().size % 4 != 0)

        val bytes = GlbContainer.write(odd, null)
        assertEquals(0, bytes.size % 4)

        val glb = GlbContainer.read(bytes)
        assertEquals(odd, glb.jsonText)
    }

    @Test
    fun `padding nol pada chunk JSON juga dibuang`() {
        val json = """{"asset":{"version":"2.0"}}""" + "\u0000\u0000"
        val bytes = GlbContainer.write(json, null)
        val glb = GlbContainer.read(bytes)
        assertEquals("""{"asset":{"version":"2.0"}}""", glb.jsonText)
    }

    @Test
    fun `panjang berkas selalu kelipatan empat`() {
        for (extra in 0..8) {
            val json = """{"asset":{"version":"2.0"},"pad":"${"x".repeat(extra)}"}"""
            assertEquals(0, GlbContainer.write(json, null).size % 4)
            assertEquals(0, GlbContainer.write(json, ByteArray(extra + 1)).size % 4)
        }
    }

    @Test
    fun `penanda awal yang salah ditolak dengan pesan jelas`() {
        val bytes = ByteArray(20)
        val e = assertFailsWith<GltfException> { GlbContainer.read(bytes) }
        assertTrue(e.message!!.contains(".glb"), "pesan: ${e.message}")
    }

    @Test
    fun `versi selain dua ditolak`() {
        val bytes = GlbContainer.write(simpleJson, null)
        // Ubah field version (offset 4) menjadi 1.
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(4, 1)

        val e = assertFailsWith<GltfException> { GlbContainer.read(bytes) }
        assertTrue(e.message!!.contains("Versi"), "pesan: ${e.message}")
    }

    @Test
    fun `berkas terpotong ditolak`() {
        val bytes = GlbContainer.write(simpleJson, TestGlb.floatBytes(1f, 2f))
        val truncated = bytes.copyOf(bytes.size - 4)
        // Panjang di header masih menyebut ukuran penuh, jadi harus terdeteksi.
        assertFailsWith<GltfException> { GlbContainer.read(truncated) }
    }

    @Test
    fun `berkas terlalu pendek ditolak`() {
        assertFailsWith<GltfException> { GlbContainer.read(ByteArray(4)) }
    }

    @Test
    fun `chunk asing diabaikan`() {
        val base = GlbContainer.write(simpleJson, null)

        // Sisipkan satu chunk dengan tipe tak dikenal sebelum akhir berkas.
        val extraPayload = "abc ".toByteArray()
        val extra = ByteBuffer.allocate(8 + extraPayload.size).order(ByteOrder.LITTLE_ENDIAN)
        extra.putInt(extraPayload.size)
        extra.putInt(0x12345678)
        extra.put(extraPayload)
        val extraBytes = extra.array()

        val merged = ByteArray(base.size + extraBytes.size)
        base.copyInto(merged, 0)
        extraBytes.copyInto(merged, base.size)
        // Perbarui total length di header.
        ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN).putInt(8, merged.size)

        val glb = GlbContainer.read(merged)
        assertEquals(simpleJson, glb.jsonText)
    }

    @Test
    fun `chunk JSON tidak ada`() {
        // Berkas hanya berisi satu chunk bertipe asing.
        val payload = "abcd".toByteArray()
        val bytes = ByteArray(12 + 8 + payload.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546C67)
        buffer.putInt(2)
        buffer.putInt(bytes.size)
        buffer.putInt(payload.size)
        buffer.putInt(0x12345678)
        buffer.put(payload)

        val e = assertFailsWith<GltfException> { GlbContainer.read(bytes) }
        assertTrue(e.message!!.contains("JSON"), "pesan: ${e.message}")
    }
}
