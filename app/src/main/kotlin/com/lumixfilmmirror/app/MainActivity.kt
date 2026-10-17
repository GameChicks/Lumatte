package com.lumixfilmmirror.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumixfilmmirror.design.FilmMirrorTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat

open class MainActivity : ComponentActivity() {
    private val camera get() = (application as FilmMirrorApplication).camera
    private var widgetRequest by mutableStateOf<WidgetLaunch?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetRequest=widgetLaunch(this,intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("widget-request-consumed",widgetRequest==null)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() { super.onStart(); camera.attachUi() }
    override fun onStop() { camera.detachUi(); super.onStop() }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) (application as FilmMirrorApplication).endEditingSession()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as FilmMirrorApplication
        super.onCreate(if (app.uiSessionStarted) savedInstanceState else null)
        if(savedInstanceState?.getBoolean("widget-request-consumed")!=true)widgetRequest=widgetLaunch(this,intent)
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
                MirrorScreen(workspace,widgetRequest) { widgetRequest=null }
            }
        }
    }
}
