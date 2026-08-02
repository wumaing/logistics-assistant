package com.yymm.monitor.util

import com.google.gson.Gson

/**
 * JSON 工具类，封装 Gson 单例。
 */
object JsonUtil {

    val gson: Gson = Gson()

    fun <T> toJson(obj: T): String = gson.toJson(obj)

    inline fun <reified T> fromJson(json: String): T? =
        try {
            gson.fromJson(json, T::class.java)
        } catch (_: Exception) {
            null
        }
}
