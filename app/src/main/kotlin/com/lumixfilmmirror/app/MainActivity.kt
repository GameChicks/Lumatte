package com.lumixfilmmirror.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumixfilmmirror.design.FilmMirrorTheme

class MainActivity : ComponentActivity() {
    private val camera get() = (application as FilmMirrorApplication).camera

    override fun onStart() { super.onStart(); camera.attachUi() }
    override fun onStop() { camera.detachUi(); super.onStop() }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) (application as FilmMirrorApplication).endEditingSession()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as FilmMirrorApplication
        super.onCreate(if (app.uiSessionStarted) savedInstanceState else null)
        app.uiSessionStarted = true
        enableEdgeToEdge()
        setContent {
            FilmMirrorTheme {
                val workspace: WorkspaceViewModel = viewModel()
                MirrorScreen(workspace)
            }
        }
    }
}
