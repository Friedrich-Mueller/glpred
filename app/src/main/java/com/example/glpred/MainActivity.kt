package com.example.glpred

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.glpred.ui.ConfirmDataScreen
import com.example.glpred.ui.DiaryViewModel
import com.example.glpred.ui.EnterDataScreen
import com.example.glpred.ui.FetchDataScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()
            val viewModel: DiaryViewModel = viewModel()

            NavHost(navController = navController, startDestination = "main") {
                composable("main") {
                    MainScreen(
                        onNewEntryClick = { navController.navigate("enter_data") },
                        onFetchDataClick = { navController.navigate("fetch_data") }
                    )
                }
                composable("enter_data") {
                    EnterDataScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToConfirm = { navController.navigate("confirm_data") }
                    )
                }
                composable("confirm_data") {
                    ConfirmDataScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onSaveSuccess = {
                            navController.navigate("main") {
                                popUpTo("main") { inclusive = true }
                            }
                        }
                    )
                }
                composable("fetch_data") {
                    FetchDataScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    onNewEntryClick: () -> Unit,
    onFetchDataClick: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onNewEntryClick,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(text = "New Entry")
            }

            Button(
                onClick = onFetchDataClick
            ) {
                Text(text = "Fetch Data")
            }
        }
    }
}
