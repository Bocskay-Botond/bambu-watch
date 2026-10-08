package com.bambu.watch

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import javax.net.ssl.SSLSocketFactory

class BambuMqttClient(
    private val devId: String,
    private val token: String,
    private val uid: String,
    private val onData: (nozzle: Int, nozzleTarget: Int, bed: Int, bedTarget: Int,
                         progress: Int, remainingMin: Int, state: String, filename: String) -> Unit
) {
    private var client: MqttClient? = null

    fun connect() {
        try {
            client = MqttClient("ssl://us.mqtt.bambulab.com:8883",
                MqttClient.generateClientId(), MemoryPersistence())

            val opts = MqttConnectOptions().apply {
                userName          = if (uid.startsWith("u_")) uid else "u_$uid"
                password          = token.toCharArray()
                isCleanSession    = true
                connectionTimeout = 15
                keepAliveInterval = 60
                // A broker publikusan megbízható (DigiCert) tanúsítványt ad:
                // a rendszer trust store-ja ellenőrzi, a hosztnevet is.
                socketFactory     = SSLSocketFactory.getDefault()
                isHttpsHostnameVerificationEnabled = true
            }

            client?.setCallback(object : MqttCallback {
                override fun connectionLost(e: Throwable?) { Thread { Thread.sleep(5000); connect() }.start() }
                override fun deliveryComplete(t: IMqttDeliveryToken?) {}
                override fun messageArrived(topic: String, msg: MqttMessage) {
                    parseMessage(msg.toString())
                }
            })

            client?.connect(opts)
            client?.subscribe("device/$devId/report", 0)

            // Kérjük az összes aktuális adatot
            val pushall = JSONObject().apply {
                put("pushing", JSONObject().apply {
                    put("sequence_id", "0")
                    put("command", "pushall")
                })
            }.toString()
            client?.publish("device/$devId/request", MqttMessage(pushall.toByteArray()))

        } catch (_: Exception) {}
    }

    fun disconnect() {
        try { client?.disconnect(); client?.close() } catch (_: Exception) {}
    }

    fun isConnected() = client?.isConnected == true

    private fun parseMessage(raw: String) {
        try {
            val print = JSONObject(raw).optJSONObject("print") ?: return

            val nozzle       = print.optInt("nozzle_temper", -1)
            val nozzleTarget = print.optInt("nozzle_target_temper", 0)
            val bed          = print.optInt("bed_temper", 0)
            val bedTarget    = print.optInt("bed_target_temper", 0)

            // Progress: mc_percent lehet string vagy int
            val progressRaw  = print.opt("mc_percent")
            val progress     = when (progressRaw) {
                is Int    -> progressRaw
                is String -> progressRaw.toIntOrNull() ?: 0
                else      -> 0
            }

            val remainingMin = print.optInt("mc_remaining_time", 0)
            val filename     = print.optString("gcode_file", "")
            val gcodeState   = print.optString("gcode_state", "")

            val state = when (gcodeState.uppercase()) {
                "RUNNING", "PRINTING" -> "RUNNING"
                "FINISH", "DONE"      -> "FINISH"
                "FAILED", "FAILED_AMS"-> "FAILED"
                "PAUSE"               -> "PAUSED"
                else                  -> ""
            }

            if (nozzle >= 0) {
                onData(nozzle, nozzleTarget, bed, bedTarget, progress, remainingMin, state, filename)
            }
        } catch (_: Exception) {}
    }
}
