package com.msgguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessengerNotificationService extends NotificationListenerService {

    public static final String ACTION_MESSAGE_EVENT = "com.msgguard.app.ACTION_MESSAGE_EVENT";
    public static final String ALERT_CHANNEL_ID = "msgguard_unsend_alerts";
    public static final String KEEP_ALIVE_CHANNEL_ID = "msgguard_keep_alive";
    private static final int KEEP_ALIVE_NOTIF_ID = 9901;

    public static volatile boolean isListenerConnected = false;
    public static volatile int lastActiveScanCount = 0;
    private static volatile MessengerNotificationService activeInstance = null;
    private static volatile long lastForceRebindMs = 0L;

    public static final Map<String, String> SUPPORTED_PACKAGES = new HashMap<>();
    static {
        SUPPORTED_PACKAGES.put("com.facebook.orca", "Messenger");
        SUPPORTED_PACKAGES.put("com.facebook.mlite", "Messenger Lite");
        SUPPORTED_PACKAGES.put("com.facebook.katana", "Facebook");
        SUPPORTED_PACKAGES.put("com.facebook.lite", "Facebook Lite");
        SUPPORTED_PACKAGES.put("com.zing.zalo", "Zalo");
        SUPPORTED_PACKAGES.put("com.instagram.android", "Instagram");
        SUPPORTED_PACKAGES.put("com.instagram.lite", "Instagram Lite");
        SUPPORTED_PACKAGES.put("com.instagram.barcelona", "Threads");
        SUPPORTED_PACKAGES.put("org.telegram.messenger", "Telegram");
        SUPPORTED_PACKAGES.put("org.telegram.messenger.web", "Telegram");
        SUPPORTED_PACKAGES.put("org.thunderdog.challegram", "Telegram X");
        SUPPORTED_PACKAGES.put("com.whatsapp", "WhatsApp");
        SUPPORTED_PACKAGES.put("com.whatsapp.w4b", "WhatsApp Business");
        SUPPORTED_PACKAGES.put("com.viber.voip", "Viber");
        SUPPORTED_PACKAGES.put("com.google.android.apps.messaging", "Tin nhắn SMS");
        SUPPORTED_PACKAGES.put("com.android.mms", "Tin nhắn SMS");
        SUPPORTED_PACKAGES.put("com.xiaomi.mms", "Tin nhắn SMS");
        SUPPORTED_PACKAGES.put("com.samsung.android.messaging", "Tin nhắn SMS");
        SUPPORTED_PACKAGES.put("com.discord", "Discord");
    }

    public static String resolveSupportedAppName(String pkg) {
        if (pkg == null) return null;
        if (pkg.contains("msgguard") || pkg.equals("android") || pkg.equals("com.android.systemui")) {
            return null;
        }
        String exact = SUPPORTED_PACKAGES.get(pkg);
        if (exact != null) return exact;
        String lower = pkg.toLowerCase(Locale.ROOT);
        if (lower.contains("orca")) return "Messenger";
        if (lower.contains("mlite")) return "Messenger Lite";
        if (lower.contains("zalo")) return "Zalo";
        if (lower.contains("instagram") || lower.contains("barcelona")) return "Instagram";
        if (lower.contains("telegram") || lower.contains("challegram") || lower.contains("nekogram")) return "Telegram";
        if (lower.contains("whatsapp")) return "WhatsApp";
        if (lower.contains("viber")) return "Viber";
        if (lower.contains("facebook") || lower.contains("katana")) return "Facebook";
        if (lower.contains("messaging") || lower.endsWith(".mms")) return "Tin nhắn SMS";
        if (lower.contains("discord")) return "Discord";
        if (lower.contains("musically") || lower.contains("trill") || lower.contains("tiktok")) return "TikTok";
        return null;
    }

    /**
     * Quét ngay lập tức toàn bộ thông báo đang hiện trên thanh trạng thái điện thoại
     * để không bỏ sót bất kỳ tin nhắn nào vừa đến trước khi mở app.
     */
    public static int scanActiveNotificationsStatic() {
        MessengerNotificationService svc = activeInstance;
        if (svc == null) return 0;
        return svc.scanActiveNotificationsNow();
    }

    public int scanActiveNotificationsNow() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return 0;
            lastActiveScanCount = active.length;
            int processed = 0;
            for (StatusBarNotification sbn : active) {
                if (sbn != null) {
                    onNotificationPosted(sbn);
                    processed++;
                }
            }
            return processed;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Khắc phục lỗi Android / Xiaomi MIUI / HyperOS tự ngắt kết nối NotificationListenerService:
     * Thực hiện tắt -> chờ 350ms -> bật lại trên luồng phụ để hệ thống Android kết nối sạch sẽ.
     */
    public static void forceRebindService(final Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (now - lastForceRebindMs < 4000L) {
            scanActiveNotificationsStatic();
            return;
        }
        lastForceRebindMs = now;
        final Context appCtx = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ComponentName cn = new ComponentName(appCtx, MessengerNotificationService.class);
                    PackageManager pm = appCtx.getPackageManager();
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    );
                    Thread.sleep(350L);
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    );
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        requestRebind(cn);
                    }
                    Thread.sleep(500L);
                    scanActiveNotificationsStatic();
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    public static void ensureServiceBound(Context context) {
        if (context == null) return;
        try {
            ComponentName cn = new ComponentName(context, MessengerNotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(cn);
            }
            if (!isListenerConnected && (System.currentTimeMillis() - lastForceRebindMs > 15000L)) {
                forceRebindService(context);
            } else {
                scanActiveNotificationsStatic();
            }
        } catch (Exception ignored) {
        }
    }

    private static final String[] UNSEND_PATTERNS = new String[]{
        "đã thu hồi một tin nhắn",
        "đã thu hồi tin nhắn",
        "đã gỡ một tin nhắn",
        "đã gỡ tin nhắn",
        "tin nhắn đã bị thu hồi",
        "tin nhắn đã được thu hồi",
        "vừa thu hồi một tin nhắn",
        "unsent a message",
        "removed a message",
        "this message was removed",
        "message unsent"
    };

    private static final String[] IGNORE_PATTERNS = new String[]{
        "bong bóng chat đang hoạt động",
        "chat heads active",
        "đang chờ mạng",
        "waiting for network",
        "đang kết nối",
        "cuộc gọi thoại đang diễn ra",
        "ongoing voice call"
    };

    // Lưu lịch sử các dòng tin nhắn trong từng cụm thông báo (sbnKey) để phát hiện khi 1 câu bất kỳ bị gỡ
    private final Map<String, List<String>> bundleHistoryMap = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        activeInstance = this;
        updateKeepAliveNotification();
        scanActiveNotificationsNow();
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        activeInstance = this;
        isListenerConnected = true;
        updateKeepAliveNotification();
        scanActiveNotificationsNow();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isListenerConnected = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(new ComponentName(this, MessengerNotificationService.class));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * NÂNG CẤP 3: Foreground Service thực thụ + Thông báo tĩnh siêu nhẹ (0% CPU, không chuông, không rung)
     * giúp hệ điều hành Xiaomi MIUI / HyperOS không bao giờ tự đóng dịch vụ khi khóa màn hình qua đêm.
     */
    public void updateKeepAliveNotification() {
        try {
            SharedPreferences prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("keep_alive_notif", true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (!enabled) {
                try {
                    stopForeground(true);
                } catch (Exception ignored) {
                }
                nm.cancel(KEEP_ALIVE_NOTIF_ID);
                return;
            }

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification notif = new Notification.Builder(this, KEEP_ALIVE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("🛡️ MsgGuard PRO v4.0 đang bảo vệ ngầm")
                .setContentText("Bắt tin nhắn gỡ & chỉnh sửa trên Messenger / Zalo (0% Pin)")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

            try {
                startForeground(KEEP_ALIVE_NOTIF_ID, notif);
            } catch (Throwable fgsErr) {
                nm.notify(KEEP_ALIVE_NOTIF_ID, notif);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        activeInstance = this;
        isListenerConnected = true;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;
        if (pkg.contains("msgguard") || pkg.equals("android") || pkg.equals("com.android.systemui")) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        Parcelable[] msgBundles = getParcelableArraySafe(extras, Notification.EXTRA_MESSAGES);
        boolean hasMsgBundles = msgBundles != null && msgBundles.length > 0;

        String appName = resolveSupportedAppName(pkg);
        if (appName == null) {
            if (Notification.CATEGORY_MESSAGE.equals(notification.category) || hasMsgBundles) {
                appName = pkg;
            } else {
                return;
            }
        }

        CharSequence rawTitleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (rawTitleCs == null) {
            rawTitleCs = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
        }
        String rawTitle = rawTitleCs != null ? rawTitleCs.toString().trim() : "";

        CharSequence convTitleCs = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        String convTitle = (convTitleCs != null && !convTitleCs.toString().trim().isEmpty())
            ? convTitleCs.toString().trim()
            : rawTitle;

        CharSequence bigTextCs = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subTextCs = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        String text = (bigTextCs != null && !bigTextCs.toString().trim().isEmpty())
            ? bigTextCs.toString().trim()
            : (textCs != null && !textCs.toString().trim().isEmpty()
                ? textCs.toString().trim()
                : (subTextCs != null ? subTextCs.toString().trim() : ""));

        // Hỗ trợ InboxStyle (Notification.EXTRA_TEXT_LINES) khi Zalo/Messenger gộp nhiều tin nhắn
        if (text.isEmpty() || isSummaryCountNoise(text)) {
            CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (textLines != null && textLines.length > 0) {
                CharSequence lastLine = textLines[textLines.length - 1];
                if (lastLine != null && !lastLine.toString().trim().isEmpty()) {
                    text = lastLine.toString().trim();
                }
            }
        }
        if (text.isEmpty() && notification.tickerText != null) {
            text = notification.tickerText.toString().trim();
        }

        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && !hasMsgBundles) {
            if (text.isEmpty() || isSummaryCountNoise(text)) return;
        }
        if (convTitle.isEmpty() && text.isEmpty() && !hasMsgBundles) return;
        if (isIgnoredSystemNoise(rawTitle, text)) return;

        String sender = !rawTitle.isEmpty() ? rawTitle : convTitle;
        String sbnKey = sbn.getKey() != null ? sbn.getKey() : (pkg + ":" + convTitle);
        long fallbackTime = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();

        // NÂNG CẤP 1: Trích xuất Avatar người gửi + Ảnh đính kèm từ cả EXTRA_PICTURE lẫn EXTRA_MESSAGES URI
        String avatarBase64 = extractAvatarBase64(notification, extras);
        String attachedPicBase64 = extractPictureBase64(extras);

        MessageDatabase db = MessageDatabase.getInstance(getApplicationContext());

        // NÂNG CẤP 2: Phân tích toàn bộ mảng EXTRA_MESSAGES (MessagingStyle) để bắt chuỗi tin nhắn liên tiếp & diff tin bị gỡ
        if (hasMsgBundles) {
            List<String> currentBundleTexts = new ArrayList<>();
            List<String> previousBundleTexts = bundleHistoryMap.get(sbnKey);
            boolean handledAnyBundle = false;

            for (int i = 0; i < msgBundles.length; i++) {
                if (!(msgBundles[i] instanceof Bundle)) continue;
                Bundle mb = (Bundle) msgBundles[i];
                CharSequence mTextCs = mb.getCharSequence("text");
                String mSender = extractSenderFromMessageBundle(mb, sender);
                long mTime = mb.getLong("time", fallbackTime);
                if (mTime <= 0) mTime = fallbackTime;

                String mText = mTextCs != null ? mTextCs.toString().trim() : "";
                if (isIgnoredSystemNoise(mSender, mText)) continue;

                // Kiểm tra xem message bundle này có chứa URI hình ảnh/sticker của Messenger không
                String uriImageB64 = extractUriImageFromBundle(mb);
                if (uriImageB64 != null && attachedPicBase64 == null) {
                    attachedPicBase64 = uriImageB64;
                }

                String effectiveConvTitle = !convTitle.isEmpty() ? convTitle : (!mSender.isEmpty() ? mSender : "Không rõ");

                if (isUnsendNotificationText(mText)) {
                    handledAnyBundle = true;
                    JSONObject unsentMsg = null;
                    if (previousBundleTexts != null && i < previousBundleTexts.size()) {
                        String oldContentAtPos = previousBundleTexts.get(i);
                        if (!oldContentAtPos.isEmpty() && !isUnsendNotificationText(oldContentAtPos)) {
                            unsentMsg = db.markSpecificContentAsUnsent(pkg, effectiveConvTitle, oldContentAtPos, "BUNDLE_DIFF_RECALL");
                        }
                    }
                    if (unsentMsg == null) {
                        unsentMsg = db.markLatestMessageAsUnsent(pkg, effectiveConvTitle, sbnKey, "TEXT_RECALL");
                    }
                    if (unsentMsg != null) {
                        sendUnsentAlertNotification(unsentMsg);
                        broadcastUpdate("UNSENT_DETECTED", unsentMsg);
                    }
                } else {
                    String contentToSave = !mText.isEmpty()
                        ? mText
                        : (uriImageB64 != null ? "📷 [Hình ảnh / Sticker đính kèm]" : "");
                    if (!contentToSave.isEmpty()) {
                        handledAnyBundle = true;
                        currentBundleTexts.add(contentToSave);
                        boolean isVip = isVipSender(mSender, effectiveConvTitle);
                        JSONObject saved = db.insertMessage(
                            sbnKey,
                            pkg,
                            appName,
                            effectiveConvTitle,
                            mSender.isEmpty() ? effectiveConvTitle : mSender,
                            contentToSave,
                            uriImageB64 != null ? uriImageB64 : (i == msgBundles.length - 1 ? attachedPicBase64 : null),
                            avatarBase64,
                            mTime,
                            isVip
                        );
                        if (saved != null) {
                            broadcastUpdate("NEW_MESSAGE", saved);
                        }
                    }
                }
            }

            // So sánh diff:
            // 1) Nếu cùng số lượng dòng trong bundle nhưng nội dung tại vị trí j bị thay đổi -> Phát hiện CHỈNH SỬA TIN NHẮN (Edited)
            if (previousBundleTexts != null && !currentBundleTexts.isEmpty() && currentBundleTexts.size() == previousBundleTexts.size()) {
                for (int j = 0; j < previousBundleTexts.size(); j++) {
                    String oldText = previousBundleTexts.get(j);
                    String newText = currentBundleTexts.get(j);
                    if (!oldText.equals(newText)
                        && !currentBundleTexts.contains(oldText)
                        && !previousBundleTexts.contains(newText)
                        && !isUnsendNotificationText(oldText)
                        && !isUnsendNotificationText(newText)) {
                        JSONObject editedMsg = db.markMessageAsEdited(pkg, convTitle, oldText, newText);
                        if (editedMsg != null) {
                            sendUnsentAlertNotification(editedMsg);
                            broadcastUpdate("UNSENT_DETECTED", editedMsg);
                        }
                    }
                }
            }
            // 2) Nếu lần trước có câu A, câu B mà lần cập nhật này câu A biến mất khỏi cụm chưa đọc -> Phát hiện GỠ TIN NHẮN (Unsent)
            else if (previousBundleTexts != null && !currentBundleTexts.isEmpty() && currentBundleTexts.size() < previousBundleTexts.size()) {
                for (String oldText : previousBundleTexts) {
                    if (!currentBundleTexts.contains(oldText) && !isUnsendNotificationText(oldText)) {
                        JSONObject diffUnsent = db.markSpecificContentAsUnsent(pkg, convTitle, oldText, "BUNDLE_DIFF_RECALL");
                        if (diffUnsent != null) {
                            sendUnsentAlertNotification(diffUnsent);
                            broadcastUpdate("UNSENT_DETECTED", diffUnsent);
                        }
                    }
                }
            }

            if (!currentBundleTexts.isEmpty()) {
                bundleHistoryMap.put(sbnKey, currentBundleTexts);
            }

            // Chỉ return nếu EXTRA_MESSAGES đã thực sự xử lý được nội dung và EXTRA_TEXT không phải là câu báo thu hồi mới
            if (handledAnyBundle && !isUnsendNotificationText(text)) {
                return;
            }
        }

        // Xử lý thông báo đơn tiêu chuẩn (khi không có mảng EXTRA_MESSAGES)
        if (isUnsendNotificationText(text)) {
            JSONObject unsentMsg = db.markLatestMessageAsUnsent(
                pkg,
                convTitle,
                sbnKey,
                "TEXT_RECALL"
            );
            if (unsentMsg != null) {
                sendUnsentAlertNotification(unsentMsg);
                broadcastUpdate("UNSENT_DETECTED", unsentMsg);
            }
            return;
        }

        String finalContent;
        if (!text.isEmpty()) {
            finalContent = text;
        } else if (attachedPicBase64 != null) {
            finalContent = "📷 [Hình ảnh đính kèm]";
        } else {
            return;
        }

        long ts = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        boolean isVip = isVipSender(sender, convTitle);
        JSONObject saved = db.insertMessage(
            sbnKey,
            pkg,
            appName,
            convTitle.isEmpty() ? "Không rõ" : convTitle,
            sender.isEmpty() ? "Không rõ" : sender,
            finalContent,
            attachedPicBase64,
            avatarBase64,
            ts,
            isVip
        );

        if (saved != null) {
            broadcastUpdate("NEW_MESSAGE", saved);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || resolveSupportedAppName(pkg) == null) return;

        String sbnKey = sbn.getKey();
        if (sbnKey != null) {
            bundleHistoryMap.remove(sbnKey);
        }

        if (reason == REASON_APP_CANCEL || reason == REASON_GROUP_SUMMARY_CANCELED) {
            Bundle extras = sbn.getNotification() != null ? sbn.getNotification().extras : null;
            String convTitle = null;
            if (extras != null) {
                CharSequence c1 = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
                CharSequence c2 = extras.getCharSequence(Notification.EXTRA_TITLE);
                if (c1 != null && !c1.toString().trim().isEmpty()) {
                    convTitle = c1.toString().trim();
                } else if (c2 != null && !c2.toString().trim().isEmpty()) {
                    convTitle = c2.toString().trim();
                }
            }

            MessageDatabase db = MessageDatabase.getInstance(getApplicationContext());
            JSONObject unsentMsg = db.markLatestMessageAsUnsent(
                pkg,
                convTitle,
                sbnKey,
                "APP_CANCEL_RETRACT"
            );
            if (unsentMsg != null) {
                sendUnsentAlertNotification(unsentMsg);
                broadcastUpdate("UNSENT_DETECTED", unsentMsg);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private String extractSenderFromMessageBundle(Bundle mb, String fallbackSender) {
        try {
            CharSequence mSenderCs = mb.getCharSequence("sender");
            if (mSenderCs != null && !mSenderCs.toString().trim().isEmpty()) {
                return mSenderCs.toString().trim();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Object personObj = mb.get("sender_person");
                if (personObj instanceof Person) {
                    CharSequence pName = ((Person) personObj).getName();
                    if (pName != null && !pName.toString().trim().isEmpty()) {
                        return pName.toString().trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallbackSender != null ? fallbackSender : "";
    }

    private boolean isSummaryCountNoise(String text) {
        if (text == null) return true;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if (lower.matches("^\\d+\\s+(tin nhắn mới|tin nhắn|new messages|messages|cuộc trò chuyện|conversations).*$")) {
            return true;
        }
        return false;
    }

    private boolean isUnsendNotificationText(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String p : UNSEND_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    private boolean isIgnoredSystemNoise(String title, String text) {
        String combined = (title + " " + text).toLowerCase(Locale.ROOT);
        for (String p : IGNORE_PATTERNS) {
            if (combined.contains(p)) return true;
        }
        return false;
    }

    private boolean isVipSender(String sender, String convTitle) {
        try {
            SharedPreferences prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE);
            String rawVip = prefs.getString("vip_watchlist", "");
            if (rawVip == null || rawVip.trim().isEmpty()) return false;
            String combined = ((sender != null ? sender : "") + " " + (convTitle != null ? convTitle : "")).toLowerCase(Locale.ROOT);
            String[] keywords = rawVip.split(",");
            for (String kw : keywords) {
                String clean = kw.trim().toLowerCase(Locale.ROOT);
                if (!clean.isEmpty() && combined.contains(clean)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private Parcelable[] getParcelableArraySafe(Bundle extras, String key) {
        try {
            return extras.getParcelableArray(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Đọc trực tiếp ảnh/sticker từ URI của MessagingStyle (content://...)
     */
    @SuppressWarnings("deprecation")
    private String extractUriImageFromBundle(Bundle msgBundle) {
        InputStream is = null;
        try {
            String mimeType = msgBundle.getString("type");
            Object uriObj = msgBundle.get("uri");
            if (!(uriObj instanceof Uri)) return null;
            if (mimeType != null && !mimeType.startsWith("image/")) return null;

            Uri uri = (Uri) uriObj;
            is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) return null;
            return bitmapToBase64(bmp, 600, 75);
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("deprecation")
    private String extractPictureBase64(Bundle extras) {
        try {
            Object obj = extras.get(Notification.EXTRA_PICTURE);
            if (!(obj instanceof Bitmap)) return null;
            return bitmapToBase64((Bitmap) obj, 600, 75);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String extractAvatarBase64(Notification notification, Bundle extras) {
        try {
            Bitmap bmp = null;
            Object largeObj = extras.get(Notification.EXTRA_LARGE_ICON);
            if (largeObj instanceof Bitmap) {
                bmp = (Bitmap) largeObj;
            } else if (largeObj instanceof Icon) {
                Drawable d = ((Icon) largeObj).loadDrawable(this);
                bmp = drawableToBitmap(d);
            }
            if (bmp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Icon icon = notification.getLargeIcon();
                if (icon != null) {
                    Drawable d = icon.loadDrawable(this);
                    bmp = drawableToBitmap(d);
                }
            }
            if (bmp == null) return null;
            return bitmapToBase64(bmp, 96, 75);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int w = Math.max(drawable.getIntrinsicWidth(), 64);
        int h = Math.max(drawable.getIntrinsicHeight(), 64);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private String bitmapToBase64(Bitmap bmp, int maxDim, int quality) {
        float ratio = Math.min(Math.min((float) maxDim / bmp.getWidth(), (float) maxDim / bmp.getHeight()), 1f);
        Bitmap scaled = ratio < 1f
            ? Bitmap.createScaledBitmap(bmp, Math.max(1, (int)(bmp.getWidth() * ratio)), Math.max(1, (int)(bmp.getHeight() * ratio)), true)
            : bmp;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
        String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        return "data:image/jpeg;base64," + b64;
    }

    private void sendUnsentAlertNotification(JSONObject msg) {
        SharedPreferences prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("notify_on_unsend", true)) return;

        long id = msg.optLong("id", 1L);
        String sender = msg.optString("sender", "Ai đó");
        String appName = msg.optString("appName", "Messenger");
        String content = msg.optString("content", "");
        String originalContent = msg.optString("originalContent", "");
        String reason = msg.optString("unsentReason", "");
        boolean isEdited = "MESSAGE_EDITED".equals(reason) && !originalContent.isEmpty();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this,
            (int) id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String titleText = isEdited
            ? "✏️ " + sender + " vừa sửa 1 tin nhắn (" + appName + ")"
            : "🚨 " + sender + " vừa gỡ 1 tin nhắn (" + appName + ")";
        String shortText = isEdited
            ? "Câu gốc: \"" + originalContent + "\" ➔ \"" + content + "\""
            : "Nội dung gốc: \"" + content + "\"";
        String bigText = isEdited
            ? "Nội dung gốc ban đầu:\n\"" + originalContent + "\"\n\nĐã sửa thành:\n\"" + content + "\"\n\nBấm để xem chi tiết trong MsgGuard PRO."
            : "Nội dung gốc trước khi gỡ:\n\"" + content + "\"\n\nBấm để xem toàn bộ lịch sử trong MsgGuard PRO.";

        Notification alert = new Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titleText)
            .setContentText(shortText)
            .setStyle(new Notification.BigTextStyle().bigText(bigText))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(("unsend_" + id).hashCode(), alert);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "Cảnh báo tin nhắn bị gỡ",
                NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Thông báo ngay khi phát hiện ai đó thu hồi tin nhắn trên Messenger");
            nm.createNotificationChannel(alertChannel);

            NotificationChannel keepAliveChannel = new NotificationChannel(
                KEEP_ALIVE_CHANNEL_ID,
                "Giữ dịch vụ chạy ngầm 24/7",
                NotificationManager.IMPORTANCE_LOW
            );
            keepAliveChannel.setDescription("Giúp Xiaomi/Samsung không tự tắt dịch vụ bắt thông báo khi khóa màn hình");
            keepAliveChannel.setSound(null, null);
            keepAliveChannel.enableVibration(false);
            nm.createNotificationChannel(keepAliveChannel);
        }
    }

    private void broadcastUpdate(String eventType, JSONObject msg) {
        Intent intent = new Intent(ACTION_MESSAGE_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra("event_type", eventType);
        intent.putExtra("message_json", msg.toString());
        sendBroadcast(intent);
    }
}
