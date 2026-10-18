package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumixfilmmirror.design.FilmMirrorTheme
import com.lumixfilmmirror.camera.CameraDevice
import kotlinx.coroutines.delay
import java.io.File

/** Manual capture of existing home components. The explicit S5 state selector affects only this image. */
class PromoCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val kind=intent.getStringExtra("card")?.takeIf { it in setOf("camera","bluetooth","phone","usage","journal") } ?: "camera"
        setContent {
            val workspace:WorkspaceViewModel=viewModel()
            val state by workspace.state.collectAsStateWithLifecycle()
            val camera by workspace.cameraState.collectAsStateWithLifecycle()
            val personal by workspace.personal.collectAsStateWithLifecycle()
            val transfers by workspace.transferTasks.collectAsStateWithLifecycle()
            val captureDevice=remember { CameraDevice(java.net.URI("http://192.168.54.1/capture-preview"),"S5","DC-S5","","promo-s5",false,java.net.URI("http://192.168.54.1/capture-preview")) }
            val connectedS5=intent.getBooleanExtra("connected-s5",false)
            val captureCamera=if(connectedS5)CameraWorkspaceState(device=captureDevice,connected=true) else camera
            val capturePersonal=if(connectedS5)personal.copy(cameras=listOf(RememberedCamera(captureDevice.rememberedIdentity(),"DC-S5","S5"))) else personal
            FilmMirrorTheme(dark=false) {
                CompositionLocalProvider(LocalMirrorReducedMotion provides true,LocalMirrorDotArt provides true) {
                    val layer=rememberGraphicsLayer()
                    val fontScale=LocalDensity.current.fontScale
                    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF17191A)),contentAlignment=Alignment.Center) {
                        val height=if(kind in setOf("bluetooth","phone"))homeActionCardHeight(maxWidth,fontScale) else homeCardHeight(maxWidth,fontScale)
                        val width=if(kind in setOf("bluetooth","phone"))(maxWidth-56.dp)/2 else maxWidth-40.dp
                        Box(Modifier.width(width).height(height).drawWithContent {
                            layer.record { this@drawWithContent.drawContent() };drawLayer(layer)
                        }) {
                            when(kind) {
                                "camera"->CameraHero(captureCamera,capturePersonal,workspace,{},Modifier.fillMaxWidth(),height)
                                "bluetooth"->ActionAtmosphere("蓝牙\n连接","连接你的相机",listOf(Color(0xFFFFCF78),Color(0xFFED653B),Color(0xFF428FA6)),Modifier.fillMaxWidth(),height,DotGlyph.BLUETOOTH,"BLUETOOTH\nCONNECT",{})
                                "phone"->ActionAtmosphere("手机\n相册","导入手机照片",listOf(Color(0xFF285D34),Color(0xFF89BB64),Color(0xFFF0DF93)),Modifier.fillMaxWidth(),height,DotGlyph.ALBUM,"IMPORT\nPHOTOS",{})
                                "usage"->UsageCard(state,transfers,height)
                                else->HomeJournalCard(personal,Modifier.fillMaxWidth(),height,{})
                            }
                        }
                    }
                    LaunchedEffect(kind,state.recovering,personal) {
                        if(state.recovering)return@LaunchedEffect
                        delay(1800)
                        val output=File(cacheDir,"promo-output").apply { mkdirs() }
                        File(output,"$kind.png").outputStream().use {
                            layer.toImageBitmap().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
                        }
                    }
                }
            }
        }
    }
}
