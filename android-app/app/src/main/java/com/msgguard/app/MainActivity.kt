package com.msgguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var db: MessageDatabase

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val eventType = intent?.getStringExtra("event_type") ?: return
            val msgJson = intent.getStringExtra("message_json") ?: return
            runOnUiThread {
                val safeJson = JSONObject.quote(msgJson)
                webView.evaluateJavascript("window.onNativeMessageEvent && window.onNativeMessageEvent('$eventType', $safeJson);", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = MessageDatabase.getInstance(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        requestPostNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MessengerNotificationService.ACTION_MESSAGE_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }
        // Cập nhật trạng thái quyền khi người dùng quay lại từ màn hình Cài đặt
        webView.evaluateJavascript("window.refreshPermissionState && window.refreshPermissionState();", null)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(messageReceiver)
        } catch (_: Exception) {
        }
    }

    private fun requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, MessengerNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(cn.flattenToString())
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun getPermissionStatus(): String {
            return JSONObject().apply {
                put("notificationListener", isNotificationListenerEnabled())
                put("batteryUnrestricted", isBatteryOptimizationIgnored())
                put("isNativeAndroid", true)
            }.toString()
        }

        @JavascriptInterface
        fun openNotificationListenerSettings() {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        @JavascriptInterface
        fun requestIgnoreBatteryOptimization() {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
            }
        }

        @JavascriptInterface
        fun getMessages(onlyUnsent: Boolean, search: String): String {
            return db.getMessages(onlyUnsent = onlyUnsent, search = search).toString()
        }

        @JavascriptInterface
        fun clearAllMessages() {
            db.deleteAllMessages()
        }

        @JavascriptInterface
        fun getSettings(): String {
            val prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE)
            return JSONObject().apply {
                put("notifyOnUnsend", prefs.getBoolean("notify_on_unsend", true))
                put("pcSyncUrl", prefs.getString("pc_sync_url", "") ?: "")
            }.toString()
        }

        @JavascriptInterface
        fun saveSettings(notifyOnUnsend: Boolean, pcSyncUrl: String) {
            val prefs = getSharedPreferences("msgguard_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("notify_on_unsend", notifyOnUnsend)
                .putString("pc_sync_url", pcSyncUrl.trim())
                .apply()
        }

        @JavascriptInterface
        fun simulateTestMessage(appPkg: String, sender: String, content: String): String {
            val appName = MessengerNotificationService.SUPPORTED_PACKAGES[appPkg] ?: "Messenger"
            val msg = db.insertMessage(
                sbnKey = "sim|$appPkg|$sender",
                packageName = appPkg,
                appName = appName,
                conversationTitle = sender,
                sender = sender,
                content = content,
                imageBase64 = null,
                avatarBase64 = null,
                timestamp = System.currentTimeMillis()
            )
            return msg?.toJson()?.toString() ?: "{}"
        }

        @JavascriptInterface
        fun simulateTestUnsend(appPkg: String, sender: String): String {
            val msg = db.markLatestMessageAsUnsent(
                packageName = appPkg,
                conversationTitle = sender,
                sbnKey = "sim|$appPkg|$sender",
                reason = "TEXT_RECALL"
            )
            return msg?.toJson()?.toString() ?: "{}"
        }
    }
}
