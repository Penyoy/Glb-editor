package id.animasi.anima3d.render

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight

/**
 * Pencahayaan.
 *
 * `ModelViewer` sudah menambahkan satu lampu directional (SUN) beserta bayangannya,
 * tetapi secara eksplisit **tidak** menambahkan indirect light — itu tugas pemanggil.
 * Tanpa indirect light, permukaan yang tidak terkena lampu jadi hitam pekat.
 *
 * Alih-alih memuat berkas HDRI `.ktx` (yang menambah beberapa megabita ke repositori
 * dan menimbulkan urusan lisensi), indirect light di sini dibangun dari koefisien
 * spherical harmonics pita pertama. Satu koefisien per kanal berarti cahaya ambient
 * yang seragam — cukup untuk melihat bentuk dan warna model dengan jujur, dan tidak
 * menyembunyikan masalah pencahayaan di balik refleksi yang cantik.
 */
object Lighting {

    /** Langit-langit studio yang sedikit kebiruan, meniru lingkungan netral. */
    private val STUDIO_AMBIENT = floatArrayOf(0.30f, 0.31f, 0.36f)

    fun createStudioIndirectLight(engine: Engine): IndirectLight =
        IndirectLight.Builder()
            .irradiance(1, STUDIO_AMBIENT)
            .intensity(30_000f)
            .build(engine)
}
