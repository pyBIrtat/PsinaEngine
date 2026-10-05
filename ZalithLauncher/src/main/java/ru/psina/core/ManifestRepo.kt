package ru.psina.core

import org.json.JSONArray
import org.json.JSONObject

/** Разбор манифеста launcher-online.json / launcher.json. */
object ManifestRepo {

    data class Extra(
        val name: String,
        val url: String,
        val sha256: String?
    )

    data class Client(
        val id: String,
        val name: String,
        val mc: String,
        val jar: String,
        val sha256: String?,
        val requires: List<String> = emptyList(),
        val extra: List<Extra> = emptyList(),
        val portable: String? = null,
        val zip: String? = null,
        val logo: String = "",
        /** Опциональный блок `android` — как ставить этого клиента на телефон. */
        val android: AndroidSpec? = null
    ) {
        val isPortable: Boolean get() = !portable.isNullOrBlank()

        /** Что показываем в списке: уровень поддержки на телефоне. */
        val support: Support get() = AndroidCompat.specOf(this).status

        val playableOnPhone: Boolean get() = AndroidCompat.specOf(this).isPlayable
    }

    data class Manifest(
        val versions: List<String>,
        val clients: List<Client>
    ) {
        fun byVersion(): Map<String, List<Client>> =
            versions.associateWith { v -> clients.filter { it.mc == v } }
    }

    fun parse(json: String): Manifest {
        val root = JSONObject(json)
        val versions = root.optJSONArray("versions")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val arr = root.optJSONArray("clients") ?: JSONArray()
        val clients = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val mc = o.optString("mc")
            val extras = o.optJSONArray("extra")?.let { e ->
                (0 until e.length()).mapNotNull { k ->
                    val eo = e.optJSONObject(k) ?: return@mapNotNull null
                    val n = eo.optString("name")
                    val u = eo.optString("url")
                    if (n.isBlank() || u.isBlank()) null
                    else Extra(n, u, eo.optString("sha256").ifBlank { null })
                }
            } ?: emptyList()

            val req = o.optJSONArray("requires")?.let { r ->
                (0 until r.length()).map { r.getString(it) }
            } ?: emptyList()

            Client(
                id = id,
                name = o.optString("name", id),
                mc = mc,
                jar = o.optString("jar"),
                sha256 = o.optString("sha256").ifBlank { null },
                requires = req,
                extra = extras,
                portable = o.optString("portable").ifBlank { null },
                zip = o.optString("zip").ifBlank { null },
                // логотипы лежат как clients/<mc>/<id>.png, поэтому по умолчанию берём id
                logo = o.optString("logo").ifBlank { id },
                android = parseAndroid(o.optJSONObject("android"))
            )
        }

        // Фолбэк: если версии не перечислены — собираем из клиентов по порядку.
        val vers = versions.ifEmpty { clients.map { it.mc }.distinct() }
        return Manifest(vers, clients)
    }

    /**
     * Блок `android` в клиенте. ПК-лаунчер его игнорирует, поэтому поле
     * необязательное и полностью обратно совместимое.
     */
    private fun parseAndroid(o: JSONObject?): AndroidSpec? {
        if (o == null) return null
        val status = when (o.optString("status").lowercase()) {
            "ok", "ready" -> Support.READY
            "experimental" -> Support.EXPERIMENTAL
            "pc", "pc_only" -> Support.PC_ONLY
            else -> Support.EXPERIMENTAL
        }
        val mods = o.optJSONArray("mods")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val eo = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = eo.optString("name")
                val url = eo.optString("url")
                if (name.isBlank() || url.isBlank()) null
                else Extra(name, url, eo.optString("sha256").ifBlank { null })
            }
        } ?: emptyList()
        return AndroidSpec(
            status = status,
            mods = mods,
            extraRemove = strList(o, "extraRemove"),
            modsFromZip = strList(o, "modsFromZip"),
            libsFromZip = strList(o, "libsFromZip"),
            modsExclude = strList(o, "modsExclude"),
            stripNatives = o.optBoolean("stripNatives", false),
            jvmArgs = strList(o, "jvmArgs"),
            mainClass = o.optString("mainClass").ifBlank { null },
            notes = o.optString("notes"),
            // требования к телефону — все необязательные, старые манифесты парсятся как раньше
            minAndroidApi = o.optInt("minAndroidApi", 0),
            architecture = o.optString("architecture"),
            requiredMemoryMb = o.optLong("requiredMemoryMb", 0L),
            requiredEngine = o.optString("requiredEngine").ifBlank { null },
            estimatedSizeMb = o.optLong("estimatedSizeMb", 0L)
        )
    }

    private fun strList(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
}
