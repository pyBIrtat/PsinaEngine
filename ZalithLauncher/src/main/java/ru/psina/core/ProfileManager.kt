package ru.psina.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Реестр файлов мобильного профиля: instances/<mc>/.psina-profile-<clientId>.json
 * (путь + sha256 + время установки). Даёт точечную проверку: повторное нажатие
 * «Играть» докачивает только реально изменённые/битые файлы, а не всё заново.
 */
object ProfileManager {

    data class Entry(val path: String, val sha256: String, val size: Long, val installedAt: Long)

    private fun ledgerFile(clientId: String, mc: String): File =
        File(Paths.instanceDir(mc), ".psina-profile-$clientId.json")

    fun record(clientId: String, mc: String, files: List<File>) {
        val base = Paths.instanceDir(mc)
        val arr = JSONArray()
        files.forEach { f ->
            if (!f.isFile) return@forEach
            arr.put(JSONObject().apply {
                put("path", f.relativeTo(base).path)
                put("sha256", Sha256.of(f))
                put("size", f.length())
                put("installedAt", System.currentTimeMillis())
            })
        }
        ledgerFile(clientId, mc).writeText(arr.toString())
    }

    private fun read(clientId: String, mc: String): List<Entry> {
        val f = ledgerFile(clientId, mc)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getString("path"), o.getString("sha256"), o.optLong("size"), o.optLong("installedAt"))
            }
        } catch (e: Exception) {
            Logx.e("профиль битый у $clientId/$mc", e)
            emptyList()
        }
    }

    data class VerifyResult(val ok: List<String>, val missing: List<String>, val corrupt: List<String>) {
        val needsReinstall: Boolean get() = missing.isNotEmpty() || corrupt.isNotEmpty()
    }

    /** Точечная проверка: повторная установка не перекачивает всё. */
    fun verify(clientId: String, mc: String): VerifyResult {
        val base = Paths.instanceDir(mc)
        val ok = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        read(clientId, mc).forEach { e ->
            val f = File(base, e.path)
            when {
                !f.exists() -> missing += e.path
                Sha256.of(f) != e.sha256 -> corrupt += e.path
                else -> ok += e.path
            }
        }
        return VerifyResult(ok, missing, corrupt)
    }

    fun deleteProfile(clientId: String, mc: String) {
        Paths.instanceDir(mc).deleteRecursively()
        Store.uninstall(clientId, mc)
    }

    fun clearTempFiles() {
        Paths.downloads.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        File(Paths.cacheDir, "tmp").deleteRecursively()
    }
}
