package com.lumixfilmmirror.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumixfilmmirror.design.FilmMirrorTheme
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat

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
            val workspace: WorkspaceViewModel = viewModel()
            val personal by workspace.personal.collectAsStateWithLifecycle()
            val dark = when (personal.appearance) {
                MirrorAppearance.SYSTEM -> isSystemInDarkTheme()
                MirrorAppearance.LIGHT -> false
                MirrorAppearance.DARK -> true
            }
            FilmMirrorTheme(dark = dark) {
                DisposableEffect(dark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                    onDispose { }
                }
                MirrorScreen(workspace)
            }
        }
    }
}
