package com.xiaozhi.musicserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.FileInputStream
import java.net.URLDecoder
import java.security.MessageDigest

class MusicService : Service() {
    private var server: HttpServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val port = prefs.getInt("port", 8787)
        val key = prefs.getString("key", "") ?: ""

        val chId = "music_server"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(chId, "Music server", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle("Xiaozhi Music Server")
            .setContentText("Dang chay tren cong $port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaozhi:music").apply { acquire() }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xiaozhi:music").apply { acquire() }

        server?.stop()
        server = HttpServer(port, key, Music(cacheDir.resolve("tracks"), key)).also {
            try { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); running = true }
            catch (e: Exception) { Log.e("MusicService", "cannot start", e); lastError = e.message; stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        server?.stop(); server = null
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var lastError: String? = null
    }
}

private class HttpServer(port: Int, private val key: String, private val music: Music) : NanoHTTPD(port) {
    private fun json(status: Response.IStatus, body: Map<String, String>) =
        newFixedLengthResponse(status, "application/json", JSONObject(body).toString())

    private fun err(code: Int, msg: String): Response {
        val st = Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.INTERNAL_ERROR
        return json(st, mapOf("error" to msg))
    }

    private fun constEq(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    override fun serve(session: IHTTPSession): Response {
        val p = session.parms
        val header = session.headers["x-api-key"] ?: ""
        try {
            val uri = session.uri
            return when {
                uri == "/music/search" -> {
                    if (key.isNotEmpty() && !constEq(header, key)) return err(401, "bad api key")
                    val q = p["q"] ?: return err(400, "missing q")
                    json(Response.Status.OK, music.search(q.take(200), p["d"] ?: ""))
                }
                uri == "/music/next" -> {
                    if (key.isNotEmpty() && !constEq(header, key)) return err(401, "bad api key")
                    val id = p["id"] ?: ""
                    if (!music.validId(id)) return err(400, "bad id")
                    json(Response.Status.OK, music.next(id, p["d"] ?: ""))
                }
                uri.startsWith("/music/stream/") -> {
                    val id = URLDecoder.decode(uri.removePrefix("/music/stream/"), "UTF-8")
                    if (!music.validId(id)) return err(400, "bad id")
                    if (key.isNotEmpty() && !(constEq(p["k"] ?: "", music.sig(id)) || constEq(header, key)))
                        return err(401, "bad signature")
                    val f = music.file(id)
                    if (!f.exists()) return err(404, "not ready")
                    f.setLastModified(System.currentTimeMillis())
                    newFixedLengthResponse(Response.Status.OK, "audio/ogg", FileInputStream(f), f.length())
                }
                else -> err(404, "not found")
            }
        } catch (e: MusicException) {
            return err(e.status, e.message ?: "error")
        } catch (e: Exception) {
            Log.e("HttpServer", "failed", e)
            return err(500, e.message?.take(120) ?: "error")
        }
    }
}
