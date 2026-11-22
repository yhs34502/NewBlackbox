package top.niunaijun.blackboxa.view.surface

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Stage-2 experiment: instantiate WindowlessWindowManager and use it as the
 * SurfaceControlViewHost window manager. This hosts an ordinary View only;
 * it is not a Guest Activity and does not prove Activity attachment.
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
        root.addView(hostSurface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(ScrollView(this).apply {
            addView(reportView)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        setContentView(root)
        hostSurface.holder.addCallback(this)

        append("Windowless Host Probe")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
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
                "Windowless SurfaceControlViewHost requires API 30+"
            }
            val display = hostSurface.display
                ?: error("SurfaceView.getDisplay() returned null")
            val hostToken = hostSurface.hostToken
                ?: error("SurfaceView.getHostToken() returned null")
            val parentSurface = hostSurface.surfaceControl
            check(parentSurface != null && parentSurface.isValid) {
                "SurfaceView SurfaceControl is null or invalid"
            }
            append("PASS: host Display, Binder token and SurfaceControl are available")
            append("Parent SurfaceControl: valid")

            val wwmClass = Class.forName("android.view.WindowlessWindowManager")
            val wwmCtor = wwmClass.getDeclaredConstructor(
                Configuration::class.java,
                SurfaceControl::class.java,
                android.os.IBinder::class.java
            )
            wwmCtor.isAccessible = true
            val windowlessManager = wwmCtor.newInstance(
                Configuration(resources.configuration),
                parentSurface,
                hostToken
            )
            append("PASS: WindowlessWindowManager constructed")

            val hostCtor = SurfaceControlViewHost::class.java.getDeclaredConstructor(
                android.content.Context::class.java,
                android.view.Display::class.java,
                wwmClass,
                String::class.java
            )
            hostCtor.isAccessible = true
            val createdHost = hostCtor.newInstance(
                this,
                display,
                windowlessManager,
                "MereviewWindowlessHostProbe"
            ) as SurfaceControlViewHost
            viewHost = createdHost
            append("PASS: SurfaceControlViewHost(WindowlessWindowManager) constructed")

            val content = FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(24, 55, 42))
                setPadding(24, 24, 24, 24)
            }
            val title = TextView(this).apply {
                text = "Windowless Window: ACTIVE"
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
            content.addView(title, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            content.addView(button, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))

            val setView = SurfaceControlViewHost::class.java.getDeclaredMethod(
                "setView", View::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            setView.isAccessible = true
            setView.invoke(createdHost, content, hostSurface.width, hostSurface.height)
            append("PASS: SurfaceControlViewHost.setView() completed")

            val pkg = createdHost.surfacePackage
                ?: error("SurfaceControlViewHost.getSurfacePackage() returned null")
            surfacePackage = pkg
            append("PASS: SurfacePackage obtained")

            hostSurface.setChildSurfacePackage(pkg)
            append("PASS: SurfacePackage attached to host SurfaceView")
            append("RESULT: Windowless View path reached attachment stage.")
            append("This is NOT a Guest Activity embedding result.")
        } catch (t: Throwable) {
            val cause = (t.cause ?: t)
            append("FAIL: ${cause.javaClass.name}: ${cause.message}")
            Log.e(TAG, "Stage-2 Windowless Host probe failed", t)
        }
    }

    private fun append(line: String) {
        Log.i(TAG, line)
        if (report.isNotEmpty()) report.append('\n')
        report.append(line)
        if (::reportView.isInitialized) reportView.text = report.toString()
    }

    private fun releaseProbe() {
        try {
            hostSurface.setChildSurfacePackage(null)
        } catch (_: Throwable) {
        }
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
