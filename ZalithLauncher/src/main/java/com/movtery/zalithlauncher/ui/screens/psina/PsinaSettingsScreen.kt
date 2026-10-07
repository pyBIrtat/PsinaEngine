package com.movtery.zalithlauncher.ui.screens.psina

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import kotlin.math.roundToInt
import ru.psina.core.Prefs

/**
 * Экран «Настройки Псины» (этап E5): ник офлайн-профиля, оперативная память
 * для игры и вход в управление (экранные кнопки форка).
 *
 * Значения сохраняются в Prefs сразу при изменении; ZalithBackend использует
 * их при нативном запуске (ник — в локальном аккаунте, RAM — в лог и на E6
 * в VersionConfig.ramAllocation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsinaSettingsScreen(
    backStackViewModel: ScreenBackStackViewModel,
    toControlManager: () -> Unit = {}
) {
    var nickname by remember { mutableStateOf(Prefs.nickname) }
    var ramGb by remember { mutableStateOf(Prefs.ramGb.toFloat()) }

    BaseScreen(
        screenKey = NormalNavKey.PsinaSettings,
        currentKey = backStackViewModel.mainScreen.currentKey
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Настройки Псины") },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { value ->
                        nickname = value
                        val trimmed = value.trim()
                        if (trimmed.isNotEmpty()) Prefs.nickname = trimmed
                    },
                    label = { Text("Ник в игре (оффлайн-аккаунт)") },
                    supportingText = {
                        Text("Этот ник используется по умолчанию. Microsoft-вход — дополнительно, с экрана «Учётная запись» → «Добавить аккаунт».")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Оперативная память для игры: ${ramGb.roundToInt()} ГБ",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = ramGb,
                    onValueChange = { value ->
                        ramGb = value
                        Prefs.ramGb = value.roundToInt().coerceAtLeast(2)
                    },
                    valueRange = 2f..12f,
                    steps = 9
                )
                Text(
                    text = "Больше памяти — меньше лагов на тяжёлых клиентах, но телефон может закрывать игру. 4 ГБ — разумный минимум.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = toControlManager,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Кнопки управления")
                }
                Text(
                    text = "Настроить экранные кнопки, которыми вы управляете в игре: раскладка, размер, прозрачность.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
