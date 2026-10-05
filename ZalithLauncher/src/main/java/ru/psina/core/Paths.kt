package ru.psina.core

import android.content.Context
import java.io.File

/**
 * Каталоги приложения. Всё живёт в private storage приложения — не нужны
 * разрешения на внешнюю память (Scoped Storage / Android 10+).
 */
object Paths {

    lateinit var root: File
        private set
    lateinit var instances: File
        private set
    lateinit var downloads: File
        private set
    lateinit var exports: File
        private set
    lateinit var logs: File
        private set
    lateinit var apkDir: File
        private set
    lateinit var cacheDir: File
        private set

    fun init(ctx: Context) {
        root = ctx.filesDir
        instances = dir("instances")
        downloads = dir("downloads")
        exports = dir("exports")
        logs = dir("logs")
        apkDir = dir("apk")
        cacheDir = ctx.cacheDir
    }

    /** Создаёт (при необходимости) каталог внутри root. */
    private fun dir(name: String): File = File(root, name).apply { mkdirs() }

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
