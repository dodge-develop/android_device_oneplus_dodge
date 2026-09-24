/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Verbatim from OSM's device-settings display/PwmController (branch 16.2),
 * except that the SharedPreferences instance comes from getSharedPreferences()
 * instead of androidx PreferenceManager. Kept because HbmController.enableHbm()
 * refuses to latch HBM while one-pulse PWM is active.
 */
package org.lineageos.device.sunlightboost;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class PwmController {
    private static final String TAG = "PwmController";
    private static PwmController sInstance;
    private final Context mContext;
    private final SharedPreferences mSharedPrefs;

    private PwmController(Context context) {
        mContext = context.getApplicationContext();
        mSharedPrefs = mContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized PwmController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PwmController(context);
        }
        return sInstance;
    }

    public boolean isPwmEnabled() {
        // The kernel state resets on reboot, so the node is the source of truth;
        // the preference is only a fallback while the node is unreadable
        String value = FileUtils.readLineTrimmed(Constants.NODE_ONEPULSE_PWM);
        if (value != null) {
            return "1".equals(value);
        }
        return mSharedPrefs.getBoolean(Constants.KEY_ONEPULSE_PWM, false);
    }

    /**
     * Re-apply the persisted PWM choice after boot: the panel always comes up with
     * one-pulse disabled, so a user selection would otherwise be lost on reboot.
     */
    public void restorePwmSetting() {
        boolean wanted = mSharedPrefs.getBoolean(Constants.KEY_ONEPULSE_PWM, false);
        if (wanted && !isPwmEnabled()) {
            if (FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
                setPwm(true);
                Log.i(TAG, "Restored PWM setting after boot");
            } else {
                Log.w(TAG, "PWM node is not writable, cannot restore setting");
            }
        }
    }

    public boolean enablePwm() {
        if (!FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            Log.w(TAG, "PWM node is not writable");
            return false;
        }

        // PWM has priority: disable HBM if it's active
        HbmController hbmController = HbmController.getInstance(mContext);
        if (hbmController.isHbmEnabled()) {
            Log.i(TAG, "HBM is active, disabling it (PWM has priority)");
            hbmController.disableHbm();
        }

        return setPwm(true);
    }

    public boolean disablePwm() {
        if (!FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            Log.w(TAG, "PWM node is not writable");
            return false;
        }

        return setPwm(false);
    }

    /**
     * Write the node without touching the persisted user preference. The sunlight
     * boost parks one-pulse (DC dimming) while it holds the panel's HBM region -
     * the two are mutually exclusive in the panel driver - and a selection that
     * was parked by the boost must not be recorded as if the user had changed it.
     */
    boolean setPwmTemporary(boolean enable) {
        if (!FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            Log.w(TAG, "PWM node is not writable");
            return false;
        }
        return FileUtils.writeLine(Constants.NODE_ONEPULSE_PWM, enable ? "1" : "0");
    }

    private boolean setPwm(boolean enable) {
        // Only record the preference once the node really took the value: it is
        // the fallback isPwmEnabled() reads when the node is unreachable, so a
        // false success would make HbmController refuse to latch HBM forever.
        if (!FileUtils.writeLine(Constants.NODE_ONEPULSE_PWM, enable ? "1" : "0")) {
            Log.w(TAG, "PWM node write failed, not recording the preference");
            return false;
        }
        mSharedPrefs.edit().putBoolean(Constants.KEY_ONEPULSE_PWM, enable).commit();
        Log.i(TAG, "PWM set to: " + enable);
        return true;
    }
}
