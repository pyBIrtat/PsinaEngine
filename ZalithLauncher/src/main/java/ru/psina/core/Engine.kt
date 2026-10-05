package ru.psina.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Мост к установленному на телефоне движку Java-Minecraft.
 *
 * Мы готовим инстанс (mods, конфиги, раскладку) и отдаём движку строку
 * JVM-аргументов. Главное здесь — classpath: движок про наши моды ничего
 * не знает, поэтому каждый jar из `instances/<mc>/mods` идёт в `-cp`,
 * а сам инстанс указывается как `--gameDir`.
 *
 * Если движка нет — предлагаем экспорт инстанса архивом.
 */
object Engine {

    data class Known(
        val pkg: String,
        val title: String,
        val launchActivity: String? = null
    )

    /**
     * Известные движки. Пакеты проверяются на устройстве: чего нет — просто
     * не показываем. Запуск всё равно идёт через общий launch-intent пакета.
     */
    val known = listOf(
        Known("com.movtery.zalithlauncher", "Zalith Launcher"),
        Known("org.angelauramc.amethyst", "Amethyst"),
        Known("net.kdt.pojavlaunch", "PojavLauncher"),
        Known("git.artdeell.mojo", "Mojo Launcher")
    )

    /** Установленные движки на устройстве. */
    fun installed(ctx: Context): List<Known> {
        val pm = ctx.packageManager
        return known.filter { k ->
            try { pm.getPackageInfo(k.pkg, 0); true }
            catch (e: PackageManager.NameNotFoundException) { false }
        }
    }

    /**
     * Запускает движок с нашими аргументами. Возвращает true, если Intent ушёл.
     * Движки читают строку запуска из разных extras, поэтому кладём сразу
     * несколько ключей — лишние они игнорируют.
     */
    @Deprecated("Возвращает только «intent ушёл», не «игра запущена». Используй requestLaunch().")
    fun launch(
        ctx: Context,
        engine: Known,
        mc: String,
        nickname: String,
        ramGb: Int,
        extraJvmArgs: List<String> = emptyList(),
        mainClass: String? = null
    ): Boolean = requestLaunch(ctx, engine, mc, nickname, ramGb, extraJvmArgs, mainClass) is LaunchOutcome.RequestSent

    /** Честный результат запроса на запуск — «Started» нет и быть не может. */
    sealed class LaunchOutcome {
        data class RequestSent(val engine: Known) : LaunchOutcome()
        object NoEngineInstalled : LaunchOutcome()
        data class Rejected(val engine: Known, val reason: String) : LaunchOutcome()
    }

    /**
     * Замена launch(): делает то же самое (общий launch-intent пакета + набор
     * extras «на всякий случай» — НИ ОДИН не задокументирован официально ни
     * у одного из 4 движков, это best-effort, не контракт), но возвращает
     * результат, который нельзя спутать с «игра запущена».
     */
    fun requestLaunch(
        ctx: Context,
        engine: Known,
        mc: String,
        nickname: String,
        ramGb: Int,
        extraJvmArgs: List<String> = emptyList(),
        mainClass: String? = null
    ): LaunchOutcome {
        val instance = Paths.instanceDir(mc)
        val args = buildArgs(mc, nickname, ramGb, extraJvmArgs, mainClass)
        Logx.i("запрос на запуск ${engine.pkg} для $mc (best-effort, без подтверждения)")
        val base = try {
            engine.launchActivity?.let { act ->
                Intent(Intent.ACTION_MAIN).apply { setClassName(engine.pkg, act) }
            } ?: ctx.packageManager.getLaunchIntentForPackage(engine.pkg)
        } catch (e: Exception) {
            Logx.e("у ${engine.pkg} нет launch-intent", e)
            null
        } ?: return LaunchOutcome.Rejected(engine, "нет launch-intent пакета")

        return try {
            base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // строка аргументов — под самые ходовые ключи движков (best-effort)
            base.putExtra("javaArgs", args)
            base.putExtra("psina_args", args)
            base.putExtra("jvmArgs", args)
            base.putExtra("args", args)
            // инстанс и ник — чтобы движок мог подхватить нашу папку
            base.putExtra("psina_instance", instance.absolutePath)
            base.putExtra("psina_nickname", nickname)
            base.putExtra("psina_mc", mc)
            ctx.startActivity(base)
            LaunchOutcome.RequestSent(engine)
        } catch (e: Exception) {
            Logx.e("не удалось запустить ${engine.pkg}", e)
            LaunchOutcome.Rejected(engine, e.message ?: "неизвестная ошибка")
        }
    }

    /**
     * Строка JVM-аргументов для движка.
     *
     * `-cp` собирается из модов инстанса — без него движок запустит чистую
     * игру и клиента не увидит. `--gameDir` указывает на папку инстанса,
     * `--username/--uuid` дают офлайн-профиль, чтобы клиент стартовал без
     * входа в аккаунт.
     */
    fun buildArgs(
        mc: String,
        nickname: String,
        ramGb: Int,
        extraJvmArgs: List<String> = emptyList(),
        mainClass: String? = null
    ): String {
        val instance = Paths.instanceDir(mc)
        val ram = ramGb.coerceIn(1, 64)
        val sb = StringBuilder()

        sb.append("-Xmx${ram}G -Xms${(ram / 2).coerceAtLeast(1)}G ")
        sb.append("-Dpsina.mobile=1 ")
        sb.append("-Dfile.encoding=UTF-8 ")
        sb.append("-Djava.io.tmpdir=${File(Paths.cacheDir, "tmp").apply { mkdirs() }.absolutePath} ")

        val cp = instanceClasspath(mc)
        if (cp.isNotEmpty()) {
            sb.append("-cp ").append(cp.joinToString(File.pathSeparator)).append(' ')
        } else {
            Logx.i("модов в инстансе нет — запускаем чистую игру")
        }

        extraJvmArgs.forEach { raw ->
            val a = raw.replace("{nick}", nickname)
            if (a.isNotBlank()) sb.append(a).append(' ')
        }
        if (!mainClass.isNullOrBlank()) sb.append("-Dpsina.mainClass=").append(mainClass).append(' ')

        sb.append("--gameDir ").append(instance.absolutePath).append(' ')
        sb.append("--username ").append(nickname).append(' ')
        sb.append("--version ").append(mc).append(' ')
        sb.append("--assetsDir ").append(File(instance, "assets").apply { mkdirs() }.absolutePath).append(' ')
        sb.append("--assetIndex ").append(mc).append(' ')
        sb.append("--uuid ").append(offlineUuid(nickname)).append(' ')
        sb.append("--accessToken 0 --clientId 0 --xuid 0 ")
        sb.append("--userType legacy --versionType release")

        return sb.toString().trim()
    }

    /** Все jar'ы инстанса (mods + libraries), которые движок должен положить в classpath. */
    fun instanceClasspath(mc: String): List<String> {
        val out = mutableListOf<String>()
        val roots = listOf(Paths.modsDir(mc), File(Paths.instanceDir(mc), "libraries"))
        roots.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jar", true) }
                .sortedBy { it.name }
                .forEach { out.add(it.absolutePath) }
        }
        return out
    }

    /** Офлайн-UUID — тот же алгоритм, что в ПК-лаунчере. */
    fun offlineUuid(nickname: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$nickname".toByteArray(Charsets.UTF_8)).toString()

    /** Отдаёт zip инстанса через «Поделиться» — закинуть в любой движок. */
    fun share(ctx: Context, zip: File, title: String) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Отправить инстанс").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Открывает ссылку в браузере. */
    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Logx.e("не удалось открыть $url", e)
        }
    }
}