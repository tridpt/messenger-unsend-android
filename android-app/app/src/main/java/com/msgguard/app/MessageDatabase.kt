package com.msgguard.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class CapturedMessage(
    val id: Long,
    val sbnKey: String,
    val packageName: String,
    val appName: String,
    val conversationTitle: String,
    val sender: String,
    val content: String,
    val imageBase64: String?,
    val avatarBase64: String?,
    val timestamp: Long,
    val isUnsent: Boolean,
    val unsentReason: String?,
    val unsentAt: Long
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("sbnKey", sbnKey)
            put("packageName", packageName)
            put("appName", appName)
            put("conversationTitle", conversationTitle)
            put("sender", sender)
            put("content", content)
            put("imageBase64", imageBase64 ?: "")
            put("avatarBase64", avatarBase64 ?: "")
            put("timestamp", timestamp)
            put("isUnsent", isUnsent)
            put("unsentReason", unsentReason ?: "")
            put("unsentAt", unsentAt)
        }
    }
}

class MessageDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "msgguard_messages.db"
        private const val DB_VERSION = 1
        const val TABLE_MESSAGES = "messages"

        @Volatile
        private var instance: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase {
            return instance ?: synchronized(this) {
                instance ?: MessageDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sbn_key TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                conversation_title TEXT NOT NULL,
                sender TEXT NOT NULL,
                content TEXT NOT NULL,
                image_base64 TEXT,
                avatar_base64 TEXT,
                timestamp INTEGER NOT NULL,
                is_unsent INTEGER DEFAULT 0,
                unsent_reason TEXT,
                unsent_at INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_conv ON $TABLE_MESSAGES(package_name, conversation_title)")
        db.execSQL("CREATE INDEX idx_sbn ON $TABLE_MESSAGES(sbn_key)")
        db.execSQL("CREATE INDEX idx_unsent ON $TABLE_MESSAGES(is_unsent, timestamp DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        onCreate(db)
    }

    /**
     * Kiểm tra trùng lặp: Khi có nhiều tin nhắn chưa đọc, Android MessagingStyle
     * sẽ gửi lại các tin cũ trong cùng bundle. Hàm này chặn lưu trùng trong cửa sổ 60 giây.
     */
    fun isDuplicate(packageName: String, conversationTitle: String, sender: String, content: String, nowMs: Long): Boolean {
        val db = readableDatabase
        val windowStart = nowMs - 60_000L
        val cursor = db.rawQuery(
            """
            SELECT id FROM $TABLE_MESSAGES
            WHERE package_name = ? AND conversation_title = ? AND sender = ? AND content = ? AND timestamp >= ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(packageName, conversationTitle, sender, content, windowStart.toString())
        )
        val exists = cursor.use { it.moveToFirst() }
        return exists
    }

    /**
     * Lưu một tin nhắn mới bắt được từ NotificationListenerService
     */
    fun insertMessage(
        sbnKey: String,
        packageName: String,
        appName: String,
        conversationTitle: String,
        sender: String,
        content: String,
        imageBase64: String?,
        avatarBase64: String?,
        timestamp: Long
    ): CapturedMessage? {
        if (isDuplicate(packageName, conversationTitle, sender, content, timestamp)) {
            return null
        }

        val db = writableDatabase
        val values = ContentValues().apply {
            put("sbn_key", sbnKey)
            put("package_name", packageName)
            put("app_name", appName)
            put("conversation_title", conversationTitle)
            put("sender", sender)
            put("content", content)
            put("image_base64", imageBase64)
            put("avatar_base64", avatarBase64)
            put("timestamp", timestamp)
            put("is_unsent", 0)
            put("unsent_reason", "")
            put("unsent_at", 0L)
        }

        val id = db.insert(TABLE_MESSAGES, null, values)
        if (id == -1L) return null

        return CapturedMessage(
            id = id,
            sbnKey = sbnKey,
            packageName = packageName,
            appName = appName,
            conversationTitle = conversationTitle,
            sender = sender,
            content = content,
            imageBase64 = imageBase64,
            avatarBase64 = avatarBase64,
            timestamp = timestamp,
            isUnsent = false,
            unsentReason = null,
            unsentAt = 0L
        )
    }

    /**
     * Đánh dấu tin nhắn gần nhất của cuộc trò chuyện (hoặc theo sbn_key) là ĐÃ BỊ GỠ
     * và trả về nội dung gốc của tin nhắn đó để bắn thông báo cho người dùng.
     */
    fun markLatestMessageAsUnsent(
        packageName: String,
        conversationTitle: String?,
        sbnKey: String?,
        reason: String
    ): CapturedMessage? {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        // Ưu tiên tìm theo conversation_title + package_name trước, nếu không có thì tìm theo sbn_key
        val cursor = if (!conversationTitle.isNullOrBlank()) {
            db.rawQuery(
                """
                SELECT * FROM $TABLE_MESSAGES
                WHERE package_name = ? AND (conversation_title = ? OR sender = ?) AND is_unsent = 0
                ORDER BY timestamp DESC LIMIT 1
                """.trimIndent(),
                arrayOf(packageName, conversationTitle, conversationTitle)
            )
        } else {
            db.rawQuery(
                """
                SELECT * FROM $TABLE_MESSAGES
                WHERE sbn_key = ? AND is_unsent = 0
                ORDER BY timestamp DESC LIMIT 1
                """.trimIndent(),
                arrayOf(sbnKey ?: "")
            )
        }

        var targetMsg: CapturedMessage? = null
        cursor.use { c ->
            if (c.moveToFirst()) {
                targetMsg = cursorToMessage(c)
            }
        }

        val msg = targetMsg ?: return null

        val values = ContentValues().apply {
            put("is_unsent", 1)
            put("unsent_reason", reason)
            put("unsent_at", now)
        }
        db.update(TABLE_MESSAGES, values, "id = ?", arrayOf(msg.id.toString()))

        return msg.copy(
            isUnsent = true,
            unsentReason = reason,
            unsentAt = now
        )
    }

    /**
     * Truy vấn danh sách tin nhắn (có thể lọc chỉ tin bị gỡ, tìm kiếm theo từ khóa)
     */
    fun getMessages(onlyUnsent: Boolean = false, search: String = "", limit: Int = 300): JSONArray {
        val db = readableDatabase
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (onlyUnsent) {
            whereClauses.add("is_unsent = 1")
        }
        if (search.isNotBlank()) {
            whereClauses.add("(sender LIKE ? OR conversation_title LIKE ? OR content LIKE ?)")
            val q = "%${search.trim()}%"
            args.add(q)
            args.add(q)
            args.add(q)
        }

        val whereSql = if (whereClauses.isEmpty()) "" else "WHERE " + whereClauses.joinToString(" AND ")
        val sql = "SELECT * FROM $TABLE_MESSAGES $whereSql ORDER BY timestamp DESC LIMIT $limit"

        val arr = JSONArray()
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                arr.put(cursorToMessage(c).toJson())
            }
        }
        return arr
    }

    fun deleteAllMessages() {
        writableDatabase.delete(TABLE_MESSAGES, null, null)
    }

    private fun cursorToMessage(c: android.database.Cursor): CapturedMessage {
        return CapturedMessage(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            sbnKey = c.getString(c.getColumnIndexOrThrow("sbn_key")),
            packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
            appName = c.getString(c.getColumnIndexOrThrow("app_name")),
            conversationTitle = c.getString(c.getColumnIndexOrThrow("conversation_title")),
            sender = c.getString(c.getColumnIndexOrThrow("sender")),
            content = c.getString(c.getColumnIndexOrThrow("content")),
            imageBase64 = c.getString(c.getColumnIndexOrThrow("image_base64")),
            avatarBase64 = c.getString(c.getColumnIndexOrThrow("avatar_base64")),
            timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
            isUnsent = c.getInt(c.getColumnIndexOrThrow("is_unsent")) == 1,
            unsentReason = c.getString(c.getColumnIndexOrThrow("unsent_reason")),
            unsentAt = c.getLong(c.getColumnIndexOrThrow("unsent_at"))
        )
    }
}
