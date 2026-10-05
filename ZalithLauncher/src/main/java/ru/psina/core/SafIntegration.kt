package ru.psina.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Опциональная интеграция через SAF (Storage Access Framework).
 *
 * Пользователь САМ один раз указывает папку (например, если его движок хранит
 * .minecraft в общедоступном месте). Мы НЕ угадываем путь движка: начиная с
 * Android 11 нельзя выбрать Android/data/<pkg> чужого приложения через
 * системный пикер. Если движок хранит данные в закрытой папке — эта
 * интеграция честно не сработает, автоматизации через чужой Android/data нет.
 */
object SafIntegration {

    const val REQUEST_PICK_ENGINE_FOLDER = 4821

    fun requestFolderPicker(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

    fun persist(ctx: Context, treeUri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        Prefs.engineFolderUri = treeUri.toString()
    }

    /** Копирует моды инстанса в выбранную папку (ожидается подпапка mods/). Best-effort. */
    fun copyModsInto(ctx: Context, treeUri: Uri, mc: String): Int {
        val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return 0
        val modsDir = tree.findFile("mods") ?: tree.createDirectory("mods") ?: return 0
        var copied = 0
        Paths.modsDir(mc).listFiles { f -> f.extension == "jar" }?.forEach { jar ->
            modsDir.findFile(jar.name)?.delete()
            val dst = modsDir.createFile("application/java-archive", jar.name) ?: return@forEach
            ctx.contentResolver.openOutputStream(dst.uri)?.use { out ->
                jar.inputStream().use { it.copyTo(out) }
            }
            copied++
        }
        return copied
    }
}
