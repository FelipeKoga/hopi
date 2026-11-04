package floor.app

import android.app.Application
import android.util.Log
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.perf.HopiPerf
import dev.koga.hopi.rules.HopiRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * What an app's own code calls of Hopi: `hopi-api` in every build (in a debug build the plugin's
 * provider registered the runtimes before `onCreate`; in a release build each call does nothing),
 * and Hopi's Ktor plugin on its client.
 */
class App : Application() {
    lateinit var client: HttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i("compat", "Hopi rules: ${HopiRules.list().size}")
        HopiPerf.screen("floor")
        client = HttpClient(OkHttp) {
            install(Hopi)
        }
    }
}
