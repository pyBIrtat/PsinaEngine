package ru.psina.core

import android.content.Context
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountType
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.activities.runGame
import java.io.File

/**
 * Родной запуск внутри Psina Engine (этап E3 форка).
 *
 * Psina-ядро готовит инстанс (Paths.modsDir(mc)), а играем мы им не через
 * внешние интенты, а напрямую движком этого APK: подбираем установленную
 * версию (само имя mc или "psina-<mc>"), синхронизируем моды инстанса в
 * mods-папку версии и вызываем runGame() — он стартует GameService и
 * открывает VMActivity с LaunchConfig(version, account), ровно как это
 * делает собственный UI ZalithLauncher2.
 *
 * Отличия от внешних движков (Engine.requestLaunch):
 *  - нет best-effort extras: это наш процесс, контракт настоящий;
 *  - ник становится локальным офлайн-профиле (accessToken "0");
 *  - RAM из настроек Псины записывается в конфиг psina-версии
 *    (VersionConfig.ramAllocation) — у чужих версий свои настройки не трогаем.
 */
object ZalithBackend {

    /** Префикс имени версии, в которую Psina ставит клиент для mc. */
    const val VERSION_PREFIX = "psina-"

    sealed class NativeOutcome {
        /** runGame() выполнен: сервис запущен, активити с игрой открыто. */
        object RequestSent : NativeOutcome()

        /** В форке нет установленной версии для mc — нужно поставить (E4). */
        data class VersionMissing(val mc: String) : NativeOutcome()

        data class Failed(val reason: String) : NativeOutcome()
    }

    /** Имена версий-кандидатов для mc: psina-<mc>, затем само <mc>. */
    fun candidates(mc: String): List<String> = listOf("$VERSION_PREFIX$mc", mc)

    /** Производная версия ownMain-клиента: psina-<mc>-<clientId>. */
    fun derivedVersionName(mc: String, clientId: String): String = "$VERSION_PREFIX$mc-$clientId"

    /**
     * Валидна ли базовая версия для mc: папка есть, json читается, jar на месте
     * (или играется без него — некоторые версии только json). Битые
     * полу-установленные папки без json/jar валидными НЕ считаются — иначе
     * сломанная установка навсегда блокирует и авто-установку, и переустановку.
     */
    fun isBaseVersionValid(mc: String): Boolean {
        val v = VersionsManager.versions.value.firstOrNull { it.getVersionName() == "$VERSION_PREFIX$mc" }
            ?: return false
        if (!v.isValid()) return false
        val dir = v.getVersionPath()
        val hasJson = File(dir, "${v.getVersionName()}.json").isFile
        val hasJar = dir.listFiles { f -> f.extension.equals("jar", true) }?.isNotEmpty() == true
        return hasJson && hasJar
    }

    /**
     * Поиск версии для mc. По умолчанию требуем валидную версию (json+jar):
     * полу-установленная папка версий клиента не запустит. Для инспекции
     * (показать, что за мусор в versions/) можно вызвать с requireValid=false.
     */
    fun findVersion(mc: String, requireValid: Boolean = true): Version? {
        val names = candidates(mc).toSet()
        return VersionsManager.versions.value.firstOrNull {
            it.getVersionName() in names && (!requireValid || it.isValid())
        }
    }

    /**
     * Есть ли в json psina-базы Fabric-лоадер (net.fabricmc:fabric-loader).
     * Именно лоадер загружает моды из mods/: ванильный json молча их игнорирует,
     * хотя менеджер модов показывает файлы — из-за этого и появлялось
     * «Для текущей версии нет мод-лоадера, моды использовать нельзя».
     */
    fun baseHasFabric(mc: String): Boolean = try {
        findVersion(mc)?.getVersionInfo()?.hasLoader(ModLoader.FABRIC) == true
    } catch (e: Exception) {
        Logx.e("не удалось определить лоадер базы psina-$mc", e)
        false
    }

    /**
     * После установки версии список VersionsManager наполняется асинхронно
     * (refresh на Dispatchers.IO). Ждём появления ВАЛИДНОЙ psina-<mc> в этом
     * списке — иначе проверки сразу после установки видят устаревший список
     * и начинают чинить то, что уже починено. Вызывать из фонового потока.
     */
    fun awaitBaseVersionValid(mc: String, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isBaseVersionValid(mc)) return true
            Thread.sleep(100)
        }
        return isBaseVersionValid(mc)
    }

    /** Лежит ли в versions/ недоделанная/битая psina-версия (папка есть, игры из неё не будет). */
    fun hasBrokenBaseVersion(mc: String): Boolean {
        val name = "$VERSION_PREFIX$mc"
        val dir = File(getGameHome(), "versions").let { File(it, name) }
        if (!dir.isDirectory) return false
        return !isBaseVersionValid(mc)
    }

    /**
     * Снести битые psina-версии для mc: недоделанную базу psina-<mc> и
     * производные psina-<mc>-* (их пересоберёт ensureCustomMainVersion).
     * Нужен после срыва установки, когда папка осталась без json/jar —
     * иначе GameInstaller считает версию «уже установленной».
     */
    fun cleanupBrokenVersions(mc: String) {
        try {
            val versionsDir = File(getGameHome(), "versions")
            val baseDir = File(versionsDir, "$VERSION_PREFIX$mc")
            if (baseDir.isDirectory && !isBaseVersionValid(mc)) {
                baseDir.deleteRecursively()
                Logx.i("снесена битая база ${baseDir.name}")
            }
            versionsDir.listFiles()?.forEach { d ->
                if (d.isDirectory && d.name.startsWith("$VERSION_PREFIX$mc-")) {
                    d.deleteRecursively()
                    Logx.i("снесена производная ${d.name} (пересоберётся из ванили)")
                }
            }
            VersionsManager.refresh("[PsinaCleanup]", null)
        } catch (e: Exception) {
            Logx.e("cleanup версий $mc не удался", e)
        }
    }

    /**
     * Синхронизирует моды psina-инстанса в mods-папку версии.
     * Источник — Paths.modsDir(mc) (туда всё поставил Installer), приёмник —
     * VersionFolders.MOD.getDir(version.getGameDir()). Копируем только jar'ы,
     * перезаписывая при несовпадении размера/времени.
     *
     * Чистка устаревших модов — ТОЛЬКО свои: в корне mods-папки версии лежит
     * маркер .psina-sync.json со списком модов, которые мы туда когда-либо
     * копировали. Удаляем только jar'ы из маркера, которых больше нет в
     * инстансе. Пользовательские моды, добавленные вручную/через менеджер
     * модов, никогда не трогаем.
     */
    fun syncMods(mc: String, version: Version, clientId: String = ""): Int {
        val src = Paths.modsDir(mc)
        if (!src.isDirectory) return 0
        val dst = VersionFolders.MOD.getDir(version.getGameDir())
        dst.mkdirs()
        val markerFile = File(dst, SYNC_MARKER)
        val prevSynced = readSyncMarker(markerFile)

        var n = 0
        val copiedNow = mutableSetOf<String>()
        src.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar", true) }
            .forEach { f ->
                val out = File(dst, f.name)
                if (!out.exists() || out.length() != f.length() || out.lastModified() < f.lastModified()) {
                    f.copyTo(out, overwrite = true)
                }
                copiedNow.add(f.name)
                n++
            }

        // Устаревшие СВОИ моды: скопированы нами раньше (в маркере), но в
        // инстансе их уже нет — например, после переустановки на новую сборку.
        (prevSynced - copiedNow).forEach { name ->
            val f = File(dst, name)
            if (f.isFile) {
                f.delete()
                Logx.i("syncMods: удалён устаревший мод $name")
            }
        }
        if (prevSynced != copiedNow) writeSyncMarker(markerFile, copiedNow)

        Logx.i("syncMods: $n jar'ов из ${src.path} -> ${dst.path}")
        return n
    }

    private const val SYNC_MARKER = ".psina-sync.json"

    private fun readSyncMarker(f: File): Set<String> = try {
        if (!f.isFile) emptySet()
        else {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
    } catch (e: Exception) {
        Logx.e("маркер syncMods битый", e)
        emptySet()
    }

    private fun writeSyncMarker(f: File, names: Set<String>) {
        try {
            val arr = org.json.JSONArray()
            names.sorted().forEach { arr.put(it) }
            f.writeText(arr.toString())
        } catch (e: Exception) {
            Logx.e("не удалось записать маркер syncMods", e)
        }
    }

    /**
     * β-порт клиентов со своим лоадером (mainClass из манифеста): создаём
     * производную версию psina-<mc>-<clientId> — ванильный json, их jar как
     * version-jar и их mainClass. Библиотеки/ассеты/нативы — ванильные,
     * штатные для Android от лаунчера. Эксперимент: их Main может ждать
     * Windows-окружение (см. notes в манифесте).
     */
    fun ensureCustomMainVersion(
        mc: String,
        clientId: String,
        clientJar: File?,
        mainClass: String
    ): Version? {
        return try {
            val base = findVersion(mc) ?: run {
                Logx.i("ensureCustomMainVersion: валидной базы $VERSION_PREFIX$mc нет (полу-установка?)")
                return null
            }
            val name = derivedVersionName(mc, clientId)
            val versionsDir = File(com.movtery.zalithlauncher.game.path.getGameHome(), "versions")
            val baseJson = File(File(versionsDir, base.getVersionName()), base.getVersionName() + ".json")
            if (!baseJson.isFile) return null
            val targetDir = File(versionsDir, name)
            targetDir.mkdirs()
            val jsonFile = File(targetDir, "$name.json")
            // Перезаписываем json всегда: битый/полу-записанный файл после сбоя
            // иначе навсегда останется невалидной версией (самовосстановление).
            run {
                val obj = org.json.JSONObject(baseJson.readText())
                obj.put("id", name)
                obj.put("mainClass", mainClass)
                // launchFor описывает состав БАЗОВОЙ версии; производная версия —
                // своя сборка со своим mainClass, чужой launchFor вводит в
                // заблуждение определитель лоадера (RevisionInfo) — убираем.
                obj.remove("launchFor")
                jsonFile.writeText(obj.toString())
                Logx.i("своя версия собрана: $name (mainClass=$mainClass)")
            }
            if (clientJar != null && clientJar.isFile) {
                val dst = File(targetDir, "$name.jar")
                if (!dst.exists() || dst.length() != clientJar.length()) {
                    clientJar.copyTo(dst, overwrite = true)
                    Logx.i("version-jar подменён на клиентский: ${dst.name}")
                }
                // ownMain-клиент живёт в version-jar со своим mainClass — его
                // НЕЛЬЗЯ грузить ещё раз как мод из mods/ (двойной класспатч,
                // гарантированный конфликт). Убираем его из модов версии.
                val versionMods = VersionFolders.MOD.getDir(
                    File(File(getGameHome(), "versions"), name)
                )
                if (versionMods.isDirectory) {
                    versionMods.listFiles { f -> f.isFile && f.name == clientJar.name }
                        ?.forEach { stale ->
                            stale.delete()
                            Logx.i("убран клиентский jar из модов версии: ${stale.name}")
                        }
                }
            }
            VersionsManager.refresh("[PsinaCustomMain]", name)
            // Список версий наполняется асинхронно — дождаться, пока derived
            // реально появится в нём, иначе вернём null и получим ложный
            // VersionMissing (часть бага «полу-установки»).
            var found: Version? = null
            val deadline = System.currentTimeMillis() + 15_000
            while (found == null && System.currentTimeMillis() < deadline) {
                found = VersionsManager.versions.value.firstOrNull {
                    it.getVersionName() == name && it.isValid()
                }
                if (found == null) Thread.sleep(100)
            }
            found
        } catch (e: Exception) {
            Logx.e("не удалось собрать версию со своим mainClass", e)
            null
        }
    }

    /**
     * Нативный запуск клиента из psina-инстанса средствами этого APK.
     * Возвращает честный outcome: RequestSent — сервис и активити запущены
     * (но «игра работает» мы по-прежнему не обещаем).
     */
    fun nativeLaunch(
        ctx: Context,
        mc: String,
        nickname: String,
        ramGb: Int,
        extraJvmArgs: List<String> = emptyList(),
        mainClass: String? = null,
        clientId: String = "",
        clientJar: File? = null
    ): NativeOutcome {
        return try {
            val version = if (!mainClass.isNullOrBlank()) {
                ensureCustomMainVersion(mc, clientId, clientJar, mainClass)
                    ?: return NativeOutcome.VersionMissing(mc)
            } else {
                findVersion(mc) ?: return NativeOutcome.VersionMissing(mc)
            }
            syncMods(mc, version, clientId)
            applyRam(version, ramGb)
            applyJvmArgs(version, extraJvmArgs)
            // Локальный офлайн-профиль — как LOCAL-аккаунты форка (accessToken "0").
            val nick = nickname.trim().ifBlank { "Player" }
            val account = Account(username = nick, accountType = AccountType.LOCAL.tag)
            Logx.i(
                "нативный запуск: версия ${version.getVersionName()}, игрок $nick " +
                    "(ram=$ramGb ГБ, jvmArgs=${extraJvmArgs.size}, mainClass=$mainClass)"
            )
            runGame(ctx, version, account)
            NativeOutcome.RequestSent
        } catch (e: Exception) {
            Logx.e("нативный запуск не удался", e)
            NativeOutcome.Failed(e.message ?: e.toString())
        }
    }

    /**
     * JVM-аргументы клиента из манифеста → конфиг psina-версии: нативный
     * запуск учитывает versionConfig.jvmArgs (GameLauncher.customArgs).
     * Только для версий psina-*: у чужих версий конфиг не трогаем.
     */
    private fun applyJvmArgs(version: Version, args: List<String>) {
        if (args.isEmpty()) return
        if (!version.getVersionName().startsWith(VERSION_PREFIX)) return
        val joined = args.joinToString(" ")
        val cfg = version.getVersionConfig()
        if (cfg.jvmArgs != joined) {
            cfg.jvmArgs = joined
            cfg.save()
            Logx.i("JVM-аргументы клиента применены к ${version.getVersionName()} (${args.size} шт.)")
        }
    }

    /**
     * RAM из настроек Псины → конфиг psina-версии (в MB). Затрагиваем только
     * версии с префиксом VERSION_PREFIX: у своих (без префикса) конфиг не
     * трогаем. GetRamAllocation дополнительно ограничит по памяти устройства.
     */
    private fun applyRam(version: Version, ramGb: Int) {
        if (ramGb <= 0) return
        if (!version.getVersionName().startsWith(VERSION_PREFIX)) return
        val ramMb = ramGb * 1024
        val cfg = version.getVersionConfig()
        if (cfg.ramAllocation != ramMb) {
            cfg.ramAllocation = ramMb
            cfg.save()
            Logx.i("RAM Псины применена к ${version.getVersionName()}: $ramMb МБ")
        }
    }
}
