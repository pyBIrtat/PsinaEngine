package ru.psina.core

import android.content.Context

/**
 * Инициализация psina-ядра. Вызывается из ZLApplication.onCreate (initializeData)
 * как можно раньше: Paths.init до любых обращений экранов, чтобы не ловить
 * "lateinit property root has not been initialized" (край — экран «Клиенты
 * Псины» открыли до завершения инициализации приложения).
 */
object PsinaBoot {

    @Volatile
    var ready: Boolean = false
        private set

    fun init(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            Paths.init(ctx)
            Prefs.init(ctx)
            Logx.init(Paths.logs)
            Logx.i("psina-ядро инициализировано")
            ready = true
        }
    }
}
