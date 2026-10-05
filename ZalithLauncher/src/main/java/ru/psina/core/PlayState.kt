package ru.psina.core

/**
 * Единый конечный автомат кнопки «Играть». Любой экран показывает ровно
 * одно из этих состояний — это и есть прогресс пайплайна.
 *
 * ВАЖНО: состояние «инстент ушёл» намеренно НЕ называется MinecraftStarted,
 * а называется LaunchRequestSent — подтвердить фактический запуск Minecraft
 * у стороннего движка без его API невозможно (см. Engine.requestLaunch).
 */
sealed class PlayState {
    object Idle : PlayState()
    object LoadingManifest : PlayState()
    object CheckingPhoneStorage : PlayState()
    object CheckingAndroidCompatibility : PlayState()
    object CheckingFiles : PlayState()
    data class Downloading(
        val file: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val percent: Int
    ) : PlayState()
    object Verifying : PlayState()
    object Installing : PlayState()
    object PreparingMobileProfile : PlayState()
    object PreparingRuntime : PlayState()
    data class LaunchingMinecraft(val engineTitle: String) : PlayState()
    /** Intent ушёл без ошибки. НЕ означает, что Minecraft открылся. */
    data class LaunchRequestSent(val engineTitle: String) : PlayState()
    object MinecraftExited : PlayState()
    data class LaunchFailed(val error: LaunchError) : PlayState()
}

/** У каждой ошибки — заголовок, причина и понятное «что делать». */
sealed class LaunchError(val title: String, val reason: String, val whatToDo: String) {
    object ManifestUnavailable : LaunchError(
        "Манифест недоступен",
        "Нет интернета и нет сохранённого манифеста на телефоне",
        "Проверь соединение и нажми «Обновить манифест»"
    )
    object ManifestCorrupt : LaunchError(
        "Манифест повреждён",
        "launcher-online.json не разобрался как JSON",
        "Подожди новую версию манифеста или удали кэш манифеста в «Ещё»"
    )
    data class ClientNotFound(val id: String) : LaunchError(
        "Клиент не найден", "В манифесте больше нет клиента «$id»", "Обнови список клиентов"
    )
    data class VersionNotFound(val mc: String) : LaunchError(
        "Версия не найдена", "В манифесте нет версии Minecraft $mc", "Выбери другую версию из списка"
    )
    data class EngineVersionMissing(val mc: String) : LaunchError(
        "Версия не установлена в движке",
        "В Psina Engine нет установленной версии для Minecraft $mc",
        "Установи Minecraft $mc в лаунчере, либо поставь внешний движок с этой версией"
    )
    data class DownloadFailed(val file: String, val cause: String) : LaunchError(
        "Файл не скачался", "$file: $cause", "Проверь интернет и нажми «Повторить»"
    )
    data class ChecksumMismatch(val file: String) : LaunchError(
        "Файл повреждён при загрузке", "sha256 «$file» не совпал с манифестом",
        "Повреждённый файл уже удалён — нажми «Повторить», перекачаем заново"
    )
    data class NotEnoughStorage(val neededMb: Long, val freeMb: Long) : LaunchError(
        "Недостаточно места на телефоне",
        "Нужно примерно $neededMb МБ, свободно $freeMb МБ",
        "Освободи место (удали другие инстансы в «Ещё») и попробуй снова"
    )
    object StorageDenied : LaunchError(
        "Нет доступа к хранилищу",
        "Android запретил запись в папку приложения",
        "Переустанови PsinaLauncher — используется только приватная папка приложения"
    )
    data class UnsupportedAndroid(val have: Int, val need: Int) : LaunchError(
        "Старая версия Android",
        "На телефоне API $have, этому клиенту нужен минимум $need",
        "Для этого клиента нужен более новый Android"
    )
    data class UnsupportedArch(val have: List<String>, val need: String) : LaunchError(
        "Другая архитектура процессора",
        "На телефоне: ${have.joinToString()}; клиенту нужен $need",
        "Нативные библиотеки для этой архитектуры отсутствуют — запуск невозможен"
    )
    data class NotEnoughRam(val haveMb: Long, val needMb: Long) : LaunchError(
        "Мало оперативной памяти",
        "На телефоне ~$haveMb МБ, клиент рекомендует от $needMb МБ",
        "Можно продолжить на свой риск — уменьши ОЗУ в настройках игры"
    )
    object NoEngine : LaunchError(
        "Нет движка Java-Minecraft",
        "На телефоне не найден Zalith Launcher, Amethyst, PojavLauncher или Mojo",
        "Поставь один из них — после этого профиль можно будет передать ему"
    )
    object EngineRejected : LaunchError(
        "Движок не открылся",
        "Android не смог запустить активность выбранного движка",
        "Попробуй другой установленный движок или экспортируй инстанс архивом"
    )
    object ClientIncompatible : LaunchError(
        "Клиент помечен «Только ПК»",
        "Манифест прямо говорит, что рантайм этого клиента на телефоне не работает",
        "Нужна мобильная пересборка клиента — одними настройками лаунчера это не решить"
    )
    object UserCancelled : LaunchError(
        "Остановлено пользователем", "Загрузка отменена",
        "Нажми «Играть» ещё раз, чтобы продолжить с того же места"
    )
    object NetworkLost : LaunchError(
        "Пропал интернет", "Соединение прервалось во время загрузки",
        "Докачаем только недостающее, как только сеть вернётся"
    )
    object InterruptedInstall : LaunchError(
        "Установка прервалась", "Приложение было закрыто или убито системой во время установки",
        "Нажми «Играть» — за счёт профиля с SHA-256 докачается только недостающее"
    )
    data class Unknown(val message: String) : LaunchError(
        "Непонятная ошибка", message, "Подробности — в логе (Показать лог в «Ещё»), пришли его при репорте бага"
    )
}
