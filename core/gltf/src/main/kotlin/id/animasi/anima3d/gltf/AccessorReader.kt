package id.animasi.anima3d.gltf

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Pembaca data mentah dari accessor glTF.
 *
 * Semua nilai dikembalikan sebagai `FloatArray` yang rata: elemen ke-i menempati
 * indeks `i * componentCount`. Matriks 4x4 karena itu menjadi 16 float berurutan
 * dalam urutan kolom-mayor, sesuai glTF.
 */
class AccessorReader(
    private val root: GltfRoot,
    private val bin: ByteArray?
) {

    private val bufferDataCache = HashMap<Int, ByteArray>()

    /** Data buffer ke-[index]: chunk BIN untuk buffer tanpa `uri`, atau hasil dekode data URI. */
    fun bufferData(index: Int): ByteArray {
        bufferDataCache[index]?.let { return it }

        val buffer = root.buffers?.getOrNull(index)
            ?: throw GltfException("Accessor menunjuk buffer $index yang tidak ada")

        val bytes = if (buffer.uri == null) {
            // Buffer tanpa uri adalah chunk BIN pada .glb.
            bin ?: throw GltfException(
                "Buffer $index tidak punya uri, tetapi berkas .glb tidak punya chunk BIN"
            )
        } else {
            decodeDataUri(buffer.uri)
        }

        if (bytes.size < buffer.byteLength) {
            throw GltfException(
                "Buffer $index hanya ${bytes.size} byte, seharusnya minimal ${buffer.byteLength}"
            )
        }
        bufferDataCache[index] = bytes
        return bytes
    }

    private fun decodeDataUri(uri: String): ByteArray {
        if (!uri.startsWith("data:")) {
            throw GltfException(
                "Buffer memakai berkas luar ('${uri.take(64)}'). Editor hanya menerima .glb " +
                    "yang seluruh datanya menyatu di dalam berkas."
            )
        }
        val comma = uri.indexOf(',')
        if (comma < 0) throw GltfException("Data URI tidak sah: tidak ada pemisah ','")
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        if (!meta.contains("base64")) {
            throw GltfException("Data URI non-base64 belum didukung")
        }
        return try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw GltfException("Data URI base64 rusak", e)
        }
    }

    fun accessor(index: Int): GltfAccessor =
        root.accessors?.getOrNull(index)
            ?: throw GltfException("Accessor $index tidak ada")

    /**
     * Membaca seluruh elemen accessor sebagai float.
     *
     * Panjang hasil = `accessor.count * accessor.componentCount`.
     */
    fun readFloats(accessorIndex: Int): FloatArray {
        val accessor = accessor(accessorIndex)

        if (accessor.sparse != null) {
            throw GltfException(
                "Accessor $accessorIndex memakai sparse data yang belum didukung editor"
            )
        }

        val components = accessor.componentCount
        val out = FloatArray(accessor.count * components)

        val bufferViewIndex = accessor.bufferView
        if (bufferViewIndex == null) {
            // Spesifikasi mengizinkan accessor tanpa bufferView; isinya nol.
            return out
        }

        val view = root.bufferViews?.getOrNull(bufferViewIndex)
            ?: throw GltfException("Accessor $accessorIndex menunjuk bufferView $bufferViewIndex yang tidak ada")

        val componentSize = GltfAccessor.componentSize(accessor.componentType)
        val elementSize = componentSize * components
        val stride = view.byteStride ?: elementSize

        if (view.byteStride != null && view.byteStride < elementSize) {
            throw GltfException(
                "bufferView $bufferViewIndex punya byteStride ${view.byteStride} yang lebih kecil " +
                    "dari ukuran satu elemen ($elementSize)"
            )
        }

        // Matriks 2x2 dan 3x3 dengan komponen kecil disimpan dengan padding kolom
        // menurut spesifikasi. Kasus ini belum muncul pada data animasi, jadi lebih
        // jujur menolaknya daripada menghitungnya salah.
        if ((accessor.type == "MAT2" || accessor.type == "MAT3") && componentSize < 4) {
            throw GltfException(
                "Accessor $accessorIndex bertipe ${accessor.type} dengan komponen $componentSize byte; " +
                    "padding kolom untuk kasus ini belum didukung"
            )
        }

        val data = bufferData(view.buffer)
        val base = view.byteOffset + accessor.byteOffset
        val lastByte = base + (accessor.count - 1).coerceAtLeast(0) * stride + elementSize
        if (lastByte > data.size) {
            throw GltfException(
                "Accessor $accessorIndex membaca sampai byte $lastByte, " +
                    "padahal buffer hanya ${data.size} byte"
            )
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var outIndex = 0
        for (element in 0 until accessor.count) {
            val elementBase = base + element * stride
            for (c in 0 until components) {
                out[outIndex++] = readComponent(buffer, elementBase + c * componentSize, accessor.componentType, accessor.normalized)
            }
        }
        return out
    }

    private fun readComponent(
        buffer: ByteBuffer,
        offset: Int,
        componentType: Int,
        normalized: Boolean
    ): Float = when (componentType) {
        GltfAccessor.COMPONENT_FLOAT -> buffer.getFloat(offset)
        GltfAccessor.COMPONENT_INT8 -> {
            val v = buffer.get(offset).toFloat()
            if (normalized) (v / 127f) else v
        }
        GltfAccessor.COMPONENT_UINT8 -> {
            val v = (buffer.get(offset).toInt() and 0xFF).toFloat()
            if (normalized) (v / 255f) else v
        }
        GltfAccessor.COMPONENT_INT16 -> {
            val v = buffer.getShort(offset).toFloat()
            if (normalized) (v / 32767f) else v
        }
        GltfAccessor.COMPONENT_UINT16 -> {
            val v = (buffer.getShort(offset).toInt() and 0xFFFF).toFloat()
            if (normalized) (v / 65535f) else v
        }
        GltfAccessor.COMPONENT_UINT32 -> (buffer.getInt(offset).toLong() and 0xFFFFFFFFL).toFloat()
        else -> throw GltfException("componentType $componentType tidak dikenal")
    }
}
