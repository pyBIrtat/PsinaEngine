package ru.psina.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

/** Проверки совместимости ТЕЛЕФОНА — выполняются до скачивания, а не после. */
object DeviceCompat {

    data class Report(
        val sdkInt: Int,
        val abis: List<String>,
        val totalRamMb: Long,
        val freeStorageMb: Long,
        val isArm64: Boolean
    )

    fun scan(ctx: Context): Report {
        val abis = Build.SUPPORTED_ABIS?.toList() ?: listOf("unknown")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val stat = StatFs(Paths.root.absolutePath)
        return Report(
            sdkInt = Build.VERSION.SDK_INT,
            abis = abis,
            totalRamMb = mi.totalMem / (1024 * 1024),
            freeStorageMb = stat.availableBytes / (1024 * 1024),
            isArm64 = abis.contains("arm64-v8a")
        )
    }

    /** Жёсткая несовместимость — установку лучше не начинать вообще. */
    fun hardBlocker(report: Report, spec: AndroidSpec, estimatedSizeMb: Long): LaunchError? {
        if (spec.minAndroidApi > 0 && report.sdkInt < spec.minAndroidApi) {
            return LaunchError.UnsupportedAndroid(report.sdkInt, spec.minAndroidApi)
        }
        if (spec.architecture.equals("arm64-v8a", ignoreCase = true) && !report.isArm64) {
            return LaunchError.UnsupportedArch(report.abis, spec.architecture)
        }
        if (estimatedSizeMb > 0) {
            // +15% (минимум +64МБ) про запас на распаковку/временные файлы
            val neededMb = (estimatedSizeMb * 1.15).toLong().coerceAtLeast(estimatedSizeMb + 64)
            if (report.freeStorageMb < neededMb) {
                return LaunchError.NotEnoughStorage(neededMb, report.freeStorageMb)
            }
        }
        return null
    }

    /** Мягкие предупреждения — ставить можно, но стоит показать текстом. */
    fun softWarnings(report: Report, spec: AndroidSpec): List<String> {
        val out = mutableListOf<String>()
        if (spec.requiredMemoryMb > 0 && report.totalRamMb < spec.requiredMemoryMb) {
            out += "Рекомендуется ${spec.requiredMemoryMb} МБ ОЗУ, на телефоне ~${report.totalRamMb} МБ"
        }
        if (!report.isArm64) {
            out += "Нет arm64-v8a — часть нативов (LWJGL/моды) может не загрузиться"
        }
        return out
    }
}
