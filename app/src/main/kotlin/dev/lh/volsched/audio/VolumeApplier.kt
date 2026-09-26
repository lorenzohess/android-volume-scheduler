package dev.lh.volsched.audio

import android.content.Context
import android.media.AudioManager
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.clamp

/** Maps this app's stream enum onto the AudioManager constants. */
val AudioStream.androidStreamType: Int
    get() = when (this) {
        AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
    }

sealed interface ApplyResult {
    data class Applied(val before: Int, val after: Int, val clamped: Boolean) : ApplyResult
    data class Refused(val reason: String) : ApplyResult
}

/**
 * The only place this app touches AudioManager.
 *
 * Every write goes through [apply], which clamps first - validation should
 * already guarantee a safe level, but a value that slipped through would throw
 * SecurityException on ring or notification rather than just sounding wrong.
 */
class VolumeApplier(context: Context) {

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun maxLevel(stream: AudioStream): Int =
        audioManager.getStreamMaxVolume(stream.androidStreamType)

    fun maxLevels(): Map<AudioStream, Int> =
        AudioStream.entries.associateWith { maxLevel(it) }

    fun currentLevel(stream: AudioStream): Int =
        audioManager.getStreamVolume(stream.androidStreamType)

    fun currentLevels(): Map<AudioStream, Int> =
        AudioStream.entries.associateWith { currentLevel(it) }

    fun apply(stream: AudioStream, level: Int): ApplyResult {
        val target = stream.clamp(level, maxLevel(stream))
        val before = currentLevel(stream)
        return try {
            // Flags must be 0: FLAG_SHOW_UI would pop the volume panel and
            // FLAG_PLAY_SOUND would beep on every scheduled change.
            audioManager.setStreamVolume(stream.androidStreamType, target, 0)
            ApplyResult.Applied(before = before, after = target, clamped = target != level)
        } catch (e: SecurityException) {
            // Should be unreachable given clamping, but worth logging rather
            // than crashing a BroadcastReceiver.
            ApplyResult.Refused(e.message ?: "SecurityException")
        }
    }
}
