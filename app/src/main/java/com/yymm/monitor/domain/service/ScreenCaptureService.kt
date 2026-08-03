package com.yymm.monitor.domain.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * 截屏服务（MediaProjection）。
 *
 * 由监控服务在每次刷新周期后调用，截取当前屏幕一帧（纯本地，不上传）。
 * 司机端屏蔽无障碍节点树/事件，截屏 + 本地 OCR 是唯一能读取订单文本的合法方案。
 *
 * 用法：
 * - 首次需通过 MainActivity 请求授权（getMediaProjectionManager().createScreenCaptureIntent()）
 * - 授权结果传给 startForeground + createVirtualDisplay
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val ACTION_START = "action_capture_start"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        /** 当前截屏服务的授权 intent 数据（授权后由 Activity 填充） */
        var pendingProjectionData: Intent? = null
        var pendingResultCode: Int = 0

        /** 最近一次截屏的 Bitmap（供 OCR 读取；null=未截到） */
        @Volatile
        var lastBitmap: Bitmap? = null

        /** 截屏服务是否正在运行 */
        @Volatile
        var isRunning = false

        /** 截一帧并保存到文件；返回文件路径，失败返回 null */
        fun captureOnce(context: Context): String? {
            val bmp = lastBitmap ?: return null
            return try {
                val file = File(context.cacheDir, "screen_capture.png")
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                file.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayMetrics: DisplayMetrics? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, pendingResultCode) ?: pendingResultCode
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: pendingProjectionData
        if (resultCode != 0 && data != null) {
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    /** 启动投影截屏 */
    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data) ?: return
        isRunning = true

        val metrics = resources.displayMetrics
        displayMetrics = metrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "screen-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, captureHandler
        )

        // 截一帧
        captureHandler?.post { captureFrame() }
    }

    /** 截取一帧并保存到 lastBitmap */
    private fun captureFrame() {
        val reader = imageReader ?: return
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        }
        image?.let {
            try {
                val plane = it.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * (it.width)
                val bmp = Bitmap.createBitmap(
                    it.width + rowPadding / pixelStride, it.height,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                lastBitmap = Bitmap.createBitmap(bmp, 0, 0, it.width, it.height)
            } catch (_: Exception) {
            } finally {
                it.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
    }
}
