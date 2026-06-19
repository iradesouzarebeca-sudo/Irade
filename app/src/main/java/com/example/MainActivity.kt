package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.data.database.PdfDatabase
import com.example.data.repository.PdfRepository
import com.example.ui.screens.PdfListScreen
import com.example.ui.screens.PdfWorkspaceScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PdfViewModel
import com.example.ui.viewmodel.PdfViewModelFactory
import com.example.ui.viewmodel.UiState

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val database = PdfDatabase.getDatabase(applicationContext)
    val repository = PdfRepository(database.pdfDao(), applicationContext)
    val factory = PdfViewModelFactory(repository, application)
    val viewModel = ViewModelProvider(this, factory)[PdfViewModel::class.java]

    setContent {
      MyApplicationTheme {
        val uiState by viewModel.uiState.collectAsState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Crossfade(targetState = uiState, label = "screen_routing_fade") { state ->
                when (state) {
                    is UiState.ListDocuments -> {
                        PdfListScreen(viewModel = viewModel)
                    }
                    is UiState.AnnotationWorkspace -> {
                        PdfWorkspaceScreen(
                            viewModel = viewModel,
                            document = state.document
                        )
                    }
                }
            }
        }
      }
    }
  }
}

