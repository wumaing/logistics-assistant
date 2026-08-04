package com.yymm.monitor.data.model

/**
 * 用户设置（TECH_SPEC 2.1.5）。
 *
 * 整个对象通过 Gson 序列化后存储于 SharedPreferences。
 */
data class UserSettings(
    val refreshIntervalSeconds: Int = 5,
    val matchMode: String = "INCLUDE_CHILDREN",  // INCLUDE_CHILDREN=包含下级匹配, EXACT=同层精确匹配
    // 自动刷新起点坐标（用户校准；-1=使用默认屏高比例）
    val refreshStartX: Int = -1,
    val refreshStartY: Int = -1,
    // 自动刷新终点坐标（用户校准；-1=使用默认屏高 65%）
    val refreshEndX: Int = -1,
    val refreshEndY: Int = -1,
    // 刷新滑动手势时长（ms；0=默认，默认推荐 400ms）
    val refreshDurationMs: Int = 400,
    val myVehicleLength: String? = null,
    val popupEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val vibrateDurationMs: Long = 3000,
    val ringtoneEnabled: Boolean = true,
    val ringtoneUri: String? = null,
    val ringtoneVolume: Float = 1.0f,
    val ttsEnabled: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val popupDurationSeconds: Int = 0,              // 0=一直显示直到点击
    val multiOrderHandling: String = "QUEUE",       // QUEUE=排队依次, LATEST=只弹最新
    val floatingWindowSizeDp: Int = 56,
    val floatingWindowAlpha: Float = 0.9f,
    val floatingWindowAutoStick: Boolean = true,
    val floatingWindowDefaultX: Int = -1,
    val floatingWindowDefaultY: Int = 400,
    val historyRetentionDays: Int = 7,              // 7/30/0(永久)
    val autoStartOnBoot: Boolean = false
)
