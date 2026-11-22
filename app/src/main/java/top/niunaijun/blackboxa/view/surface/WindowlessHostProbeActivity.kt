package top.niunaijun.blackboxa.view.surface

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.InvocationTargetException

/**
 * Safe SurfaceControlViewHost probe.
 *
 * IMPORTANT: The former WWM-backed path was disabled after repeat
 * SurfaceFlinger stack-overflow crashes on the Samsung SM-A528B.
 *
 * This version deliberately does NOT:
 * - read or pass SurfaceView.surfaceControl as a WindowlessWindowManager parent;
 * - construct WindowlessWindowManager;
 * - combine an explicit WindowlessWindowManager parent with
 *   SurfaceView.setChildSurfacePackage().
 *
 * It uses the platform host-token constructor so Android owns the parent/window
 * relationship. This still embeds only an ordinary View, not a Guest Activity.
 */
class WindowlessHostProbeActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var hostSurface: SurfaceView
    private lateinit var reportView: TextView
    private var viewHost: SurfaceControlViewHost? = null
    private var surfacePackage: SurfaceControlViewHost.SurfacePackage? = null
    private var started = false
    private val report = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        hostSurface = SurfaceView(this)
        reportView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD101820.toInt())
            setPadding(18, 14, 18, 14)
            setTextIsSelectable(true)
        }
        root.addView(
            hostSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            ScrollView(this).apply { addView(reportView) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        setContentView(root)
        hostSurface.holder.addCallback(this)

        append("Safe SurfaceControlViewHost Probe")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append("Safety mode: host-token constructor; no explicit WWM parent")
        append("Waiting for host Surface...")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        runProbe()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!started && width > 0 && height > 0) runProbe()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseProbe()
    }

    private fun runProbe() {
        if (started || isFinishing) return
        started = true
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "SurfaceControlViewHost requires API 30+"
            }
            check(hostSurface.isAttachedToWindow) {
                "Host SurfaceView is not attached to a window"
            }
            check(hostSurface.width > 0 && hostSurface.height > 0) {
                "Host SurfaceView has no measured size"
            }
            val display = hostSurface.display
                ?: error("SurfaceView.getDisplay() returned null")
            val hostToken = hostSurface.hostToken
                ?: error("SurfaceView.getHostToken() returned null")
            append("PASS: host Display, Binder token and measured size are available")

            // Use the supported host-token path. Do not pass hostSurface.surfaceControl
            // into a WindowlessWindowManager and then attach its package to this same
            // SurfaceView: that former combination correlated with SurfaceFlinger
            // Layer::setGameModeForTree() recursion on the test device.
            val createdHost = SurfaceControlViewHost(this, display, hostToken)
            viewHost = createdHost
            append("PASS: SurfaceControlViewHost(Context, Display, hostToken) constructed")

            val content = FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(24, 55, 42))
                setPadding(24, 24, 24, 24)
            }
            val title = TextView(this).apply {
                text = "Safe Surface Host: ACTIVE"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            val button = Button(this).apply {
                text = "Tap to verify input"
                setOnClickListener {
                    text = "Touch received"
                    append("PASS: embedded View received touch")
                }
            }
            content.addView(
                title,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            content.addView(
                button,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val setView = SurfaceControlViewHost::class.java.getDeclaredMethod(
                "setView",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            setView.isAccessible = true
            try {
                setView.invoke(createdHost, content, hostSurface.width, hostSurface.height)
            } catch (e: InvocationTargetException) {
                throw (e.cause ?: e)
            }
            append("PASS: SurfaceControlViewHost.setView() completed")

            val pkg = createdHost.surfacePackage
                ?: error("SurfaceControlViewHost.getSurfacePackage() returned null")
            surfacePackage = pkg
            append("PASS: SurfacePackage obtained")

            hostSurface.setChildSurfacePackage(pkg)
            append("PASS: SurfacePackage attached through host-token path")
            append("RESULT: safe synthetic View attachment reached.")
            append("This is NOT a Guest Activity embedding result.")
            append("The former WWM-backed path remains disabled.")
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            append("FAIL: ${cause.javaClass.name}: ${cause.message}")
            Log.e(TAG, "Safe Surface Host probe failed", t)
            releaseProbe()
        }
    }

    private fun append(line: String) {
        Log.i(TAG, line)
        if (report.isNotEmpty()) report.append('\n')
        report.append(line)
        if (::reportView.isInitialized) reportView.text = report.toString()
    }

    private fun releaseProbe() {
        surfacePackage = null
        viewHost?.release()
        viewHost = null
        started = false
    }

    override fun onDestroy() {
        releaseProbe()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WindowlessHostProbe"
    }
}
