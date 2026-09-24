/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Starts SunlightBoostService on boot. Redundant with SunlightBoostApp, which
 * already initialises the controller when the persistent process is created,
 * but it gives the service an explicit start so its onDestroy cleanup path is
 * live, mirroring how the device-settings app owns its service.
 */

package org.lineageos.device.sunlightboost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SunlightBoostBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        // This receiver lives in a persistent app: an exception escaping here
        // would crash the process, and AMS would restart it in a loop until
        // RescueParty reboots the device.
        try {
            context.startService(new Intent(context, SunlightBoostService.class));
            if (Constants.DEBUG) Log.i(TAG, "Started SunlightBoostService on " + action);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SunlightBoostService", e);
        }
    }
}
