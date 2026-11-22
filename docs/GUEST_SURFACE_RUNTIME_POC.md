# Guest Surface Runtime POC

Status: isolated investigation branch; Stage-2 Windowless Host probe added, pending build and device execution.

Base revision: `89b59836c66f173756a4ae258cf379a957649820`

Branch: `experiment/guest-surface-runtime-poc`

## Objective

Replace the current proxy-to-normal-Activity handoff with a guest Activity window that is rendered into a host-owned surface in the Mereview car UI. The POC must not use a VirtualDisplay, `ActivityOptions.setLaunchDisplayId()`, root, or Shizuku.

## Confirmed baseline behavior

At the pinned revision:

1. `ActivityStack.startActivityInNewTaskLocked()` creates a guest process and proxy intent, then calls `BlackBoxCore.getContext().startActivity(shadow)`.
2. `ProxyActivity.onCreate()` calls `finish()`, then `startActivity(record.mTarget)`.
3. `ProxyActivityRecord` contains only `userId`, `ActivityInfo`, target `Intent`, and an activity-record Binder token. It has no host surface, window session, or embedding handle.
4. `HCallbackProxy` rewrites the framework launch record's Intent and ActivityInfo to the guest target after Android has already accepted a real Activity launch.
5. `AppInstrumentation` hooks Instrumentation and delegates Activity creation/callbacks. It does not provide a guest WindowManager or attach the Activity decor to a host surface.

This is a process/package virtualization layer with Android's normal Activity/window launch path, not an embedded Activity runtime.

## Stage 0 — SurfaceView embedding plumbing

An isolated helper was added at
`Bcore/src/main/java/top/niunaijun/blackbox/surface/SurfaceViewEmbedder.java`, with a manual host test at
`app/src/main/java/top/niunaijun/blackboxa/view/surface/SurfaceEmbeddingProbeActivity.kt`.

It attempts to:
1. Require an attached, measured host `SurfaceView` with a valid display and host token.
2. Create a real `SurfaceControlViewHost` on that display.
3. Invoke the platform `setView(View, int, int)` entry point and obtain a `SurfacePackage`.
4. Attach that package to the host `SurfaceView`, with an explicit close path.

Important limits:
- This helper embeds an ordinary supplied `View`; it does **not** embed a Guest Activity.
- The `setView(View, int, int)` entry point is accessed reflectively because it is not part of the normal public SDK surface in the targeted SDK. Android 14 hidden-API enforcement may reject it.
- A dedicated manual probe Activity is registered under action
  `top.niunaijun.blackbox.action.SURFACE_EMBED_PROBE`; it hosts a SurfaceView and
  attempts to embed a TextView/Button hierarchy. This Activity itself is a normal
  top-level host window, so it does not satisfy the Guest Activity containment gate.
- This change has not been compiled or run on a device. It is a probe, not a passing result.

Stage 0 passes only after a build and a device test show a real View rendered inside the host SurfaceView, with no separate top-level window. Even then, Gate A still requires replacing the supplied View with the actual Guest Activity decor and proving Activity window/lifecycle ownership.

## Stage 1 — Guest window API inventory

Added `GuestWindowApiProbeActivity` at
`app/src/main/java/top/niunaijun/blackboxa/view/surface/GuestWindowApiProbeActivity.kt`
and registered action
`top.niunaijun.blackbox.action.GUEST_WINDOW_API_PROBE`.

This diagnostic reports the device's declarations for:
- `WindowlessWindowManager` constructors and relevant methods
- `SurfaceControlViewHost` constructors and relevant methods
- `Window` window/attachment methods
- the platform `Activity.attach(...)` signature

It is deliberately read-only. It does not instantiate an Activity, call hidden APIs,
or claim that a guest window has been embedded. Its output is needed before writing
an Android-version-specific attachment adapter; guessing the private
`Activity.attach` signature would make the next prototype unsafe and brittle.

Manual launch on a test device:
`adb shell am start -n <host.package>/top.niunaijun.blackboxa.view.surface.GuestWindowApiProbeActivity`

The next implementation gate is to use the captured API inventory to design a
windowless guest Activity attach path, then prove the real guest Activity decor
renders inside the host SurfaceView. A normal Activity launch or a synthetic View
does not pass that gate.

## Stage 2 — Windowless host construction probe

Added `WindowlessHostProbeActivity` at
`app/src/main/java/top/niunaijun/blackboxa/view/surface/WindowlessHostProbeActivity.kt`
and registered action
`top.niunaijun.blackbox.action.WINDOWLESS_HOST_PROBE`.

This probe uses the Android 14 API signatures observed on the Samsung SM-A528B:
1. Reads the host SurfaceView Display, host Binder token and its SurfaceControl.
2. Reflectively constructs `WindowlessWindowManager(Configuration, SurfaceControl, IBinder)`.
3. Constructs `SurfaceControlViewHost(Context, Display, WindowlessWindowManager, String)`.
4. Calls `setView(View, int, int)`, obtains the SurfacePackage, and attempts to attach it to the host SurfaceView.
5. Displays a step-by-step PASS/FAIL report and releases the SurfaceControlViewHost when the host surface is destroyed.

This remains a synthetic View probe. It does not instantiate or attach a Guest Activity. In particular, successful construction is not sufficient: the device test must show the embedded content and confirm the touch button responds. Hidden-API enforcement, invalid SurfaceControl parenting, or SurfacePackage attachment may still fail on the target firmware.

Manual launch:
`adb shell am start -n <host.package>/top.niunaijun.blackboxa.view.surface.WindowlessHostProbeActivity`

Collect the on-screen report or Logcat tag `WindowlessHostProbe`. The first failing step and full exception determine the next adapter change. Do not proceed to Activity.attach integration until this probe demonstrates a visible, touch-responsive View.

## POC architecture to prove

The work is split into explicit proof gates. Do not connect this runtime to Mereview's production Clone/MereviewSpace routes until every gate passes.

### Gate A — Guest window ownership

- Identify the exact Android 14 ActivityThread launch and Window attachment path used by the pinned library.
- Prevent the guest Activity from becoming a phone-visible top-level Activity.
- Create a windowless guest window session backed by a host-created `SurfaceControlViewHost` / `SurfacePackage`.
- Attach the guest Activity's actual decor hierarchy to that session. A synthetic Compose screen or copied screenshot is not acceptable.
- Demonstrate one minimal guest Activity appears inside the Mereview-owned host surface and nowhere else.

### Gate B — Input and lifecycle

- Route touch and pointer input to the guest hierarchy.
- Verify keyboard/IME composition, key events, Back, focus, and accessibility behavior.
- Verify configuration changes, surface resize, pause/resume, destroy, process death, and cleanup.
- Verify child dialogs, popups, and secondary windows are contained or explicitly rejected.

### Gate C — Compatibility

Test in this order:
1. Minimal Activity with TextView and Button.
2. A simple multi-Activity app.
3. WebView app.
4. Maps and Google Play Services as a separate compatibility gate.

## Technical investigation targets

- `Bcore/src/main/java/top/niunaijun/blackbox/core/system/am/ActivityStack.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/proxy/ProxyActivity.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/proxy/record/ProxyActivityRecord.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java`
- Android 14 hidden Activity attach / Window / WindowManagerGlobal / WindowlessWindowManager APIs.

## Non-goals and safety rules

- Do not disguise the existing normal Activity or VirtualDisplay path as embedding.
- Do not add a fake `GuestSurfaceRuntime` interface without a functioning implementation.
- Do not transplant UI code that imports APIs absent from this pinned source.
- Keep the existing MereviewSpace branch and its BlackBox submodule untouched.
- Every experimental change stays on this branch until it builds and passes the containment gate.

## Current status

- Isolated branch created from the exact pinned NewBlackbox revision.
- Source audit confirms no existing `GuestSurfaceRuntime`, `IGuestSurfaceCallback`, or `SurfaceControlViewHost` implementation in this revision.
- Stage-2 Windowless Host probe source and manifest registration are committed; no build or device test result exists yet.
- Next task: build this branch, run WindowlessHostProbeActivity on the SM-A528B, and use its first failing step or touch-test result to decide whether the WWM-backed SurfaceControlViewHost path is viable.
