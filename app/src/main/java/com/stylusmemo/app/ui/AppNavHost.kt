package com.stylusmemo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stylusmemo.app.ui.editor.EditorScreen
import com.stylusmemo.app.ui.editor.EditorViewModel
import com.stylusmemo.app.ui.home.HomeScreen
import com.stylusmemo.app.ui.home.HomeViewModel
import com.stylusmemo.app.ui.settings.SettingsScreen
import com.stylusmemo.app.ui.settings.SettingsViewModel

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{noteId}"
    const val SETTINGS = "settings"

    fun editor(noteId: String) = "editor/$noteId"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel()
            HomeScreen(
                viewModel = vm,
                onOpenNote = { id -> navController.navigate(Routes.editor(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
        ) { entry ->
            val noteId = entry.arguments?.getString("noteId").orEmpty()
            val vm: EditorViewModel = viewModel()
            EditorScreen(
                noteId = noteId,
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
