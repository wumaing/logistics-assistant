package com.yymm.monitor.domain.service

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR 引擎（ML Kit 中文识别，Google 官方 on-device 离线）。
 *
 * 对截屏 Bitmap 做 OCR 识别，输出文本供订单解析。
 * ML Kit 中文识别率（90%+）远高于 tesseract（手机 UI 小字号约 20%），
 * 且完全离线（on-device），不上传任何数据。
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * 识别截屏文本（ML Kit 是异步 API，这里用阻塞等待封装成同步）。
     * @return 识别出的文本；失败返回空字符串
     */
    fun recognize(bitmap: Bitmap): String {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            result.text?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 释放 OCR 资源 */
    fun destroy() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
    }

    /** Tasks 工具：阻塞等待 ML Kit 异步结果 */
    private object Tasks {
        fun <T> await(task: com.google.android.gms.tasks.Task<T>): T {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: T? = null
            var error: Exception? = null
            task.addOnSuccessListener {
                result = it
                latch.countDown()
            }.addOnFailureListener {
                error = it as? Exception ?: Exception(it)
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            error?.let { throw it }
            return result ?: throw IllegalStateException("OCR timeout")
        }
    }
}
