package top.niunaijun.blackbox.core.surface;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;
import android.view.SurfaceControlViewHost;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Opt-in Activity lifecycle adapter for Mereview's embedded guest surface.
 *
 * The host supplies a SurfaceView host token and an {@link IGuestSurfaceCallback}
 * Binder in the launch Intent. The callback receives SurfacePackage in a Bundle.
 * Activities launched without these extras continue through the normal BlackBox path.
 */
public final class GuestSurfaceRuntime {
    public static final String EXTRA_ENABLED =
            "top.niunaijun.blackbox.extra.EMBEDDED_SURFACE";
    public static final String EXTRA_HOST_TOKEN =
            "top.niunaijun.blackbox.extra.HOST_TOKEN";
    public static final String EXTRA_CALLBACK =
            "top.niunaijun.blackbox.extra.SURFACE_CALLBACK";
    public static final String EXTRA_WIDTH =
            "top.niunaijun.blackbox.extra.SURFACE_WIDTH";
    public static final String EXTRA_HEIGHT =
            "top.niunaijun.blackbox.extra.SURFACE_HEIGHT";
    public static final String EXTRA_DISPLAY_ID =
            "top.niunaijun.blackbox.extra.DISPLAY_ID";
    public static final String RESULT_SURFACE_PACKAGE = "surface_package";

    private static final Map<Activity, GuestSurfaceBridge> BRIDGES =
            Collections.synchronizedMap(new WeakHashMap<Activity, GuestSurfaceBridge>());

    private GuestSurfaceRuntime() {
    }

    /** Called by AppInstrumentation after the guest's onCreate has installed its content. */
    public static void onActivityCreated(final Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Intent intent = activity.getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA_ENABLED, false)) {
            return;
        }

        Bundle launchExtras = intent.getExtras();
        IBinder hostToken = launchExtras == null ? null : launchExtras.getBinder(EXTRA_HOST_TOKEN);
        IBinder callbackBinder = launchExtras == null ? null : launchExtras.getBinder(EXTRA_CALLBACK);
        final IGuestSurfaceCallback callback =
                IGuestSurfaceCallback.Stub.asInterface(callbackBinder);
        int width = intent.getIntExtra(EXTRA_WIDTH, 0);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 0);
        int displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        DisplayManager displayManager =
                (DisplayManager) activity.getSystemService(Activity.DISPLAY_SERVICE);
        Display hostDisplay = displayManager == null ? null : displayManager.getDisplay(displayId);

        if (hostToken == null || callback == null || hostDisplay == null
                || width <= 0 || height <= 0) {
            notifyError(callback, "Embedded surface launch configuration is incomplete");
            return;
        }

        try {
            GuestSurfaceBridge bridge = GuestSurfaceBridge.attach(
                    activity, hostDisplay, hostToken, width, height,
                    surfacePackage -> {
                        Bundle result = new Bundle();
                        result.putParcelable(RESULT_SURFACE_PACKAGE, surfacePackage);
                        try {
                            callback.onSurfaceReady(result);
                        } catch (RemoteException e) {
                            throw new IllegalStateException("Host process disconnected before surface delivery", e);
                        }
                    });
            GuestSurfaceBridge previous = BRIDGES.put(activity, bridge);
            if (previous != null) {
                previous.close();
            }
        } catch (RuntimeException | Error error) {
            notifyError(callback, error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    /** Called by AppInstrumentation when the guest Activity is destroyed. */
    public static void onActivityDestroyed(Activity activity) {
        GuestSurfaceBridge bridge = BRIDGES.remove(activity);
        if (bridge != null) {
            bridge.close();
        }
    }

    private static void notifyError(IGuestSurfaceCallback callback, String message) {
        if (callback == null) {
            return;
        }
        try {
            callback.onSurfaceError(message == null ? "Unknown embedded surface error" : message);
        } catch (RemoteException ignored) {
            // The host may have gone away; Activity lifecycle must still complete.
        }
    }
}
