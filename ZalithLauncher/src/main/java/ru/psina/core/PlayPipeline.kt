package ru.psina.core

import android.content.Context

/**
 * Полный пайплайн кнопки «Играть» — один объект, одно состояние за раз:
 * манифест → место → совместимость → файлы → скачивание → sha256 →
 * профиль → движок → запрос запуска (без обещаний, что игра открылась).
 */
class PlayPipeline(private val ctx: Context) {

    private val cancel = Net.CancelToken()
    var onState: (PlayState) -> Unit = {}

    fun cancelDownload() { cancel.cancelled = true }

    fun run(clientId: String, nickname: String, ramGb: Int) {
        try {
            onState(PlayState.LoadingManifest)
            val manifest = Store.loadManifest(false)
            val client = manifest.clients.firstOrNull { it.id == clientId }
                ?: return fail(LaunchError.ClientNotFound(clientId))
            if (manifest.versions.none { it == client.mc } && manifest.clients.none { it.mc == client.mc }) {
                return fail(LaunchError.VersionNotFound(client.mc))
            }

            val spec = AndroidCompat.specOf(client)
            if (!spec.isPlayable) return fail(LaunchError.ClientIncompatible)

            onState(PlayState.CheckingPhoneStorage)
            onState(PlayState.CheckingAndroidCompatibility)
            val device = DeviceCompat.scan(ctx)
            val estimate = if (spec.estimatedSizeMb > 0) spec.estimatedSizeMb
            else Net.estimateTotalMb(listOfNotNull(client.jar.ifBlank { null }, client.zip))
            DeviceCompat.hardBlocker(device, spec, estimate)?.let { return fail(it) }

            onState(PlayState.CheckingFiles)
            val verify = ProfileManager.verify(clientId, client.mc)
            // Обновление: манифест указывает на другой файл клиента, чем стоит
            // в профиле (автор выпустил новую сборку) — переустанавливаем.
            val jarName = client.jar.takeIf { it.isNotBlank() }?.let { Installer.fileNameOf(it) }
            val jarMissing = jarName != null &&
                ProfileManager.installedPaths(clientId, client.mc)
                    .none { it == jarName || it.endsWith("/$jarName") }
            if (!verify.needsReinstall && !jarMissing && Store.isInstalled(clientId)) {
                Logx.i("профиль $clientId/${client.mc} уже проверен — установка не требуется")
            } else {
                if (jarMissing) Logx.i("в манифесте новый файл клиента ($jarName) — обновляю профиль")
                val oldPaths = ProfileManager.installedPaths(clientId, client.mc)
                onState(PlayState.Installing)
                val result = Installer.install(client) { p ->
                    onState(PlayState.Downloading(p.detail.ifBlank { p.stage }, 0, 0, p.percent))
                }
                onState(PlayState.Verifying)
                ProfileManager.record(clientId, client.mc, result.files)
                // Убираем файлы старой сборки, чтобы в mods не осталось двух клиентов.
                ProfileManager.deleteStale(clientId, client.mc, oldPaths, result.files)
            }

            onState(PlayState.PreparingMobileProfile)
            onState(PlayState.PreparingRuntime)

            // Родной запуск: этот APK сам является движком (Psina Engine, этап E3).
            onState(PlayState.LaunchingMinecraft("Psina Engine"))
            // β-порт клиентов со своим mainClass: их jar из корня инстанса.
            val ownMainJar = if (AndroidCompat.needsOwnMain(spec) && client.jar.isNotBlank()) {
                java.io.File(Paths.instanceDir(client.mc), Installer.fileNameOf(client.jar))
            } else null
            var nativeMissing: String? = null
            var nativeFailure: String? = null
            when (
                val native = ZalithBackend.nativeLaunch(
                    ctx, client.mc, nickname, ramGb,
                    spec.jvmArgs, spec.mainClass, clientId, ownMainJar
                )
            ) {
                is ZalithBackend.NativeOutcome.RequestSent -> {
                    onState(PlayState.LaunchRequestSent("Psina Engine"))
                    return
                }
                is ZalithBackend.NativeOutcome.VersionMissing -> nativeMissing = native.mc
                is ZalithBackend.NativeOutcome.Failed -> {
                    nativeFailure = native.reason
                    Logx.i("нативный запуск не удался (${native.reason})")
                }
            }

            // Psina fork: этот APK сам является движком — фолбэка на внешние
            // лаунчеры больше нет. Честная ошибка вместо установки чужих лаунчеров.
            return fail(
                if (nativeMissing != null) LaunchError.EngineVersionMissing(nativeMissing)
                else LaunchError.NativeLaunchFailed(nativeFailure)
            )
        } catch (e: Net.DownloadCancelledException) {
            fail(LaunchError.UserCancelled)
        } catch (e: java.io.IOException) {
            fail(if (cancel.cancelled) LaunchError.UserCancelled else LaunchError.NetworkLost)
        } catch (e: Exception) {
            Logx.e("пайплайн запуска упал", e)
            fail(LaunchError.Unknown(e.message ?: e.toString()))
        }
    }

    private fun fail(e: LaunchError) = onState(PlayState.LaunchFailed(e))
}
