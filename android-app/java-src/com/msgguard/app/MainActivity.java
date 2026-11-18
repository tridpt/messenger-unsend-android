package com.msgguard.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView webView;
    private MessageDatabase db;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            final String eventType = intent.getStringExtra("event_type");
            final String msgJson = intent.getStringExtra("message_json");
            if (eventType == null || msgJson == null) return;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String safeJson = JSONObject.quote(msgJson);
                    webView.evaluateJavascript(
                        "window.onNativeMessageEvent && window.onNativeMessageEvent('" + eventType + "', " + safeJson + ");",
                        null
                    );
                }
            });
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = MessageDatabase.getInstance(this);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        registerMessageReceiverSafe();
        requestPostNotificationPermissionIfNeeded();
        if (isNotificationListenerEnabled()) {
            MessengerNotificationService.ensureServiceBound(this);
        }
    }

    private void registerMessageReceiverSafe() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(MessengerNotificationService.ACTION_MESSAGE_EVENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(messageReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerMessageReceiverSafe();
        if (isNotificationListenerEnabled()) {
            MessengerNotificationService.ensureServiceBound(this);
        }
        webView.evaluateJavascript(
            "(function(){ if(window.refreshPermissionState) window.refreshPermissionState(); if(window.loadMessages) window.loadMessages(); })();",
            null
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try {
                unregisterReceiver(messageReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }

    private void requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private boolean isNotificationListenerEnabled() {
        ComponentName cn = new ComponentName(this, MessengerNotificationService.class);
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private boolean isBatteryOptimizationIgnored() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    public class AndroidBridge {

        @JavascriptInterface
        public String getPermissionStatus() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("notificationListener", isNotificationListenerEnabled());
                obj.put("listenerConnected", MessengerNotificationService.isListenerConnected);
                obj.put("activeNotifCount", MessengerNotificationService.lastActiveScanCount);
                obj.put("batteryUnrestricted", isBatteryOptimizationIgnored());
                obj.put("isNativeAndroid", true);
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public boolean forceRebindListener() {
            try {
                MessengerNotificationService.forceRebindService(MainActivity.this);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openNotificationListenerSettings() {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        }

        @JavascriptInterface
        public void openAppDetailsSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimization() {
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                openAppDetailsSettings();
            }
        }

        @JavascriptInterface
        public String getMessages(boolean onlyUnsent, String search) {
            MessengerNotificationService.scanActiveNotificationsStatic();
            return db.getMessages(onlyUnsent, search, 400).toString();
        }

        @JavascriptInterface
        public boolean deleteMessage(long id) {
            return db.deleteMessageById(id);
        }

        @JavascriptInterface
        public boolean toggleMessageUnsent(long id) {
            return db.toggleMessageUnsentById(id);
        }

        @JavascriptInterface
        public boolean toggleMessageStar(long id) {
            return db.toggleMessageStarById(id);
        }

        @JavascriptInterface
        public int cleanNormalMessages(int daysOld) {
            return db.cleanOldNormalMessages(daysOld);
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("MsgGuard", text != null ? text : ""));
            }
        }

        @JavascriptInterface
        public void clearAllMessages() {
            db.deleteAllMessages();
        }

        @JavascriptInterface
        public String getSettings() {
            try {
                SharedPreferences prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE);
                JSONObject obj = new JSONObject();
                obj.put("notifyOnUnsend", prefs.getBoolean("notify_on_unsend", true));
                obj.put("keepAliveNotif", prefs.getBoolean("keep_alive_notif", true));
                obj.put("pinCode", prefs.getString("pin_code", ""));
                obj.put("vipWatchlist", prefs.getString("vip_watchlist", ""));
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveSettings(boolean notifyOnUnsend, boolean keepAliveNotif, String pinCode) {
            saveSettingsV3(notifyOnUnsend, keepAliveNotif, pinCode, "");
        }

        @JavascriptInterface
        public void saveSettingsV3(boolean notifyOnUnsend, boolean keepAliveNotif, String pinCode, String vipWatchlist) {
            SharedPreferences prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putBoolean("notify_on_unsend", notifyOnUnsend)
                .putBoolean("keep_alive_notif", keepAliveNotif)
                .putString("pin_code", pinCode != null ? pinCode.trim() : "")
                .putString("vip_watchlist", vipWatchlist != null ? vipWatchlist.trim() : "")
                .apply();

            try {
                Intent serviceIntent = new Intent(MainActivity.this, MessengerNotificationService.class);
                startService(serviceIntent);
            } catch (Exception ignored) {
            }
        }
    }
}
