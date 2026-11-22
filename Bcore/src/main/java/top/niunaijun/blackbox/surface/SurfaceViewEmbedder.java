package top.niunaijun.blackbox.surface;

import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Experimental bridge for embedding a View hierarchy into a host SurfaceView.
 *
 * This is only the SurfaceControlViewHost plumbing layer. It does not create,
 * attach, or lifecycle-manage a guest Activity.
 */
public final class SurfaceViewEmbedder implements AutoCloseable {
    private final SurfaceView hostSurfaceView;
    private final SurfaceControlViewHost viewHost;
    private boolean closed;

    private SurfaceViewEmbedder(
            SurfaceView hostSurfaceView,
            SurfaceControlViewHost viewHost) {
        this.hostSurfaceView = hostSurfaceView;
        this.viewHost = viewHost;
    }

    /**
     * Embeds {@code content} into an already attached and sized host SurfaceView.
     *
     * Android 14 exposes the required setView(View, int, int) method as a
     * non-SDK/test API on some platform builds. Reflection is intentionally
     * isolated here so the POC can report whether the device permits access.
     */
    public static SurfaceViewEmbedder attach(
            Context context,
            SurfaceView host,
            View content) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new UnsupportedOperationException(
                    "SurfaceControlViewHost requires Android 11 (API 30) or later");
        }
        if (context == null || host == null || content == null) {
            throw new IllegalArgumentException("context, host and content must be non-null");
        }
        if (!host.isAttachedToWindow()) {
            throw new IllegalStateException("Host SurfaceView is not attached to a window");
        }
        if (host.getWidth() <= 0 || host.getHeight() <= 0) {
            throw new IllegalStateException("Host SurfaceView has no measured size");
        }

        Display display = host.getDisplay();
        if (display == null) {
            throw new IllegalStateException("Host SurfaceView has no Display");
        }
        if (host.getHostToken() == null) {
            throw new IllegalStateException("Host SurfaceView has no host token");
        }

        SurfaceControlViewHost viewHost = new SurfaceControlViewHost(
                context, display, host.getHostToken());
        boolean attached = false;
        try {
            Method setView = SurfaceControlViewHost.class.getDeclaredMethod(
                    "setView", View.class, int.class, int.class);
            setView.setAccessible(true);
            setView.invoke(viewHost, content, host.getWidth(), host.getHeight());

            SurfaceControlViewHost.SurfacePackage surfacePackage =
                    viewHost.getSurfacePackage();
            if (surfacePackage == null) {
                throw new IllegalStateException(
                        "SurfaceControlViewHost returned a null SurfacePackage");
            }

            host.setChildSurfacePackage(surfacePackage);
            attached = true;
            return new SurfaceViewEmbedder(host, viewHost);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException("SurfaceControlViewHost.setView failed", cause);
        } finally {
            if (!attached) {
                viewHost.release();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                hostSurfaceView.setChildSurfacePackage(null);
            } finally {
                viewHost.release();
            }
        }
    }
}
