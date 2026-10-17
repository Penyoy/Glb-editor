# Anima3D

Editor animasi 3D untuk Android. Membuka model `.glb` yang sudah memiliki rig dan klip
animasi, memutarnya, dan menyiapkan jalan menuju penyuntingan penuh: timeline, keyframe
per bone, IK, pencampuran animasi, serta ekspor ke `.glb` dan MP4.

Nama aplikasi: **Anima3D** · Application ID: **`id.animasi.anima3d`** · minSdk 26

---

## Status saat ini

Yang **sudah jalan** di kode ini:

| Bagian | Keadaan |
| --- | --- |
| Pembaca `.glb` dan JSON glTF (murni Kotlin) | Selesai, teruji di JVM |
| Pembaca accessor (termasuk `byteStride`, data URI, MAT4) | Selesai, teruji |
| Deteksi kerangka: joint, hierarki, rest pose, inverse bind | Selesai, teruji |
| Konversi klip bawaan glTF menjadi model `Clip` milik editor | Selesai, teruji |
| Sampler animasi: STEP, LINEAR, CUBICSPLINE, slerp | Selesai, teruji |
| Matematika transformasi: TRS ke matriks, pemecahan matriks, deteksi shear | Selesai, teruji |
| Aplikasi Android: buka berkas lewat SAF, viewport Filament, orbit kamera | Selesai |
| Pemutaran klip: pilih klip, putar/jeda, kecepatan, geser playhead, perulangan | Selesai |
| Daftar bone dari berkas, dengan hierarki dan kedalaman | Selesai |
| Panel diagnostik: pemetaan node glTF ke entity Filament | Selesai |

Yang **belum** ada (lihat peta jalan di bawah): menulis keyframe dari UI, timeline,
gizmo, IK, pencampuran, dan seluruh jalur ekspor.

> **Catatan kejujuran.** Kode ini ditulis tanpa pernah dikompilasi di mesin pengembang,
> karena mesin tersebut tidak punya JDK, Android SDK, maupun `/dev/kvm`. Kompilasi
> sepenuhnya bergantung pada GitHub Actions. Karena itu, kegagalan kompilasi pada
> percobaan pertama adalah hal yang wajar dan akan segera diperbaiki.

---

## Kenapa arsitekturnya seperti ini

Tiga keputusan membentuk seluruh struktur proyek.

### 1. Logika animasi tidak menyentuh Android

`core/anim` dan `core/gltf` adalah modul Kotlin/JVM murni. Tidak ada `import android.*`
di sana. Akibatnya seluruh matematika animasi — interpolasi, kuaternion, hierarki
kerangka, pembacaan berkas — bisa diuji dengan `./gradlew :core:gltf:test` di runner CI
biasa, tanpa perangkat dan tanpa emulator. Ini penting karena mesin pengembang tidak
punya `/dev/kvm`, sehingga emulator tidak mungkin dijalankan.

Semua kode yang menyentuh Android hanya ada di `render/` dan `app/`.

### 2. Parser kita sendiri adalah sumber kebenaran, bukan Filament

Filament menggambar model dengan sangat baik, tetapi API-nya tidak mengungkap dua hal
yang mutlak dibutuhkan sebuah editor:

- node glTF ke-`i` menjadi entity yang mana,
- node mana yang berupa joint.

`FilamentAsset.getEntities()` tidak menjamin urutannya sepadan dengan `nodes[]` di
berkas, dan tidak ada API yang bertanya "apakah entity ini sebuah joint".
`FilamentInstance.getJointsAt()` memberi daftar entity joint, tetapi tanpa indeks node,
nama, maupun hierarkinya.

Jalan keluarnya: parser Kotlin di `core/gltf` yang menentukan isi berkas secara
otoritatif, lalu hasilnya **dipadankan dan diverifikasi** dengan Filament saat model
dimuat. Verifikasi itu ada di `render/NodeBinding.kt` dan dilakukan lewat dua pemeriksaan
independen:

1. **Nama.** Untuk setiap node bernama, nama entity pada posisi yang sama harus identik.
2. **Joint.** Untuk setiap skin, himpunan entity dari `getJointsAt` harus sama dengan
   himpunan entity yang dihitung dari `skins[].joints` di berkas.

Hasilnya dilaporkan terus terang di tab **Diagnostik** dengan tiga tingkat keyakinan:
`VERIFIED_BY_INDEX`, `FALLBACK_BY_NAME`, atau `UNRELIABLE`. Bila pemetaan tidak bisa
dipercaya, editor tidak akan mengizinkan penyuntingan bone — salah memetakan node berarti
menggeser satu bone akan menggerakkan bone lain, dan pengguna tidak akan bisa menyadarinya.

### 3. Animasi bawaan diubah menjadi data milik kita

`Animator` milik Filament hanya dipakai untuk memutar klip. Agar klip bawaan bisa
**diedit**, satu langkah konversi mengubah setiap animasi di berkas menjadi `Clip`
berisi `Channel` dengan array waktu, nilai, dan tangen. Setelah itu mesin kita sendiri
yang memiliki seluruh data, dan `Animator` tinggal menjadi penggerak sementara.

Data disimpan sebagai array rata (*structure of arrays*), bukan `List<Keyframe>`.
Evaluasi berjalan setiap frame untuk setiap channel; dengan array rata, pemutaran tidak
menghasilkan sampah memori sama sekali.

---

## Struktur modul

```
app/                       UI Compose, navigasi, pemilihan berkas (SAF)
render/                    Filament: GlbViewport, NodeBinding, pencahayaan
core/gltf/                 Parser GLB + JSON glTF, accessor, kerangka, konversi klip
core/anim/                 Model klip, sampler, matematika kuaternion dan matriks
```

Arah ketergantungan: `app` → `render` → `core/gltf` → `core/anim`. Tidak ada anak panah
yang mengarah balik ke Android.

`GlbViewport` memakai `ModelViewer` dari `filament-utils` untuk menyiapkan engine, scene,
view, kamera, lampu matahari, dan penanganan gestur. Yang ditambahkan sendiri di atasnya:
indirect light (yang tidak disediakan `ModelViewer`), kendali waktu penuh (jeda,
kecepatan, geser playhead — `ModelViewer.autoPlayAnimations` tidak bisa melakukan ini),
dan urutan pembongkaran sumber daya yang eksplisit.

Pencahayaan sengaja **tidak** memakai berkas HDRI `.ktx`. Berkas itu menambah beberapa
megabita ke repositori dan menimbulkan urusan lisensi, sementara editor hanya perlu
melihat bentuk dan warna model dengan jujur. Sebagai gantinya, indirect light dibangun
dari koefisien spherical harmonics pita pertama: cahaya ambient seragam, beberapa baris
kode, tanpa berkas.

---

## Membangun dan memasang

### Lewat GitHub Actions

Setiap push ke `main` menjalankan satu job `build` dengan dua tahap yang berurutan:

1. **Unit test inti (JVM)** — `:core:anim:test` dan `:core:gltf:test`.
2. **Build APK debug** — `:app:assembleDebug`.

Tahap kedua tidak akan berjalan bila tahap pertama gagal. Keduanya digabung dalam
satu job karena Gradle tetap mengonfigurasi semua modul walau hanya tugas JVM yang
diminta: modul Android, dan karena itu Android SDK, sudah dibutuhkan sejak tahap test.

APK hasil build tersedia sebagai artefak `app-debug-apk` di halaman *Actions* pada run
yang bersangkutan. Unduh, lalu pasang di ponsel (perlu mengizinkan "pasang dari sumber
tidak dikenal").

### Di mesin sendiri

Butuh JDK 17 dan Android SDK dengan platform API 37 — paket `platforms;android-37.0`,
karena Android 17 memakai penomoran versi minor — serta `build-tools;36.0.0`.

```bash
./gradlew :core:anim:test :core:gltf:test   # logika inti, tanpa menyentuh perangkat
./gradlew :app:assembleDebug
```

### Versi yang dipakai

| Komponen | Versi | Catatan |
| --- | --- | --- |
| Gradle | 9.8.1 | |
| Android Gradle Plugin | 9.4.1 | AGP pertama yang mendukung API 37 adalah 9.3 |
| Kotlin | 2.4.21 | |
| Compose BOM | 2026.09.00 | Compose UI 1.12.1, Material3 1.4.0 |
| Filament | 1.77.3 | `filament-android`, `gltfio-android`, `filament-utils-android` |
| kotlinx-serialization | 1.11.0 | |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | |

`compileSdk` harus 37 karena Compose UI 1.12.1 dan seluruh AAR Filament 1.77.3
mendeklarasikan `minCompileSdk=37` di metadata AAR-nya. Batas bawah itu menaikkan
AGP juga: `androidx.compose.ui:ui-android:1.12.1` dan `core-ktx:1.19.1` menulis
`minAndroidGradlePluginVersion=9.1.0`, sedangkan AGP baru mengenal API 37 sejak 9.3.
`targetSdk` tetap 36 supaya perubahan perilaku Android 17 belum berlaku.

`gradle.properties` memuat dua pengecualian untuk AGP 9 — `android.newDsl=false` dan
`android.builtInKotlin=false`. Keduanya mengembalikan perilaku DSL dan Kotlin seperti
AGP 8.x, yang cocok dengan susunan modul di sini: `org.jetbrains.kotlin.android`
memang tidak kompatibel dengan DSL baru, dan modul `core` memakai `kotlin("jvm")`.
Keduanya dihapus pada AGP 10, jadi ini utang teknis yang harus dilunasi lebih dahulu.

**SceneView tidak dipakai.** `io.github.sceneview:sceneview` masih terpaku pada Filament
1.72.1 dan sudah ditulis ulang sebagai proyek Kotlin Multiplatform dengan API yang
berbeda. Editor memerlukan kendali penuh atas `TransformManager` dan swapchain, jadi
Filament dipakai langsung.

---

## Peta jalan

| Fase | Isi | Keadaan |
| --- | --- | --- |
| 0 | Kerangka proyek, CI, APK kosong bisa dipasang | Selesai |
| 1 | Viewport, pemutaran klip bawaan, daftar bone, verifikasi pemetaan node | Selesai |
| 2 | Timeline, pemilihan bone, keyframe, langsung diedit lewat mesin sendiri | Berikutnya |
| 3 | Gizmo 2D di atas viewport, memindahkan/memutar/menskalakan bone | |
| 4 | Kurva editor untuk nilai keyframe dan interpolasi | |
| 5 | IK dua tulang dan target IK yang bisa diseret | |
| 6 | Ekspor `.glb` (menambahkan animasi ke chunk BIN yang sudah ada) dan MP4 | |
| 7 | Pencampuran animasi, lapisan, pengaturan proyek `.anim` | |

Batas yang **bukan** tujuan versi 1: menyunting topologi mesh, sculpting, membuat rig
baru, editor node material, animasi 2D, audio, fisika, iOS, sinkronisasi awan, dan
penerbitan ke Play Store.

---

## Berkas proyek

Format proyek `.anim` yang direncanakan akan **menyalin** berkas `.glb` ke dalamnya,
sehingga proyek tetap utuh walau berkas aslinya dipindahkan atau dihapus. Rencana ekspor
`.glb` tidak menulis ulang berkas: byte animasi baru ditambahkan ke ujung chunk BIN yang
sudah ada, `buffers[0].byteLength` diperbarui, `bufferViews` baru menunjuk ke `buffer: 0`,
keselarasan 4 byte dijaga, dan setiap accessor `input` diberi `min`/`max`.

---

## Penyimpangan dari rencana awal

Empat hal berbeda dari rencana semula, semuanya disengaja:

1. **`core/project` belum dibuat.** Modul itu belum punya isi sampai Fase 7, dan modul
   kosong hanya menambah kebisingan.
2. **`UiHelper` bukan API `filament-utils`, melainkan `filament.android`.** Rencana awal
   menyebutnya di `filament-utils`; hasil pemeriksaan bytecode AAR menunjukkan lokasinya
   di `com.google.android.filament.android.UiHelper`. Di `filament-utils` yang ada adalah
   `Manipulator` dan `GestureDetector`.
3. **Model masih diskalakan agar muat kubus satuan** lewat `ModelViewer.transformToUnitCube`,
   seperti contoh resmi Filament. Cara ini membuat model langsung terlihat, tetapi skala
   pada root membuat ruang bone tidak sama dengan ruang dunia — yang akan mengacaukan
   gizmo dan target IK. Fase 2 menggantinya dengan mengarahkan kamera ke bounding box
   tanpa menyentuh transform model. Sudah ditandai di `GlbViewport.kt`.
4. **Menyorot bone di viewport belum ada.** Daftar bone dan pemilihannya sudah jalan,
   tetapi penanda 3D-nya baru masuk bersama gizmo di Fase 3.

---

## Lisensi aset uji

Tes unit di repositori ini **tidak** memakai model pihak ketiga: berkas `.glb` uji dibuat
di dalam memori oleh `TestGlb.kt`. Tidak ada berkas biner besar, dan tidak ada pertanyaan
lisensi.

Ketika model contoh eksternal dipakai untuk uji asap di perangkat, asal-usulnya akan
dicatat di `docs/lisensi-aset.md`. Rencananya memakai Khronos Sample Models: **Fox**
(CC0) dan **CesiumMan** (CC-BY 4.0, wajib mencantumkan atribusi).

Filament berlisensi Apache 2.0; Kotlin, AndroidX, dan kotlinx-serialization berlisensi
Apache 2.0.
