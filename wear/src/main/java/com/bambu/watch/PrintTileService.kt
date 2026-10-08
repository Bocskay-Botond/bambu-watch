package com.bambu.watch

import android.content.Context
import android.graphics.BitmapFactory
import androidx.wear.tiles.*
import androidx.wear.tiles.LayoutElementBuilders.*
import androidx.wear.tiles.ModifiersBuilders.*
import androidx.wear.tiles.ResourceBuilders.*
import androidx.wear.tiles.DimensionBuilders.*
import androidx.wear.tiles.ColorBuilders.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File

class PrintTileService : TileService() {

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val p = getSharedPreferences("bambu", Context.MODE_PRIVATE)
        val data = TileData(
            progress       = p.getInt("progress", 0),
            remaining      = p.getInt("remaining", 0),
            completionTime = p.getString("completionTime", "") ?: "",
            filename       = p.getString("filename", "") ?: "",
            state          = p.getString("state", "IDLE") ?: "IDLE",
            device         = p.getString("device", "A1") ?: "A1",
            online         = p.getBoolean("online", false),
            nozzleTemp     = p.getInt("nozzleTemp", 0),
            nozzleTarget   = p.getInt("nozzleTarget", 0),
            bedTemp        = p.getInt("bedTemp", 0),
            bedTarget      = p.getInt("bedTarget", 0),
            hasThumb       = File(filesDir, "thumb.jpg").exists(),
            thumbVersion   = p.getLong("thumb_version", 0)
        )

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(if (data.hasThumb) "thumb_${data.thumbVersion}" else "1")
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(buildLayout(data))
                                    .build()
                            ).build()
                    ).build()
            )
            .setFreshnessIntervalMillis(5_000L)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val builder = ResourceBuilders.Resources.Builder().setVersion(req.version)
        val thumbFile = File(filesDir, "thumb.jpg")
        if (thumbFile.exists()) {
            try {
                val orig = BitmapFactory.decodeFile(thumbFile.absolutePath)
                val bmp  = android.graphics.Bitmap.createScaledBitmap(orig, 80, 80, true)
                    .copy(android.graphics.Bitmap.Config.RGB_565, false)
                val buf  = java.nio.ByteBuffer.allocate(bmp.byteCount)
                bmp.copyPixelsToBuffer(buf)
                builder.addIdToImageMapping("thumb",
                    ImageResource.Builder()
                        .setInlineResource(
                            InlineImageResource.Builder()
                                .setData(buf.array())
                                .setWidthPx(80).setHeightPx(80)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
                                .build()
                        ).build()
                )
            } catch (_: Exception) {}
        }
        return Futures.immediateFuture(builder.build())
    }

    private data class TileData(
        val progress: Int, val remaining: Int, val completionTime: String,
        val filename: String, val state: String, val device: String,
        val online: Boolean, val nozzleTemp: Int, val nozzleTarget: Int,
        val bedTemp: Int, val bedTarget: Int, val hasThumb: Boolean, val thumbVersion: Long
    )

    private fun buildLayout(d: TileData): LayoutElement {
        val stateColor = when (d.state) {
            "RUNNING" -> "#00BFFF"; "FINISH" -> "#00FF88"; "FAILED" -> "#FF4444"
            "PAUSED"  -> "#FFAA00"; else -> "#888888"
        }
        val stateText = when (d.state) {
            "RUNNING" -> "NYOMTAT"; "FINISH" -> "KÉSZ"; "FAILED" -> "HIBA"
            "PAUSED"  -> "SZÜNET"; else -> "TÉTLEN"
        }
        val shortName = if (d.filename.length > 16) d.filename.take(13) + "..." else d.filename

        val col = Column.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(spacer(4))
            // Header: device + online dot + state
            .addContent(Row.Builder()
                .addContent(txt(d.device, 11f, "#888888"))
                .addContent(txt("  ●  ", 10f, if (d.online) "#00FF88" else "#666666"))
                .addContent(txt(stateText, 11f, stateColor))
                .build())
            .addContent(spacer(4))

        // Thumbnail if available
        if (d.hasThumb) {
            col.addContent(
                Image.Builder()
                    .setResourceId("thumb")
                    .setWidth(dp(72f)).setHeight(dp(72f))
                    .setModifiers(Modifiers.Builder()
                        .setPadding(Padding.Builder().setAll(dp(2f)).build())
                        .build())
                    .build()
            )
            col.addContent(spacer(4))
        }

        // Progress
        col.addContent(txt("${d.progress}%", if (d.hasThumb) 34f else 44f, "#FFFFFF", bold = true))
        col.addContent(spacer(2))
        col.addContent(progressBar(d.progress))
        col.addContent(spacer(4))

        // Filename
        if (shortName.isNotEmpty()) {
            col.addContent(txt(shortName, 10f, "#00BFFF"))
            col.addContent(spacer(3))
        }

        // Hőmérsékletek (MQTT-ből)
        if (d.nozzleTemp > 0 || d.bedTemp > 0) {
            col.addContent(Row.Builder()
                .addContent(txt("N:${d.nozzleTemp}°/${d.nozzleTarget}°", 10f, "#FF8C00"))
                .addContent(txt("  B:${d.bedTemp}°/${d.bedTarget}°", 10f, "#FF6B6B"))
                .build())
            col.addContent(spacer(3))
        }

        // Completion time
        if (d.completionTime.isNotEmpty()) {
            col.addContent(txt("Kész: ${d.completionTime}", 11f, "#AAAAAA"))
        } else if (d.state == "FINISH") {
            col.addContent(txt("Befejezve!", 11f, "#00FF88"))
        }

        col.addContent(spacer(4))

        return Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .addContent(col.build())
            .build()
    }

    private fun progressBar(progress: Int): LayoutElement {
        val filled = (progress / 5).coerceIn(0, 20)
        return txt("█".repeat(filled) + "░".repeat(20 - filled), 7f, "#00BFFF")
    }

    private fun txt(content: String, size: Float, colorHex: String, bold: Boolean = false): LayoutElement =
        Text.Builder().setText(content)
            .setFontStyle(FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(android.graphics.Color.parseColor(colorHex)))
                .apply { if (bold) setWeight(FONT_WEIGHT_BOLD) }
                .build())
            .build()

    private fun spacer(dp: Int): LayoutElement =
        Spacer.Builder().setHeight(dp(dp.toFloat())).build()
}
