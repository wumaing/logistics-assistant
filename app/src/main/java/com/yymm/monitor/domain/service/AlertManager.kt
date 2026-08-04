package com.yymm.monitor.domain.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.gson.Gson
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.model.UserSettings
import com.yymm.monitor.data.repository.HistoryRepository
import com.yymm.monitor.data.repository.SettingsRepository
import com.yymm.monitor.presentation.AlertPopupActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 提醒管理器（TECH_SPEC 2.3.4 / PRD 3.7）。
 *
 * 职责：
 * - 当日订单去重（内存 + 数据库初始化加载）
 * - 多订单处理：QUEUE 排队依次弹窗 / LATEST 只弹最新
 * - 三种提醒效果：震动、响铃、语音播报（按设置独立开关）
 * - 页面错误提示转发给悬浮窗
 */
@Singleton
class AlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository
) {

    private val todayAlertedOrders = Collections.synchronizedSet<String>(LinkedHashSet())
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private val alertQueue = LinkedBlockingQueue<ParsedOrder>()
    private var isProcessingQueue = false

    init {
        // 初始化 TTS 引擎，中文播报
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
        // 从数据库加载今日已提醒订单，恢复去重状态
        CoroutineScope(Dispatchers.IO).launch {
            todayAlertedOrders.addAll(historyRepository.getTodayAlertedOrderIds())
        }
    }

    fun isAlertedToday(orderId: String): Boolean {
        return todayAlertedOrders.contains(orderId)
    }

    suspend fun triggerAlert(order: ParsedOrder, missingFields: Set<String> = emptySet()) {
        todayAlertedOrders.add(order.id)
        historyRepository.recordAlert(order)
        val settings = settingsRepository.getSettings()

        when (settings.multiOrderHandling) {
            "QUEUE" -> {
                alertQueue.offer(order)
                processQueue(settings, missingFields)
            }
            "LATEST" -> {
                showAlertPopup(order, missingFields)
                triggerEffects(order, settings)
            }
        }
    }

    /**
     * 排队处理队列中的订单弹窗。
     *
     * popupDurationSeconds > 0 时按设定时长展示；0 表示一直显示（依赖用户点击关闭，
     * 这里用一个较短 delay 防止连续弹窗过快）。
     */
    private fun processQueue(settings: UserSettings, missingFields: Set<String> = emptySet()) {
        if (isProcessingQueue) return
        isProcessingQueue = true
        CoroutineScope(Dispatchers.Main).launch {
            while (alertQueue.isNotEmpty()) {
                val order = alertQueue.poll() ?: break
                showAlertPopup(order, missingFields)
                triggerEffects(order, settings)
                // 弹窗显示时长：settings.popupDurationSeconds 秒，0 表示一直显示
                // 如果 >0 则 delay，否则不 delay（依赖用户点击关闭）
                if (settings.popupDurationSeconds > 0) {
                    delay(settings.popupDurationSeconds * 1000L + 500)
                } else {
                    // 一直显示，等待用户操作，这里简单 delay 一个较长间隔防止连续弹窗过快
                    delay(500)
                }
            }
            isProcessingQueue = false
        }
    }

    private fun showAlertPopup(order: ParsedOrder, missingFields: Set<String> = emptySet()) {
        val intent = Intent(context, AlertPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra("order_json", Gson().toJson(order))
            putExtra("missing_fields", missingFields.joinToString(","))
        }
        context.startActivity(intent)
    }

    private fun triggerEffects(order: ParsedOrder, settings: UserSettings) {
        if (settings.vibrateEnabled) vibrate(settings.vibrateDurationMs)
        if (settings.ringtoneEnabled) playRingtone(settings)
        if (settings.ttsEnabled) speakOrder(order, settings.ttsSpeed)
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun playRingtone(settings: UserSettings) {
        try {
            mediaPlayer?.release()
            val uri = settings.ringtoneUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setVolume(settings.ringtoneVolume, settings.ringtoneVolume)
                prepare()
                start()
                setOnCompletionListener { it.release() }
            }
        } catch (e: Exception) {
            Log.e("AlertManager", "Play ringtone failed", e)
        }
    }

    /**
     * 语音播报（PRD G.3）：
     * "发现新订单，从{出发地}到{目的地}，运费{价格}，重量{重量}吨，请查看。"
     * 不播报车型、车长、距离等额外信息。
     */
    private fun speakOrder(order: ParsedOrder, speed: Float) {
        tts?.let { engine ->
            engine.setSpeechRate(speed)
            val pricePart = when {
                order.isDianyi && order.priceAmount == null -> "运费电议"
                order.isDianyi -> "运费电议${order.priceAmount}元"
                order.priceAmount != null -> "运费${order.priceAmount}元"
                else -> ""
            }
            val weightPart = order.weightMin?.let { min ->
                order.weightMax?.let { max ->
                    if (min == max) "重量${min}吨" else "重量${min}到${max}吨"
                } ?: "重量${min}吨"
            } ?: ""
            val text = buildString {
                append("发现新订单，")
                append("从${order.fromProvince}${order.fromCity ?: ""}")
                append("到${order.toProvince}${order.toCity ?: ""}，")
                if (pricePart.isNotEmpty()) append("$pricePart，")
                if (weightPart.isNotEmpty()) append("$weightPart，")
                append("请查看。")
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun showPageError(message: String) {
        FloatingWindowManager.showError(message)
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
    }
}
