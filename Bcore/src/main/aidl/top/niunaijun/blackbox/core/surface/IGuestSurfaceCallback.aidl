package top.niunaijun.blackbox.core.surface;

import android.os.Bundle;

interface IGuestSurfaceCallback {
    void onSurfaceReady(in Bundle payload);
    void onSurfaceError(String message);
}
