package ru.psina.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("psina", Context.MODE_PRIVATE)
    }

    var nickname: String
        get() = sp.getString("nickname", "Player") ?: "Player"
        set(v) = sp.edit().putString("nickname", v).apply()

    var ramGb: Int
        get() = sp.getInt("ramGb", 4)
        set(v) = sp.edit().putInt("ramGb", v).apply()

    var sensitivity: Float
        get() = sp.getFloat("sens", 1.35f)
        set(v) = sp.edit().putFloat("sens", v).apply()

    var javaArgs: String
        get() = sp.getString("javaArgs", "") ?: ""
        set(v) = sp.edit().putString("javaArgs", v).apply()

    var clientId: String?
        get() = sp.getString("clientId", null)
        set(v) = sp.edit().putString("clientId", v).apply()

    /** Показывали ли уже объяснение про права SMS/звонков (после первого experimental-запуска). */
    var smsExplained: Boolean
        get() = sp.getBoolean("smsExplained", false)
        set(v) = sp.edit().putBoolean("smsExplained", v).apply()

    var serverId: String?
        get() = sp.getString("serverId", null)
        set(v) = sp.edit().putString("serverId", v).apply()

    var activeLayout: String
        get() = sp.getString("activeLayout", "default") ?: "default"
        set(v) = sp.edit().putString("activeLayout", v).apply()

    var autoVerify: Boolean
        get() = sp.getBoolean("autoVerify", true)
        set(v) = sp.edit().putBoolean("autoVerify", v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", true)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    var haptic: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) = sp.edit().putBoolean("haptic", v).apply()

    var engineFolderUri: String?
        get() = sp.getString("engineFolderUri", null)
        set(v) = sp.edit().putString("engineFolderUri", v).apply()

    var manifestUrl: String
        get() = sp.getString("manifestUrl", DEFAULT_MANIFEST) ?: DEFAULT_MANIFEST
        set(v) = sp.edit().putString("manifestUrl", v).apply()

    const val DEFAULT_MANIFEST =
        "https://cdn.jsdelivr.net/gh/pyBIrtat/PsinaLauncher@main/launcher-online.json"
}
