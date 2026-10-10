package com.lumixfilmmirror.app

import android.app.Application

class FilmMirrorApplication : Application() {
    val camera: CameraController by lazy { CameraController(this) }
}
