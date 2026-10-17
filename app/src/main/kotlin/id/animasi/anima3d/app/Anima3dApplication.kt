package id.animasi.anima3d.app

import android.app.Application
import com.google.android.filament.Filament

/**
 * Pemuatan pustaka native Filament dilakukan sekali di sini.
 *
 * Filament bisa memuat pustakanya sendiri saat `Engine.create()`, tetapi melakukan
 * eksplisit di awal membuat kegagalan pemuatan pustaka muncul sebagai kesalahan yang
 * jelas saat aplikasi dibuka, bukan sebagai crash tak terduga saat model dimuat.
 */
class Anima3dApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Filament.init()
    }
}
