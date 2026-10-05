package ru.psina.core

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Modrinth API v2 без ключей: поиск модов и подбор файла под версию MC. */
object Modrinth {

    private const val API = "https://api.modrinth.com/v2"

    data class ModHit(
        val slug: String,
        val title: String,
        val description: String,
        val downloads: Long,
        val iconUrl: String?,
        val versions: List<String>
    )

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Modrinth HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    fun search(query: String, mc: String, limit: Int = 20): List<ModHit> {
        val facets = """[["project_type:mod"],["categories:fabric"],["versions:$mc"]]"""
        val url = "$API/search?query=${enc(query)}&limit=$limit&facets=${enc(facets)}"
        val root = JSONObject(get(url))
        val hits = root.optJSONArray("hits") ?: JSONArray()
        return (0 until hits.length()).mapNotNull { i ->
            val o = hits.optJSONObject(i) ?: return@mapNotNull null
            ModHit(
                slug = o.optString("slug"),
                title = o.optString("title"),
                description = o.optString("description"),
                downloads = o.optLong("downloads"),
                iconUrl = o.optString("icon_url").ifBlank { null },
                versions = o.optJSONArray("versions")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList()
            )
        }
    }

    /** Прямая ссылка на подходящий .jar последней версии проекта для mc. */
    fun latestFile(slugOrId: String, mc: String): String? {
        val loaders = """["fabric","quilt"]"""
        val versions = """["$mc"]"""
        val url = "$API/project/${enc(slugOrId)}/version?loaders=${enc(loaders)}&game_versions=${enc(versions)}"
        val arr = JSONArray(get(url))
        if (arr.length() == 0) return null
        val v = arr.optJSONObject(0) ?: return null
        val files = v.optJSONArray("files") ?: return null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (f.optBoolean("primary", false)) return f.optString("url")
        }
        return files.optJSONObject(0)?.optString("url")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
