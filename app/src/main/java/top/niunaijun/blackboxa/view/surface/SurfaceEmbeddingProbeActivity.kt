package top.niunaijun.blackboxa.view.surface

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.niunaijun.blackbox.surface.SurfaceViewEmbedder

/**
 * Manual Stage-0 probe for SurfaceControlViewHost plumbing.
 *
 * This deliberately embeds a normal View, not a Guest Activity. It must not be
 * treated as proof of Guest Activity window embedding.
 */
class SurfaceEmbeddingProbeActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var hostSurface: SurfaceView
    private lateinit var status: TextView
    private var embedder: SurfaceViewEmbedder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        hostSurface = SurfaceView(this)
        status = TextView(this).apply {
            text = "Waiting for host Surface..."
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(16, 12, 16, 12)
        }

        root.addView(
            hostSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)
        hostSurface.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachProbeView()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (embedder == null && width > 0 && height > 0) {
            attachProbeView()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        embedder?.close()
        embedder = null
    }

    private fun attachProbeView() {
        if (embedder != null || isFinishing) return

        val guestLikeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(24, 38, 54))
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "SurfaceControlViewHost"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val detail = TextView(this).apply {
            text = "Stage 0: embedded View plumbing only"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val button = Button(this).apply {
            text = "Touch test"
            setOnClickListener {
                text = "Touch OK"
                status.text = "PASS: embedded button received touch"
            }
        }
        guestLikeContent.addView(title)
        guestLikeContent.addView(detail)
        guestLikeContent.addView(button)

        try {
            embedder = SurfaceViewEmbedder.attach(this, hostSurface, guestLikeContent)
            status.text = "Attached. Tap the embedded button."
            Log.i(TAG, "Stage-0 SurfaceView embedding attached")
        } catch (t: Throwable) {
            status.text = "FAILED: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "Stage-0 SurfaceView embedding failed", t)
        }
    }

    override fun onDestroy() {
        embedder?.close()
        embedder = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SurfaceEmbedProbe"
    }
}
