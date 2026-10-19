package io.github.eggplants.maydo

import android.app.Application
import android.content.Context
import io.github.eggplants.maydo.core.AppUpdater
import io.github.eggplants.maydo.core.SettingsRepository
import io.github.eggplants.maydo.core.UpdateManager
import io.github.eggplants.maydo.core.YtdlpRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val runtime = YtdlpRuntime(context)
    val updates = UpdateManager(runtime, AppUpdater(context))
}

class MaydoApp : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Unpacking Python takes a few seconds after an install or update; do it before the first
        // video asks for it.
        scope.launch {
            container.runtime.prepare()
            if (container.settings.current().checkUpdates) container.updates.checkAll()
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as MaydoApp).container
