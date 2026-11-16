// File: app/src/main/java/com/example/motointercom/AudioFocusHandler.java
package com.example.motointercom;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

public class AudioFocusHandler {
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private final AtomicBoolean focusHeld = new AtomicBoolean(false);

    public AudioFocusHandler(AudioManager audioManager) {
        this.audioManager = audioManager;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        this.audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .build();
    }

    public void request() {
        if (focusHeld.compareAndSet(false, true)) {
            new Thread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.requestAudioFocus(audioFocusRequest);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
            }).start();
        }
    }

    public void release() {
        if (focusHeld.compareAndSet(true, false)) {
            new Thread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            }).start();
        }
    }

    public boolean isHeld() {
        return focusHeld.get();
    }
}
