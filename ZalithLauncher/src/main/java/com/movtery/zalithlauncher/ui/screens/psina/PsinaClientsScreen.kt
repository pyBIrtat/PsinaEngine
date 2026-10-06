package com.movtery.zalithlauncher.ui.screens.psina

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import kotlin.concurrent.thread
import ru.psina.core.Logx
import ru.psina.core.ManifestRepo
import ru.psina.core.PlayPipeline
import ru.psina.core.PlayState
import ru.psina.core.Prefs
import ru.psina.core.Store
import ru.psina.core.Support

/**
 * Экран «Клиенты Псины» (этап E4): список клиентов из манифеста и кнопка «Играть».
 *
 * Тяжёлая работа — в PlayPipeline (ru.psina.core): манифест → совместимость →
 * файлы → установка инстанса → родной запуск через ZalithBackend (runGame) с
 * фолбэком на внешние движки. Здесь только состояние (PlayState) и диалоги:
 *  - подтверждение «клиент — не ванильный Minecraft» (осознанный запуск мода);
 *  - объяснение прав SMS/звонков, которые запрашивают некоторые клиенты
 *    (ничего не отправляется, права можно не давать).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsinaClientsScreen(
    backStackViewModel: ScreenBackStackViewModel
) {
    val context = LocalContext.current

    var clients by remember { mutableStateOf<List<ManifestRepo.Client>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var pendingClient by remember { mutableStateOf<ManifestRepo.Client?>(null) }
    var smsDialog by remember { mutableStateOf<String?>(null) }

    var pipeline by remember { mutableStateOf<PlayPipeline?>(null) }
    var playState by remember { mutableStateOf<PlayState>(PlayState.Idle) }

    LaunchedEffect(Unit) {
        try {
            clients = Store.loadManifest(false).clients
        } catch (e: Exception) {
            Logx.e("не удалось загрузить манифест клиентов", e)
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { pipeline?.cancelDownload() }
    }

    val startPlay: (ManifestRepo.Client) -> Unit = { client ->
        val p = PlayPipeline(context)
        pipeline = p
        p.onState = { st ->
            playState = st
            if (st is PlayState.LaunchRequestSent) Prefs.clientId = client.id
        }
        // run() блокирующий (сеть/файлы) — уводим с UI-потока.
        thread(name = "psina-play-${client.id}") { p.run(client.id, Prefs.nickname, Prefs.ramGb) }
    }

    BaseScreen(
        screenKey = NormalNavKey.PsinaClients,
        currentKey = backStackViewModel.mainScreen.currentKey
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Клиенты Псины") },
                    navigationIcon = {
                        IconButton(onClick = {
                            backStackViewModel.mainScreen.clearWith(NormalNavKey.LauncherMain)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = null
                            )
                        }
                    }
                )
            }
        ) { padding ->
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { Text("Манифест недоступен: $error") }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Игра без аккаунта: ник офлайн-профиля — «${Prefs.nickname}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(clients) { client ->
                        PsinaClientCard(
                            client = client,
                            busy = playState !is PlayState.Idle &&
                                playState !is PlayState.LaunchFailed &&
                                playState !is PlayState.LaunchRequestSent,
                            onPlay = { pendingClient = client }
                        )
                    }
                }
            }
        }
    }

    // Подтверждение: «это мод, а не ваниль».
    pendingClient?.let { client ->
        AlertDialog(
            onDismissRequest = { pendingClient = null },
            title = { Text("Это не ванильный Minecraft") },
            text = {
                Text(
                    "«${client.name}» — кастомный клиент с модами. Установка может занять " +
                        "несколько минут (до 300 МБ). Продолжить?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = client
                    pendingClient = null
                    startPlay(c)
                }) { Text("Продолжить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClient = null }) { Text("Отмена") }
            }
        )
    }

    // Объяснение прав SMS/звонков (клиенты могут запросить; мы не просим заранее).
    smsDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { smsDialog = null },
            title = { Text("Права на SMS и звонки") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    smsDialog = null
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    }
                }) { Text("Открыть настройки") }
            },
            dismissButton = {
                TextButton(onClick = { smsDialog = null }) { Text("Позже") }
            }
        )
    }

    when (val st = playState) {
        is PlayState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Скачивание") },
            text = {
                Column {
                    Text(st.file, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { st.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${st.percent}%")
                }
            },
            confirmButton = {
                TextButton(onClick = { pipeline?.cancelDownload() }) { Text("Отменить") }
            }
        )

        is PlayState.LaunchFailed -> AlertDialog(
            onDismissRequest = { playState = PlayState.Idle },
            title = { Text(st.error.title) },
            text = { Text("${st.error.reason}\n\n${st.error.whatToDo}") },
            confirmButton = { TextButton(onClick = { playState = PlayState.Idle }) { Text("Понятно") } }
        )

        is PlayState.LaunchRequestSent -> AlertDialog(
            onDismissRequest = { playState = PlayState.Idle },
            title = { Text("Запуск отправлен") },
            text = { Text("«${st.engineTitle}»: запрос на запуск отправлен. Если игра не открылась — открой её вручную.") },
            confirmButton = { TextButton(onClick = { playState = PlayState.Idle }) { Text("ОК") } }
        )

        else -> {}
    }
}

private fun supportLabel(client: ManifestRepo.Client): String = when (client.support) {
    Support.READY -> "готов к запуску"
    Support.EXPERIMENTAL -> "экспериментально"
    Support.PC_ONLY -> "только на ПК"
}

@Composable
private fun PsinaClientCard(
    client: ManifestRepo.Client,
    busy: Boolean,
    onPlay: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = client.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Minecraft ${client.mc} · ${supportLabel(client)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                client.android?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Button(onClick = onPlay, enabled = !busy) {
                Icon(
                    painter = painterResource(R.drawable.ic_play_arrow_filled),
                    contentDescription = null
                )
                Text("Играть")
            }
        }
    }
}
