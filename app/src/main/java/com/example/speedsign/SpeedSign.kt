package com.example.speedsign

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ===== غيّر هذا حسب عنوان الـ Pi =====
const val PI_URL = "http://10.42.0.1:5000/limit"
// =====================================

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // صلاحية الظهور فوق التطبيقات
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        val tv = TextView(this).apply {
            text = "تطبيق لوحات السرعة يشتغل.\nأعط صلاحية الظهور فوق التطبيقات ثم ارجع."
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        setContentView(tv)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            val i = Intent(this, SpeedService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }
}

class SpeedService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var overlay: TextView? = null
    @Volatile private var running = true

    private var lastRaw: String? = null     // آخر قراءة
    private var streak = 0                  // كم مرة تكررت
    private var shown: String? = null       // اللي معروض حالياً
    private val hideRunnable = Runnable { removeOverlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        Thread { pollLoop() }.start()
    }

    private fun buildNotification(): Notification {
        val chId = "speedsign"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "Speed Sign", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId)
                else Notification.Builder(this)
        return b.setContentTitle("كاشف لوحات السرعة يشتغل")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun pollLoop() {
        while (running) {
            try {
                val c = URL(PI_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 1500
                val body = c.inputStream.bufferedReader().readText()
                c.disconnect()
                val j = JSONObject(body)
                val limit = if (j.isNull("limit")) null else j.optString("limit")
                handler.post { onReading(limit) }
            } catch (e: Exception) {
                // الـ Pi مو متصل، نكمل المحاولة بصمت
            }
            Thread.sleep(500)
        }
    }

    // نعتمد القراءة إذا تكررت 3 مرات متتالية
    private fun onReading(limit: String?) {
        if (limit.isNullOrEmpty()) { streak = 0; lastRaw = null; return }
        if (limit == lastRaw) streak++ else { lastRaw = limit; streak = 1 }
        if (streak >= 3 && limit != shown) {
            shown = limit
            showOverlay("السرعة المسموحة\n$limit")
        }
    }

    private fun showOverlay(text: String) {
        if (!Settings.canDrawOverlays(this)) return
        removeOverlay(keepShown = true)

        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(60, 30, 60, 30)
        }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 40
        }
        wm.addView(tv, lp)
        overlay = tv

        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 8000)
    }

    private fun removeOverlay(keepShown: Boolean = false) {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        if (!keepShown) shown = null   // يسمح بإظهار نفس الرقم مرة ثانية بعد الاختفاء
    }

    override fun onDestroy() {
        running = false
        removeOverlay()
        super.onDestroy()
    }
}

// تشغيل تلقائي مع تشغيل السيارة (إذا النظام يسمح)
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Settings.canDrawOverlays(ctx)
        ) {
            val i = Intent(ctx, SpeedService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
