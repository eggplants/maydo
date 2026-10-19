package io.github.eggplants.maydo.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.eggplants.maydo.AppContainer
import io.github.eggplants.maydo.ui.home.HomeScreen
import io.github.eggplants.maydo.ui.settings.ConfigEditorScreen
import io.github.eggplants.maydo.ui.settings.ConfigFile
import io.github.eggplants.maydo.ui.settings.LicensesScreen
import io.github.eggplants.maydo.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CONFIG = "config/{file}"
    const val LICENSES = "licenses"

    fun config(file: ConfigFile) = "config/${file.name}"
}

@Composable
fun MaydoRoot(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(container, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container,
                onBack = { nav.popBackStack() },
                onEditConfig = { nav.navigate(Routes.config(it)) },
                onLicenses = { nav.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.CONFIG) { entry ->
            val file =
                ConfigFile.entries.firstOrNull { it.name == entry.arguments?.getString("file") }
                    ?: ConfigFile.MPV
            ConfigEditorScreen(container, file, onBack = { nav.popBackStack() })
        }
        composable(Routes.LICENSES) { LicensesScreen(onBack = { nav.popBackStack() }) }
    }
}
