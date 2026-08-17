package com.douyin.auto.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 系统级录屏服务（MediaProjection）。
 *
 * 抖音视频画面无法被无障碍服务直接读取像素，因此本服务通过 MediaProjection +
 * ImageReader + VirtualDisplay 把抖音界面实时渲染到离屏缓冲区，供视频分析功能随机截帧。
 *
 * 该服务以前台服务形式运行，前台服务类型为 mediaProjection（Android 10+）。
 * 录屏授权需由 Activity 通过 [MediaProjectionManager.createScreenCaptureIntent] 获取，
 * 并以 Intent 的 "resultCode" / "data" 附带启动本服务。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID = 1001

        /** 当前实例（供视频分析功能调用截帧） */
        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        /** 录屏是否可用 */
        fun isAvailable(): Boolean = instance != null
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "缺少录屏授权数据，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        setupVirtualDisplay()
        instance = this
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val dpi = dm.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DouyinScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    /**
     * 截取当前一帧并压缩为 JPEG 字节数组（最长边缩放到 [maxEdge] 以减小体积）。
     * @return JPEG 字节数组；若暂无可用的帧或出错则返回 null
     */
    fun captureFrameJpeg(maxEdge: Int = 720, quality: Int = 70): ByteArray? {
        val reader = imageReader ?: return null
        var image: android.media.Image? = null
        try {
            // 等待可用帧（最多约 600ms）
            var attempt = 0
            while (attempt < 12) {
                image = reader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(50)
                attempt++
            }
            if (image == null) return null

            val bitmap = imageToBitmap(image) ?: return null
            val scaled = downscale(bitmap, maxEdge)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled != bitmap) scaled.recycle()
            bitmap.recycle()
            return out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "截帧失败: ${e.message}", e)
            return null
        } finally {
            image?.close()
        }
    }

    /** 把 ImageReader 的 RGBA_8888 帧转换为 Bitmap */
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    /** 等比缩放到最长边不超过 [maxEdge] */
    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longSide = maxOf(w, h)
        if (longSide <= maxEdge) return src
        val scale = maxEdge.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            src, (w * scale).toInt(), (h * scale).toInt(), true
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "录屏分析", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频分析录屏中")
            .setContentText("正在截取抖音视频画面用于内容分析")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
