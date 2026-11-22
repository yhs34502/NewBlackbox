package top.niunaijun.blackboxa.view.surface

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.SurfaceControlViewHost
import android.widget.ScrollView
import android.widget.TextView

/**
 * Stage-1 platform API inventory.
 *
 * This activity does not launch or embed a guest Activity. It records the
 * platform contracts that the next windowless-Activity prototype must satisfy.
 */
class GuestWindowApiProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = buildString {
            appendLine("Guest Window API Probe")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()

            inspectClass("android.view.WindowlessWindowManager")
            inspectClass("android.view.SurfaceControlViewHost")
            inspectClass("android.view.Window")
            inspectActivityAttach()
        }

        val text = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(24, 24, 24, 24)
            text = report
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    private fun StringBuilder.inspectClass(name: String) {
        appendLine("=== $name ===")
        try {
            val type = Class.forName(name)
            appendLine("Class: FOUND")
            type.declaredConstructors.forEach { constructor ->
                appendLine("CTOR: ${constructor.parameterTypes.joinToString { it.name }}")
            }
            type.declaredMethods
                .filter { method ->
                    method.name.contains("view", true) ||
                        method.name.contains("surface", true) ||
                        method.name.contains("window", true) ||
                        method.name.contains("attach", true) ||
                        method.name.contains("inset", true) ||
                        method.name.contains("release", true)
                }
                .sortedBy { it.name }
                .forEach { method ->
                    appendLine(
                        "METHOD: ${method.name}(" +
                            method.parameterTypes.joinToString { it.name } +
                            "): ${method.returnType.name}"
                    )
                }
        } catch (t: Throwable) {
            appendLine("UNAVAILABLE: ${t.javaClass.name}: ${t.message}")
        }
        appendLine()
    }

    private fun StringBuilder.inspectActivityAttach() {
        appendLine("=== android.app.Activity.attach ===")
        try {
            Activity::class.java.declaredMethods
                .filter { it.name == "attach" }
                .forEach { method ->
                    appendLine(
                        "METHOD: attach(" +
                            method.parameterTypes.joinToString { it.name } +
                            "): ${method.returnType.name}"
                    )
                }
        } catch (t: Throwable) {
            appendLine("UNAVAILABLE: ${t.javaClass.name}: ${t.message}")
        }
    }
}
