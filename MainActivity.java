// File: app/src/main/java/com/example/motointercom/MainActivity.java
package com.example.motointercom;

import android.Manifest;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.lang.ref.WeakReference;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 55555;
    private static final int SAMPLE_RATE = 16000;
    private static final int PACKET_SIZE = 640;
    private static final int VAD_RMS_THRESHOLD = 1500;
    private static final long FOCUS_RELEASE_DELAY_MS = 2000;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WiFiDirectBroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private TextView statusText;
    private TextView pauseText;
    private TextView signalText;
    private SeekBar voiceVolumeBar;

    private WifiManager wifiManager;
    private AudioManager audioManager;
    private AudioFocusHandler focusHandler;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;

    private AudioEngine audioEngine;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    startIntercom();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initSystemServices();
        setupUi();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            startIntercom();
        }
    }

    private void bindViews() {
        statusText = findViewById(R.id.statusText);
        pauseText = findViewById(R.id.pauseText);
        signalText = findViewById(R.id.signalText);
        voiceVolumeBar = findViewById(R.id.voiceVolumeBar);
    }

    private void initSystemServices() {
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        focusHandler = new AudioFocusHandler(audioManager);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void setupUi() {
        voiceVolumeBar.setMax(100);
        voiceVolumeBar.setProgress(100);
        voiceVolumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (audioEngine != null) audioEngine.setVolume(value / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void startIntercom() {
        statusText.setText(getString(R.string.status_starting));
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {}
            @Override public void onFailure(int reasonCode) {
                statusText.setText(getString(R.string.error_discovery));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, new WeakReference<>(this));
        registerReceiver(receiver, intentFilter);
        setupPhoneListener();
        startSignalUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
            receiver = null;
        }
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        stopSignalUpdates();
    }

    private void setupPhoneListener() {
        phoneStateListener = new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String incomingNumber) {
                if (audioEngine != null) {
                    if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        audioEngine.pause();
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                        audioEngine.resume();
                    }
                }
            }
        };
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    private void startSignalUpdates() {
        stopSignalUpdates();
        Runnable signalUpdater = new Runnable() {
            @Override public void run() {
                if (wifiManager != null) {
                    WifiInfo info = wifiManager.getConnectionInfo();
                    int rssi = info != null ? info.getRssi() : Integer.MIN_VALUE;
                    if (rssi < 0) {
                        int percent = WifiManager.calculateSignalLevel(rssi, 100);
                        signalText.setText(getString(R.string.status_signal, percent, rssi));
                    } else {
                        signalText.setText(getString(R.string.status_no_signal));
                    }
                }
                uiHandler.postDelayed(this, 1500);
            }
        };
        uiHandler.post(signalUpdater);
    }

    private void stopSignalUpdates() {
        uiHandler.removeCallbacksAndMessages(null);
    }

    public void updatePeers(List<WifiP2pDevice> peers) {
        // Реализация списка устройств (диалог выбора)
    }

    public void onConnectionEstablished(InetAddress address) {
        if (audioEngine != null) {
            audioEngine.stop();
        }
        audioEngine = new AudioEngine(this, address, PORT, SAMPLE_RATE, PACKET_SIZE, VAD_RMS_THRESHOLD, FOCUS_RELEASE_DELAY_MS, focusHandler);
        statusText.setText(getString(R.string.status_active));
        audioEngine.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSignalUpdates();
        if (audioEngine != null) {
            audioEngine.stop();
        }
        focusHandler.release();
    }
}
