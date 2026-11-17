package com.example.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val AUDIO_PORT = 45678
        const val SERVICE_TYPE = "_intercom._tcp."
        const val SERVICE_PREFIX = "intercom-"
        const val REQUEST_MIC = 1
        const val PREFS = "intercom_prefs"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_SENS = "sensitivity"
        const val KEY_DELAY = "musicDelay"
        const val KEY_THEME = "theme"   // 0 = светлая, 1 = тёмная
        const val KEY_RELAY = "relay"   // ретрансляция: true = включена
        const val MAX_HOPS = 3          // через сколько устройств звук может пройти
        const val HEADER = 9            // 4 байта id + 4 байта номер + 1 байт ttl
    }

    private lateinit var nsdManager: NsdManager
    private lateinit var audioManager: AudioManager

    private val myId = Random.nextInt()
    private var seq = 0

    private val peers = CopyOnWriteArraySet<InetAddress>()
    private val discovered = LinkedHashMap<String, NsdServiceInfo>()
    private val whitelist = HashSet<String>()
    private var myServiceName: String? = null

    private var socket: DatagramSocket? = null
    private var running = false
    private var micEnabled = true
    private var musicDelaySec = 2
    private var voiceThreshold = 1500.0
    private var themeMode = 0
    private var relayEnabled = true

    private val recentPackets = LinkedHashMap<Long, Boolean>()

    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var lastSpeechTime = 0L

    private var echoTrack: AudioTrack? = null
    private var scoActive = false

    private lateinit var mainScreen: LinearLayout
    private lateinit var settingsScreen: LinearLayout

    private lateinit var statusView: TextView
    private lateinit var devicesView: TextView
    private lateinit var micButton: Button
    private lateinit var foundListLayout: LinearLayout
    private var voiceVolumeBar: SeekBar? = null

    private var sensBar: SeekBar? = null
    private var sensLabel: TextView? = null
    private var delaySpinner: Spinner? = null
    private var themeSpinner: Spinner? = null
    private var relayCheck: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Интерком"
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        whitelist.addAll(
            (prefs.getString(KEY_WHITELIST, "") ?: "")
                .split("\n").filter { it.isNotBlank() })
        voiceThreshold = prefs.getInt(KEY_SENS, 1500).toDouble()
        musicDelaySec = prefs.getInt(KEY_DELAY, 2)
        themeMode = prefs.getInt(KEY_THEME, 0)
        relayEnabled = prefs.getBoolean(KEY_RELAY, true)

        buildUi()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAll()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAll()
        } else {
            setStatus("Нет доступа к микрофону", false)
        }
    }

    private fun startAll() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("intercom-lock")
        lock.setReferenceCounted(false)
        lock.acquire()

        enableBluetoothSco()

        running = true
        socket = try {
            DatagramSocket(AUDIO_PORT).apply { broadcast = true; reuseAddress = true }
        } catch (e: Exception) {
            null
        }
        if (socket == null) {
            setStatus("Порт занят. Перезапустите приложение", false)
            return
        }

        thread(name = "recv") { receiveLoop() }
        thread(name = "send") { sendLoop() }
        registerNsd()
        setStatus("Микрофон активен. Ищем выбранные устройства в Wi-Fi сети...", true)
    }

    @SuppressLint("MissingPermission")
    private fun enableBluetoothSco() {
        try {
            val bt = BluetoothAdapter.getDefaultAdapter()
            if (bt == null || !bt.isEnabled) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scoDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (scoDevice != null) {
                    audioManager.setCommunicationDevice(scoDevice)
                    scoActive = true
                }
            } else {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                scoActive = true
            }
        } catch (e: SecurityException) {
            // Нет разрешения на Bluetooth — работает обычный микрофон
        }
    }

    // ================= NSD: поиск и автоподключение к выбранным =================

    private fun registerNsd() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_PREFIX + Build.MODEL.replace(" ", "_")
            serviceType = SERVICE_TYPE
            port = AUDIO_PORT
        }

        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(n: NsdServiceInfo) {
                    myServiceName = n.serviceName
                }
                override fun onRegistrationFailed(n: NsdServiceInfo, e: Int) {
                    setStatus("Ошибка регистрации в сети: $e", false)
                }
                override fun onServiceUnregistered(n: NsdServiceInfo) {}
                override fun onUnregistrationFailed(n: NsdServiceInfo, e: Int) {}
            })

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {
                    setStatus("Поиск не запустился: $e", false)
                }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName == myServiceName) return
                    runOnUiThread { showDiscoveredDevice(info) }
                }
            })
    }

    private fun showDiscoveredDevice(info: NsdServiceInfo) {
        val name = info.serviceName
        discovered[name] = info
        if (whitelist.contains(name)) resolveAndConnect(info, name)
        rebuildFoundList()
    }

    private fun resolveAndConnect(info: NsdServiceInfo, name: String) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host ?: return
                if (peers.add(host)) {
                    setStatus("Подключено: $name. Говорите без кнопок!", true)
                    runOnUiThread { refreshActiveList() }
                }
            }
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
        })
    }

    private fun rebuildFoundList() {
        runOnUiThread {
            foundListLayout.removeAllViews()
            for ((name, info) in discovered) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val label = TextView(this).apply {
                    text = name.removePrefix(SERVICE_PREFIX) +
                            if (whitelist.contains(name)) "  ✔ выбрано" else ""
                    setTextColor(if (themeMode == 0) Color.BLACK else Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btn = Button(this).apply {
                    text = if (whitelist.contains(name)) "Убрать" else "Выбрать"
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (themeMode == 0) 0xFF28A745.toInt() else 0xFFFFC107.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                btn.setOnClickListener {
                    if (whitelist.contains(name)) whitelist.remove(name)
                    else { whitelist.add(name); resolveAndConnect(info, name) }
                    saveWhitelist()
                    rebuildFoundList()
                }
                row.addView(label)
                row.addView(btn)
                foundListLayout.addView(row)
            }
        }
    }

    private fun refreshActiveList() {
        devicesView.text = peers.joinToString("\n") { "• $it" }
    }

    private fun saveWhitelist() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WHITELIST, whitelist.joinToString("\n")).apply()
    }

    // ================= Пакеты единой сети =================

    private fun buildPacket(audio: ByteArray, audioLen: Int, srcId: Int, seqNum: Int, ttl: Int): ByteArray {
        val bb = ByteBuffer.allocate(audioLen + HEADER)
        bb.putInt(srcId)
        bb.putInt(seqNum)
        bb.put(ttl.toByte())
        bb.put(audio, 0, audioLen)
        return bb.array()
    }

    private fun isDuplicate(key: Long): Boolean {
        synchronized(recentPackets) {
            if (recentPackets.containsKey(key)) return true
            recentPackets[key] = true
            if (recentPackets.size > 512) {
                val it = recentPackets.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            return false
        }
    }

    // ================= Звук: приём и ретрансляция =================

    private fun pcmOutFormat(): AudioFormat =
        AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

    private fun pcmInFormat(): AudioFormat =
        AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

    private fun receiveLoop() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build(),
                pcmOutFormat(), maxOf(minBuf, SAMPLE_RATE * 2),
                AudioTrack.MODE_STREAM, 0)
            track.play()

            val buf = ByteArray(6400)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    if (packet.length <= HEADER) continue

                    val bb = ByteBuffer.wrap(buf, 0, packet.length)
                    val srcId = bb.int
                    val rseq = bb.int
                    val ttl = bb.get().toInt()

                    if (srcId == myId) continue

                    val key = (srcId.toLong() shl 32) or (rseq.toLong() and 0xFFFFFFFFL)
                    if (isDuplicate(key)) continue

                    val audioLen = packet.length - HEADER
                    track.write(buf, HEADER, audioLen)
                    echoTrack?.write(buf, HEADER, audioLen)

                    if (relayEnabled && ttl > 1) {
                        val fwd = buildPacket(buf, audioLen, srcId, rseq, ttl - 1)
                        for (p in peers) {
                            if (p == packet.address) continue
                            try {
                                socket?.send(DatagramPacket(fwd, fwd.size, p, AUDIO_PORT))
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) { break }
            }
            track.stop(); track.release()
        } catch (e: Exception) {
            setStatus("Ошибка звука: ${e.message}", false)
        }
    }

    // ================= Звук: передача =================

    @SuppressLint("MissingPermission")
    private fun sendLoop() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                pcmInFormat(), maxOf(minBuf, SAMPLE_RATE * 2))

            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)

            rec.startRecording()
            val buf = ShortArray(1600)

            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                }

                if (micEnabled && peers.isNotEmpty()) {
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    handleVoiceActivity(Math.sqrt(sum / n))

                    seq++
                    val out = buildPacket(bytes, bytes.size, myId, seq, MAX_HOPS)
                    for (p in peers) {
                        try {
                            socket?.send(DatagramPacket(out, out.size, p, AUDIO_PORT))
                        } catch (e: Exception) {}
                    }
                } else if (!micEnabled && focusHeld) {
                    abandonFocus()
                }
            }
            rec.stop(); rec.release()
        } catch (e: Exception) {
            setStatus("Ошибка микрофона: ${e.message}", false)
        }
    }

    // ================= Пауза и продолжение музыки =================

    private fun handleVoiceActivity(rms: Double) {
        val now = System.currentTimeMillis()
        if (rms > voiceThreshold) {
            if (!focusHeld) requestFocus()
            lastSpeechTime = now
        }
        if (focusHeld && now - lastSpeechTime > musicDelaySec * 1000L) {
            abandonFocus()
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focusRequest = req
        focusHeld = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) setStatus("Музыка на паузе (идёт разговор)", true)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusHeld = false
    }

    // ================= Интерфейс =================

    private fun applyBackground(view: View) {
        view.setBackgroundColor(if (themeMode == 0) 0xFFF8F9FA.toInt() else 0xFF1E1E1E.toInt())
        if (view is TextView) {
            view.setTextColor(if (themeMode == 0) Color.BLACK else Color.WHITE)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyBackground(view.getChildAt(i))
        }
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        statusView = TextView(this)
        devicesView = TextView(this)
        micButton = Button(this).apply { text = "Микрофон: ВКЛ" }
        foundListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---------- ГЛАВНЫЙ ЭКРАН ----------

        voiceVolumeBar = SeekBar(this).apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, vol: Int, fromUser: Boolean) {
                    if (fromUser) audioManager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL, vol, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        micButton.setOnClickListener {
            micEnabled = !micEnabled
            micButton.text = if (micEnabled) "Микрофон: ВКЛ" else "Микрофон: ВЫКЛ"
            if (!micEnabled && focusHeld) abandonFocus()
        }

        val testButton = Button(this).apply { text = "Проверка связи (эхо 3 сек)" }
        testButton.setOnClickListener {
            if (!running) return@setOnClickListener
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
                pcmOutFormat(), maxOf(minBuf, SAMPLE_RATE * 2), AudioTrack.MODE_STREAM, 0)
            echoTrack = track
            thread {
                track.play()
                Thread.sleep(3000)
                track.stop(); track.release()
                echoTrack = null
            }
            Toast.makeText(this, "Говорите — услышите себя 3 секунды", Toast.LENGTH_SHORT).show()
        }

        val settingsButton = Button(this).apply { text = "Настройки" }
        settingsButton.setOnClickListener {
            mainScreen.visibility = View.GONE
            settingsScreen.visibility = View.VISIBLE
        }

        mainScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply {
                text = "Говорите без кнопок. Все выбранные устройства образуют единую сеть."
                textSize = 16f
            })
            addView(TextView(this@MainActivity).apply {
                text = "Громкость разговора:"
                setPadding(0, pad, 0, 0)
            })
            addView(voiceVolumeBar)
            addView(micButton)
            addView(testButton)
            addView(settingsButton)
            addView(statusView)
            addView(TextView(this@MainActivity).apply {
                text = "Найденные устройства (нажми «Выбрать» для автоподключения):"
                setPadding(0, pad, 0, pad / 2)
            })
            addView(foundListLayout)
            addView(TextView(this@MainActivity).apply {
                text = "Активные соединения:"
                setPadding(0, pad, 0, pad / 2)
            })
            addView(devicesView)
        }

        // ---------- ЭКРАН НАСТРОЕК ----------

        delaySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Пауза музыки: 0 сек", "Пауза музыки: 1 сек", "Пауза музыки: 2 сек",
                        "Пауза музыки: 3 сек", "Пауза музыки: 4 сек", "Пауза музыки: 5 сек"))
            setSelection(musicDelaySec)
        }
        delaySpinner!!.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                musicDelaySec = pos
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_DELAY, pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        sensBar = SeekBar(this).apply {
            max = 100
            progress = ((voiceThreshold - 500) / 45).toInt().coerceIn(0, 100)
        }
        sensLabel = TextView(this).apply {
            text = "Чувствительность микрофона: ${sensBar!!.progress}%"
        }
        sensBar!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, prog: Int, fromUser: Boolean) {
                if (fromUser) {
                    voiceThreshold = 500.0 + prog * 45.0
                    sensLabel?.text = "Чувствительность микрофона: $prog%"
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_SENS, voiceThreshold.toInt()).apply()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        relayCheck = CheckBox(this).apply {
            text = "Ретрансляция звука (единая сеть): звук передаётся и через промежуточные устройства"
            isChecked = relayEnabled
            setOnCheckedChangeListener { _, checked ->
                relayEnabled = checked
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RELAY, checked).apply()
            }
        }

        themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Оформление: светлое", "Оформление: тёмное"))
            setSelection(themeMode)
        }
        themeSpinner!!.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != themeMode) {
                    themeMode = pos
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_THEME, pos).apply()
                    recreate()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val backButton = Button(this).apply { text = "Назад" }
        backButton.setOnClickListener {
            settingsScreen.visibility = View.GONE
            mainScreen.visibility = View.VISIBLE
        }

        settingsScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "Настройки"
                textSize = 20f
            })
            addView(TextView(this@MainActivity).apply {
                text = "Задержка паузы музыки (сколько секунд тишины ждать, прежде чем музыка продолжится):"
                setPadding(0, pad, 0, 0)
            })
            addView(delaySpinner)
            addView(TextView(this@MainActivity).apply {
                text = "Чувствительность микрофона (влево — реагирует на тихую речь, вправо — только на громкий голос):"
                setPadding(0, pad, 0, 0)
            })
            addView(sensLabel)
            addView(sensBar)
            addView(relayCheck)
            addView(themeSpinner)
            addView(TextView(this@MainActivity).apply {
                text = "Все настройки сохраняются автоматически."
                setPadding(0, pad, 0, 0)
            })
            addView(backButton)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mainScreen)
            addView(settingsScreen)
        }
        applyBackground(root)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun setStatus(text: String, ok: Boolean) {
        runOnUiThread {
            statusView.text = "Статус: $text"
            statusView.setTextColor(if (ok) 0xFF28A745.toInt() else 0xFFDC3545.toInt())
        }
    }

    override fun onBackPressed() {
        if (settingsScreen.visibility == View.VISIBLE) {
            settingsScreen.visibility = View.GONE
            mainScreen.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        if (scoActive && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            } catch (e: Exception) {}
        }
        try { socket?.close() } catch (e: Exception) {}
        try { echoTrack?.stop(); echoTrack?.release() } catch (e: Exception) {}
        abandonFocus()
    }
}
