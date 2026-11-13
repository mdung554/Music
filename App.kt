package com.xiaozhi.musicserver

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Giai nen python + yt-dlp + ffmpeg lan dau mat vai giay; lam ngoai luong chinh.
        thread(name = "ytdlp-init") {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                ready = true
                Log.i("App", "yt-dlp ready")
            } catch (e: Exception) {
                initError = e.message
                Log.e("App", "init failed", e)
            }
        }
    }

    companion object {
        @Volatile var ready = false
        @Volatile var initError: String? = null
    }
}
