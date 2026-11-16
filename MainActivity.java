private static final int PORT = 55555;
private static final int SAMPLE_RATE = 16000;    // было 8000 — теперь звук заметно чище
private static final int PACKET_SIZE = 640;      // 20 мс звука: 16000 Гц * 2 байта * 0,02 с
private static final int VAD_RMS_THRESHOLD = 1500;         // порог «говорит / молчит» — подбирается на месте
private static final long FOCUS_RELEASE_DELAY_MS = 2000;   // тишина, после которой музыку отпускаем

private WifiP2pManager manager;
private WifiP2pManager.Channel channel;
private BroadcastReceiver receiver;
private IntentFilter intentFilter;

private TextView statusText;
private TextView pauseText;
private TextView signalText;
private SeekBar voiceVolumeBar;

private WifiManager wifiManager;
private AudioManager audioManager;
private AudioFocusRequest audioFocusRequest;
private TelephonyManager telephonyManager;
private PhoneStateListener phoneStateListener;

private AudioRecord recorder;
private AudioTrack player;
private DatagramSocket socket;
private ExecutorService executor;

private final AtomicReference<InetAddress> targetAddress = new AtomicReference<>(null);
private final AtomicBoolean isRunning = new AtomicBoolean(false);
private final AtomicBoolean isOnPhoneCall = new AtomicBoolean(false);
private final AtomicBoolean focusHeld = new AtomicBoolean(false);
private volatile float voiceVolume = 1.0f;
private volatile long lastVoiceTime = 0;

private final Handler uiHandler = new Handler(Looper.getMainLooper());
private Runnable signalUpdater;

private final ActivityResultLauncher<String> requestPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (!isGranted) {
                Toast.makeText(this, "Доступ к микрофону обязателен", Toast.LENGTH_LONG).show();
                finish();
            }
        });

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    statusText = findViewById(R.id.statusText);
    pauseText = findViewById(R.id.pauseText);
    signalText = findViewById(R.id.signalText);
    voiceVolumeBar = findViewById(R.id.voiceVolumeBar);

    manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
    channel = manager.initialize(this, getMainLooper(), null);

    intentFilter = new IntentFilter();
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

    wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    setupAudioFocus();
    setupPhoneListener();

    // Слайдер громкости голоса (не влияет на музыку)
    voiceVolumeBar.setMax(100);
    voiceVolumeBar.setProgress(100);
    voiceVolumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            voiceVolume = value / 100f;
            if (player != null) player.setVolume(voiceVolume);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    });

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    } else {
        startIntercom();
    }
}

private void startIntercom() {
    statusText.setText("Запуск Wi-Fi Direct...");
    manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
        @Override public void onSuccess() {}
        @Override public void onFailure(int reasonCode) {
            statusText.setText("Не удалось начать поиск устройств");
        }
    });
}

// Фокус запрашиваем только на время разговора — плееры ставят музыку на паузу
private void setupAudioFocus() {
    AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true) // просим паузу, а не приглушение
            .build();
}

private void requestMusicPause() {
    if (focusHeld.compareAndSet(false, true)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }
}

private void releaseMusicPause() {
    if (focusHeld.compareAndSet(true, false)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }
}

private void setupPhoneListener() {
    telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
    phoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                pauseIntercom();
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                resumeIntercom();
            }
        }
    };
}

@Override
protected void onResume() {
    super.onResume();
    receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
    registerReceiver(receiver, intentFilter); // регистрируем только здесь — раньше было дважды
    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    startSignalUpdates();
}

@Override
protected void onPause() {
    super.onPause();
    if (receiver != null) {
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        receiver = null;
    }
    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
    stopSignalUpdates();
}

// --- Индикатор сигнала ---
private void startSignalUpdates() {
    stopSignalUpdates();
    signalUpdater = new Runnable() {
        @Override
        public void run() {
            if (wifiManager != null) {
                WifiInfo info = wifiManager.getConnectionInfo();
                int rssi = info != null ? info.getRssi() : Integer.MIN_VALUE;
                if (rssi < 0) {
                    int percent = WifiManager.calculateSignalLevel(rssi, 100);
                    signalText.setText("Сигнал: " + percent + "% (" + rssi + " dBm)");
                } else {
                    signalText.setText("Сигнал: нет данных");
                }
            }
            uiHandler.postDelayed(this, 1500);
        }
    };
    uiHandler.post(signalUpdater);
}

private void stopSignalUpdates() {
    if (signalUpdater != null) {
        uiHandler.removeCallbacks(signalUpdater);
        signalUpdater = null;
    }
}

// --- Пауза / возобновление ---
private void pauseIntercom() {
    isOnPhoneCall.set(true);
    releaseMusicPause();
    runOnUiThread(() -> {
        statusText.setText("НА ПАУЗЕ");
        pauseText.setVisibility(android.view.View.VISIBLE);
    });
    stopAudioThreads();
}

private void resumeIntercom() {
    if (isOnPhoneCall.compareAndSet(true, false)) {
        runOnUiThread(() -> {
            statusText.setText("Интерком активен");
            pauseText.setVisibility(android.view.View.GONE);
        });
        if (targetAddress.get() != null && executor == null) {
            startAudioThreads();
        }
    }
}

// --- Подключение ---
public void updatePeers(List<WifiP2pDevice> peers) { /* без изменений */ }

public void onConnectionEstablished(InetAddress address) {
    targetAddress.set(address);
    statusText.setText("Интерком активен");
    if (executor == null || executor.isShutdown()) {
        initAudio();
        startAudioThreads();
    }
}

private void initAudio() {
    try {
        socket = new DatagramSocket();
        socket.setSoTimeout(200); // поток приёма не зависает и успевает проверять флаги
        socket.setReceiveBufferSize(PACKET_SIZE * 4);
    } catch (Exception e) {
        statusText.setText("Ошибка сокета: " + e.getMessage());
        return;
    }

    int recBufSize = Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), PACKET_SIZE * 2);
    recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBufSize);

    AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    AudioFormat trackFormat = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build();
    int trackBuf = Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), PACKET_SIZE * 4);

    AudioTrack.Builder builder = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(trackFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(trackBuf);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
    }
    player = builder.build();
    player.setVolume(voiceVolume);
}

private void startAudioThreads() {
    isRunning.set(true);
    executor = Executors.newFixedThreadPool(2);

    // Поток ОТПРАВКИ: микрофон -> сеть + детекция речи для паузы музыки
    executor.execute(() -> {
        recorder.startRecording();
        short[] pcm = new short[PACKET_SIZE / 2];
        byte[] packetBytes = new byte[PACKET_SIZE];
        while (isRunning.get() && !isOnPhoneCall.get()) {
            InetAddress addr = targetAddress.get();
            if (addr == null) {
                releaseMusicPause();
                sleepQuiet(50);
                continue;
            }
            int shorts = recorder.read(pcm, 0, pcm.length);
            if (shorts <= 0) continue;

            // Простой VAD: считаем среднюю громкость (RMS)
            double sumSq = 0;
            for (int i = 0; i < shorts; i++) sumSq += (double) pcm[i] * pcm[i];
            double rms = Math.sqrt(sumSq / shorts);

            if (rms > VAD_RMS_THRESHOLD) {
                lastVoiceTime = System.currentTimeMillis();
                requestMusicPause(); // кто-то говорит — музыка на паузу
            } else if (focusHeld.get()
                    && System.currentTimeMillis() - lastVoiceTime > FOCUS_RELEASE_DELAY_MS) {
                releaseMusicPause(); // тишина — музыку отпускаем
            }

            for (int i = 0; i < shorts; i++) {
                packetBytes[2 * i] = (byte) (pcm[i] & 0xff);
                packetBytes[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            try {
                socket.send(new DatagramPacket(packetBytes, shorts * 2, addr, PORT));
            } catch (IOException ignored) {}
        }
        releaseMusicPause();
        try { recorder.stop(); } catch (IllegalStateException ignored) {}
    });

    // Поток ПРИЁМА: сеть -> динамик
    executor.execute(() -> {
        byte[] buffer = new byte[PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        player.play();
        while (isRunning.get() && !isOnPhoneCall.get()) {
            try {
                socket.receive(packet);
                player.write(buffer, 0, packet.getLength(), AudioTrack.WRITE_BLOCKING);
            } catch (IOException ignored) {}
        }
    });
}

private void stopAudioThreads() {
    isRunning.set(false);
    if (executor != null) {
        executor.shutdownNow();
        executor = null;
    }
    // recorder/player/socket не уничтожаем: при возобновлении просто перезапускаем потоки
}

@Override
protected void onDestroy() {
    super.onDestroy();
    isRunning.set(false);
    stopSignalUpdates();
    releaseMusicPause();
    if (executor != null) executor.shutdownNow();
    if (socket != null) socket.close();
    if (recorder != null) recorder.release();
    if (player != null) player.release();
}

private void sleepQuiet(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
}
