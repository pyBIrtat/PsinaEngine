package ru.psina.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Сеть: OkHttp + фолбэк по зеркалам.
 *
 * raw.githubusercontent.com бывает заблокирован провайдером в РФ, а CDN
 * jsdelivr и релизы GitHub — нет. Поэтому каждый GET пробует список URL
 * по очереди, пока один не ответит 200.
 */
object Net {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val dlClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // долгие загрузки без таймаута чтения
        .build()

    const val UA = "psina-mobile/1.0 (Android)"

    /** Простой токен отмены — передаётся в download(), проверяется в цикле копирования. */
    class CancelToken { @Volatile var cancelled = false }

    class DownloadCancelledException : IOException("Загрузка отменена пользователем")

    /** HEAD-запрос ради Content-Length — нужен ДО скачивания, для проверки места на телефоне. */
    fun contentLength(url: String): Long? = try {
        val req = Request.Builder().url(url).head().header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() else null
        }
    } catch (e: Exception) {
        Logx.i("HEAD $url -> ${e.message}")
        null
    }

    /** Сумма размеров — неизвестные (null) считаются нулём, поэтому оценка не занижена по известным. */
    fun estimateTotalMb(urls: List<String>): Long =
        urls.sumOf { contentLength(it) ?: 0L } / (1024 * 1024)

    /** Возвращает (url, тело) первого успешного зеркала. */
    fun getText(mirrors: List<String>): Pair<String, String> {
        var last: Exception? = null
        for (u in mirrors) {
            try {
                val req = Request.Builder().url(u).header("User-Agent", UA).build()
                dlClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            Logx.i("GET ok: $u (${body.length} симв.)")
                            return u to body
                        }
                    } else {
                        Logx.i("GET $u -> HTTP ${r.code}")
                    }
                }
            } catch (e: Exception) {
                last = e
                Logx.i("GET $u -> ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IOException("Все зеркала недоступны: ${last?.message ?: "нет ответа"}")
    }

    /** Зеркала для файла манифеста: jsdelivr, raw github, releases/latest. */
    fun manifestMirrors(path: String = "launcher-online.json"): List<String> {
        val owner = "pyBIrtat"
        val repo = "PsinaLauncher"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$owner/$repo@main/$path",
            "https://raw.githubusercontent.com/$owner/$repo/main/$path",
            "https://github.com/$owner/$repo/releases/latest/download/$path"
        )
    }

    /**
     * Скачивание файла с прогрессом, докачкой (.part + Range) и отменой.
     * Уже существующий файл с совпадающим sha256 не перекачивается.
     */
    fun download(
        url: String,
        to: File,
        sha256: String? = null,
        cancel: CancelToken? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File {
        to.parentFile?.mkdirs()
        if (to.exists() && sha256 != null && Sha256.of(to).equals(sha256, ignoreCase = true)) {
            Logx.i("кэш: ${to.name} уже корректен")
            onProgress?.invoke(to.length(), to.length())
            return to
        }

        val part = File(to.parentFile, to.name + ".part")
        // ДОКАЧКА: если частичный файл уже есть — просим сервер отдать «хвост» через Range.
        val resumeFrom = if (part.exists()) part.length() else 0L
        val reqBuilder = Request.Builder().url(url).header("User-Agent", UA)
        if (resumeFrom > 0) reqBuilder.header("Range", "bytes=$resumeFrom-")
        dlClient.newCall(reqBuilder.build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 206) throw IOException("HTTP ${r.code} для $url")
            val resumed = r.code == 206
            val append = resumed && resumeFrom > 0
            if (!append && part.exists()) part.delete()
            val total = (r.body?.contentLength() ?: -1L).let { if (append && it > 0) it + resumeFrom else it }
            java.io.FileOutputStream(part, append).use { out ->
                r.body!!.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = if (append) resumeFrom else 0L
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        if (cancel?.cancelled == true) throw DownloadCancelledException()
                        out.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
        }

        if (sha256 != null) {
            val got = Sha256.of(part)
            if (!got.equals(sha256, ignoreCase = true)) {
                part.delete() // повреждённый файл НЕ остаётся на диске
                throw IOException("sha256 не совпал для ${to.name}: ожидался $sha256, получен $got")
            }
        }
        to.delete()
        if (!part.renameTo(to)) {
            part.copyTo(to, overwrite = true)
            part.delete()
        }
        return to
    }

    fun isOnline(): Boolean = try {
        getText(manifestMirrors("launcher.version")).second.isNotBlank()
    } catch (e: Exception) {
        false
    }
}

/** SHA-256 файла/строки. */
object Sha256 {
    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun of(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
