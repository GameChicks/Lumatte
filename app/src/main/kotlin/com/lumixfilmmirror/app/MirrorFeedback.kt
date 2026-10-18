package com.lumixfilmmirror.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

internal val LocalMirrorSoundEffects=compositionLocalOf { true }
internal val LocalMirrorFeedbackPlayer=compositionLocalOf<MirrorFeedbackPlayer?> { null }

/** Short, original sonification. Uses system effect volume; never raises volume or bypasses silent mode. */
internal class MirrorFeedbackPlayer(context: Context) {
    private val audio=context.applicationContext.getSystemService(AudioManager::class.java)
    private val pool=SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    @Volatile private var loaded=false
    @Volatile private var released=false
    private var last=0L
    private val sound: Int
    init {
        pool.setOnLoadCompleteListener { _,_,status -> if(!released)loaded=status==0 }
        sound=pool.load(context,R.raw.feedback_tick,1)
    }
    fun tick() {
        if(released || !loaded || audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_SYSTEM)==0)return
        val now=SystemClock.elapsedRealtime()
        if(now-last<65)return
        last=now
        pool.play(sound,.35f,.35f,1,0,1f)
    }
    fun release() { released=true;loaded=false;pool.release() }
}

@Composable
internal fun rememberMirrorTick(): ()->Unit {
    val haptic=LocalHapticFeedback.current
    val vibration=LocalMirrorHaptics.current
    val sound=LocalMirrorSoundEffects.current
    val player=LocalMirrorFeedbackPlayer.current
    return remember(haptic,vibration,sound,player) { {
        if(vibration)haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if(sound)player?.tick()
    } }
}
