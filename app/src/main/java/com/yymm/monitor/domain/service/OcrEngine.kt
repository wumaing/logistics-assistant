package com.yymm.monitor.domain.service

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 OCR 引擎（tesseract，中文）。
 *
 * 对截屏 Bitmap 做 OCR 识别，输出文本供订单解析。
 * 纯本地识别，不上传任何数据。traineddata 从 assets 复制到
 * tessdata 目录（首次启动复制，约 20MB）。
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val DATA_PATH_PREFIX = "tessdata"
        private const val TRAINED_DATA = "chi_sim.traineddata"
    }

    @Volatile
    private var tessApi: TessBaseAPI? = null

    @Volatile
    private var ready = false

    /**
     * 初始化 OCR（幂等）：复制训练数据到内部存储 + 创建 TessBaseAPI。
     * @return true=初始化成功
     */
    fun init(): Boolean {
        if (ready) return true
        return try {
            val tessDir = File(context.filesDir, DATA_PATH_PREFIX)
            val trainedFile = File(tessDir, TRAINED_DATA)
            if (!trainedFile.exists()) {
                tessDir.mkdirs()
                // 从 assets 复制 chi_sim.traineddata（assets/tessdata/ 下）
                context.assets.open("$DATA_PATH_PREFIX/$TRAINED_DATA").use { input ->
                    FileOutputStream(trainedFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val api = TessBaseAPI()
            val ok = api.init(context.filesDir.absolutePath, "chi_sim")
            if (ok) {
                tessApi = api
                ready = true
            }
            ready
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 识别截屏文本。
     * @return 识别出的文本行；失败返回空字符串
     */
    fun recognize(bitmap: Bitmap): String {
        if (!init()) return ""
        val api = tessApi ?: return ""
        return try {
            api.setImage(bitmap)
            // 页面分割模式 6=块文本（订单列表是块状文本，比默认模式准确）
            api.pageSegMode = com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            // 白名单：中文 + 数字 + 常用符号（过滤乱码）
            api.setVariable("tessedit_char_whitelist", "全国省市自治区县区镇乡村北京天津上海重庆山东江苏浙江安徽福建江西河南湖北湖南广东广西海南四川贵州云南陕西甘肃青海宁夏新疆西藏内蒙古辽宁吉林黑龙江河北山西0123456789.元吨公里到至→-~·：:()（）米平板高栏厢式集装箱拖车自卸冷藏保温高低板面包车棉被爬梯飞翼依维柯整车优车即时拼车电议面议今天明天后天")
            api.utF8Text?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 释放 OCR 资源 */
    fun destroy() {
        try {
            tessApi?.end()
        } catch (_: Exception) {
        }
        tessApi = null
        ready = false
    }
}
