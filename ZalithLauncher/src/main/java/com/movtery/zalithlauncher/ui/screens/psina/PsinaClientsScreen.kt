package com.movtery.zalithlauncher.ui.screens.psina

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.TaskStage
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ru.psina.core.Logx
import ru.psina.core.ManifestRepo
import ru.psina.core.PlayPipeline
import ru.psina.core.PlayState
import ru.psina.core.Prefs
import ru.psina.core.Store
import ru.psina.core.Support
import ru.psina.core.ZalithBackend

/**
 * Экран «Клиенты Псины»: список клиентов из манифеста и кнопка «Играть».
 *
 * Тяжёлая работа — в PlayPipeline (ru.psina.core): манифест → совместимость →
 * файлы → установка инстанса → родной запуск через ZalithBackend (runGame) с
 * фолбэком на внешние движки. Здесь только состояние (PlayState) и диалоги.
 *
 * Список: поиск по имени/версии, сортировка (готовые → экспериментальные →
 * только ПК), логотипы из репозитория конфигов (jsdelivr), обновление манифеста.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsinaClientsScreen(
    backStackViewModel: ScreenBackStackViewModel,
    toPsinaSettings: () -> Unit = {}
) {
    val context = LocalContext.current

    var clients by remember { mutableStateOf<List<ManifestRepo.Client>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var pendingClient by remember { mutableStateOf<ManifestRepo.Client?>(null) }
    var smsDialog by remember { mutableStateOf<String?>(null) }

    var pipeline by remember { mutableStateOf<PlayPipeline?>(null) }
    var playState by remember { mutableStateOf<PlayState>(PlayState.Idle) }

    // Авто-установка ванильной базы для «игры в один тап» на чистом телефоне.
    val scope = rememberCoroutineScope()
    var autoInstallFor by remember { mutableStateOf<String?>(null) }
    var autoInstallProgress by remember { mutableIntStateOf(-1) }
    var autoInstallError by remember { mutableStateOf<String?>(null) }

    // Первый показ — кэш/сеть; «Обновить» и «Повторить» — force из сети.
    // СЕТЬ — только с Dispatchers.IO: на главном потоке Android кидает
    // NetworkOnMainThreadException (баг первых релизов — манифест не грузился).
    LaunchedEffect(refreshTick) {
        loading = true
        error = null
        try {
            clients = withContext(Dispatchers.IO) {
                Store.loadManifest(force = refreshTick > 0).clients
            }
        } catch (e: Exception) {
            Logx.e("не удалось загрузить манифест клиентов", e)
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pipeline?.cancelDownload()
            // Уход с экрана посреди авто-установки: не оставляем полу-версию.
            PsinaAutoInstall.cancel()
        }
    }

    fun runPipeline(client: ManifestRepo.Client) {
        val p = PlayPipeline(context)
        pipeline = p
        p.onState = { st ->
            playState = st
            if (st is PlayState.LaunchRequestSent) {
                Prefs.clientId = client.id
                // Экспериментальные портативки могут запросить у игры права на
                // SMS/звонки — один раз честно предупреждаем после запуска.
                if (client.support == Support.EXPERIMENTAL && !Prefs.smsExplained) {
                    Prefs.smsExplained = true
                    smsDialog =
                        "Если игра попросит доступ к SMS или звонкам — это для определения модели " +
                            "телефона. Ничего не отправляется: можно отказать, игра будет работать."
                }
            }
        }
        // run() блокирующий (сеть/файлы) — уводим с UI-потока.
        thread(name = "psina-play-${client.id}") { p.run(client.id, Prefs.nickname, Prefs.ramGb) }
    }

    val startPlay: (ManifestRepo.Client) -> Unit = { client ->
        val mc = client.mc
        // На чистом телефоне у форка нет версии psina-<mc>: сначала тихо
        // ставим ванильную базу штатным установщиком форка (psina-<mc>),
        // затем сразу продолжаем обычный пайплайн — в один тап для игрока.
        if (ZalithBackend.findVersion(mc) == null && autoInstallFor == null) {
            autoInstallProgress = -1
            autoInstallFor = mc
            val started = PsinaAutoInstall.startVanillaInstall(context, mc, scope) { ok, err ->
                autoInstallFor = null
                if (ok) runPipeline(client) else autoInstallError = err
            }
            // Не смогли даже начать (установка уже идёт) — не оставляем
            // вечный диалог прогресса.
            if (!started) autoInstallFor = null
        } else {
            runPipeline(client)
        }
    }

    // Поиск + сортировка: сначала готовые к запуску, затем экспериментальные,
    // в конце «только на ПК»; внутри группы — по алфавиту.
    val shownClients = remember(clients, query) {
        val q = query.trim()
        clients
            .filter { q.isBlank() || it.name.contains(q, true) || it.mc.contains(q, true) }
            .sortedWith(
                compareBy(
                    { c ->
                        when (c.support) {
                            Support.READY -> 0
                            Support.EXPERIMENTAL -> 1
                            Support.PC_ONLY -> 2
                        }
                    },
                    { c -> c.name.lowercase() }
                )
            )
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
                    },
                    actions = {
                        IconButton(onClick = { refreshTick++ }, enabled = !loading) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Обновить список"
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
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Манифест недоступен: $error")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { refreshTick++ }) { Text("Повторить") }
                    }
                }

                else -> Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск клиента или версии") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "Игра без аккаунта: ник офлайн-профиля — «${Prefs.nickname}» · нажми, чтобы настроить",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { toPsinaSettings() }
                            )
                        }
                        if (shownClients.isEmpty()) {
                            item {
                                Text(
                                    text = "Ничего не найдено по «$query»",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(shownClients) { client ->
                            PsinaClientCard(
                                client = client,
                                busy = autoInstallFor != null ||
                                    playState !is PlayState.Idle &&
                                    playState !is PlayState.LaunchFailed &&
                                    playState !is PlayState.LaunchRequestSent,
                                onPlay = { pendingClient = client }
                            )
                        }
                    }
                }
            }
        }
    }

    // Прогресс авто-установки ванильной базы (опрос задач GameInstaller).
    PsinaAutoInstall.activeInstaller?.let { installer ->
        LaunchedEffect(installer) {
            while (isActive && PsinaAutoInstall.activeInstaller === installer) {
                val running = installer.tasksFlow.value
                    .filter { it.task.stage.value == TaskStage.RUNNING }
                autoInstallProgress = if (running.isEmpty()) -1
                else (running.maxOf { it.task.progress.value }.coerceAtLeast(0f) * 100).toInt()
                delay(200)
            }
        }
    }

    autoInstallFor?.let { mc ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Устанавливаю Minecraft $mc") },
            text = {
                Column {
                    Text(
                        "Первый запуск: скачиваю ванильную базу $mc. " +
                            "Это нужно один раз — дальше клиент встанет поверх и запуск станет быстрым."
                    )
                    Spacer(Modifier.height(8.dp))
                    if (autoInstallProgress >= 0) {
                        LinearProgressIndicator(
                            progress = { autoInstallProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("$autoInstallProgress%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PsinaAutoInstall.cancel()
                    autoInstallFor = null
                }) { Text("Отменить") }
            }
        )
    }

    autoInstallError?.let { err ->
        AlertDialog(
            onDismissRequest = { autoInstallError = null },
            title = { Text("Не удалось установить Minecraft") },
            text = { Text("Проверь интернет и попробуй ещё раз.\n\n$err") },
            confirmButton = {
                TextButton(onClick = { autoInstallError = null }) { Text("Понятно") }
            }
        )
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

    // Объяснение прав SMS/звонков: показываем один раз после первого
    // experimental-запуска — игра может запросить их у пользователя.
    smsDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { smsDialog = null },
            title = { Text("Права на SMS и звонки") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { smsDialog = null }) { Text("Понятно") }
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

/** Логотип клиента: детерминированный путь в репозитории конфигов. */
private fun logoUrl(client: ManifestRepo.Client): String =
    "https://cdn.jsdelivr.net/gh/pyBIrtat/PsinaLauncher@main/clients/${client.mc}/${client.id}.png"

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
            // Логотип с буквенным фолбэком, пока картинка грузится/нет в репо.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = client.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                AsyncImage(
                    model = logoUrl(client),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.size(10.dp))

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

            if (client.support == Support.PC_ONLY) {
                // Запускать с телефона нельзя честно: кнопки нет, объяснение на карточке.
                Text(
                    text = "Нужен ПК",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
}
