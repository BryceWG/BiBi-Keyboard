package com.brycewg.asrkb.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Pro 版本宣传弹窗工具类
 *
 * 用于向用户展示 Pro 版本的功能介绍，仅显示一次。
 * 可在关于页面手动触发再次显示。
 */
object ProPromoDialog {

    private const val TAG = "ProPromoDialog"

    // Pro 版 Play 商店链接
    private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.brycewg.asrkb.pro"

    // Telegram 群链接
    private const val TELEGRAM_URL = "https://t.me/+UGFobXqi2bYzMDFl"

    /**
     * 检查是否需要显示弹窗（仅检查是否已显示过）
     *
     * @param context Context
     * @return true 如果需要显示弹窗
     */
    fun shouldShow(context: Context): Boolean {
        val prefs = Prefs(context)
        return !prefs.proPromoShown
    }

    /**
     * 显示弹窗（自动标记已显示）
     *
     * @param context Context
     */
    fun show(context: Context) {
        showInternal(context, markAsShown = true)
    }

    /**
     * 强制显示弹窗（不标记已显示，用于关于页面手动触发）
     *
     * @param context Context
     */
    fun showForce(context: Context) {
        showInternal(context, markAsShown = false)
    }

    /**
     * 检查并在需要时显示弹窗
     *
     * @param context Context
     * @return true 如果显示了弹窗
     */
    fun showIfNeeded(context: Context): Boolean {
        if (shouldShow(context)) {
            show(context)
            return true
        }
        return false
    }

    private fun showInternal(context: Context, markAsShown: Boolean) {
        try {
            val inflater = LayoutInflater.from(context)
            val customView = inflater.inflate(R.layout.dialog_pro_promo, null)

            val btnPlayStore = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPlayStore)
            val btnTelegram = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTelegram)
            val btnPaymentQr = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPaymentQr)
            val btnClose = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)

            val dialog = MaterialAlertDialogBuilder(context)
                .setView(customView)
                .setCancelable(true)
                .create()

            btnPlayStore.setOnClickListener {
                openUrl(context, PLAY_STORE_URL)
            }

            btnTelegram.setOnClickListener {
                openUrl(context, TELEGRAM_URL)
            }

            btnPaymentQr.setOnClickListener {
                showPaymentQrDialog(context)
            }

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            // 标记已显示（在弹窗关闭时）
            if (markAsShown) {
                dialog.setOnDismissListener {
                    val prefs = Prefs(context)
                    prefs.proPromoShown = true
                }
            }

            dialog.show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show Pro promo dialog", e)
        }
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser found to open URL: $url", e)
            Toast.makeText(context, R.string.error_open_browser, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open URL: $url", e)
            Toast.makeText(context, R.string.error_open_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // 作者邮箱
    private const val AUTHOR_EMAIL = "bryce1577006721@gmail.com"

    /**
     * 显示付款码弹窗
     *
     * @param context Context
     */
    private fun showPaymentQrDialog(context: Context) {
        try {
            val inflater = LayoutInflater.from(context)
            val customView = inflater.inflate(R.layout.dialog_payment_qr, null)

            val btnClose = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClosePayment)
            val btnSaveWechat = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveWechat)
            val btnSaveAlipay = customView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveAlipay)
            val layoutEmail = customView.findViewById<LinearLayout>(R.id.layoutEmail)

            val dialog = MaterialAlertDialogBuilder(context)
                .setView(customView)
                .setCancelable(true)
                .create()

            // 邮箱点击复制
            layoutEmail.setOnClickListener {
                copyEmailToClipboard(context)
            }

            // 保存微信付款码
            btnSaveWechat.setOnClickListener {
                saveQrCodeToGallery(context, R.drawable.qr_wechat_pay, "wechat_pay_qr")
            }

            // 保存支付宝付款码
            btnSaveAlipay.setOnClickListener {
                saveQrCodeToGallery(context, R.drawable.qr_alipay, "alipay_qr")
            }

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show payment QR dialog", e)
        }
    }

    /**
     * 复制邮箱到剪贴板
     */
    private fun copyEmailToClipboard(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("email", AUTHOR_EMAIL)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.payment_qr_email_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to copy email to clipboard", e)
        }
    }

    /**
     * 保存二维码到相册
     */
    private fun saveQrCodeToGallery(context: Context, drawableResId: Int, fileName: String) {
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, drawableResId)
            if (bitmap == null) {
                Toast.makeText(context, R.string.payment_qr_save_failed, Toast.LENGTH_SHORT).show()
                return
            }

            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                saveImageToMediaStore(context, bitmap, fileName)
            } else {
                // Android 9 及以下使用传统方式
                saveImageToExternalStorage(context, bitmap, fileName)
            }

            if (saved) {
                Toast.makeText(context, R.string.payment_qr_save_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.payment_qr_save_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save QR code to gallery", e)
            Toast.makeText(context, R.string.payment_qr_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Android 10+ 使用 MediaStore 保存图片
     */
    private fun saveImageToMediaStore(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${fileName}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LexiSharp")
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return false

        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save image via MediaStore", e)
            false
        }
    }

    /**
     * Android 9 及以下使用传统方式保存图片
     */
    @Suppress("DEPRECATION")
    private fun saveImageToExternalStorage(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val appDir = File(picturesDir, "LexiSharp")
        if (!appDir.exists() && !appDir.mkdirs()) {
            return false
        }

        val file = File(appDir, "${fileName}_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            // 通知媒体库扫描
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save image to external storage", e)
            false
        }
    }
}
