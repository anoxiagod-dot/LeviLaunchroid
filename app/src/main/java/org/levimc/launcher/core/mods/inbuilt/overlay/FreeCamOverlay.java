package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;
import org.levimc.launcher.core.mods.inbuilt.nativemod.FreeCamMod;

public class FreeCamOverlay extends BaseOverlayButton {
    private static final String TAG = "FreeCamOverlay";
    private boolean isActive = false;
    private boolean initialized = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public FreeCamOverlay(Activity activity) {
        super(activity);
    }

    @Override
    protected String getModId() {
        return ModIds.FREECAM;
    }

    @Override
    protected int getIconResource() {
        return isActive ? R.drawable.ic_zoom_enabled : R.drawable.ic_zoom_disabled;
    }

    @Override
    public void show(int startX, int startY) {
        if (!initialized) {
            handler.postDelayed(() -> {
                if (FreeCamMod.nativeInit()) {
                    initialized = true;
                    Log.i(TAG, "FreeCam initialized");
                } else {
                    Log.e(TAG, "FreeCam init failed");
                }
            }, 1000);
        }
        super.show(startX, startY);
    }

    @Override
    protected void onButtonClick() {
        if (!initialized) return;
        if (isActive) {
            FreeCamMod.nativeDeactivate();
            isActive = false;
        } else {
            FreeCamMod.nativeActivate(0, 0, 0, 0, 0);
            isActive = true;
        }
        updateIcon();
    }

    private void updateIcon() {
        if (overlayView instanceof ImageButton) {
            ImageButton btn = (ImageButton) overlayView;
            btn.setImageResource(isActive ? R.drawable.ic_zoom_enabled : R.drawable.ic_zoom_disabled);
        }
    }

    @Override
    public void hide() {
        if (isActive && initialized) {
            FreeCamMod.nativeDeactivate();
            isActive = false;
        }
        super.hide();
    }
}