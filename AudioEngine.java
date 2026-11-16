// File: app/src/main/java/com/example/motointercom/AudioEngine.java
package com.example.motointercom;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AudioEngine {

    private final Context context;
    private final InetAddress targetAddress;
    private final int port;
    private final int sampleRate;
    private final int packetSize;
    private final int vadThreshold;
    private final long focusReleaseDelay;
    private final AudioFocusHandler focusHandler;

    private AudioRecord recorder;
    private AudioTrack player;
    private DatagramSocket socket;
    private ExecutorService executor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isOnPhoneCall = new AtomicBoolean(false);
    private final AtomicReference<Long> lastVoiceTime = new AtomicReference<>(0L);
    private volatile float voiceVolume = 1.0f;

    public AudioEngine(Context context, InetAddress targetAddress, int port, int sampleRate, int packetSize, 
                       int vadThreshold, long focusReleaseDelay, AudioFocusHandler focusHandler) {
        this.context = context;
        this.targetAddress = targetAddress;
        this.port = port;
        this.sampleRate = sampleRate;
        this.packetSize = packetSize;
        this.vadThreshold = vadThreshold;
        this.focusReleaseDelay = focusReleaseDelay;
        this.focusHandler = focusHandler;
    }

    public void setVolume(float volume) {
        this.voiceVolume = volume;
        if (player != null) player.setVolume(volume);
    }

    public void start() {
        if (isRunning.get()) return;
        initAudio();
        startThreads();
    }

    public void pause() {
        isOnPhoneCall.set(true);
        stopThreads();
        new Handler(Looper.getMainLooper()).post(() -> {
            // UI обновляется через MainActivity
        });
    }

    public void resume() {
        if (isOnPhoneCall.compareAndSet(true, false)) {
            start();
        }
    }

    public void stop() {
        isOnPhoneCall.set(false);
        stopThreads();
        releaseAudio();
    }

    private void initAudio() {
        try {
            socket = new DatagramSocket(port);
            socket.setReceiveBufferSize(packetSize * 4);
        } catch (IOException e) {
            Log.e("AudioEngine", "Socket error", e);
        }

        int recBufSize = Math.max(AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), packetSize * 2);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBufSize);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat trackFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int trackBuf = Math.max(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), packetSize * 4);

        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(trackFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBuf);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        player = builder.build();
        player.setVolume(voiceVolume);
    }

    private void startThreads() {
        isRunning.set(true);
        executor = Executors.newFixedThreadPool(2);

        executor.execute(() -> {
            recorder.startRecording();
            short[] pcm = new short[packetSize / 2];
            byte[] packetBytes = new byte[packetSize];
            while (isRunning.get() && !isOnPhoneCall.get()) {
                if (targetAddress == null) {
                    focusHandler.release();
                    sleepQuiet(50);
                    continue;
                }
                int shorts = recorder.read(pcm, 0, pcm.length);
                if (shorts <= 0) continue;

                double sumSq = 0;
                for (int i = 0; i < shorts; i++) sumSq += (double) pcm[i] * pcm[i];
                double rms = Math.sqrt(sumSq / shorts);

                if (rms > vadThreshold) {
                    lastVoiceTime.set(System.currentTimeMillis());
                    focusHandler.request();
                } else if (focusHandler.isHeld() && System.currentTimeMillis() - lastVoiceTime.get() > focusReleaseDelay) {
                    focusHandler.release();
                }

                for (int i = 0; i < shorts; i++) {
                    packetBytes[2 * i] = (byte) (pcm[i] & 0xff);
                    packetBytes[2 * i + 1] = (byte) (pcm[i] >> 8);
                }
                try {
                    socket.send(new DatagramPacket(packetBytes, shorts * 2, targetAddress, port));
                } catch (IOException ignored) {}
            }
            focusHandler.release();
            try { recorder.stop(); } catch (IllegalStateException ignored) {}
        });

        executor.execute(() -> {
            byte[] buffer = new byte[packetSize];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            player.play();
            while (isRunning.get() && !isOnPhoneCall.get()) {
                try {
                    socket.receive(packet);
                    player.write(buffer, 0, packet.getLength(), AudioTrack.WRITE_BLOCKING);
                } catch (IOException ignored) {}
            }
            try { player.stop(); } catch (IllegalStateException ignored) {}
        });
    }

    private void stopThreads() {
        isRunning.set(false);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void releaseAudio() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
