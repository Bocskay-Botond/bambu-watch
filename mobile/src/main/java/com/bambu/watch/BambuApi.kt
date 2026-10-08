package com.bambu.watch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object BambuApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://api.bambulab.com"
    private val JSON_MT = "application/json".toMediaType()

    var lastError = ""

    data class PrintStatus(
        val deviceId: String,
        val deviceName: String,
        val online: Boolean,
        val filename: String,
        val progress: Int,
        val remainingMin: Int,
        val completionTime: String,
        val state: String,
        val nozzleTemp: Int,
        val nozzleTarget: Int,
        val bedTemp: Int,
        val bedTarget: Int,
        val thumbnailUrl: String
    )

    fun sendVerifyCode(email: String): Boolean {
        val body = JSONObject().apply {
            put("email", email); put("type", "codeLogin")
        }.toString().toRequestBody(JSON_MT)
        return try {
            val req = Request.Builder()
                .url("$BASE/v1/user-service/user/sendemail/code")
                .post(body).header("User-Agent", "bambu_network_agent").build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    fun loginWithCode(email: String, code: String): String? {
        val body = JSONObject().apply {
            put("account", email); put("code", code.trim()); put("apiError", "")
        }.toString().toRequestBody(JSON_MT)
        return try {
            val req = Request.Builder()
                .url("$BASE/v1/user-service/user/login")
                .post(body).header("User-Agent", "bambu_network_agent").build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                lastError = raw
                val token = JSONObject(raw).optString("accessToken", "")
                if (token.isNotEmpty() && token != "null") token else null
            }
        } catch (e: Exception) { lastError = e.message ?: ""; null }
    }

    fun login(email: String, password: String): String? {
        val body = JSONObject().apply {
            put("account", email); put("password", password); put("apiError", "")
        }.toString().toRequestBody(JSON_MT)
        return try {
            val req = Request.Builder()
                .url("$BASE/v1/user-service/user/login")
                .post(body)
                .header("User-Agent", "bambu_network_agent")
                .header("App-Lang", "en")
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                lastError = raw
                if (!resp.isSuccessful) return null
                val json = JSONObject(raw)
                if (json.optBoolean("success"))
                    json.optString("accessToken").takeIf { it.isNotEmpty() }
                else { lastError = json.optString("message", raw); null }
            }
        } catch (e: Exception) { lastError = e.message ?: ""; null }
    }

    fun getUid(token: String): String {
        // Próbáljuk API-ból
        val json = get("$BASE/v1/design-user-service/my/preference",
            mapOf("Authorization" to "Bearer $token"))
        val uid = json?.optString("uid", "")
        if (!uid.isNullOrEmpty() && uid != "null") return uid

        // Fallback: JWT payload decode
        return try {
            val payload = token.split(".")[1]
            val padded  = payload.padEnd(payload.length + (4 - payload.length % 4) % 4, '=')
            val decoded = android.util.Base64.decode(padded, android.util.Base64.URL_SAFE)
            val j = JSONObject(String(decoded))
            j.optString("uid", j.optString("u", j.optString("sub", "0"))).removePrefix("u_")
        } catch (_: Exception) { "0" }
    }

    fun getPrintStatus(token: String): PrintStatus? {
        val headers = mapOf("Authorization" to "Bearer $token")

        // Device lista
        val devJson = get("$BASE/v1/iot-service/api/user/bind", headers) ?: return null
        val devices = devJson.optJSONArray("devices") ?: return null
        if (devices.length() == 0) return null
        val device  = devices.getJSONObject(0)
        val devId   = device.optString("dev_id")
        val devName = device.optString("dev_name", device.optString("name", "A1"))
        val online  = device.optBoolean("dev_online", device.optBoolean("online"))

        // Aktív nyomtatás státusza a device-ból
        val devPrintStatus = device.optString("print_status", "").lowercase()
        val isActiveFromDevice = devPrintStatus.contains("running") ||
                                 devPrintStatus.contains("printing") ||
                                 devPrintStatus == "active"

        // Tasks az utolsó/aktív feladathoz
        val tasksRaw = getRaw("$BASE/v1/user-service/my/tasks?deviceId=$devId&after=0&limit=20", headers)
        val taskList = tasksRaw?.let { try { JSONObject(it).optJSONArray("hits") } catch (_: Exception) { null } }

        // Legjobb task: status=1 (running), ha nincs akkor az első
        var taskItem: JSONObject? = null
        if (taskList != null) {
            for (i in 0 until taskList.length()) {
                val t = taskList.getJSONObject(i)
                if (t.optInt("status", -1) == 1) { taskItem = t; break }
            }
            if (taskItem == null && taskList.length() > 0) taskItem = taskList.getJSONObject(0)
        }

        val taskStatus   = taskItem?.optInt("status", -1) ?: -1

        // Státusz: device lista alapján pontosabb
        val state = when {
            isActiveFromDevice                             -> "RUNNING"
            taskStatus == 4 && !isActiveFromDevice         -> "FINISH"
            taskStatus == 5                                -> "FAILED"
            taskStatus == 2                                -> "PAUSED"
            else                                           -> "IDLE"
        }

        // Ha aktív nyomtatás de a task már befejezett → keressük az aktuális print endpointon
        val activeTask = if (isActiveFromDevice) {
            val printRaw = getRaw("$BASE/v1/iot-service/api/user/print?force=true", headers)
            printRaw?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        } else null

        val filename     = activeTask?.optString("task_name", taskItem?.optString("title", "") ?: "") ?: taskItem?.optString("title", "") ?: ""
        val progress     = activeTask?.optInt("progress", taskItem?.optInt("progress", 0) ?: 0) ?: taskItem?.optInt("progress", 0) ?: 0
        val remainingMin = if (activeTask != null) (activeTask.optInt("prediction", 0) / 60) else (taskItem?.optInt("remainingTime", 0) ?: 0)
        val thumbUrl     = activeTask?.optString("thumbnail", taskItem?.optString("cover", "") ?: "") ?: taskItem?.optString("cover", "") ?: ""

        lastError = "devStatus=$devPrintStatus taskStatus=$taskStatus active=$isActiveFromDevice progress=$progress"

        val completionTime = if (remainingMin > 0) {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.MINUTE, remainingMin)
            String.format(java.util.Locale.getDefault(), "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        } else ""

        return PrintStatus(
            deviceId      = devId,
            deviceName    = devName,
            online        = online,
            filename      = filename,
            progress      = progress,
            remainingMin  = remainingMin,
            completionTime = completionTime,
            state         = state,
            nozzleTemp    = 0,
            nozzleTarget  = 0,
            bedTemp       = 0,
            bedTarget     = 0,
            thumbnailUrl  = thumbUrl
        )
    }

    fun downloadThumbnail(url: String, token: String = ""): ByteArray? {
        if (url.isEmpty()) return null
        return try {
            // S3 URL-ekhez nem kell Bearer auth — próbáljuk anélkül
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.body?.bytes()
            } else if (resp.code == 403 && token.isNotEmpty()) {
                // Ha 403, próbáljuk tokennel (Bambu CDN)
                resp.close()
                val req2 = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").build()
                client.newCall(req2).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                }
            } else null
        } catch (_: Exception) { null }
    }

    private fun get(url: String, headers: Map<String, String>): JSONObject? {
        val raw = getRaw(url, headers) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun getRaw(url: String, headers: Map<String, String>): String? {
        val req = Request.Builder().url(url).get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return try { client.newCall(req).execute().use { it.body?.string() } } catch (_: Exception) { null }
    }
}
