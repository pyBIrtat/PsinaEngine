package ru.psina.core

import java.io.File
import java.util.zip.ZipFile

// Модель совместимости клиентов с телефоном: уровень поддержки из манифеста
// и разбор jar'а на то, что на Android не заводится.

/** Насколько клиент живой на телефоне. */
enum class Support {
    /** Обычный Fabric-мод — ставится как есть. */
    READY,

    /** Портативка/Windows-сборка, которую мы пересобираем в набор модов (может не взлететь). */
    EXPERIMENTAL,

    /** Нужен Windows-рантайм или нативы, которых на Android нет. */
    PC_ONLY
}

/**
 * Как ставить клиента на телефон. Приходит из блока `android` в манифесте;
 * если блока нет — выводим сами (портативка => PC_ONLY, обычный мод => READY).
 */
data class AndroidSpec(
    val status: Support,
    /** Прямые ссылки на мод-файлы клиента (маленькие; вместо целого zip портативки). */
    val mods: List<ManifestRepo.Extra> = emptyList(),
    /**
     * Какие `extra` из манифеста НЕ ставить на телефон (по имени файла,
     * подстрокой). Например desktop-onnxruntime у Dimasik 26.2 или Windows-нативы.
     */
    val extraRemove: List<String> = emptyList(),
    /** Если прямых ссылок нет — вытащить эти пути из zip портативки. */
    val modsFromZip: List<String> = emptyList(),
    /**
     * Что положить не в mods/, а просто в classpath (compat-хелперы вроде
     * VMBridge у Destra: модом они не являются, но без них клиент падает).
     */
    val libsFromZip: List<String> = emptyList(),
    /** Что из набора портативки выкинуть на телефоне (Windows-нативы, голосовой чат и т.п.). */
    val modsExclude: List<String> = emptyList(),
    /**
     * Вырезать из скачанного jar встроенные Windows-нативы (.dll/.exe),
     * Windows-скрипты и десктопные .so/.dylib. Мод тянет их только при
     * вызове фичи (дискорд-RPC, медиаплеер, onnx/catboost) — на телефоне
     * такие фичи отключаются, а сам клиент продолжает работать.
     */
    val stripNatives: Boolean = false,
    /** Доп. JVM-аргументы; {nick} подставляется ником из настроек. */
    val jvmArgs: List<String> = emptyList(),
    /** Свой главный класс вместо KnotClient (нужен редким сборкам). */
    val mainClass: String? = null,
    val notes: String = "",

    // --- требования к телефону (проверяются ДО скачивания) ---
    /** Минимальный Build.VERSION.SDK_INT; 0 = не проверять. */
    val minAndroidApi: Int = 0,
    /** Обязательная архитектура, сейчас поддерживается только "arm64-v8a"; пусто = не проверять. */
    val architecture: String = "",
    /** Рекомендуемый объём ОЗУ телефона в МБ — мягкая проверка (предупреждение). */
    val requiredMemoryMb: Long = 0,
    /** Если задано — клиент проверен только с этим движком (package name из Engine.known). */
    val requiredEngine: String? = null,
    /** Оценка суммарного размера установки в МБ — для проверки свободного места до скачивания. */
    val estimatedSizeMb: Long = 0
) {
    val isPlayable: Boolean get() = status != Support.PC_ONLY
}

/**
 * Совместимость клиентов с Android: разбор блока `android` из манифеста и
 * разбор jar'а на предмет того, что на телефоне не заведётся.
 */
object AndroidCompat {

    // ---------- что ищем в jar ----------

    private class Rule(val tag: String, val needle: String, val why: String)

    private val RULES = listOf(
        Rule("jna", "com/sun/jna/Native", "JNA тянет Windows-.dll"),
        Rule("onnx", "ai/onnxruntime", "нужен onnxruntime под Android, а не desktop"),
        Rule("catboost", "ai/catboost", "CatBoost без Android-нативов"),
        Rule("discord", "discord-rpc", "Discord RPC (.dll)"),
        Rule("discord", "discordrpc", "Discord RPC (.dll)"),
        Rule("media", "MediaPlayerInfo", "Windows-медиасессия"),
        Rule("awt", "java/awt/Desktop", "AWT Desktop на Android нет"),
        Rule("awt", "java/awt/Robot", "AWT Robot на Android нет"),
        Rule("awt", "javax/swing", "Swing на Android нет"),
        Rule("oshi", "oshi/SystemInfo", "OSHI может падать на Android"),
        Rule("process", "java/lang/ProcessBuilder", "запуск процессов (powershell/explorer)"),
        Rule("process", "java/lang/Runtime", "Runtime.exec"),
        Rule("winpath", "powershell", "Windows-команда"),
        Rule("winpath", "explorer.exe", "Windows-команда"),
        Rule("winpath", "SystemDrive", "Windows-путь"),
        Rule("winpath", "LOCALAPPDATA", "Windows-путь")
    )

    /** Одна находка: что нашли и почему это мешает. */
    data class Finding(val tag: String, val why: String, val count: Int, val example: String)

    data class Report(
        val classes: Int,
        val maxMajor: Int,
        val natives: List<String>,
        val scripts: List<String>,
        val findings: List<Finding>
    ) {
        /** Windows-нативы без Android-версии рядом. */
        val windowsOnlyNatives: List<String>
            get() {
                val hasSo = natives.any { it.endsWith(".so", true) }
                val win = natives.filter { it.endsWith(".dll", true) || it.endsWith(".exe", true) }
                return if (hasSo) emptyList() else win
            }

        val javaLabel: String
            get() = when {
                maxMajor >= 69 -> "Java 25"
                maxMajor >= 65 -> "Java 21"
                maxMajor >= 61 -> "Java 17"
                maxMajor >= 55 -> "Java 11"
                else -> "Java 8"
            }

        val blockers: List<String>
            get() {
                val out = mutableListOf<String>()
                if (maxMajor >= 69) out.add("нужна Java 25")
                if (windowsOnlyNatives.isNotEmpty()) out.add("только Windows-нативы (${windowsOnlyNatives.size})")
                if (scripts.isNotEmpty()) out.add("Windows-скрипты (${scripts.size})")
                findings.forEach { f ->
                    if (f.tag != "process" || f.count > 3) out.add(f.why)
                }
                return out.distinct()
            }

        val clean: Boolean get() = blockers.isEmpty()
    }

    private const val MAX_ENTRY = 12 * 1024 * 1024

    /** Разбирает jar и говорит, что в нём не поедет на телефоне. */
    fun scan(jar: File): Report {
        var classes = 0
        var maxMajor = 0
        val natives = mutableListOf<String>()
        val scripts = mutableListOf<String>()
        val hits = linkedMapOf<String, MutableList<String>>()

        ZipFile(jar).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name
                val low = name.lowercase()

                when {
                    low.endsWith(".dll") || low.endsWith(".so") || low.endsWith(".dylib") ||
                        low.endsWith(".exe") || low.endsWith(".jnilib") -> natives.add(name)

                    low.endsWith(".ps1") || low.endsWith(".bat") || low.endsWith(".cmd") -> scripts.add(name)

                    low.endsWith(".class") -> {
                        if (e.size > MAX_ENTRY || e.size <= 0) continue
                        val bytes = z.getInputStream(e).readBytes()
                        classes++
                        if (bytes.size > 8 && bytes[0] == 0xCA.toByte() && bytes[1] == 0xFE.toByte()) {
                            val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                            if (major > maxMajor) maxMajor = major
                        }
                        val text = String(bytes, Charsets.ISO_8859_1)
                        RULES.forEach { r ->
                            if (text.contains(r.needle)) hits.getOrPut(r.tag) { mutableListOf() }.add(name)
                        }
                    }
                }
            }
        }

        val findings = hits.map { (tag, names) ->
            val rule = RULES.first { it.tag == tag }
            Finding(tag, rule.why, names.size, names.first())
        }.sortedByDescending { it.count }

        return Report(classes, maxMajor, natives.distinct(), scripts.distinct(), findings)
    }

    /** Короткий человеческий вердикт по разбору jar. */
    fun verdict(r: Report): String =
        if (r.clean) "Похоже на обычный мод — должен пойти на телефоне"
        else "На телефоне может не пойти: " + r.blockers.joinToString(", ")

    // ---------- уровень поддержки из манифеста ----------

    fun specOf(c: ManifestRepo.Client): AndroidSpec =
        c.android ?: if (c.isPortable) {
            AndroidSpec(
                status = Support.PC_ONLY,
                notes = "Портативка со своим Windows-рантаймом и скриптом запуска"
            )
        } else {
            AndroidSpec(status = Support.READY)
        }

    fun statusLabel(s: Support): String = when (s) {
        Support.READY -> "телефон"
        Support.EXPERIMENTAL -> "β экспериментально"
        Support.PC_ONLY -> "Только ПК"
    }

    /** Требует ли клиент своего главного класса (не KnotClient). */
    fun needsOwnMain(spec: AndroidSpec): Boolean = !spec.mainClass.isNullOrBlank()
}