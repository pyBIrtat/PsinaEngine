package ru.psina.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Установка клиента на телефон.
 *
 * Три случая:
 *  1) обычный Fabric-клиент — скачиваем jar (+extra) в mods;
 *  2) портативка с блоком `android` — качаем её zip и вытаскиваем из него
 *     ТОЛЬКО мод-файлы (modsFromZip) и мод-ссылки (mods), минус modsExclude;
 *  3) всё остальное — блокируем на уровне UI (Только ПК).
 *
 * Плюс всегда: extra из манифеста, requires через Modrinth, fabric-api.
 */
object Installer {

    data class Progress(val stage: String, val percent: Int, val detail: String = "")

    /** Итог установки — что реально легло в инстанс. */
    data class Result(
        val clientId: String,
        val mc: String,
        val files: List<File>,
        val bytes: Long
    )

    fun install(
        client: ManifestRepo.Client,
        onProgress: (Progress) -> Unit
    ): Result {
        val spec = AndroidCompat.specOf(client)
        val mods = Paths.modsDir(client.mc)
        val installed = mutableListOf<File>()
        var total = 0L

        if (spec.status == Support.PC_ONLY) {
            throw IllegalStateException(
                "«${client.name}» помечен как «Только ПК»: " +
                    (spec.notes.ifBlank { "на телефоне его рантайм не работает" })
            )
        }

        // 1) основной клиент
        if (spec.status == Support.EXPERIMENTAL && client.isPortable) {
            // Клиент со своим mainClass — НЕ мод: кладём в корень инстанса,
            // он станет version-jar производной версии (см. ZalithBackend).
            // Портативке он нужен даже тогда, когда из её zip мы ничего
            // не берём (как у Нурсултана: клиентский jar отдаётся отдельной
            // ссылкой, а весь остальной пакет — Windows-рантайм).
            if (AndroidCompat.needsOwnMain(spec) && client.jar.isNotBlank()) {
                val dst = File(Paths.instanceDir(client.mc), fileNameOf(client.jar))
                onProgress(Progress("Скачиваем ${client.name}", 5))
                Net.download(client.jar, dst, client.sha256) { done, len ->
                    val pct = if (len > 0) (done * 60 / len).toInt() else 0
                    onProgress(Progress("Скачиваем ${client.name}", 5 + pct, "${done / 1048576} МБ"))
                }
                stripIfNeeded(spec, dst)
                installed.add(dst); total += dst.length()
            }
            // Из zip портативки берём только перечисленное в манифесте.
            // Нечего брать (моды отдаются прямыми ссылками / это ownMain) —
            // НЕ качаем сотни мегабайт зря.
            if (spec.modsFromZip.isNotEmpty() || spec.libsFromZip.isNotEmpty()) {
                total += installFromPortableZip(client, spec, mods, installed, onProgress)
            } else {
                Logx.i("портативка ${client.id}: из zip ничего не нужно — пропускаю скачивание пакета")
            }
        } else {
            if (client.jar.isBlank()) throw IllegalStateException("в манифесте нет ссылки на jar для ${client.id}")
            onProgress(Progress("Скачиваем ${client.name}", 5))
            // Клиент со своим mainClass — НЕ мод: кладём в корень инстанса,
            // он станет version-jar производной версии (см. ZalithBackend).
            val dst = if (AndroidCompat.needsOwnMain(spec)) {
                File(Paths.instanceDir(client.mc), fileNameOf(client.jar))
            } else {
                File(mods, fileNameOf(client.jar))
            }
            Net.download(client.jar, dst, client.sha256) { done, len ->
                val pct = if (len > 0) (done * 60 / len).toInt() else 0
                onProgress(Progress("Скачиваем ${client.name}", 5 + pct, "${done / 1048576} МБ"))
            }
            stripIfNeeded(spec, dst)
            installed.add(dst); total += dst.length()
        }

        // 1б) мод-файлы клиента, отданные прямыми ссылками (маленькие, без zip портативки)
        spec.mods.forEachIndexed { i, m ->
            onProgress(Progress("Мод клиента ${m.name}", 62 + i))
            val dst = File(mods, m.name)
            Net.download(m.url, dst, m.sha256)
            stripIfNeeded(spec, dst)
            installed.add(dst); total += dst.length()
        }

        // 2) дополнительные моды клиента из манифеста (минус то, что просил выкинуть блок android)
        val extras = client.extra.filterNot { e ->
            spec.extraRemove.any { token -> e.name.contains(token, ignoreCase = true) }
        }
        client.extra.filterNot { it in extras }.forEach {
            Logx.i("пропуск на телефоне: extra ${it.name}")
        }
        extras.forEachIndexed { i, e ->
            val base = 65 + (i * 20 / extras.size.coerceAtLeast(1))
            onProgress(Progress("Доп. мод ${e.name}", base))
            val dst = File(mods, e.name)
            Net.download(e.url, dst, e.sha256) { done, len ->
                val pct = if (len > 0) (done * 18 / len).toInt() else 0
                onProgress(Progress("Доп. мод ${e.name}", base + pct))
            }
            stripIfNeeded(spec, dst)
            installed.add(dst); total += dst.length()
        }

        // 3) инфраструктурные моды по requires (Modrinth)
        client.requires.forEachIndexed { i, project ->
            onProgress(Progress("Инфра-мод $project", 88 + i * 4))
            try {
                val url = Modrinth.latestFile(project, client.mc)
                if (url == null) {
                    Logx.i("Modrinth: $project для ${client.mc} не найден — пропуск")
                } else {
                    val dst = File(mods, fileNameOf(url))
                    if (!dst.exists()) {
                        Net.download(url, dst, null)
                        installed.add(dst); total += dst.length()
                    }
                }
            } catch (e: Exception) {
                Logx.e("не удалось добавить $project", e)
            }
        }

        // 4) базовые моды, без которых Fabric-клиент не стартует
        ensureFabricApi(client.mc, mods, installed)

        onProgress(Progress("Готово", 100))
        Store.markInstalled(
            client.id, client.mc,
            if (client.isPortable) null else installed.firstOrNull()?.name,
            spec.status
        )
        return Result(client.id, client.mc, installed, total)
    }

    /**
     * Портативка на телефон: качаем её zip и распаковываем только нужные моды.
     * Сам Windows-рантайм (jre/natives/скрипты) не трогаем — игру запустит движок.
     */
    private fun installFromPortableZip(
        client: ManifestRepo.Client,
        spec: AndroidSpec,
        mods: File,
        out: MutableList<File>,
        onProgress: (Progress) -> Unit
    ): Long {
        val zipUrl = client.zip
            ?: throw IllegalStateException("у портативки ${client.id} нет zip-ссылки в манифесте")
        val zip = File(Paths.downloads, "${client.id}.zip")

        onProgress(Progress("Скачиваем пакет ${client.name}", 8, "нужен один раз"))
        // GitHub-релизы на мобильном интернете обрываются: докачка (.part + Range)
        // уже есть в Net, добавляем до 3 попыток — хвост продолжится с места обрыва.
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                Net.download(zipUrl, zip, null) { done, len ->
                    val pct = if (len > 0) (done * 50 / len).toInt() else 0
                    onProgress(Progress("Скачиваем пакет ${client.name}", 8 + pct, "${done / 1048576} МБ"))
                }
                lastErr = null
                break
            } catch (e: Exception) {
                lastErr = e
                Logx.e("скачивание пакета ${client.id}: попытка $attempt не удалась", e)
            }
        }
        lastErr?.let { throw it }

        val wantedMods = spec.modsFromZip.map { it.replace('\\', '/') }.toSet()
        val wantedLibs = spec.libsFromZip.map { it.replace('\\', '/') }.toSet()
        val excluded = spec.modsExclude.map { it.replace('\\', '/') }.toSet()
        val libs = File(Paths.instanceDir(client.mc), "libraries")
        var total = 0L
        var done = 0

        onProgress(Progress("Распаковываем моды", 60))
        ZipFile(zip).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                // Точное совпадение пути из манифеста; фолбэк — суффикс «/путь»
                // (папка-обёртка в zip) или совпадение по имени файла.
                fun wanted(paths: Set<String>): Boolean {
                    if (name in paths) return true
                    if (paths.any { p -> name.endsWith("/$p") }) return true
                    // имя файла без пути в манифесте — сравниваем по базовому имени
                    if (paths.any { p -> !p.contains('/') && name.substringAfterLast('/') == p }) return true
                    return false
                }
                val isMod = wanted(wantedMods)
                val isLib = wanted(wantedLibs)
                if (!isMod && !isLib) continue
                if (excluded.any { name.contains(it) }) {
                    Logx.i("пропуск на телефоне: $name")
                    continue
                }
                // compat-хелперы кладём в libraries/ — они не моды, но нужны в classpath
                val dst = File(if (isLib) libs else mods, name.substringAfterLast('/'))
                dst.parentFile?.mkdirs()
                z.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                stripIfNeeded(spec, dst)
                if (isMod) out.add(dst)
                total += dst.length()
                done++
                onProgress(Progress("Распаковываем моды", 60 + done * 5, dst.name))
            }
        }
        if (done == 0 && wantedMods.isNotEmpty()) {
            throw IllegalStateException(
                "в пакете ${client.id} не нашлось файлов из modsFromZip — проверь блок android в манифесте. " +
                    "Искали: ${wantedMods.joinToString(", ")}. " +
                    "В пакете есть: " + ZipFile(zip).use { z ->
                        z.entries().asSequence().map { it.name }.take(15).toList().joinToString(", ")
                    }
            )
        }
        Logx.i("портативка ${client.id}: распаковано $done файлов")
        return total
    }

    /** Вырезаем Windows-нативы и подписи, если блок android так просит. */
    private fun stripIfNeeded(spec: AndroidSpec, jar: File) {
        if (!spec.stripNatives) return
        try {
            val n = JarRewriter.stripNatives(jar)
            if (n > 0) Logx.i("${jar.name}: вырезано десктопных файлов — $n")
        } catch (e: Exception) {
            Logx.e("не удалось почистить ${jar.name}", e)
        }
    }

    /** fabric-api обязателен для любого Fabric-клиента — ставим, если его нет. */
    private fun ensureFabricApi(mc: String, mods: File, out: MutableList<File>) {
        val has = mods.listFiles()?.any { it.name.contains("fabric-api") } == true
        if (has) return
        try {
            val url = Modrinth.latestFile("fabric-api", mc) ?: return
            val dst = File(mods, fileNameOf(url))
            Net.download(url, dst, null)
            out.add(dst)
            Logx.i("fabric-api добавлен: ${dst.name}")
        } catch (e: Exception) {
            Logx.e("fabric-api не добавлен", e)
        }
    }

    fun fileNameOf(url: String): String {
        var s = url.substringBefore('#').substringBefore('?')
        s = s.substringAfterLast('/')
        s = try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) { s }
        s = s.replace(Regex("[^A-Za-z0-9._+\\-]"), "_")
        return s.ifBlank { "file.jar" }
    }
}