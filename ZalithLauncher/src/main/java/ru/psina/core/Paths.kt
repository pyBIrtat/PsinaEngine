package ru.psina.core

import android.content.Context
import java.io.File

/**
 * Каталоги приложения. Всё живёт в private storage приложения — не нужны
 * разрешения на внешнюю память (Scoped Storage / Android 10+).
 *
 * init() вызывается из ZLApplication.onCreate (PsinaBoot), но все геттеры
 * имеют безопасный фолбэк: если кто-то обратился к Paths раньше (край —
 * экран «Клиенты Псины» открыли до окончания инициализации приложения),
 * каталоги вычисляются лениво из GlobalContext вместо краша
 * "lateinit property root has not been initialized".
 */
object Paths {

    @Volatile
    private var _root: File? = null

    @Volatile
    private var _instances: File? = null

    @Volatile
    private var _downloads: File? = null

    @Volatile
    private var _exports: File? = null

    @Volatile
    private var _logs: File? = null

    @Volatile
    private var _apkDir: File? = null

    @Volatile
    private var _cacheDir: File? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        _root = app.filesDir
        _instances = dir(app, "instances")
        _downloads = dir(app, "downloads")
        _exports = dir(app, "exports")
        _logs = dir(app, "logs")
        _apkDir = dir(app, "apk")
        _cacheDir = app.cacheDir
    }

    private fun dir(parent: File, name: String): File =
        File(parent, name).apply { mkdirs() }

    private fun dir(app: Context, name: String): File =
        dir(app.filesDir, name)

    /** Корневой каталог psina-данных; ленивый фолбэк до явного init(). */
    val root: File
        get() {
            _root?.let { return it }
            return fallbackBase().apply { _root = this }
        }

    val instances: File get() = _instances ?: subDir("instances").also { _instances = it }
    val downloads: File get() = _downloads ?: subDir("downloads").also { _downloads = it }
    val exports: File get() = _exports ?: subDir("exports").also { _exports = it }
    val logs: File get() = _logs ?: subDir("logs").also { _logs = it }
    val apkDir: File get() = _apkDir ?: subDir("apk").also { _apkDir = it }

    val cacheDir: File
        get() {
            _cacheDir?.let { return it }
            return fallbackCache().also { _cacheDir = it }
        }

    /**
     * Отдельная private-папка "psina" в данных приложения. Используется, только
     * если экраны достучались до Paths до init() из ZLApplication — так фолбэк
     * не смешивается с файлами init()-каталогов после нормальной инициализации.
     */
    private fun fallbackBase(): File {
        val dir = runCatching {
            File(com.movtery.zalithlauncher.context.GlobalContext.getDir("psina", Context.MODE_PRIVATE), "fallback")
        }.getOrElse { File(File(System.getProperty("java.io.tmpdir") ?: "."), "psina") }
        return dir.apply { mkdirs() }
    }

    private fun fallbackCache(): File {
        return runCatching {
            com.movtery.zalithlauncher.context.GlobalContext.cacheDir
        }.getOrElse { File(File(System.getProperty("java.io.tmpdir") ?: "."), "psina-cache") }
            .apply { mkdirs() }
    }

    /** Каталог внутри fallback-корня (для доступа до init()). */
    private fun subDir(name: String): File = dir(fallbackBase(), name)

    fun instanceDir(mc: String): File = File(instances, sanitize(mc)).apply { mkdirs() }
    fun modsDir(mc: String): File = File(instanceDir(mc), "mods").apply { mkdirs() }
    fun configDir(mc: String): File = File(instanceDir(mc), "config").apply { mkdirs() }
    fun savesDir(mc: String): File = File(instanceDir(mc), "saves").apply { mkdirs() }
    fun controlLayoutsDir(mc: String): File = File(instanceDir(mc), "controlmap").apply { mkdirs() }

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "x" }

    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += sizeOf(it) }
        return total
    }
}
