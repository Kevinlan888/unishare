package com.unishare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unishare.app.presentation.home.HomeViewModel
import com.unishare.app.presentation.navigation.UniShareNavHost
import com.unishare.app.presentation.session.SessionRoute
import com.unishare.app.presentation.session.SessionViewModel
import com.unishare.app.presentation.theme.UniShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val graph = UniShareApplication.graph(this)
            val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(graph))
            val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
            UniShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    UniShareNavHost(
                        homeUiState = homeState,
                        onRefresh = homeViewModel::refresh,
                        sessionContent = { deviceId, onBack ->
                            val sessionViewModel: SessionViewModel = viewModel(
                                key = "session-${deviceId.value}",
                                factory = SessionViewModel.Factory(graph, deviceId),
                            )
                            SessionRoute(sessionViewModel, onBack)
                        },
                    )
                }
            }
        }
    }
}
