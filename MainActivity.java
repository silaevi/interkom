package com.example.autointercom;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private WifiP2pManager manager;
    private Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private TextView statusText;
    private TextView pauseText;

    // Àóäèî
    private AudioRecord recorder;
    private AudioTrack player;
    private DatagramSocket socket;
    private ExecutorService executor;
    private InetAddress targetAddress;

    private final int PORT = 55555;
    private final int SAMPLE_RATE = 8000;
    private volatile boolean isRunning = true;
    private final AtomicBoolean isOnPhoneCall = new AtomicBoolean(false); // Ôëàã òåëåôîííîãî çâîíêà

    // Ìåíåäæåð çâóêà äëÿ ducking'à (ïðèãëóøåíèÿ ìóçûêè)
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    // Ñëóøàòåëü ñîñòîÿíèÿ òåëåôîíà
    private PhoneStateListener phoneStateListener;
    private TelephonyManager telephonyManager;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Äîñòóï ê ìèêðîôîíó îáÿçàòåëåí", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        statusText = findViewById(R.id.statusText);
        pauseText = findViewById(R.id.pauseText);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        // Èíèöèàëèçàöèÿ AudioManager
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupAudioFocus();

        // Èíèöèàëèçàöèÿ ñëóøàòåëÿ çâîíêîâ
        setupPhoneListener();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            startService();
        }
    }

    // Íàñòðîéêà: ïðîñèì ñèñòåìó ïðèãëóøàòü ìóçûêó, êîãäà ìû èñïîëüçóåì ìèêðîôîí
    private void setupAudioFocus() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_DUCK) {
                        // Ìóçûêà ñòàëà òèøå (ýòî è åñòü ducking)
                    }
                })
                .build();
    }

    // Ñëóøàåì ñîòîâóþ ñåòü
    private void setupPhoneListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    // ÂÕÎÄßÙÈÉ ÇÂÎÍÎÊ èëè ÐÀÇÃÎÂÎÐ
                    pauseIntercom();
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    // ÒÅËÅÔÎÍ ÑÂÎÁÎÄÅÍ
                    resumeIntercom();
                }
            }
        };
    }

    private void startService() {
        statusText.setText("Çàïóñê Wi-Fi Direct...");
        registerReceiver(receiver, intentFilter);
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {}
            @Override public void onFailure(int reasonCode) {}
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        registerReceiver(receiver, intentFilter);
        
        // Íà÷èíàåì ñëóøàòü çâîíêè
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        abandonAudioFocus();
    }

    // --- Ëîãèêà ÏÀÓÇÛ ---
    private void pauseIntercom() {
        isOnPhoneCall.set(true);
        runOnUiThread(() -> {
            statusText.setText("ÍÀ ÏÀÓÇÅ");
            pauseText.setVisibility(android.view.View.VISIBLE);
        });
        stopAudioThreads();
        abandonAudioFocus();
    }

    private void resumeIntercom() {
        if (isOnPhoneCall.compareAndSet(true, false)) {
            runOnUiThread(() -> {
                statusText.setText("Èíòåðêîì àêòèâåí");
                pauseText.setVisibility(android.view.View.GONE);
            });
            requestAudioFocus();
            if (targetAddress != null) {
                initAudio();
                startFullDuplex();
            }
        }
    }

    // --- Ëîãèêà Ducking'à (ïðèãëóøåíèÿ ìóçûêè) ---
    private void requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private void abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    // --- Îñòàëüíîé êîä (áåç èçìåíåíèé, íî ñ âûçîâîì focus) ---
    public void updatePeers(List<WifiP2pDevice> peers) { /* ... áåç èçìåíåíèé ... */ }
    public void onConnectionEstablished(InetAddress address) { 
        this.targetAddress = address; 
        statusText.setText("Èíòåðêîì àêòèâåí");
        requestAudioFocus(); // Çàïðàøèâàåì ôîêóñ ïðè ïîäêëþ÷åíèè
        initAudio();
        startFullDuplex();
    }

    private void initAudio() { /* ... áåç èçìåíåíèé ... */ }

    private void startFullDuplex() {
        executor = Executors.newFixedThreadPool(2);
        
        // Ïîòîê ÎÒÏÐÀÂÊÈ
        executor.execute(() -> {
            recorder.startRecording();
            byte[] buffer = new byte[1024];
            while (isRunning && targetAddress != null && !isOnPhoneCall.get()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, read, targetAddress, PORT);
                        socket.send(packet);
                    } catch (IOException ignored) {}
                }
            }
            recorder.stop();
        });

        // Ïîòîê ÏÐÈÅÌÀ
        executor.execute(() -> {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (isRunning && !isOnPhoneCall.get()) {
                try {
                    socket.receive(packet);
                    player.write(buffer, 0, packet.getLength());
                } catch (IOException ignored) {}
            }
        });
    }

    private void stopAudioThreads() {
        isRunning = false;
        try { Thread.sleep(100); } catch (InterruptedException ignored) {} // Äàåì ïîòîêàì óïàñòü
        isRunning = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        abandonAudioFocus();
        if (socket != null) socket.close();
        if (recorder != null) recorder.release();
        if (player != null) player.release();
        if (executor != null) executor.shutdownNow();
    }
}
