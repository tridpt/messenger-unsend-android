package com.msgguard.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MessageDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "msgguard_messages.db";
    private static final int DB_VERSION = 4;
    public static final String TABLE_MESSAGES = "messages";
    private static final String FILE_REF_PREFIX = "@file:";

    private static volatile MessageDatabase instance;
    private final Context appContext;

    public static synchronized MessageDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new MessageDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private MessageDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
    }

    private File getMediaDir() {
        File dir = new File(appContext.getFilesDir(), "msgguard_media");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * NÂNG CẤP v4.0: Tách chuỗi ảnh Base64 lớn ra lưu thành file riêng trong bộ nhớ trong của App
     * để SQLite CursorWindow (giới hạn cứng 2MB của Android) không bao giờ bị tràn khi lưu hàng trăm bức ảnh.
     */
    private String persistImageRef(String imageBase64) {
        if (imageBase64 == null || imageBase64.isEmpty()) return "";
        if (imageBase64.startsWith(FILE_REF_PREFIX)) return imageBase64;
        if (imageBase64.length() < 4096) return imageBase64;
        try {
            String fileName = "img_" + System.currentTimeMillis() + "_" + Math.abs(imageBase64.hashCode()) + ".b64";
            File outFile = new File(getMediaDir(), fileName);
            FileOutputStream fos = new FileOutputStream(outFile);
            try {
                fos.write(imageBase64.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                fos.close();
            }
            return FILE_REF_PREFIX + fileName;
        } catch (Exception e) {
            return imageBase64;
        }
    }

    private String resolveImageRef(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) return "";
        if (!storedValue.startsWith(FILE_REF_PREFIX)) return storedValue;
        String fileName = storedValue.substring(FILE_REF_PREFIX.length());
        File file = new File(getMediaDir(), fileName);
        if (!file.exists()) return "";
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) file.length());
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_MESSAGES + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "sbn_key TEXT NOT NULL," +
            "package_name TEXT NOT NULL," +
            "app_name TEXT NOT NULL," +
            "conversation_title TEXT NOT NULL," +
            "sender TEXT NOT NULL," +
            "content TEXT NOT NULL," +
            "original_content TEXT DEFAULT ''," +
            "image_base64 TEXT," +
            "avatar_base64 TEXT," +
            "timestamp INTEGER NOT NULL," +
            "is_unsent INTEGER DEFAULT 0," +
            "unsent_reason TEXT," +
            "unsent_at INTEGER DEFAULT 0," +
            "is_starred INTEGER DEFAULT 0" +
            ")"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv ON " + TABLE_MESSAGES + "(package_name, conversation_title)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sbn ON " + TABLE_MESSAGES + "(sbn_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_unsent ON " + TABLE_MESSAGES + "(is_unsent, timestamp DESC)");
    }

    private void ensureSchema(SQLiteDatabase db) {
        if (db == null) return;
        try {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN is_starred INTEGER DEFAULT 0");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN original_content TEXT DEFAULT ''");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
        ensureSchema(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
        ensureSchema(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            ensureSchema(db);
        }
    }

    public boolean isDuplicate(String packageName, String conversationTitle, String sender, String content, long nowMs) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            long windowStart = nowMs - 12000L;
            Cursor cursor = db.rawQuery(
                "SELECT id FROM " + TABLE_MESSAGES +
                " WHERE package_name = ? AND conversation_title = ? AND sender = ? AND content = ? AND is_unsent = 0 AND timestamp >= ? LIMIT 1",
                new String[]{packageName, conversationTitle, sender, content, String.valueOf(windowStart)}
            );
            try {
                return cursor.moveToFirst();
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public JSONObject insertMessage(
        String sbnKey,
        String packageName,
        String appName,
        String conversationTitle,
        String sender,
        String content,
        String imageBase64,
        String avatarBase64,
        long timestamp,
        boolean isStarredVip
    ) {
        if (isDuplicate(packageName, conversationTitle, sender, content, timestamp)) {
            if ((imageBase64 != null && !imageBase64.isEmpty()) || (avatarBase64 != null && !avatarBase64.isEmpty())) {
                updateMediaIfEmpty(packageName, conversationTitle, sender, content, imageBase64, avatarBase64);
            }
            return null;
        }

        SQLiteDatabase db = getWritableDatabase();
        String storedImageRef = persistImageRef(imageBase64);
        ContentValues values = new ContentValues();
        values.put("sbn_key", sbnKey);
        values.put("package_name", packageName);
        values.put("app_name", appName);
        values.put("conversation_title", conversationTitle);
        values.put("sender", sender);
        values.put("content", content);
        values.put("original_content", "");
        values.put("image_base64", storedImageRef);
        values.put("avatar_base64", avatarBase64 != null ? avatarBase64 : "");
        values.put("timestamp", timestamp);
        values.put("is_unsent", 0);
        values.put("unsent_reason", "");
        values.put("unsent_at", 0L);
        values.put("is_starred", isStarredVip ? 1 : 0);

        long id = db.insert(TABLE_MESSAGES, null, values);
        if (id == -1L) {
            ensureSchema(db);
            values.remove("is_starred");
            values.remove("original_content");
            id = db.insert(TABLE_MESSAGES, null, values);
        }
        if (id == -1L) return null;

        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("sbnKey", sbnKey);
            obj.put("packageName", packageName);
            obj.put("appName", appName);
            obj.put("conversationTitle", conversationTitle);
            obj.put("sender", sender);
            obj.put("content", content);
            obj.put("originalContent", "");
            obj.put("imageBase64", imageBase64 != null ? imageBase64 : "");
            obj.put("avatarBase64", avatarBase64 != null ? avatarBase64 : "");
            obj.put("timestamp", timestamp);
            obj.put("isUnsent", false);
            obj.put("unsentReason", "");
            obj.put("unsentAt", 0L);
            obj.put("isStarred", isStarredVip);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateMediaIfEmpty(
        String packageName,
        String conversationTitle,
        String sender,
        String content,
        String imageBase64,
        String avatarBase64
    ) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                values.put("image_base64", persistImageRef(imageBase64));
            }
            if (avatarBase64 != null && !avatarBase64.isEmpty()) {
                values.put("avatar_base64", avatarBase64);
            }
            if (values.size() > 0) {
                db.update(
                    TABLE_MESSAGES,
                    values,
                    "package_name = ? AND conversation_title = ? AND sender = ? AND content = ? AND (image_base64 IS NULL OR image_base64 = '')",
                    new String[]{packageName, conversationTitle, sender, content}
                );
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * NÂNG CẤP v4.0: Phát hiện khi đối phương CHỈNH SỬA (Edit) tin nhắn đã gửi,
     * giữ lại nguyên vẹn nội dung gốc ban đầu vào original_content và đánh dấu để người dùng biết.
     */
    public JSONObject markMessageAsEdited(
        String packageName,
        String conversationTitle,
        String oldContent,
        String newContent
    ) {
        if (oldContent == null || newContent == null || oldContent.equals(newContent)) return null;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor cursor = db.rawQuery(
            "SELECT * FROM " + TABLE_MESSAGES +
            " WHERE package_name = ? AND conversation_title = ? AND content = ?" +
            " ORDER BY timestamp DESC LIMIT 1",
            new String[]{packageName, conversationTitle, oldContent}
        );

        JSONObject targetMsg = null;
        try {
            if (cursor.moveToFirst()) {
                targetMsg = cursorToJson(cursor);
            }
        } finally {
            cursor.close();
        }

        if (targetMsg == null) return null;

        long id = targetMsg.optLong("id", -1L);
        String prevOrig = targetMsg.optString("originalContent", "");
        String finalOriginal = (prevOrig != null && !prevOrig.isEmpty()) ? prevOrig : oldContent;

        ContentValues values = new ContentValues();
        values.put("original_content", finalOriginal);
        values.put("content", newContent);
        values.put("is_unsent", 1);
        values.put("unsent_reason", "MESSAGE_EDITED");
        values.put("unsent_at", now);
        db.update(TABLE_MESSAGES, values, "id = ?", new String[]{String.valueOf(id)});

        try {
            targetMsg.put("originalContent", finalOriginal);
            targetMsg.put("content", newContent);
            targetMsg.put("isUnsent", true);
            targetMsg.put("unsentReason", "MESSAGE_EDITED");
            targetMsg.put("unsentAt", now);
        } catch (Exception ignored) {
        }
        return targetMsg;
    }

    public JSONObject markSpecificContentAsUnsent(
        String packageName,
        String conversationTitle,
        String content,
        String reason
    ) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor cursor = db.rawQuery(
            "SELECT * FROM " + TABLE_MESSAGES +
            " WHERE package_name = ? AND conversation_title = ? AND content = ? AND is_unsent = 0" +
            " ORDER BY timestamp DESC LIMIT 1",
            new String[]{packageName, conversationTitle, content}
        );

        JSONObject targetMsg = null;
        try {
            if (cursor.moveToFirst()) {
                targetMsg = cursorToJson(cursor);
            }
        } finally {
            cursor.close();
        }

        if (targetMsg == null) return null;

        long id = targetMsg.optLong("id", -1L);
        ContentValues values = new ContentValues();
        values.put("is_unsent", 1);
        values.put("unsent_reason", reason);
        values.put("unsent_at", now);
        db.update(TABLE_MESSAGES, values, "id = ?", new String[]{String.valueOf(id)});

        try {
            targetMsg.put("isUnsent", true);
            targetMsg.put("unsentReason", reason);
            targetMsg.put("unsentAt", now);
        } catch (Exception ignored) {
        }
        return targetMsg;
    }

    public JSONObject markLatestMessageAsUnsent(
        String packageName,
        String conversationTitle,
        String sbnKey,
        String reason
    ) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        Cursor cursor;
        if (conversationTitle != null && !conversationTitle.trim().isEmpty()) {
            cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_MESSAGES +
                " WHERE package_name = ? AND (conversation_title = ? OR sender = ?) AND is_unsent = 0" +
                " ORDER BY timestamp DESC LIMIT 1",
                new String[]{packageName, conversationTitle, conversationTitle}
            );
        } else {
            cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_MESSAGES +
                " WHERE sbn_key = ? AND is_unsent = 0" +
                " ORDER BY timestamp DESC LIMIT 1",
                new String[]{sbnKey != null ? sbnKey : ""}
            );
        }

        JSONObject targetMsg = null;
        try {
            if (cursor.moveToFirst()) {
                targetMsg = cursorToJson(cursor);
            }
        } finally {
            cursor.close();
        }

        if (targetMsg == null) return null;

        long id = targetMsg.optLong("id", -1L);
        ContentValues values = new ContentValues();
        values.put("is_unsent", 1);
        values.put("unsent_reason", reason);
        values.put("unsent_at", now);
        db.update(TABLE_MESSAGES, values, "id = ?", new String[]{String.valueOf(id)});

        try {
            targetMsg.put("isUnsent", true);
            targetMsg.put("unsentReason", reason);
            targetMsg.put("unsentAt", now);
        } catch (Exception ignored) {
        }
        return targetMsg;
    }

    public boolean deleteMessageById(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_MESSAGES, "id = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public boolean toggleMessageUnsentById(long id) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT is_unsent FROM " + TABLE_MESSAGES + " WHERE id = ?", new String[]{String.valueOf(id)});
        int current = 0;
        try {
            if (c.moveToFirst()) {
                current = c.getInt(0);
            } else {
                return false;
            }
        } finally {
            c.close();
        }
        int next = current == 1 ? 0 : 1;
        ContentValues cv = new ContentValues();
        cv.put("is_unsent", next);
        cv.put("unsent_reason", next == 1 ? "MANUAL_MARK" : "");
        cv.put("unsent_at", next == 1 ? System.currentTimeMillis() : 0L);
        return db.update(TABLE_MESSAGES, cv, "id = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public boolean toggleMessageStarById(long id) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT is_starred FROM " + TABLE_MESSAGES + " WHERE id = ?", new String[]{String.valueOf(id)});
        int current = 0;
        try {
            if (c.moveToFirst()) {
                current = c.getInt(0);
            } else {
                return false;
            }
        } finally {
            c.close();
        }
        int next = current == 1 ? 0 : 1;
        ContentValues cv = new ContentValues();
        cv.put("is_starred", next);
        return db.update(TABLE_MESSAGES, cv, "id = ?", new String[]{String.valueOf(id)}) > 0;
    }

    /**
     * NÂNG CẤP v3.0: Dọn nhẹ tin nhắn thường đã cũ (quá N ngày),
     * nhưng GIỮ VĨNH VIỄN 100% tin nhắn bị gỡ (is_unsent = 1) và tin gắn Sao/VIP (is_starred = 1).
     */
    public int cleanOldNormalMessages(int daysOld) {
        SQLiteDatabase db = getWritableDatabase();
        if (daysOld <= 0) {
            return db.delete(TABLE_MESSAGES, "is_unsent = 0 AND is_starred = 0", null);
        }
        long cutoff = System.currentTimeMillis() - (daysOld * 86400000L);
        return db.delete(
            TABLE_MESSAGES,
            "is_unsent = 0 AND is_starred = 0 AND timestamp < ?",
            new String[]{String.valueOf(cutoff)}
        );
    }

    public JSONArray getMessages(boolean onlyUnsent, String search, int limit) {
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();

        if (onlyUnsent) {
            where.append("is_unsent = 1");
        }
        if (search != null && !search.trim().isEmpty()) {
            if (where.length() > 0) where.append(" AND ");
            where.append("(sender LIKE ? OR conversation_title LIKE ? OR content LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q);
            args.add(q);
            args.add(q);
        }

        String whereSql = where.length() > 0 ? "WHERE " + where.toString() : "";
        String sql = "SELECT * FROM " + TABLE_MESSAGES + " " + whereSql + " ORDER BY timestamp DESC LIMIT " + limit;

        JSONArray arr = new JSONArray();
        Cursor c = db.rawQuery(sql, args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                JSONObject item = cursorToJson(c);
                if (item != null) arr.put(item);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    public void deleteAllMessages() {
        getWritableDatabase().delete(TABLE_MESSAGES, null, null);
    }

    private JSONObject cursorToJson(Cursor c) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", c.getLong(c.getColumnIndexOrThrow("id")));
            obj.put("sbnKey", c.getString(c.getColumnIndexOrThrow("sbn_key")));
            obj.put("packageName", c.getString(c.getColumnIndexOrThrow("package_name")));
            obj.put("appName", c.getString(c.getColumnIndexOrThrow("app_name")));
            obj.put("conversationTitle", c.getString(c.getColumnIndexOrThrow("conversation_title")));
            obj.put("sender", c.getString(c.getColumnIndexOrThrow("sender")));
            obj.put("content", c.getString(c.getColumnIndexOrThrow("content")));
            int origCol = c.getColumnIndex("original_content");
            obj.put("originalContent", origCol >= 0 ? (c.getString(origCol) != null ? c.getString(origCol) : "") : "");
            String rawImg = c.getString(c.getColumnIndexOrThrow("image_base64"));
            obj.put("imageBase64", resolveImageRef(rawImg));
            obj.put("avatarBase64", c.getString(c.getColumnIndexOrThrow("avatar_base64")));
            obj.put("timestamp", c.getLong(c.getColumnIndexOrThrow("timestamp")));
            obj.put("isUnsent", c.getInt(c.getColumnIndexOrThrow("is_unsent")) == 1);
            obj.put("unsentReason", c.getString(c.getColumnIndexOrThrow("unsent_reason")));
            obj.put("unsentAt", c.getLong(c.getColumnIndexOrThrow("unsent_at")));
            int starCol = c.getColumnIndex("is_starred");
            obj.put("isStarred", starCol >= 0 && c.getInt(starCol) == 1);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }
}
