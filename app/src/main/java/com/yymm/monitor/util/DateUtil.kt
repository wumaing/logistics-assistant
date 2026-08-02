package com.yymm.monitor.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期工具类。
 *
 * 每次调用新建 SimpleDateFormat 实例，避免线程安全问题。
 */
object DateUtil {

    /** 今日日期 yyyy-MM-dd */
    fun getTodayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** 时间 HH:mm:ss */
    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))

    /** 完整时间 yyyy-MM-dd HH:mm:ss */
    fun formatDateTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))

    /** N 天前的日期 yyyy-MM-dd（用于历史清理） */
    fun getDateBefore(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(calendar.time)
    }
}
