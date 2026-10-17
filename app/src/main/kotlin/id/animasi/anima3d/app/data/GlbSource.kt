package id.animasi.anima3d.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/** Berkas `.glb` yang sudah dibaca ke memori, beserta nama tampilannya. */
class LoadedGlb(
    val bytes: ByteArray,
    val displayName: String
)

class GlbLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Membaca berkas `.glb` dari URI yang dipilih pengguna lewat Storage Access Framework.
 *
 * Berkas dibaca sekaligus ke dalam memori karena dibutuhkan dua konsumen: parser Kotlin
 * dan Filament, yang keduanya memerlukan seluruh byte. Batas ukuran dipasang lebih dahulu
 * supaya berkas raksasa gagal dengan pesan yang jelas, bukan dengan kehabisan memori.
 */
object GlbSource {

    /** Batas atas ukuran berkas. Model di atas ini hampir pasti juga tidak muat di GPU ponsel. */
    const val MAX_BYTES = 200L * 1024 * 1024

    fun load(context: Context, uri: Uri): LoadedGlb {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "model.glb"

        val declaredSize = querySize(context, uri)
        if (declaredSize != null && declaredSize > MAX_BYTES) {
            throw GlbLoadException(
                "Berkas ${declaredSize / (1024 * 1024)} MB melebihi batas " +
                    "${MAX_BYTES / (1024 * 1024)} MB yang bisa dibuka aplikasi ini."
            )
        }

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    // Ukuran dari penyedia berkas tidak selalu ada, jadi batasnya
                    // diperiksa lagi sambil membaca.
                    if (total > MAX_BYTES) {
                        throw GlbLoadException(
                            "Berkas melebihi batas ${MAX_BYTES / (1024 * 1024)} MB."
                        )
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: throw GlbLoadException("Tidak bisa membuka berkas yang dipilih.")
        } catch (e: GlbLoadException) {
            throw e
        } catch (e: IOException) {
            throw GlbLoadException("Gagal membaca berkas: ${e.message}", e)
        } catch (e: SecurityException) {
            throw GlbLoadException("Aplikasi tidak diberi akses ke berkas itu.", e)
        }

        if (bytes.isEmpty()) {
            throw GlbLoadException("Berkas kosong.")
        }

        return LoadedGlb(bytes, name)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        queryString(context, uri, OpenableColumns.DISPLAY_NAME)

    private fun querySize(context: Context, uri: Uri): Long? =
        queryString(context, uri, OpenableColumns.SIZE)?.toLongOrNull()?.takeIf { it >= 0 }

    /** Membaca satu kolom sebagai teks; sebagian penyedia menyimpan ukuran sebagai string. */
    private fun queryString(context: Context, uri: Uri, column: String): String? {
        // Sebagian penyedia berkas melempar untuk URI non-dokumen; itu bukan kesalahan fatal.
        return try {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(column)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
