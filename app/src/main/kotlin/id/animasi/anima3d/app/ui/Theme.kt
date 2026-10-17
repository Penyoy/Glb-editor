package id.animasi.anima3d.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Skema warna gelap tetap.
 *
 * Editor 3D selalu memakai latar gelap: warna model jadi lebih mudah dinilai, dan
 * panel di sekitar viewport tidak bersaing dengan isi viewport.
 */
@Composable
fun AnimaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}
