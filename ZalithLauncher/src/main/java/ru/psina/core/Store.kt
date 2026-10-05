package ru.psina.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Хранилище: манифест в кэше, список клиентов, состояние установки,
 * сервера, раскладки кнопок. Всё в private storage приложения.
 */
object Store {

    private lateinit var ctx: Context

    fun init(c: Context) {
        ctx = c.applicationContext
    }

    // ---------- манифест ----------

    private val manifestFile get() = File(Paths.root, "manifest.json")

    @Volatile
    var manifest: ManifestRepo.Manifest? = null
        private set

    /** Читает манифест: сеть (с зеркалами) -> кэш на диске -> встроенный минимальный. */
    fun loadManifest(force: Boolean = false): ManifestRepo.Manifest {
        if (!force) manifest?.let { return it }

        try {
            // пользовательский URL — первым, затем зеркала
            val mirrors = (listOf(Prefs.manifestUrl) + Net.manifestMirrors()).distinct()
            val (url, body) = Net.getText(mirrors)
            manifestFile.writeText(body)
            val m = ManifestRepo.parse(body)
            manifest = m
            Logx.i("манифест: ${m.clients.size} клиентов, версии ${m.versions} из $url")
            return m
        } catch (e: Exception) {
            Logx.e("манифест из сети не получен", e)
        }

        if (manifestFile.exists()) {
            try {
                val m = ManifestRepo.parse(manifestFile.readText())
                manifest = m
                Logx.i("манифест из кэша: ${m.clients.size} клиентов")
                return m
            } catch (e: Exception) {
                Logx.e("кэш манифеста битый", e)
            }
        }

        val m = ManifestRepo.parse(BUILTIN_MANIFEST)
        manifest = m
        Logx.i("манифест встроенный: ${m.clients.size} клиентов")
        return m
    }

    // ---------- установка клиентов ----------

    data class Installed(
        val id: String,
        val mc: String,
        val jar: String?,
        val files: Int,
        val bytes: Long,
        val at: Long,
        /** Уровень поддержки, с которым клиент ставился (чтобы UI не гадал). */
        val status: String = ""
    )

    private val installedFile get() = File(Paths.root, "installed.json")

    private fun readInstalled(): MutableMap<String, Installed> {
        val out = mutableMapOf<String, Installed>()
        if (!installedFile.exists()) return out
        try {
            val arr = JSONArray(installedFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                out[id] = Installed(
                    id, o.optString("mc"), o.optString("jar").ifBlank { null },
                    o.optInt("files"), o.optLong("bytes"), o.optLong("at"),
                    o.optString("status")
                )
            }
        } catch (e: Exception) {
            Logx.e("installed.json битый", e)
        }
        return out
    }

    private fun writeInstalled(map: Map<String, Installed>) {
        val arr = JSONArray()
        map.values.forEach { v ->
            arr.put(JSONObject().apply {
                put("id", v.id); put("mc", v.mc); put("jar", v.jar ?: "")
                put("files", v.files); put("bytes", v.bytes); put("at", v.at)
                put("status", v.status)
            })
        }
        installedFile.writeText(arr.toString())
    }

    fun isInstalled(id: String): Boolean = readInstalled().containsKey(id)

    fun installedInfo(id: String): Installed? = readInstalled()[id]

    fun markInstalled(id: String, mc: String, jar: String?, status: Support = Support.READY) {
        val map = readInstalled()
        val dir = Paths.instanceDir(mc)
        val count = dir.walkTopDown().count { it.isFile }
        map[id] = Installed(id, mc, jar, count, Paths.sizeOf(dir), System.currentTimeMillis(), status.name)
        writeInstalled(map)
    }

    fun uninstall(id: String, mc: String) {
        val map = readInstalled()
        map.remove(id)
        writeInstalled(map)
        Paths.instanceDir(mc).deleteRecursively()
    }

    // ---------- сервера ----------

    data class Server(val id: String, val name: String, val host: String, val port: Int) {
        val address: String get() = if (port == 25565) host else "$host:$port"
    }

    private val serversFile get() = File(Paths.root, "servers.json")

    fun servers(): MutableList<Server> {
        val out = mutableListOf<Server>()
        if (!serversFile.exists()) return defaults()
        try {
            val arr = JSONArray(serversFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Server(o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("port", 25565)))
            }
        } catch (e: Exception) {
            Logx.e("servers.json битый", e)
        }
        return if (out.isEmpty()) defaults() else out
    }

    private fun defaults(): MutableList<Server> = mutableListOf(
        Server("bravo", "Bravohvh", "redirect.bravohvh.fun", 25565),
        Server("svin", "SvinWorld", "mc.svinworld.space", 25565),
        Server("svinfun", "SvinWorld Fun", "mc.svinworld.fun", 25565)
    )

    fun saveServers(list: List<Server>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("host", s.host); put("port", s.port)
            })
        }
        serversFile.writeText(arr.toString())
    }

    // ---------- раскладки кнопок ----------

    fun layoutsDir(): File = File(Paths.root, "layouts").apply { mkdirs() }

    fun layoutFile(id: String): File = File(layoutsDir(), Paths.sanitize(id) + ".json")

    fun layoutIds(): List<String> =
        layoutsDir().listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted()
            ?: emptyList()

    fun readLayout(id: String): String? =
        layoutFile(id).takeIf { it.exists() }?.readText()

    fun writeLayout(id: String, json: String) {
        layoutFile(id).writeText(json)
    }

    fun deleteLayout(id: String) {
        layoutFile(id).delete()
    }

    /**
     * Минимальный встроенный манифест — чтобы приложение было осмысленным
     * без сети при первом запуске. Полный список подтянется из сети/кэша.
     */
    const val BUILTIN_MANIFEST = """
{
  "versions": ["1.21.4", "1.21.11", "26.2"],
  "clients": [
    {"id": "rockstar", "name": "Rockstar SunShine", "mc": "1.21.11", "jar": "", "logo": "rockstar"},
    {"id": "liquidbounce", "name": "LiquidBounce", "mc": "1.21.11", "jar": "", "logo": "liquidbounce"},
    {"id": "nursultan", "name": "Nursultan", "mc": "1.21.11", "jar": "", "portable": "nursultan", "logo": "nursultan"},
    {"id": "dimasik", "name": "Dimasik", "mc": "26.2", "jar": "", "logo": "dimasik"},
    {"id": "liquidbounce26", "name": "LiquidBounce 26.2", "mc": "26.2", "jar": "", "logo": "liquidbounce26"}
  ]
}
"""
}
