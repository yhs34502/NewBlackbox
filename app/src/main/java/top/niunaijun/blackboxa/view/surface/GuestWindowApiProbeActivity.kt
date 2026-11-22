package top.niunaijun.blackboxa.view.surface

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceControlViewHost
import android.widget.ScrollView
import android.widget.TextView

/**
 * Platform capability inventory for a future Guest Activity embedding path.
 *
 * This is intentionally diagnostic only: it does not instantiate hidden
 * window managers, attach an Activity manually, or launch a guest into a
 * top-level window and misreport that as embedding.
 */
class GuestWindowApiProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = buildString {
            appendLine("Guest Activity Embedding Capability Probe")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Host package: $packageName")
            appendLine("Host UID: ${applicationInfo.uid}")
            appendLine()

            inspectClass("android.view.WindowlessWindowManager")
            inspectClass("android.view.SurfaceControlViewHost")
            inspectClass("android.view.Window")
            inspectClass("android.app.ActivityView")
            inspectClass("android.window.TaskFragment")
            inspectClass("android.window.TaskFragmentOrganizer")
            inspectActivityAttach()
            inspectEmbeddingPermission()

            appendLine("=== CURRENT POC BOUNDARY ===")
            appendLine("SurfaceControlViewHost hosts a View hierarchy, not an Activity instance.")
            appendLine("Class/API presence does not prove permission to embed another task.")
            appendLine("No Guest Activity has been attached or launched by this probe.")
            appendLine("The WWM-backed path remains disabled for device safety.")
        }

        val text = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(24, 24, 24, 24)
            this.text = report
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
                        method.name.contains("release", true) ||
                        method.name.contains("task", true) ||
                        method.name.contains("organize", true)
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
        appendLine()
    }

    private fun StringBuilder.inspectEmbeddingPermission() {
        appendLine("=== TASK EMBEDDING PERMISSION ===")
        val permission = "android.permission.MANAGE_ACTIVITY_TASKS"
        val result = checkSelfPermission(permission)
        appendLine("Permission: $permission")
        appendLine(
            "Granted: ${result == PackageManager.PERMISSION_GRANTED}"
        )
        appendLine(
            "Note: this permission is normally system/privileged; " +
                "a runtime permission prompt cannot grant it."
        )
        appendLine()
    }
}
