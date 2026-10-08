package com.movtery.zalithlauncher.ui.screens.psina

import android.content.Context
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.psina.core.Logx

/**
 * Установка ванильной базы psina-<mc> штатным GameInstaller форка
 * (customVersionName = psina-<mc>). Вызывается ТОЛЬКО после явного согласия
 * юзера (диалог на экране «Клиенты»): никаких фоновых автозапусков версий.
 *
 * Если папка psina-<mc> осталась от сорванной установки (клиент удалён
 * посреди установки и т.п.), её надо снести — иначе GameInstaller считает
 * базу «уже установленной» и валидную версию так и не собирает. Экран
 * делает это в startBaseInstall перед вызовом (cleanupBrokenVersions).
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

    /** Установка запрошена, но ещё не стартовала (фетч списка Fabric). */
    @Volatile
    private var pendingStart: Boolean = false

    /** Отмена ДО старта (пока список Fabric ещё грузится). */
    @Volatile
    private var startCancelled: Boolean = false

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
     * Запуск установки базы psina-<mc> с Fabric-лоадером (вызывать из
     * композиции: GameInstaller исполняет задачи в переданном scope).
     *
     * Fabric нужен НЕ для галочки: Fabric-клиенты Псины кладутся в mods/, а
     * моды из mods/ загружает только Fabric-лоадер (net.fabricmc:fabric-loader
     * в libraries json версии). Ванильная база молча игнорировала все моды.
     * @return false, если начать не удалось (установка уже идёт).
     */
    fun startVanillaInstall(
        context: Context,
        mc: String,
        scope: CoroutineScope,
        onResult: (ok: Boolean, error: String?) -> Unit
    ): Boolean {
        if (activeInstaller != null || pendingStart) return false
        val name = "psina-$mc"
        Logx.i("auto-install base with fabric: $name")
        pendingStart = true
        startCancelled = false
        // Список Fabric-версий грузим внутри scope (сеть с main-потока запрещена),
        // затем обычная установка. installGame сам асинхронный.
        scope.launch {
            val fabric = runCatching { FabricVersions.fabricFor(mc) }
                .onFailure { Logx.e("список Fabric не получен — ставим ваниль", it) }
                .getOrNull()
            if (startCancelled) {
                Logx.i("установка $name отменена до старта")
                pendingStart = false
                // Обязательно сообщаем результат: ждущий startVanillaInstall-caller
                // иначе зависнет навсегда на deferred.await().
                onResult(false, "Установка отменена")
                return@launch
            }
            Logx.i("fabric для $mc: ${fabric?.version ?: "нет (ванильная база)"}")
            val installer = GameInstaller(
                context = context,
                info = GameDownloadInfo(
                    gameVersion = mc,
                    customVersionName = name,
                    fabric = fabric
                ),
                scope = scope
            )
            pendingStart = false
            activeInstaller = installer
            installer.installGame(
                onInstalled = {
                    Logx.i("база установлена: $name")
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
        }
        return true
    }

    /** Отмена установки (кнопка в диалоге). */
    fun cancel() {
        startCancelled = true
        activeInstaller?.cancelInstall()
        activeInstaller = null
    }
}
