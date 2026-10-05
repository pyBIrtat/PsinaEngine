package ru.psina.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * «Портация» ПК-клиента на телефон: вырезаем из его jar то, что Android
 * заведомо не потянет — Windows-нативы (.dll/.exe), Windows-скрипты (.ps1/.bat)
 * и десктопные .so/.dylib.
 *
 * Мод грузит такие файлы лениво, внутри конкретной фичи (дискорд-RPC,
 * медиаплеер, onnx/catboost). Без файла фича отваливается с ошибкой в логе,
 * но сам клиент стартует и работает. Это и есть «портирование» для тех
 * клиентов, которые ломались только на встроенных Windows-библиотеках.
 */
object JarRewriter {

    private val BAD_EXT = listOf(".dll", ".exe", ".ps1", ".bat", ".cmd", ".dylib", ".jnilib")

    /** Десктопный .so — оставляем только те, что похожи на Android. */
    private val DESKTOP_SO = listOf("linux", "osx", "darwin", "win32", "win64", "windows", "x86", "amd64", "i386", "i686", "freebsd")

    private val SIGNATURE = Regex("""^META-INF/[^/]+\.(SF|DSA|RSA)$""", RegexOption.IGNORE_CASE)

    /** Нужно ли выбросить эту запись из jar. */
    fun isDesktopOnly(entry: String): Boolean {
        val name = entry.replace('\\', '/')
        val low = name.lowercase()
        if (BAD_EXT.any { low.endsWith(it) }) return true
        if (low.endsWith(".so")) {
            // если рядом android/arm64 — это наша натива, её не трогаем
            if (low.contains("android") || low.contains("arm64") || low.contains("armeabi") || low.contains("aarch64") && low.contains("android")) return false
            return DESKTOP_SO.any { low.contains(it) }
        }
        return false
    }

    /**
     * Переписывает jar на месте, удаляя десктопные нативы/скрипты и подписи.
     * @return сколько записей выкинуто.
     */
    fun stripNatives(jar: File): Int {
        if (!jar.isFile) return 0
        val tmp = File(jar.parentFile, jar.name + ".stripped")
        var removed = 0
        ZipFile(jar).use { z ->
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (e.isDirectory || isDesktopOnly(name) || SIGNATURE.matches(name)) {
                        if (!e.isDirectory) removed++
                        continue
                    }
                    val ne = ZipEntry(name)
                    ne.time = e.time
                    out.putNextEntry(ne)
                    if (!e.isDirectory) {
                        z.getInputStream(e).use { input -> input.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
        if (!tmp.renameTo(jar)) {
            jar.delete()
            if (!tmp.renameTo(jar)) {
                tmp.copyTo(jar, overwrite = true)
                tmp.delete()
            }
        }
        return removed
    }
}
