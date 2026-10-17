package id.animasi.anima3d.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import id.animasi.anima3d.app.data.GlbSource
import id.animasi.anima3d.app.data.LoadedGlb
import id.animasi.anima3d.app.ui.AnimaTheme
import id.animasi.anima3d.app.ui.HomeScreen
import id.animasi.anima3d.app.ui.ViewerScreen
import id.animasi.anima3d.gltf.ModelSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnimaTheme {
                AnimaApp()
            }
        }
    }
}

/**
 * Navigasi sengaja dibuat tanpa pustaka: hanya dua layar, dan menambah dependensi
 * navigasi hanya akan menambah satu hal lagi yang bisa berubah API-nya.
 */
@Composable
private fun AnimaApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadedGlb by remember { mutableStateOf<LoadedGlb?>(null) }
    var session by remember { mutableStateOf<ModelSession?>(null) }

    val glb = loadedGlb
    val model = session

    if (glb != null && model != null) {
        ViewerScreen(
            bytes = glb.bytes,
            session = model,
            displayName = glb.displayName,
            onClose = {
                loadedGlb = null
                session = null
            }
        )
        return
    }

    HomeScreen(
        isLoading = isLoading,
        errorMessage = errorMessage,
        onOpenFile = { uri ->
            isLoading = true
            errorMessage = null
            scope.launch {
                // Membaca berkas dan mengurai seluruh glTF bisa makan waktu untuk model
                // besar, jadi keduanya dijalankan di luar thread utama.
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val loaded = GlbSource.load(context, uri)
                        val parsed = ModelSession.parse(loaded.bytes)
                        loaded to parsed
                    }
                }
                isLoading = false
                result
                    .onSuccess { (loaded, parsed) ->
                        loadedGlb = loaded
                        session = parsed
                    }
                    .onFailure { error ->
                        errorMessage = error.message
                            ?: error::class.simpleName
                            ?: "Terjadi kesalahan yang tidak dikenal"
                    }
            }
        }
    )
}
