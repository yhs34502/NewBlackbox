package top.niunaijun.blackbox.core.surface;

import android.app.Activity;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts an Activity's current content hierarchy in a SurfaceControlViewHost.
 *
 * <p>This is the rendering primitive for an embedded guest window. It does not create
 * a VirtualDisplay. The host application must provide the host token obtained from
 * its destination SurfaceView and consume the returned SurfacePackage with
 * SurfaceView.setChildSurfacePackage().</p>
 *
 * <p>Call attach after the guest Activity has installed its content view. The Activity
 * remains alive in its guest process; only its current content children are moved into
 * the windowless surface host. Dialogs, additional windows, and later calls to
 * setContentView require separate window/lifecycle handling.</p>
 */
public final class GuestSurfaceBridge implements AutoCloseable {
    public interface Listener {
        void onSurfacePackageReady(SurfaceControlViewHost.SurfacePackage surfacePackage);
    }

    private final Activity activity;
    private final ViewGroup originalContent;
    private final FrameLayout hostedContent;
    private final List<View> originalChildren = new ArrayList<>();
    private final SurfaceControlViewHost surfaceHost;
    private boolean closed;

    private GuestSurfaceBridge(
            Activity activity,
            ViewGroup originalContent,
            FrameLayout hostedContent,
            SurfaceControlViewHost surfaceHost) {
        this.activity = activity;
        this.originalContent = originalContent;
        this.hostedContent = hostedContent;
        this.surfaceHost = surfaceHost;
    }

    /**
     * Moves the Activity's current content children into a windowless surface host.
     *
     * @param activity guest Activity whose content should be embedded
     * @param hostToken token from the host SurfaceView.getHostToken()
     * @param widthPx requested embedded content width in pixels
     * @param heightPx requested embedded content height in pixels
     * @param listener receives a Parcelable SurfacePackage for the host process
     */
    public static GuestSurfaceBridge attach(
            Activity activity,
            Display hostDisplay,
            IBinder hostToken,
            int widthPx,
            int heightPx,
            Listener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new UnsupportedOperationException(
                    "Embedded guest surfaces require Android 11 (API 30) or later.");
        }
        if (activity == null || hostDisplay == null || hostToken == null || listener == null) {
            throw new IllegalArgumentException("activity, hostDisplay, hostToken and listener are required");
        }
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("Surface dimensions must be positive");
        }

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            throw new IllegalStateException("Activity has no ViewGroup content root");
        }

        ViewGroup originalContent = (ViewGroup) content;
        if (originalContent.getChildCount() == 0) {
            throw new IllegalStateException(
                    "Activity content is empty; attach after setContentView has completed");
        }

        FrameLayout hostedContent = new FrameLayout(activity);
        hostedContent.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        List<View> children = new ArrayList<>();
        for (int i = 0; i < originalContent.getChildCount(); i++) {
            children.add(originalContent.getChildAt(i));
        }

        // Detach the current content views from the Activity decor before giving them
        // to the windowless ViewRoot owned by SurfaceControlViewHost.
        originalContent.removeAllViews();
        for (View child : children) {
            hostedContent.addView(child);
        }

        SurfaceControlViewHost surfaceHost = null;
        try {
            surfaceHost = new SurfaceControlViewHost(activity, hostDisplay, hostToken);
            surfaceHost.setView(hostedContent, widthPx, heightPx);
            SurfaceControlViewHost.SurfacePackage surfacePackage =
                    surfaceHost.getSurfacePackage();
            if (surfacePackage == null) {
                throw new IllegalStateException("SurfaceControlViewHost returned no SurfacePackage");
            }

            GuestSurfaceBridge bridge = new GuestSurfaceBridge(
                    activity, originalContent, hostedContent, surfaceHost);
            bridge.originalChildren.addAll(children);
            listener.onSurfacePackageReady(surfacePackage);
            return bridge;
        } catch (RuntimeException | Error error) {
            if (surfaceHost != null) {
                surfaceHost.release();
            }
            hostedContent.removeAllViews();
            for (View child : children) {
                if (child.getParent() == null) {
                    originalContent.addView(child);
                }
            }
            throw error;
        }
    }

    /** Updates the embedded hierarchy size after the host SurfaceView is resized. */
    public void resize(int widthPx, int heightPx) {
        ensureOpen();
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("Surface dimensions must be positive");
        }
        surfaceHost.relayout(widthPx, heightPx);
    }

    /** Returns the SurfacePackage currently owned by this bridge. */
    public SurfaceControlViewHost.SurfacePackage getSurfacePackage() {
        ensureOpen();
        return surfaceHost.getSurfacePackage();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("GuestSurfaceBridge is closed");
        }
    }

    /**
     * Releases the embedded surface and restores the Activity's content children.
     * Call from the guest Activity's destruction/finish lifecycle path.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        surfaceHost.release();
        hostedContent.removeAllViews();
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            for (View child : originalChildren) {
                if (child.getParent() == null) {
                    originalContent.addView(child);
                }
            }
        }
        originalChildren.clear();
    }
}
