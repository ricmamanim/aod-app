package com.example.aod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class AodActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var content: LinearLayout
    private lateinit var clock: TextView
    private lateinit var dateText: TextView
    private lateinit var battery: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var mediaPanel: View
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var notifRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var controller: MediaController? = null
    private lateinit var gestureDetector: GestureDetector

    // ---- burn-in protection -------------------------------------------------
    private val shiftRunnable = object : Runnable {
        override fun run() {
            shiftContent()
            handler.postDelayed(this, SHIFT_INTERVAL_MS)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }

    // Screen turned on by the user via power button -> hand control back.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowFlags()
        setContentView(R.layout.activity_aod)
        bindViews()
        setupGestures()
        acquireWakeLock()
        hideSystemBars()
    }

    private fun applyWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        // Lowest possible brightness: AOD should be dim.
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
        window.setBackgroundDrawableResource(android.R.color.black)
    }

    private fun bindViews() {
        root = findViewById(R.id.aod_root)
        content = findViewById(R.id.aod_content)
        clock = findViewById(R.id.tv_clock)
        dateText = findViewById(R.id.tv_date)
        battery = findViewById(R.id.tv_battery)
        mediaPanel = findViewById(R.id.media_panel)
        trackTitle = findViewById(R.id.tv_track)
        trackArtist = findViewById(R.id.tv_artist)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnNext = findViewById(R.id.btn_next)
        notifRow = findViewById(R.id.notif_row)

        btnPlayPause.setOnClickListener { togglePlayback() }
        btnNext.setOnClickListener { controller?.transportControls?.skipToNext() }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                dismiss()
                return true
            }
            override fun onDown(e: MotionEvent) = true
        })
        root.setOnTouchListener { v, event ->
            v.performClick()
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "aod:screen"
        ).also { it.acquire(AOD_TIMEOUT_MS) }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tickRunnable)
        handler.postDelayed(shiftRunnable, SHIFT_INTERVAL_MS)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        refreshMedia()
        refreshNotifications()
        AodNotificationListener.onChange = { refreshNotifications(); refreshMedia() }
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(screenOnReceiver) }
        AodNotificationListener.onChange = null
        super.onStop()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- clock --------------------------------------------------------------

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now)
    }

    /** Anti burn-in: nudge the whole content block every 60 s. */
    private fun shiftContent() {
        val dx = Random.nextInt(-SHIFT_PX, SHIFT_PX + 1).toFloat()
        val dy = Random.nextInt(-SHIFT_PX, SHIFT_PX + 1).toFloat()
        content.animate().translationX(dx).translationY(dy).setDuration(1200).start()
    }

    // ---- battery ------------------------------------------------------------

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0) level * 100 / scale else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        battery.text = if (charging) "⚡ $pct%" else "$pct%"
    }

    // ---- media --------------------------------------------------------------

    private fun refreshMedia() {
        controller = activeController()
        val c = controller
        val meta = c?.metadata
        if (c == null || meta == null) {
            mediaPanel.visibility = View.GONE
            return
        }
        mediaPanel.visibility = View.VISIBLE
        trackTitle.text = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "—"
        trackArtist.text = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        btnPlayPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun activeController(): MediaController? = try {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = android.content.ComponentName(this, AodNotificationListener::class.java)
        msm.getActiveSessions(component).firstOrNull()
    } catch (e: SecurityException) {
        // Notification access not granted yet.
        null
    }

    private fun togglePlayback() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
        handler.postDelayed({ refreshMedia() }, 350)
    }

    // ---- notification icons -------------------------------------------------

    private fun refreshNotifications() {
        notifRow.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.aod_notif_icon)
        AodNotificationListener.activeIcons(this).take(6).forEach { drawable ->
            val iv = ImageView(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = size / 3
            iv.layoutParams = lp
            iv.setImageDrawable(drawable)
            iv.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            iv.alpha = 0.75f
            notifRow.addView(iv)
        }
    }

    // ---- exit ---------------------------------------------------------------

    /** Power button pressed while AOD is showing -> dismiss safely. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            dismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() = dismiss()

    private fun dismiss() {
        releaseWakeLock()
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        finish()
        overridePendingTransition(0, 0)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val SHIFT_INTERVAL_MS = 60_000L
        private const val SHIFT_PX = 24
        private const val AOD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
