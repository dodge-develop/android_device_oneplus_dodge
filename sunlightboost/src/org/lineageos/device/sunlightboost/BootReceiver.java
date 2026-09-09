/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.sunlightboost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts SunlightBoostService once the device finishes booting. */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SunlightBoostBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // BOOT_COMPLETED is exempt from background-start restrictions; the
        // controller only registers listeners and does no ongoing work, so a
        // plain started service is all we need.
        Intent service = new Intent(context, SunlightBoostService.class);
        context.startService(service);
    }
}