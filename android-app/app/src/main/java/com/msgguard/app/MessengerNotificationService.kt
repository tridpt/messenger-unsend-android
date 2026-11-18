package com.msgguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MessengerNotificationService : NotificationListenerService() {

    companion object {
        const val ACTION_MESSAGE_EVENT = "com.msgguard.app.ACTION_MESSAGE_EVENT"
        const val ALERT_CHANNEL_ID = "msgguard_unsend_alerts"

        val SUPPORTED_PACKAGES = mapOf(
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.zing.zalo" to "Zalo",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram"
        )

        // Các mẫu câu thông báo khi đối phương thu hồi tin nhắn trên Messenger / Zalo / Instagram
        private val UNSEND_PATTERNS = listOf(
            "đã thu hồi một tin nhắn",
            "đã thu hồi tin nhắn",
            "đã gỡ một tin nhắn",
            "tin nhắn đã bị thu hồi",
            "tin nhắn đã được thu hồi",
            "vừa thu hồi một tin nhắn",
            "unsent a message",
            "removed a message",
            "this message was removed",
            "message unsent"
        )

        // Các thông báo hệ thống không phải tin nhắn thật
        private val IGNORE_PATTERNS = listOf(
            "bong bóng chat đang hoạt động",
            "chat heads active",
            "đang chờ mạng",
            "waiting for network",
            "đang kết nối",
            "cuộc gọi thoại đang diễn ra",
            "ongoing voice call"
        )
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createAlertChannel()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Tự động yêu cầu hệ thống Android kết nối lại nếu bị ngắt
        try {
            requestRebind(ComponentName(this, MessengerNotificationService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Chỉ bắt thông báo từ Messenger (và các app nhắn tin được hỗ trợ)
        val appName = SUPPORTED_PACKAGES[pkg] ?: return
        val notification = sbn.notification ?: return

        // Bỏ qua thông báo dạng Ongoing (ví dụ: cuộc gọi, bong bóng chat) hoặc Group Summary rỗng
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras: Bundle = notification.extras ?: return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: rawTitle

        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: ""

        if (convTitle.isEmpty() && text.isEmpty()) return
        if (isIgnoredSystemNoise(rawTitle, text)) return

        // Tách tên người gửi trong nhóm nếu có dạng "Tên nhóm: Người gửi"
        val sender = if (rawTitle.isNotEmpty()) rawTitle else convTitle

        // Trích xuất ảnh đính kèm (nếu đối phương gửi ảnh) và Avatar người gửi
        val attachedPicBase64 = extractPictureBase64(extras)
        val avatarBase64 = extractAvatarBase64(notification, extras)

        val db = MessageDatabase.getInstance(applicationContext)

        // KIỂM TRA LỚP 1: Nội dung thông báo báo hiệu đối phương vừa THU HỒI tin nhắn
        if (isUnsendNotificationText(text)) {
            val unsentMsg = db.markLatestMessageAsUnsent(
                packageName = pkg,
                conversationTitle = convTitle,
                sbnKey = sbn.key,
                reason = "TEXT_RECALL"
            )
            if (unsentMsg != null) {
                sendUnsentAlertNotification(unsentMsg)
                broadcastUpdate("UNSENT_DETECTED", unsentMsg)
                syncToPcIfEnabled("unsent", unsentMsg)
            }
            return
        }

        // Nếu gửi ảnh mà không có chữ thì ghi chú "[Hình ảnh đính kèm]"
        val finalContent = when {
            text.isNotEmpty() -> text
            attachedPicBase64 != null -> "📷 [Hình ảnh đính kèm]"
            else -> return
        }

        val saved = db.insertMessage(
            sbnKey = sbn.key ?: "$pkg:$convTitle",
            packageName = pkg,
            appName = appName,
            conversationTitle = convTitle.ifEmpty { "Không rõ" },
            sender = sender.ifEmpty { "Không rõ" },
            content = finalContent,
            imageBase64 = attachedPicBase64,
            avatarBase64 = avatarBase64,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

        if (saved != null) {
            broadcastUpdate("NEW_MESSAGE", saved)
            syncToPcIfEnabled("message", saved)
        }
    }

    /**
     * KIỂM TRA LỚP 2: Khi đối phương thu hồi tin nhắn trên Messenger,
     * ứng dụng Messenger thường gọi lệnh tự hủy thông báo (REASON_APP_CANCEL = 8)
     * ngay trên thanh trạng thái trước khi bạn kịp bấm vào đọc.
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!SUPPORTED_PACKAGES.containsKey(pkg)) return

        // REASON_APP_CANCEL (8) = Chính app Messenger tự rút lại thông báo (không phải người dùng vuốt xóa hay bấm vào)
        if (reason == REASON_APP_CANCEL) {
            val extras = sbn.notification?.extras
            val convTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
                ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()

            val db = MessageDatabase.getInstance(applicationContext)
            val unsentMsg = db.markLatestMessageAsUnsent(
                packageName = pkg,
                conversationTitle = convTitle,
                sbnKey = sbn.key,
                reason = "APP_CANCEL_RETRACT"
            )
            if (unsentMsg != null) {
                sendUnsentAlertNotification(unsentMsg)
                broadcastUpdate("UNSENT_DETECTED", unsentMsg)
                syncToPcIfEnabled("unsent", unsentMsg)
            }
        }
    }

    private fun isUnsendNotificationText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return UNSEND_PATTERNS.any { lower.contains(it) }
    }

    private fun isIgnoredSystemNoise(title: String, text: String): Boolean {
        val combined = "$title $text".lowercase(Locale.ROOT)
        return IGNORE_PATTERNS.any { combined.contains(it) }
    }

    private fun extractPictureBase64(extras: Bundle): String? {
        return try {
            val bmp = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE) ?: return null
            bitmapToBase64(bmp, maxDim = 600, quality = 75)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAvatarBase64(notification: Notification, extras: Bundle): String? {
        return try {
            val bmp: Bitmap? = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val icon: Icon? = notification.getLargeIcon()
                    val drawable = icon?.loadDrawable(this)
                    (drawable as? BitmapDrawable)?.bitmap
                } else null
            if (bmp != null) bitmapToBase64(bmp, maxDim = 96, quality = 75) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bmp: Bitmap, maxDim: Int, quality: Int): String {
        val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else bmp

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    /**
     * Bắn thông báo riêng của MsgGuard báo cho bạn biết ngay lập tức nội dung vừa bị đối phương gỡ
     */
    private fun sendUnsentAlertNotification(msg: CapturedMessage) {
        val prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notify_on_unsend", true)) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            msg.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alert = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 ${msg.sender} vừa gỡ 1 tin nhắn (${msg.appName})")
            .setContentText("Nội dung gốc: \"${msg.content}\"")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("Nội dung gốc trước khi gỡ:\n\"${msg.content}\"\n\nBấm để xem toàn bộ lịch sử trong MsgGuard.")
            )
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(("unsend_" + msg.id).hashCode(), alert)
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Cảnh báo tin nhắn bị gỡ",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo ngay khi phát hiện ai đó thu hồi tin nhắn trên Messenger"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun broadcastUpdate(eventType: String, msg: CapturedMessage) {
        val intent = Intent(ACTION_MESSAGE_EVENT).apply {
            setPackage(packageName)
            putExtra("event_type", eventType)
            putExtra("message_json", msg.toJson().toString())
        }
        sendBroadcast(intent)
    }

    /**
     * Đồng bộ ngầm qua Wi-Fi/LAN về Web Dashboard trên máy tính nếu người dùng bật
     */
    private fun syncToPcIfEnabled(eventType: String, msg: CapturedMessage) {
        val prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE)
        val syncUrl = prefs.getString("pc_sync_url", "")?.trim() ?: ""
        if (syncUrl.isEmpty()) return

        ioExecutor.execute {
            try {
                val url = URL(if (syncUrl.endsWith("/api/ingest")) syncUrl else "${syncUrl.trimEnd('/')}/api/ingest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject().apply {
                    put("eventType", eventType)
                    put("message", msg.toJson())
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
