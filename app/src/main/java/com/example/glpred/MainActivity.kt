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
import com.example.glpred.ui.AnalysisScreen
import com.example.glpred.ui.ConfirmDataScreen
import com.example.glpred.ui.DiaryViewModel
import com.example.glpred.ui.EnterDataScreen
import com.example.glpred.ui.FetchDataScreen
import com.example.glpred.ui.FinalConfirmScreen
import com.example.glpred.ui.HistoryScreen

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
                        onFetchDataClick = { navController.navigate("fetch_data") },
                        onHistoryClick = { navController.navigate("history") },
                        onAnalysisClick = { navController.navigate("analysis") },
                        trainingStatus = viewModel.trainingStatus,
                        isTraining = viewModel.isTraining,
                        onTrainClick = { viewModel.trainModel() }
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
                        onNavigateToFinal = { navController.navigate("final_confirm") }
                    )
                }
                composable("final_confirm") {
                    FinalConfirmScreen(
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
                composable("history") {
                    HistoryScreen(
                        onNavigateBack = { navController.popBackStack() },
                        viewModel = viewModel
                    )
                }
                composable("analysis") {
                    AnalysisScreen(
                        onNavigateBack = { navController.popBackStack() },
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    onNewEntryClick: () -> Unit,
    onFetchDataClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onAnalysisClick: () -> Unit,
    trainingStatus: String,
    isTraining: Boolean,
    onTrainClick: () -> Unit
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
                onClick = onFetchDataClick,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(text = "Fetch Data")
            }

            Button(
                onClick = onHistoryClick,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(text = "History")
            }

            Button(
                onClick = onAnalysisClick,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(text = "Model Performance Analysis")
            }

            Button(
                onClick = onTrainClick,
                enabled = !isTraining,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(text = if (isTraining) "Training..." else "Train Model")
            }

            Text(text = trainingStatus)
        }
    }
}
