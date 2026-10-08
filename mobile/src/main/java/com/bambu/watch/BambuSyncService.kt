package com.bambu.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class BambuSyncService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs get() = getSharedPreferences("bambu", Context.MODE_PRIVATE)
    private var lastThumbUrl = ""
    private var mqttClient: BambuMqttClient? = null

    // MQTT valós idejű adatok
    @Volatile private var nozzleTemp    = 0
    @Volatile private var nozzleTarget  = 0
    @Volatile private var bedTemp       = 0
    @Volatile private var bedTarget     = 0
    @Volatile private var mqttProgress  = -1   // -1 = még nincs MQTT adat
    @Volatile private var mqttRemaining = 0
    @Volatile private var mqttState     = ""
    @Volatile private var mqttFilename  = ""

    private val poller = object : Runnable {
        override fun run() {
            Thread { poll() }.start()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(this, 10, buildNotif(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        mqttClient?.disconnect()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun poll() {
        val token = prefs.getString("token", null) ?: return
        val status = BambuApi.getPrintStatus(token) ?: return

        // MQTT csatlakozás
        if (mqttClient == null || mqttClient?.isConnected() == false) {
            mqttClient?.disconnect()
            val uid = BambuApi.getUid(token)
            mqttClient = BambuMqttClient(
                devId = status.deviceId,
                token = token,
                uid   = uid,
                onData = { n, nt, b, bt, prog, rem, st, fn ->
                    nozzleTemp    = n
                    nozzleTarget  = nt
                    bedTemp       = b
                    bedTarget     = bt
                    if (prog > 0)      mqttProgress  = prog
                    if (rem > 0)       mqttRemaining = rem
                    if (st.isNotEmpty()) mqttState    = st
                    if (fn.isNotEmpty()) mqttFilename = fn
                }
            )
            Thread { mqttClient?.connect() }.start()
        }

        // MQTT adatok előnyben részesítése REST-tel szemben
        val finalProgress  = if (mqttProgress >= 0) mqttProgress else status.progress
        val finalRemaining = if (mqttRemaining > 0) mqttRemaining else status.remainingMin
        val finalState     = if (mqttState.isNotEmpty()) mqttState else status.state
        val finalFilename  = if (mqttFilename.isNotEmpty()) mqttFilename else status.filename

        val completionTime = if (finalRemaining > 0) {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.MINUTE, finalRemaining)
            String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        } else ""

        prefs.edit().putString("last_debug",
            "state=$finalState progress=$finalProgress%\n" +
            "mqtt=${mqttClient?.isConnected()} N:${nozzleTemp}° B:${bedTemp}°\n" +
            "thumb=${status.thumbnailUrl.take(30)}\n${prefs.getString("thumb_debug","—")}").apply()

        sendToWatch(status, finalProgress, finalRemaining, completionTime, finalState, finalFilename)
        updateNotif(finalState, finalProgress, finalFilename)
    }

    private fun sendToWatch(
        s: BambuApi.PrintStatus,
        progress: Int, remaining: Int, completionTime: String,
        state: String, filename: String
    ) {
        val req = PutDataMapRequest.create("/bambu/status").apply {
            dataMap.putString("filename",       filename)
            dataMap.putInt("progress",          progress)
            dataMap.putInt("remaining",         remaining)
            dataMap.putString("completionTime", completionTime)
            dataMap.putString("state",          state)
            dataMap.putString("device",         s.deviceName)
            dataMap.putBoolean("online",        s.online)
            dataMap.putInt("nozzleTemp",        nozzleTemp)
            dataMap.putInt("nozzleTarget",      nozzleTarget)
            dataMap.putInt("bedTemp",           bedTemp)
            dataMap.putInt("bedTarget",         bedTarget)
            dataMap.putLong("ts",               System.currentTimeMillis())

            if (s.thumbnailUrl.isNotEmpty() && s.thumbnailUrl != lastThumbUrl) {
                val token = prefs.getString("token", "") ?: ""
                val bytes = BambuApi.downloadThumbnail(s.thumbnailUrl, token)
                val dlResult = if (bytes != null) "OK(${bytes.size}b)" else "FAIL"
                prefs.edit().putString("thumb_debug", "dl=$dlResult url=${s.thumbnailUrl.take(30)}").apply()
                if (bytes != null) {
                    dataMap.putAsset("thumbnail", Asset.createFromBytes(bytes))
                    lastThumbUrl = s.thumbnailUrl
                }
            }
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(this).putDataItem(req)
    }

    private fun updateNotif(state: String, progress: Int, filename: String) {
        val text = when (state) {
            "RUNNING" -> "$progress% — ${filename.take(20)} | N:${nozzleTemp}° B:${bedTemp}°"
            "FINISH"  -> "Nyomtatás kész!"
            "FAILED"  -> "Hiba a nyomtatásban"
            else      -> "Tétlen | N:${nozzleTemp}° B:${bedTemp}°"
        }
        getSystemService(NotificationManager::class.java).notify(10, buildNotif(text))
    }

    private fun buildNotif(text: String = "Szinkronizál...") =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Bambu Watch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).build()

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Bambu Sync", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object { private const val CHANNEL = "bambu_sync" }
}
