package id.animasi.anima3d.gltf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Isi berkas GLB: satu teks JSON dan opsional satu blok biner.
 *
 * GLB hanya boleh punya **satu** chunk BIN, dan chunk itu dipetakan ke `buffers[0]`
 * (buffer yang tidak punya `uri`). Ini penting untuk penulis ekspor di Phase 6: data
 * animasi baru harus ditambahkan ke ujung [bin] yang sudah ada, bukan dibuat sebagai
 * buffer kedua.
 */
class Glb(val jsonText: String, val bin: ByteArray?)

object GlbContainer {

    private const val MAGIC_GLTF = 0x46546C67 // "glTF"
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_TYPE_BIN = 0x004E4942 // "BIN\0"
    private const val HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val SUPPORTED_VERSION = 2

    /** Padding chunk JSON memakai spasi, sedangkan chunk BIN memakai nol. */
    private const val JSON_PAD: Byte = 0x20
    private const val BIN_PAD: Byte = 0x00

    fun read(bytes: ByteArray): Glb {
        if (bytes.size < HEADER_SIZE) {
            throw GltfException("Berkas terlalu pendek untuk sebuah .glb (${bytes.size} byte)")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != MAGIC_GLTF) {
            throw GltfException(
                "Bukan berkas .glb: penanda awal tidak cocok (0x${magic.toString(16)}). " +
                    "Bila ini .gltf, model harus dikonversi ke .glb lebih dahulu."
            )
        }

        val version = buffer.int
        if (version != SUPPORTED_VERSION) {
            throw GltfException("Versi glTF $version tidak didukung; hanya versi 2.")
        }

        val declaredLength = buffer.int
        if (declaredLength > bytes.size) {
            throw GltfException(
                "Berkas terpotong: header menyebut $declaredLength byte, tersedia hanya ${bytes.size}"
            )
        }
        // Beberapa pengekspor menulis panjang yang lebih kecil dari ukuran berkas
        // (mis. karena metadata tambahan di akhir). Batasi ke ukuran yang benar-benar ada.
        val limit = if (declaredLength in HEADER_SIZE..bytes.size) declaredLength else bytes.size

        var jsonText: String? = null
        var bin: ByteArray? = null

        while (buffer.position() + CHUNK_HEADER_SIZE <= limit) {
            val chunkLength = buffer.int
            val chunkType = buffer.int

            if (chunkLength < 0 || buffer.position() + chunkLength > limit) {
                throw GltfException(
                    "Chunk rusak pada offset ${buffer.position() - CHUNK_HEADER_SIZE}: " +
                        "panjang $chunkLength melampaui batas berkas"
                )
            }

            val start = buffer.position()
            when (chunkType) {
                CHUNK_TYPE_JSON -> {
                    if (jsonText != null) throw GltfException("Berkas .glb punya lebih dari satu chunk JSON")
                    jsonText = String(bytes, start, chunkLength, Charsets.UTF_8)
                }
                CHUNK_TYPE_BIN -> {
                    if (bin != null) throw GltfException("Berkas .glb punya lebih dari satu chunk BIN")
                    bin = bytes.copyOfRange(start, start + chunkLength)
                }
                // Chunk tak dikenal harus diabaikan, bukan jadi kesalahan: spesifikasi
                // mengizinkan penambahan chunk baru di masa depan.
                else -> Unit
            }
            buffer.position(start + chunkLength)
        }

        val json = jsonText ?: throw GltfException("Berkas .glb tidak punya chunk JSON")
        return Glb(stripChunkPadding(json), bin)
    }

    /**
     * Menyusun berkas GLB.
     *
     * Dipakai unit test untuk uji bolak-balik dan akan menjadi dasar penulis ekspor
     * di Phase 6 bersama `AccessorWriter`.
     */
    fun write(jsonText: String, bin: ByteArray?): ByteArray {
        val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
        val jsonPadded = alignTo4(jsonBytes.size)
        val binPadded = if (bin != null) alignTo4(bin.size) else 0

        val total = HEADER_SIZE +
            CHUNK_HEADER_SIZE + jsonPadded +
            (if (bin != null) CHUNK_HEADER_SIZE + binPadded else 0)

        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC_GLTF)
        out.putInt(SUPPORTED_VERSION)
        out.putInt(total)

        out.putInt(jsonPadded)
        out.putInt(CHUNK_TYPE_JSON)
        out.put(jsonBytes)
        repeat(jsonPadded - jsonBytes.size) { out.put(JSON_PAD) }

        if (bin != null) {
            out.putInt(binPadded)
            out.putInt(CHUNK_TYPE_BIN)
            out.put(bin)
            repeat(binPadded - bin.size) { out.put(BIN_PAD) }
        }

        return out.array()
    }

    private fun alignTo4(size: Int): Int = ((size + 3) / 4) * 4

    /**
     * Chunk JSON dipad dengan spasi, dan sebagian pengekspor memakai nol. Keduanya
     * harus dibuang supaya parser JSON tidak tersandung.
     */
    private fun stripChunkPadding(json: String): String {
        var end = json.length
        while (end > 0) {
            val c = json[end - 1]
            if (c == ' ' || c == '\u0000' || c == '\n' || c == '\r' || c == '\t') end-- else break
        }
        var start = 0
        while (start < end) {
            val c = json[start]
            if (c == '\uFEFF' || c == ' ' || c == '\n' || c == '\r' || c == '\t') start++ else break
        }
        return json.substring(start, end)
    }
}
