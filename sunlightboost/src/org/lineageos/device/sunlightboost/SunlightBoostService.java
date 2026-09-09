/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Holds SunlightBoostController for the device lifetime. It runs only while
 * the screen is on (the controller registers/unregisters the light sensor
 * itself) and is otherwise idle, so it adds effectively no battery cost on top
 * of the ALS that auto-brightness already powers.
 */

package org.lineageos.device.sunlightboost;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class SunlightBoostService extends Service {

    private static final String TAG = "SunlightBoostService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            SunlightBoostController.getInstance(this).init();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SunlightBoostController", e);
        }
        // No need to keep the process alive once initialized: the controller's
        // receivers/observers keep it registered, but Android can reclaim the
        // process if memory is low and restart us via START_STICKY.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            SunlightBoostController.getInstance(this).shutdown();
        } catch (Exception e) {
            Log.e(TAG, "Failed to shut down SunlightBoostController", e);
        }
    }
}