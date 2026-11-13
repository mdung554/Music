package com.xiaozhi.musicserver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import com.yausername.youtubedl_android.YoutubeDL
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress ?: "?"
    } catch (e: Exception) { "?" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun add(v: android.view.View) = root.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply { textSize = 16f }
        add(status)

        val port = EditText(this).apply {
            hint = "Cong (mac dinh 8787)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", 8787).toString())
        }
        val key = EditText(this).apply { hint = "API key (de trong = khong khoa)"; setText(prefs.getString("key", "")) }
        add(port); add(key)

        fun save() = prefs.edit().putInt("port", port.text.toString().toIntOrNull() ?: 8787)
            .putString("key", key.text.toString().trim()).apply()

        add(Button(this).apply {
            text = "Bat server"
            setOnClickListener { save(); startForegroundService(Intent(this@MainActivity, MusicService::class.java)) }
        })
        add(Button(this).apply {
            text = "Tat server"
            setOnClickListener { startService(Intent(this@MainActivity, MusicService::class.java).setAction(MusicService.ACTION_STOP)) }
        })
        add(Button(this).apply {
            text = "Cap nhat yt-dlp"
            setOnClickListener {
                Toast.makeText(context, "Dang cap nhat...", Toast.LENGTH_SHORT).show()
                thread {
                    val msg = try {
                        YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                        "Da cap nhat"
                    } catch (e: Exception) { "Loi: ${e.message}" }
                    ui.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                }
            }
        })
        add(Button(this).apply {
            text = "Tat tiet kiem pin cho app nay"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refresh() {
        val port = getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("port", 8787)
        status.text = buildString {
            append(if (MusicService.running) "Server: DANG CHAY\n" else "Server: dang tat\n")
            append("yt-dlp: ").append(if (App.ready) "san sang" else (App.initError ?: "dang khoi dong...")).append('\n')
            MusicService.lastError?.let { append("Loi: $it\n") }
            append("\nDia chi dien vao robot:\nhttp://${localIp()}:$port")
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
