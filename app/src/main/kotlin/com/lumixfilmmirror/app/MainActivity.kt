package com.lumixfilmmirror.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumixfilmmirror.design.FilmMirrorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilmMirrorTheme {
                val workspace: WorkspaceViewModel = viewModel()
                WorkspaceScreen(workspace)
            }
        }
    }
}
