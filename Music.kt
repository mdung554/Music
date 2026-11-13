package com.xiaozhi.musicserver

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MusicException(val status: Int, message: String) : Exception(message)

data class Meta(val id: String, val title: String, val artist: String)

/** Tim bai bang yt-dlp, tai ve, doi sang Ogg/Opus mono 24 kHz (dung dinh dang firmware phat duoc). */
class Music(private val cacheDir: File, private val apiKey: String) {
    private val idRe = Regex("^[A-Za-z0-9_-]{11}$")
    private val sep = "|||"
    private val metaCache = ConcurrentHashMap<String, Meta>()
    private val history = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val locks = ConcurrentHashMap<String, Any>()

    init { cacheDir.mkdirs() }

    fun validId(id: String) = idRe.matches(id)

    fun sig(id: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA256"))
        return mac.doFinal(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    fun streamPath(id: String) = "/music/stream/$id" + if (apiKey.isNotEmpty()) "?k=${sig(id)}" else ""

    fun file(id: String) = File(cacheDir, "$id.opus")

    private fun list(url: String, extra: List<String> = emptyList()): List<Meta> {
        if (!App.ready) throw MusicException(503, App.initError ?: "yt-dlp is still starting")
        val req = YoutubeDLRequest(url)
        req.addOption("--flat-playlist")
        req.addOption("--no-warnings")
        req.addOption("--print", "%(id)s$sep%(title)s$sep%(channel)s")
        var i = 0
        while (i < extra.size) { req.addOption(extra[i], extra[i + 1]); i += 2 }
        val out = try {
            YoutubeDL.getInstance().execute(req).out
        } catch (e: Exception) {
            throw MusicException(502, "search failed: ${e.message?.take(120)}")
        }
        return out.lineSequence().mapNotNull { line ->
            val p = line.split(sep)
            if (p.size >= 2 && validId(p[0])) {
                Meta(p[0], p[1], p.getOrNull(2)?.takeIf { it != "NA" } ?: "")
            } else null
        }.toList().onEach { metaCache[it.id] = it }
    }

    /** Tai + chuyen dinh dang neu chua co. Duoc goi truoc khi tra loi de /stream phat ngay. */
    private fun ensureFile(id: String) {
        val out = file(id)
        if (out.exists() && out.length() > 0) return
        synchronized(locks.getOrPut(id) { Any() }) {
            if (out.exists() && out.length() > 0) return
            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            req.addOption("--match-filter", "duration<1200")           // bo qua video > 20 phut
            // AAC (m4a) luon buoc phai ma hoa lai sang Opus nen -ac 1 / -ar 24000 chac chan duoc ap dung.
            req.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            req.addOption("-x")
            req.addOption("--audio-format", "opus")
            req.addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 24000 -b:a 48k -application audio")
            req.addOption("-o", File(cacheDir, "$id.%(ext)s").absolutePath)
            try {
                YoutubeDL.getInstance().execute(req)
            } catch (e: Exception) {
                throw MusicException(502, "cannot resolve track: ${e.message?.take(120)}")
            }
            if (!out.exists() || out.length() == 0L) throw MusicException(502, "track unavailable or too long")
            trimCache(keep = id)
        }
    }

    private fun trimCache(keep: String) {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".opus") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(6).filter { it.name != "$keep.opus" }.forEach { it.delete() }
    }

    private fun track(m: Meta, device: String): Map<String, String> {
        ensureFile(m.id)
        history.getOrPut(device) { ArrayDeque() }.also { h -> h.addLast(m.id); while (h.size > 50) h.removeFirst() }
        return mapOf("id" to m.id, "title" to m.title, "artist" to m.artist, "url" to streamPath(m.id))
    }

    fun search(q: String, device: String): Map<String, String> {
        val first = list("ytsearch5:$q").firstOrNull() ?: throw MusicException(404, "no results")
        return track(first, device)
    }

    fun next(id: String, device: String): Map<String, String> {
        val seen = (history[device]?.toSet() ?: emptySet()) + id
        var cands = try {
            list("https://www.youtube.com/watch?v=$id&list=RD$id", listOf("--playlist-end", "25"))
        } catch (e: MusicException) { emptyList() }
        if (cands.none { it.id !in seen }) {
            val m = metaCache[id]
            val query = if (m != null) "${m.artist} ${m.title} songs" else throw MusicException(404, "no related track")
            cands = list("ytsearch10:$query")
        }
        val pick = cands.firstOrNull { it.id !in seen } ?: throw MusicException(404, "no new related track")
        return track(pick, device)
    }
}
