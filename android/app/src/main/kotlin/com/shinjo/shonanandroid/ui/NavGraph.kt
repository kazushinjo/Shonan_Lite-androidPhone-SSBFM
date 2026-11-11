package com.shinjo.shonanandroid.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shinjo.shonanandroid.AppViewModel

@Composable
fun ShonanNavHost(viewModel: AppViewModel) {
    val navController = rememberNavController()
    viewModel.plutoCapabilityNotice?.let { notice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.plutoCapabilityNotice = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.plutoCapabilityNotice = null }) {
                    androidx.compose.material3.Text("OK")
                }
            },
            title = { androidx.compose.material3.Text(viewModel.settings.t("変調方式", "Modulation")) },
            text = { androidx.compose.material3.Text(notice) },
        )
    }
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(viewModel) }
    }
}
