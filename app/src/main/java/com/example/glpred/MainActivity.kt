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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainScreen(
                onNewEntryClick = {
                    // TODO: Navigate to Enter Data screen
                },
                onFetchDataClick = {
                    // TODO: Navigate to Fetch Data screen
                }
            )
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
