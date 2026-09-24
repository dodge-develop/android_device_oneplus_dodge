/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The manifest marks this app persistent, so the system starts this process at
 * boot and never caches it. Initialising here means the controller is live as
 * soon as that happens, without depending on the BOOT_COMPLETED broadcast
 * reaching BootReceiver in time.
 */

package org.lineageos.device.sunlightboost;

import android.app.Application;
import android.util.Log;

public class SunlightBoostApp extends Application {

    private static final String TAG = "SunlightBoostApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            SunlightBoostController.getInstance(this).init();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SunlightBoostController", e);
        }
    }
}
