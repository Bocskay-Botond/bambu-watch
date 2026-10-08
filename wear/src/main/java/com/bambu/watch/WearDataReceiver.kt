package com.bambu.watch

import android.content.Context
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.*
import java.io.File
import java.io.FileOutputStream

class WearDataReceiver : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.dataItem.uri.path != "/bambu/status") continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val prefs = getSharedPreferences("bambu", Context.MODE_PRIVATE)

            prefs.edit()
                .putString("filename",       map.getString("filename", ""))
                .putInt("progress",          map.getInt("progress", 0))
                .putInt("remaining",         map.getInt("remaining", 0))
                .putString("completionTime", map.getString("completionTime", ""))
                .putString("state",          map.getString("state", "IDLE"))
                .putString("device",         map.getString("device", "A1"))
                .putBoolean("online",        map.getBoolean("online", false))
                .putInt("nozzleTemp",        map.getInt("nozzleTemp", 0))
                .putInt("nozzleTarget",      map.getInt("nozzleTarget", 0))
                .putInt("bedTemp",           map.getInt("bedTemp", 0))
                .putInt("bedTarget",         map.getInt("bedTarget", 0))
                .apply()

            val asset = map.getAsset("thumbnail")
            if (asset != null) saveThumbnail(asset, prefs)

            TileService.getUpdater(this).requestUpdate(PrintTileService::class.java)
        }
    }

    private fun saveThumbnail(asset: Asset, prefs: android.content.SharedPreferences) {
        Wearable.getDataClient(this).getFdForAsset(asset)
            .addOnSuccessListener { result ->
                try {
                    val bytes = result.inputStream.readBytes()
                    result.inputStream.close()
                    File(filesDir, "thumb.jpg").outputStream().use { it.write(bytes) }
                    // Új verzió → tile újratölti a képet
                    prefs.edit().putLong("thumb_version", System.currentTimeMillis()).apply()
                    TileService.getUpdater(this).requestUpdate(PrintTileService::class.java)
                } catch (_: Exception) {}
            }
    }
}
