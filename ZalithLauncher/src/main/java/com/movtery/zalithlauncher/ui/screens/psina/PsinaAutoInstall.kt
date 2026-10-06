package com.movtery.zalithlauncher.ui.screens.psina

import android.content.Context
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import kotlinx.coroutines.CoroutineScope
import ru.psina.core.Logx

/**
 * Мост «играть в один тап»: если у форка ещё нет версии psina-<mc>,
 * доустанавливаем ванильную базу <mc> штатным GameInstaller форка
 * (customVersionName = psina-<mc>) — после чего экран сам перезапускает
 * pipeline и клиент доустанавливается поверх готовой базы.
 */
object PsinaAutoInstall {
    const val TAG = "PsinaAutoInstall"

    /** Активный установщик (для диалога прогресса и отмены). */
    @Volatile
    var activeInstaller: GameInstaller? = null
        private set

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

    /** Отмена установки (кнопка в диалоге). */
    fun cancel() {
        activeInstaller?.cancelInstall()
        activeInstaller = null
    }
}
