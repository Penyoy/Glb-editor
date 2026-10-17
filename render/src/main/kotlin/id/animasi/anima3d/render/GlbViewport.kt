package id.animasi.anima3d.render

import android.content.Context
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import com.google.android.filament.IndirectLight
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.GestureDetector
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import id.animasi.anima3d.gltf.GltfDocument
import id.animasi.anima3d.gltf.Skeleton
import java.nio.ByteBuffer

/**
 * Viewport 3D: permukaan render Filament untuk memuat dan memutar model `.glb`.
 *
 * `ModelViewer` dipakai untuk menyiapkan engine, scene, view, kamera, lampu matahari,
 * dan penanganan gestur. Kelas ini menambahkan tiga hal yang tidak diberikan
 * `ModelViewer`:
 *
 * 1. **Indirect light.** Tanpa ini, sisi model yang tidak terkena lampu menjadi hitam.
 * 2. **Kendali waktu sendiri.** `ModelViewer.autoPlayAnimations` hanya bisa maju dengan
 *    kecepatan waktu nyata. Editor memerlukan jeda, kecepatan variabel, dan penggeseran
 *    playhead, jadi mesin animasi dimatikan dan waktunya dikelola di sini.
 * 3. **Pengelolaan daur hidup yang eksplisit.** Konstruktor utama `ModelViewer` tidak
 *    memasang pemantau pelepasan view sendiri, sehingga urutan pembongkaran sumber daya
 *    bisa ditentukan dengan pasti — swapchain, indirect light, lalu engine.
 *
 * ## Catatan penting untuk Phase 2
 *
 * Model masih dipanggil lewat [ModelViewer.transformToUnitCube], yang **menskalakan
 * dan menggeser root** agar muat dalam kubus satuan. Cara ini berasal dari contoh resmi
 * Filament dan membuat model langsung terlihat, tetapi skala itu membuat ruang bone
 * tidak sama dengan ruang dunia — yang akan mengacaukan gizmo dan target IK.
 * Di Phase 2 penggantinya adalah mengarahkan kamera ke bounding box model tanpa
 * menyentuh transform model sama sekali.
 */
class GlbViewport(context: Context) : SurfaceView(context), UiHelper.RendererCallback {

    val engine: Engine = Engine.create()

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    val viewer: ModelViewer = ModelViewer(engine, uiHelper)

    private val displayHelper = DisplayHelper(context)

    // Lebar/tinggi masih 0 saat konstruksi; Manipulator menolak viewport berukuran nol
    // dan akan diperbaiki lewat onResized begitu permukaan siap.
    private val manipulator: Manipulator = Manipulator.Builder()
        .targetPosition(0f, 0f, -4f)
        .orbitHomePosition(0f, 0f, 6f)
        .viewport(width.coerceAtLeast(1), height.coerceAtLeast(1))
        .build(Manipulator.Mode.ORBIT)

    private val gestureDetector = GestureDetector(this, manipulator)

    private val choreographer: Choreographer = Choreographer.getInstance()

    private var indirectLight: IndirectLight? = null
    private var swapChain: SwapChain? = null
    private var released = false
    private var loopRunning = false
    private var cameraConfigured = false

    private val eyePos = DoubleArray(3)
    private val targetPos = DoubleArray(3)
    private val upVector = DoubleArray(3)

    /** Waktu berjalan untuk klip yang sedang aktif, dalam detik. */
    var currentTimeSeconds: Float = 0f
        private set

    var isPlaying: Boolean = true

    var playbackSpeed: Float = 1f

    var activeClipIndex: Int = 0
        private set

    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released || !loopRunning) return
            stepAndRender(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    init {
        // Semua properti sudah diinisialisasi di titik ini, jadi renderCallback dan
        // lampu sudah siap ketika UiHelper mulai memanggil balik.
        indirectLight = Lighting.createStudioIndirectLight(engine).also {
            viewer.scene.indirectLight = it
        }
        viewer.autoPlayAnimations = false

        uiHelper.renderCallback = this
        uiHelper.attachTo(this)

        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // --- Pemuatan model ------------------------------------------------------

    /**
     * Memuat model dari byte mentah `.glb`.
     *
     * Buffer harus berupa `ByteBuffer` langsung (direct) karena diteruskan ke kode
     * native. Byte yang sama juga dipakai parser Kotlin, sehingga berkas hanya dibaca
     * satu kali.
     */
    fun loadModel(bytes: ByteArray): Boolean {
        if (released) return false

        val direct = ByteBuffer.allocateDirect(bytes.size)
        direct.put(bytes)
        direct.rewind()

        viewer.loadModelGlb(direct)

        val loaded = viewer.asset != null
        if (loaded) {
            viewer.scene.indirectLight = indirectLight
            viewer.transformToUnitCube()
        }
        resetPlayback()
        return loaded
    }

    fun unloadModel() {
        if (released) return
        viewer.destroyModel()
        resetPlayback()
    }

    private fun resetPlayback() {
        activeClipIndex = 0
        currentTimeSeconds = 0f
        lastFrameNanos = 0L
    }

    /**
     * Memadankan node glTF dengan entity Filament untuk model yang sedang termuat.
     *
     * Dipanggil setelah [loadModel]. Mengembalikan null bila model belum termuat.
     * Hasilnya diserahkan apa adanya ke panel diagnostik; tidak ada asumsi bahwa
     * pemetaan selalu berhasil.
     */
    fun buildNodeBinding(document: GltfDocument, skeleton: Skeleton): NodeBinding? {
        val asset = viewer.asset ?: return null
        return NodeBinding.build(asset, document, skeleton)
    }

    // --- Kendali klip ------------------------------------------------------

    val clipCount: Int get() = viewer.animator?.getAnimationCount() ?: 0

    fun clipName(index: Int): String =
        viewer.animator?.getAnimationName(index) ?: ""

    fun clipDuration(index: Int): Float =
        viewer.animator?.getAnimationDuration(index) ?: 0f

    fun selectClip(index: Int) {
        if (index < 0 || index >= clipCount) return
        activeClipIndex = index
        currentTimeSeconds = 0f
        lastFrameNanos = 0L
    }

    /** Menggeser playhead ke waktu tertentu, dan menghentikan pemutaran. */
    fun seekTo(seconds: Float) {
        isPlaying = false
        currentTimeSeconds = seconds
    }

    // --- Loop frame --------------------------------------------------------

    private fun startFrameLoop() {
        if (loopRunning || released) return
        loopRunning = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        loopRunning = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun stepAndRender(frameTimeNanos: Long) {
        applyAnimationIfNeeded(frameTimeNanos)

        // Kamera Manipulator adalah sumber kebenaran untuk orbit; nilainya disalin ke
        // kamera Filament setiap frame.
        manipulator.getLookAt(eyePos, targetPos, upVector)
        viewer.camera.lookAt(
            eyePos[0], eyePos[1], eyePos[2],
            targetPos[0], targetPos[1], targetPos[2],
            upVector[0], upVector[1], upVector[2]
        )

        viewer.render(frameTimeNanos)
    }

    private fun applyAnimationIfNeeded(frameTimeNanos: Long) {
        val animator = viewer.animator ?: return
        val count = animator.getAnimationCount()
        if (count <= 0) {
            lastFrameNanos = frameTimeNanos
            return
        }

        if (lastFrameNanos != 0L && isPlaying) {
            val deltaSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
            currentTimeSeconds += deltaSeconds * playbackSpeed
        }
        lastFrameNanos = frameTimeNanos

        val index = activeClipIndex.coerceIn(0, count - 1)
        activeClipIndex = index

        val duration = animator.getAnimationDuration(index)
        if (duration > 0f) {
            // Perulangan dikerjakan di sini, bukan diserahkan ke Filament, supaya
            // perilakunya sama untuk klip berdurasi nol maupun waktu negatif.
            currentTimeSeconds %= duration
            if (currentTimeSeconds < 0f) currentTimeSeconds += duration
        } else {
            currentTimeSeconds = 0f
        }

        animator.applyAnimation(index, currentTimeSeconds)
        animator.updateBoneMatrices()
    }

    // --- Daur hidup --------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrameLoop()
    }

    override fun onDetachedFromWindow() {
        // Hanya loop yang dihentikan. Pelepasan sumber daya dilakukan lewat [release]
        // supaya view yang sementara terlepas tidak menghancurkan engine.
        stopFrameLoop()
        super.onDetachedFromWindow()
    }

    /**
     * Melepas seluruh sumber daya Filament, termasuk engine.
     *
     * Aman dipanggil berkali-kali. Setelah dipanggil, objek ini tidak bisa dipakai lagi.
     */
    fun release() {
        if (released) return
        released = true
        stopFrameLoop()

        uiHelper.detach()
        displayHelper.detach()

        swapChain?.let {
            engine.destroy(it)
            engine.flushAndWait()
            swapChain = null
        }

        // Scene harus melepas referensinya sebelum objeknya dihancurkan.
        viewer.scene.setIndirectLight(null)
        indirectLight?.let {
            engine.destroy(it)
            indirectLight = null
        }

        // viewer.destroy() juga menghancurkan renderer, view, scene, kamera, lampu, dan engine.
        viewer.destroy()
    }

    // --- UiHelper.RendererCallback ----------------------------------------

    override fun onNativeWindowChanged(surface: Surface) {
        if (released) return
        swapChain?.let { engine.destroy(it) }
        swapChain = engine.createSwapChain(surface)
        displayHelper.attach(viewer.renderer, display)
    }

    override fun onDetachedFromSurface() {
        if (released) return
        displayHelper.detach()
        swapChain?.let {
            engine.destroy(it)
            // Menunggu frame yang masih berjalan selesai, supaya permukaan tidak
            // dihancurkan saat GPU masih menulis ke sana.
            engine.flushAndWait()
            swapChain = null
        }
    }

    override fun onResized(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        viewer.view.viewport = Viewport(0, 0, width, height)
        manipulator.setViewport(width, height)

        // Focal length baru diatur di sini, bukan saat konstruksi: setter
        // `cameraFocalLength` milik ModelViewer langsung menghitung ulang proyeksi
        // memakai ukuran viewport, dan ukuran itu masih nol (menghasilkan aspek NaN)
        // sebelum permukaan benar-benar siap.
        if (!cameraConfigured) {
            // 35 mm lebih dekat ke perspektif mata manusia daripada bawaan 28 mm,
            // sehingga proporsi model lebih mudah dinilai saat menyunting.
            viewer.cameraFocalLength = 35f
            cameraConfigured = true
        }

        updateCameraProjection()
        synchronizePendingFrames()
    }

    private fun updateCameraProjection() {
        val viewport = viewer.view.viewport
        if (viewport.height <= 0 || viewport.width <= 0) return
        val aspect = viewport.width.toDouble() / viewport.height.toDouble()
        viewer.camera.setLensProjection(
            viewer.cameraFocalLength.toDouble(),
            aspect,
            viewer.cameraNear.toDouble(),
            viewer.cameraFar.toDouble()
        )
    }

    private fun synchronizePendingFrames() {
        val fence = engine.createFence()
        fence.wait(Fence.Mode.FLUSH, Fence.WAIT_FOR_EVER)
        engine.destroy(fence)
    }
}
