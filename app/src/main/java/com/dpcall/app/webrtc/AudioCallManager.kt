package com.dpcall.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Управление аудиофокусом и маршрутизацией звука во время звонка.
 */
class AudioCallManager(context: Context) {

    companion object {
        private const val TAG = "AudioCallManager"
    }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeaker: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost: $change")
                hasFocus = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                hasFocus = true
            }
        }
    }

    fun start() {
        savedMode = audioManager.mode
        savedSpeaker = audioManager.isSpeakerphoneOn
        requestFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(false)
        Log.d(TAG, "Audio session started")
    }

    fun stop() {
        setSpeakerphone(savedSpeaker)
        audioManager.mode = savedMode
        abandonFocus()
        Log.d(TAG, "Audio session stopped")
    }

    fun setSpeakerphone(on: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
        Log.d(TAG, "Speakerphone: $on")
    }

    fun isSpeakerphoneOn(): Boolean {
        @Suppress("DEPRECATION")
        return audioManager.isSpeakerphoneOn
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = req
            val result = audioManager.requestAudioFocus(req)
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        Log.d(TAG, "Focus requested, granted=$hasFocus")
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasFocus = false
    }
}
