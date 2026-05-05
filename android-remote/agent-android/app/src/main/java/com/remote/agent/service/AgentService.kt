package com.remote.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.remote.agent.MainActivity
import com.remote.agent.R
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
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
    private var screenStreamJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDpi = 320

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

        // 根据是否有 projection 数据决定 foreground service 类型
        val projResultCode = intent.getIntExtra("projectionResultCode", -1)
        @Suppress("DEPRECATION")
        val projData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("projectionData", Intent::class.java)
        } else {
            intent.getParcelableExtra("projectionData")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val svcType = if (projResultCode != -1 && projData != null) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(1, buildNotification("连接中..."), svcType)
        } else {
            startForeground(1, buildNotification("连接中..."))
        }

        // 初始化 MediaProjection
        if (projResultCode != -1 && projData != null) {
            setupScreenCapture(projResultCode, projData)
        }

        isRunning = true
        connect()
        startHeartbeat()
        return START_STICKY
    }

    private fun setupScreenCapture(resultCode: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDpi = metrics.densityDpi

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RemoteAgentScreen",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            Log.i(TAG, "MediaProjection 屏幕捕获已初始化 (${screenWidth}x${screenHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 初始化失败: ${e.message}")
            mediaProjection = null
            virtualDisplay = null
            imageReader = null
        }
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
                "screen_stream_start" -> {
                    val interval = (msg["interval"] as? Double)?.toLong() ?: 1500
                    startScreenStream(clientId ?: "", interval)
                }
                "screen_stream_stop" -> {
                    stopScreenStream()
                }
                "swipe" -> {
                    val x1 = (msg["x1"] as? Double)?.toInt() ?: 0
                    val y1 = (msg["y1"] as? Double)?.toInt() ?: 0
                    val x2 = (msg["x2"] as? Double)?.toInt() ?: 0
                    val y2 = (msg["y2"] as? Double)?.toInt() ?: 0
                    val duration = (msg["duration"] as? Double)?.toInt() ?: 300
                    execShell("input swipe $x1 $y1 $x2 $y2 $duration")
                }
                "key_event" -> {
                    val keyCode = (msg["keyCode"] as? Double)?.toInt() ?: return
                    execShell("input keyevent $keyCode")
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

    private fun startScreenStream(clientId: String, interval: Long) {
        stopScreenStream()
        screenStreamJob = scope.launch {
            // 第一帧先测试，失败则反馈错误并停止
            val firstFrame = takeScreenshot()
            if (firstFrame.startsWith("ERROR")) {
                send(mapOf("type" to "screen_frame_error", "error" to firstFrame, "clientId" to clientId))
                return@launch
            }
            send(mapOf("type" to "screen_frame", "image" to firstFrame, "clientId" to clientId))
            delay(interval)

            while (isActive) {
                val base64 = takeScreenshot()
                if (!base64.startsWith("ERROR")) {
                    send(mapOf("type" to "screen_frame", "image" to base64, "clientId" to clientId))
                }
                delay(interval)
            }
        }
    }

    private fun stopScreenStream() {
        screenStreamJob?.cancel()
        screenStreamJob = null
    }

    private fun takeScreenshot(): String {
        // 优先使用 MediaProjection
        val projResult = captureFromProjection()
        if (projResult != null) return projResult
        // 回退到 screencap
        return takeScreenshotFallback()
    }

    private fun captureFromProjection(): String? {
        val reader = imageReader ?: return null
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bmpWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            } else bitmap

            val baos = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
            finalBitmap.recycle()
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 截图失败: ${e.message}")
            return null
        } finally {
            image.close()
        }
    }

    private fun takeScreenshotFallback(): String {
        return try {
            val rawPath = "${cacheDir.absolutePath}/screenshot.png"
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $rawPath"))
            proc.waitFor()
            val file = File(rawPath)
            if (!file.exists() || file.length() == 0L) {
                return "ERROR: 截图失败。请重新启动 Agent 并允许屏幕录制权限"
            }
            val bitmap = BitmapFactory.decodeFile(rawPath)
            file.delete()
            if (bitmap == null) return "ERROR: 无法解码截图文件"
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
            bitmap.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
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
        stopScreenStream()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        scope.cancel()
        ws?.close(1000, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
