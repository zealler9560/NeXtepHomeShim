package io.muse.nextepshim;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible launcher entry that toggles the Panes workbench.
 *
 * Gesture apps such as MyGesture can only "open an app" as an action; the
 * Panes app's own entry opens its settings, not the workbench. This
 * activity gives them a target that fires HOST_TOGGLE_REQUEST at the
 * module's broker (the same entry the shim's relay uses for HOST_SET)
 * and finishes immediately, without any UI.
 */
public class ToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // The module's broker receiver is guarded by
            // android.permission.STATUS_BAR_SERVICE, which this plain app
            // process does not hold (its broadcasts are dropped silently).
            // The shim's responder inside com.miui.home holds it, so ask
            // that responder to relay the toggle instead.
            Intent toggle = new Intent(HomeShim.ACTION_TOGGLE);
            toggle.setPackage("com.miui.home");
            sendBroadcast(toggle);
        } catch (Throwable ignored) {
            // Home not running: nothing to do.
        }
        finish();
    }
}
