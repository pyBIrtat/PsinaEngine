package ru.psina.core

import android.content.Context
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountType
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
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

    fun findVersion(mc: String): Version? {
        val names = candidates(mc).toSet()
        return VersionsManager.versions.value.firstOrNull { it.getVersionName() in names }
    }

    /**
     * Синхронизирует моды psina-инстанса в mods-папку версии.
     * Источник — Paths.modsDir(mc) (туда всё поставил Installer), приёмник —
     * VersionFolders.MOD.getDir(version.getGameDir()). Копируем только jar'ы,
     * перезаписывая при несовпадении размера/времени.
     */
    fun syncMods(mc: String, version: Version): Int {
        val src = Paths.modsDir(mc)
        if (!src.isDirectory) return 0
        val dst = VersionFolders.MOD.getDir(version.getGameDir())
        dst.mkdirs()
        var n = 0
        src.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar", true) }
            .forEach { f ->
                val out = File(dst, f.name)
                if (!out.exists() || out.length() != f.length() || out.lastModified() < f.lastModified()) {
                    f.copyTo(out, overwrite = true)
                }
                n++
            }
        Logx.i("syncMods: $n jar'ов из ${src.path} -> ${dst.path}")
        return n
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
            val base = findVersion(mc) ?: return null
            val name = "psina-$mc-$clientId"
            val versionsDir = File(com.movtery.zalithlauncher.game.path.getGameHome(), "versions")
            val baseJson = File(File(versionsDir, base.getVersionName()), base.getVersionName() + ".json")
            if (!baseJson.isFile) return null
            val targetDir = File(versionsDir, name)
            targetDir.mkdirs()
            val jsonFile = File(targetDir, "$name.json")
            if (!jsonFile.isFile) {
                val obj = org.json.JSONObject(baseJson.readText())
                obj.put("id", name)
                obj.put("mainClass", mainClass)
                jsonFile.writeText(obj.toString())
                Logx.i("своя версия собрана: $name (mainClass=$mainClass)")
            }
            if (clientJar != null && clientJar.isFile) {
                val dst = File(targetDir, "$name.jar")
                if (!dst.exists() || dst.length() != clientJar.length()) {
                    clientJar.copyTo(dst, overwrite = true)
                    Logx.i("version-jar подменён на клиентский: ${dst.name}")
                }
            }
            VersionsManager.refresh("[PsinaCustomMain]", name)
            VersionsManager.versions.value.firstOrNull { it.getVersionName() == name }
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
            syncMods(mc, version)
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
