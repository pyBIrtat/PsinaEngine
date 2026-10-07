package com.movtery.zalithlauncher.ui.screens.psina

import android.content.Context
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.psina.core.Logx

/**
 * Мост «играть в один тап»: если у форка ещё нет валидной версии psina-<mc>,
 * ставим ванильную базу <mc> штатным GameInstaller форка
 * (customVersionName = psina-<mc>) — после чего экран сам перезапускает
 * pipeline и клиент доустанавливается поверх готовой базы.
 *
 * Также умеет ЛЕЧИТЬ полу-установку: если папка psina-<mc> осталась от
 * сорванной установки (клиент удалён посреди установки и т.п.), её надо
 * снести — иначе GameInstaller считает базу «уже установленной» и
 * валидную версию так и не собирает.
 */
object PsinaAutoInstall {
    const val TAG = "PsinaAutoInstall"

    /** Активный установщик (для диалога прогресса и отмены). */
    @Volatile
    var activeInstaller: GameInstaller? = null
        private set

    /** Общий прогресс активной установки в %, -1 = неопределённый. */
    @Volatile
    var progressPercent: Int = -1
        private set

    /** Играется ли сейчас чинящий пере-инсталл (для UI поверх пайплайна). */
    @Volatile
    var repairing: Boolean = false
        private set

    private val progressWatchers =
        mutableListOf<(Int) -> Unit>()

    /** Подписка на прогресс (диалог). Возвращает функцию отписки. */
    fun addProgressListener(l: (Int) -> Unit): () -> Unit {
        synchronized(progressWatchers) { progressWatchers.add(l) }
        return {
            synchronized(progressWatchers) { progressWatchers.remove(l) }
            Unit
        }
    }

    private fun reportProgress(pct: Int) {
        progressPercent = pct
        synchronized(progressWatchers) { progressWatchers.toList() }.forEach { w ->
            runCatching { w(pct) }
        }
    }

    /**
     * Запуск установки ванильной базы psina-<mc> (вызывать из композиции:
     * GameInstaller исполняет задачи в переданном scope).
     * @return false, если начать не удалось (установка уже идёт).
     */
    fun startVanillaInstall(
        context: Context,
        mc: String,
        scope: CoroutineScope,
        onResult: (ok: Boolean, error: String?) -> Unit
    ): Boolean {
        if (activeInstaller != null) return false
        val name = "psina-$mc"
        Logx.i("auto-install vanilla base: $name")
        val installer = GameInstaller(
            context = context,
            info = GameDownloadInfo(
                gameVersion = mc,
                customVersionName = name
            ),
            scope = scope
        )
        activeInstaller = installer
        installer.installGame(
            onInstalled = {
                Logx.i("vanilla base installed: $name")
                VersionsManager.refresh("[$TAG]", name)
                activeInstaller = null
                onResult(true, null)
            },
            onError = { th ->
                Logx.e("auto-install failed: $name", th)
                activeInstaller = null
                onResult(false, th.message ?: th.toString())
            },
            onGameAlreadyInstalled = {
                Logx.i("vanilla base already installed: $name")
                VersionsManager.refresh("[$TAG]", name)
                activeInstaller = null
                onResult(true, null)
            }
        )
        return true
    }

    /**
     * ЛЕЧИМ полу-установку: сносим битую папку psina-<mc> (и её производные
     * psina-<mc>-<clientId>), затем БЛОКИРУЮЩЕ ставим ванильную базу заново.
     * Вызывается из фонового потока пайплайна. Прогресс — в progressPercent
     * и через addProgressListener (диалог поверх пайплайна).
     *
     * @return true — база валидна и можно запускать клиента заново.
     */
    fun repairVanillaBase(context: Context, mc: String): Boolean = try {
        repairing = true
        reportProgress(-1)
        val deferred = CompletableDeferred<Boolean>()
        // repair() зовут из worker-потока, а installGame выполняет задачи
        // в переданном scope — берём независимый IO-scope и ждём результат.
        val installScope = CoroutineScope(Dispatchers.IO)
        val started = startVanillaInstall(context, mc, installScope) { ok, err ->
            if (!ok) Logx.e("пере-установка ванили $mc не удалась: $err")
            deferred.complete(ok)
        }
        if (!started) {
            Logx.e("пере-установка ванили $mc не началась (уже идёт?)")
            false
        } else {
            // Вызов из worker-потока (пайплайн/IO), блокировка допустима.
            kotlinx.coroutines.runBlocking {
                // Следим за задачами установщика: средний прогресс по запущенным.
                val watcher = installScope.launch {
                    while (deferred.isActive) {
                        val inst = activeInstaller
                        val running = inst?.tasksFlow?.value
                            ?.filter { it.task.stage.value == com.movtery.zalithlauncher.coroutine.TaskStage.RUNNING }
                        if (running.isNullOrEmpty()) reportProgress(-1)
                        else reportProgress(
                            (running.maxOf { it.task.progress.value }.coerceAtLeast(0f) * 100).toInt()
                        )
                        kotlinx.coroutines.delay(200)
                    }
                }
                val ok = deferred.await()
                watcher.cancel()
                ok
            }
        }
    } finally {
        repairing = false
        reportProgress(-1)
    }

    /** Отмена установки (кнопка в диалоге). */
    fun cancel() {
        activeInstaller?.cancelInstall()
        activeInstaller = null
    }
}
