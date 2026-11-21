package com.jarvis.film

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object VideoTools {

    private fun perm(ctx: Context, video: Boolean): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) {
            if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private fun latest(ctx: Context, base: Uri, idCol: String, dateCol: String, n: Int): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val cur = ctx.contentResolver.query(base, arrayOf(idCol), null, null, "$dateCol DESC")
            cur?.use {
                while (out.size < n && it.moveToNext()) out.add(ContentUris.withAppendedId(base, it.getLong(0)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun latestVideos(ctx: Context, n: Int): List<Uri> = latest(
        ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, n
    )

    private fun latestAudios(ctx: Context, n: Int): List<Uri> = latest(
        ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_ADDED, n
    )

    private fun open(ctx: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        return ex
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun rotation(ctx: Context, uri: Uri): Int {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            val v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r.release()
            v
        } catch (e: Exception) {
            0
        }
    }

    /** Parçadaki örnekleri muxer'a yazar. Son örneğin zamanını (us) döner. */
    private fun copyTrack(ex: MediaExtractor, track: Int, muxer: MediaMuxer, outTrack: Int, offsetUs: Long, limitUs: Long): Long {
        ex.selectTrack(track)
        ex.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var last = 0L
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val t = ex.sampleTime
            if (t > limitUs) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = t + offsetUs
            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(outTrack, buf, info)
            last = t
            ex.advance()
        }
        ex.unselectTrack(track)
        return last
    }

    private fun save(ctx: Context, f: File): Boolean {
        return try {
            val name = "Jarvis_" + System.currentTimeMillis() + ".mp4"
            val v = ContentValues()
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Jarvis")
                v.put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(f).use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val u = ContentValues()
                u.put(MediaStore.Video.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, u, null, null)
            }
            f.delete()
            true
        } catch (e: Exception) {
            Bus.log("Video kaydedilemedi: ${e.message}")
            false
        }
    }

    fun trim(ctx: Context, secs: Int): String {
        if (!perm(ctx, true)) return "Video izni yok efendim."
        val uri = latestVideos(ctx, 1).firstOrNull() ?: return "Videonuzu bulamadım efendim."
        val out = File(ctx.cacheDir, "jarvis_out.mp4")
        var ex: MediaExtractor? = null
        var mux: MediaMuxer? = null
        var started = false
        try {
            val e = open(ctx, uri)
            ex = e
            val vi = findTrack(e, "video/")
            if (vi < 0) return "Bu dosyada video parçası yok efendim."
            val ai = findTrack(e, "audio/")
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            m.setOrientationHint(rotation(ctx, uri))
            val vOut = m.addTrack(e.getTrackFormat(vi))
            val aOut = if (ai >= 0) m.addTrack(e.getTrackFormat(ai)) else -1
            m.start()
            started = true
            val limit = secs * 1000000L
            copyTrack(e, vi, m, vOut, 0L, limit)
            if (aOut >= 0) copyTrack(e, ai, m, aOut, 0L, limit)
            m.stop()
            started = false
        } catch (e: Exception) {
            Bus.log("Kırpma hatası: ${e.message}")
            return "Kırpamadım efendim."
        } finally {
            try { ex?.release() } catch (e: Exception) {}
            try { if (started) mux?.stop() } catch (e: Exception) {}
            try { mux?.release() } catch (e: Exception) {}
        }
        return if (save(ctx, out)) "Video ilk $secs saniyeye kırpıldı ve Filmler Jarvis klasörüne kaydedildi efendim."
        else "Kaydedemedim efendim."
    }

    fun merge(ctx: Context, count: Int): String {
        if (!perm(ctx, true)) return "Video izni yok efendim."
        val uris = latestVideos(ctx, count).reversed()
        if (uris.size < 2) return "Birleştirecek yeterli video bulamadım efendim."
        val out = File(ctx.cacheDir, "jarvis_out.mp4")
        val exs = ArrayList<MediaExtractor>()
        var mux: MediaMuxer? = null
        var started = false
        try {
            for (u in uris) exs.add(open(ctx, u))
            val v0 = findTrack(exs[0], "video/")
            if (v0 < 0) return "Video parçası bulunamadı efendim."
            val vf = exs[0].getTrackFormat(v0)
            val a0 = findTrack(exs[0], "audio/")
            for (ex in exs) {
                val vi = findTrack(ex, "video/")
                if (vi < 0) return "Videolardan biri geçersiz efendim."
                val f = ex.getTrackFormat(vi)
                val same = f.getString(MediaFormat.KEY_MIME) == vf.getString(MediaFormat.KEY_MIME) &&
                    f.getInteger(MediaFormat.KEY_WIDTH) == vf.getInteger(MediaFormat.KEY_WIDTH) &&
                    f.getInteger(MediaFormat.KEY_HEIGHT) == vf.getInteger(MediaFormat.KEY_HEIGHT)
                if (!same) return "Videoların biçimi veya çözünürlüğü farklı, birleştiremem efendim."
                if ((findTrack(ex, "audio/") >= 0) != (a0 >= 0)) return "Bazı videolarda ses yok, birleştiremem efendim."
            }
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            m.setOrientationHint(rotation(ctx, uris[0]))
            val vOut = m.addTrack(vf)
            val aOut = if (a0 >= 0) m.addTrack(exs[0].getTrackFormat(a0)) else -1
            m.start()
            started = true
            var offset = 0L
            for (ex in exs) {
                val vLast = copyTrack(ex, findTrack(ex, "video/"), m, vOut, offset, Long.MAX_VALUE)
                var aLast = 0L
                if (aOut >= 0) aLast = copyTrack(ex, findTrack(ex, "audio/"), m, aOut, offset, Long.MAX_VALUE)
                offset += maxOf(vLast, aLast) + 33000L
            }
            m.stop()
            started = false
        } catch (e: Exception) {
            Bus.log("Birleştirme hatası: ${e.message}")
            return "Birleştiremedim efendim."
        } finally {
            for (ex in exs) { try { ex.release() } catch (e: Exception) {} }
            try { if (started) mux?.stop() } catch (e: Exception) {}
            try { mux?.release() } catch (e: Exception) {}
        }
        return if (save(ctx, out)) "${uris.size} video birleştirildi ve Filmler Jarvis klasörüne kaydedildi efendim."
        else "Kaydedemedim efendim."
    }

    fun addMusic(ctx: Context): String {
        if (!perm(ctx, true) || !perm(ctx, false)) return "Video veya müzik izni yok efendim."
        val vUri = latestVideos(ctx, 1).firstOrNull() ?: return "Videonuzu bulamadım efendim."
        var mex: MediaExtractor? = null
        var mi = -1
        for (u in latestAudios(ctx, 12)) {
            val cand = try { open(ctx, u) } catch (e: Exception) { null }
            if (cand == null) continue
            var found = -1
            for (i in 0 until cand.trackCount) {
                if (cand.getTrackFormat(i).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") { found = i; break }
            }
            if (found >= 0) { mex = cand; mi = found; break }
            cand.release()
        }
        if (mex == null) return "AAC biçiminde müzik bulamadım efendim. Telefonda m4a türünde bir müzik dosyası olmalı."
        val out = File(ctx.cacheDir, "jarvis_out.mp4")
        var vex: MediaExtractor? = null
        var mux: MediaMuxer? = null
        var started = false
        try {
            val v = open(ctx, vUri)
            vex = v
            val vi = findTrack(v, "video/")
            if (vi < 0) return "Bu dosyada video parçası yok efendim."
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            m.setOrientationHint(rotation(ctx, vUri))
            val vOut = m.addTrack(v.getTrackFormat(vi))
            val aOut = m.addTrack(mex.getTrackFormat(mi))
            m.start()
            started = true
            val vLast = copyTrack(v, vi, m, vOut, 0L, Long.MAX_VALUE)
            copyTrack(mex, mi, m, aOut, 0L, vLast)
            m.stop()
            started = false
        } catch (e: Exception) {
            Bus.log("Müzik ekleme hatası: ${e.message}")
            return "Müzik ekleyemedim efendim."
        } finally {
            try { vex?.release() } catch (e: Exception) {}
            try { mex.release() } catch (e: Exception) {}
            try { if (started) mux?.stop() } catch (e: Exception) {}
            try { mux?.release() } catch (e: Exception) {}
        }
        return if (save(ctx, out)) "Müzik eklendi ve video Filmler Jarvis klasörüne kaydedildi efendim."
        else "Kaydedemedim efendim."
    }
}
