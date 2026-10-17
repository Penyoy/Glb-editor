package id.animasi.anima3d.gltf

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Perkakas pengujian untuk menyusun berkas `.glb` buatan sendiri.
 *
 * Berkas uji dibuat di dalam memori supaya tesnya mandiri: tidak perlu mengunduh model
 * contoh, tidak ada berkas biner besar di repositori, dan setiap kasus tepi bisa
 * dirancang persis sesuai yang ingin diuji.
 */
object TestGlb {

    fun encode(root: GltfRoot): String = Json.encodeToString(GltfRoot.serializer(), root)

    fun buildGlb(root: GltfRoot, bin: ByteArray? = null): ByteArray =
        GlbContainer.write(encode(root), bin)

    fun floatBytes(values: FloatArray): ByteArray {
        val bytes = ByteArray(values.size * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return bytes
    }

    // Di JVM, vararg Float tersusun sebagai float[], sehingga tanpa @JvmName
    // kedua fungsi ini punya tanda tangan yang sama dan saling bertabrakan.
    // Nama JVM-nya dibedakan; pemanggilan dari Kotlin tetap floatBytes(1f, 2f).
    @JvmName("floatBytesOf")
    fun floatBytes(vararg values: Float): ByteArray = floatBytes(values.copyOf())
}

/**
 * Menyusun chunk BIN sekaligus mendaftarkan bufferView dan accessor yang menunjuk ke
 * dalamnya, sehingga offset dan alignment selalu konsisten.
 */
class BinBuilder {

    private val stream = ByteArrayOutputStream()
    private val views = mutableListOf<GltfBufferView>()
    private val accessors = mutableListOf<GltfAccessor>()

    val bufferViews: List<GltfBufferView> get() = views
    val accessorList: List<GltfAccessor> get() = accessors

    fun bytes(): ByteArray = stream.toByteArray()

    /**
     * Menambahkan array float rapat (tightly packed) dan mengembalikan indeks accessor.
     * [inputForAnimation] menandai accessor waktu, yang menurut spesifikasi wajib
     * mencantumkan `min` dan `max`.
     */
    fun addFloats(
        type: String,
        componentCount: Int,
        count: Int,
        values: FloatArray,
        inputForAnimation: Boolean = false
    ): Int {
        require(values.size == componentCount * count) {
            "butuh ${componentCount * count} float untuk $count elemen $type, diberikan ${values.size}"
        }

        // Accessor float harus mulai pada kelipatan 4 byte.
        while (stream.size() % 4 != 0) stream.write(0)
        val offset = stream.size()
        stream.write(TestGlb.floatBytes(values))
        // Setiap bufferView berikutnya juga harus mulai pada offset yang selaras.
        while (stream.size() % 4 != 0) stream.write(0)

        val viewIndex = views.size
        views.add(
            GltfBufferView(
                buffer = 0,
                byteOffset = offset,
                byteLength = values.size * 4
            )
        )

        val accessorIndex = accessors.size
        val min: List<Float>?
        val max: List<Float>?
        if (inputForAnimation) {
            min = List(componentCount) { c -> (0 until count).minOf { values[it * componentCount + c] } }
            max = List(componentCount) { c -> (0 until count).maxOf { values[it * componentCount + c] } }
        } else {
            min = null
            max = null
        }

        accessors.add(
            GltfAccessor(
                bufferView = viewIndex,
                byteOffset = 0,
                componentType = GltfAccessor.COMPONENT_FLOAT,
                count = count,
                type = type,
                min = min,
                max = max
            )
        )
        return accessorIndex
    }
}

/** Rangka sederhana: Root -> Hip -> Knee, dengan satu kulit dan satu animasi. */
object Fixtures {

    const val NODE_ROOT = 0
    const val NODE_HIP = 1
    const val NODE_KNEE = 2
    const val NODE_MESH = 3

    /**
     * Membuat berkas `.glb` minimal namun sah: satu skin dua joint (Hip dan Knee),
     * dan satu animasi dua channel (translasi Hip, rotasi Knee).
     */
    fun riggedModel(): ByteArray {
        val builder = BinBuilder()

        // Matriks inverse bind: dua matriks identitas 4x4.
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val ibm = builder.addFloats("MAT4", 16, 2, identity + identity)

        val hipTimes = floatArrayOf(0f, 1f)
        val hipTranslations = floatArrayOf(0f, 1f, 0f, 0f, 3f, 0f)
        val hipTimeAccessor = builder.addFloats("SCALAR", 1, 2, hipTimes, inputForAnimation = true)
        val hipValueAccessor = builder.addFloats("VEC3", 3, 2, hipTranslations)

        // Rotasi Knee: 0 derajat lalu 90 derajat pada sumbu X.
        val half = Math.toRadians(45.0)
        val kneeTimes = floatArrayOf(0f, 0.5f)
        val kneeRotations = floatArrayOf(
            0f, 0f, 0f, 1f,
            kotlin.math.sin(half).toFloat(), 0f, 0f, kotlin.math.cos(half).toFloat()
        )
        val kneeTimeAccessor = builder.addFloats("SCALAR", 1, 2, kneeTimes, inputForAnimation = true)
        val kneeValueAccessor = builder.addFloats("VEC4", 4, 2, kneeRotations)

        val root = GltfRoot(
            asset = GltfAssetInfo(version = "2.0", generator = "TestGlb"),
            scene = 0,
            scenes = listOf(GltfScene(name = "Scene", nodes = listOf(NODE_ROOT))),
            nodes = listOf(
                GltfNode(name = "Root", children = listOf(NODE_HIP)),
                GltfNode(name = "Hip", children = listOf(NODE_KNEE), translation = listOf(0f, 1f, 0f)),
                GltfNode(name = "Knee", translation = listOf(0f, 0.5f, 0f)),
                GltfNode(name = "Body", mesh = 0, skin = 0)
            ),
            skins = listOf(
                GltfSkin(
                    name = "Rig",
                    joints = listOf(NODE_HIP, NODE_KNEE),
                    skeleton = NODE_HIP,
                    inverseBindMatrices = ibm
                )
            ),
            animations = listOf(
                GltfAnimation(
                    name = "Berjalan",
                    channels = listOf(
                        GltfAnimationChannel(0, GltfAnimationTarget(NODE_HIP, "translation")),
                        GltfAnimationChannel(1, GltfAnimationTarget(NODE_KNEE, "rotation"))
                    ),
                    samplers = listOf(
                        GltfAnimationSampler(hipTimeAccessor, hipValueAccessor, "LINEAR"),
                        GltfAnimationSampler(kneeTimeAccessor, kneeValueAccessor, "LINEAR")
                    )
                )
            ),
            accessors = builder.accessorList,
            bufferViews = builder.bufferViews,
            buffers = listOf(GltfBuffer(uri = null, byteLength = builder.bytes().size)),
            meshes = emptyList()
        )

        return TestGlb.buildGlb(root, builder.bytes())
    }
}
