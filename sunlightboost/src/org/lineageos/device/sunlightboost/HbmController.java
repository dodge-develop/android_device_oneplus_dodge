/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * From OSM's device-settings display/HbmController (branch 16.2). Two changes:
 *
 *   * The SharedPreferences instance comes from getSharedPreferences() instead of
 *     androidx PreferenceManager (see SunlightBoostController for why).
 *   * disableHbm() reports whether the node write actually landed, and the
 *     refresh-rate / auto-brightness backups are only dropped when it did, so a
 *     release that the driver refused (panel already off) can be retried instead
 *     of stranding the panel at max HBM and leaving auto-brightness parked.
 *     reconcileBackups() exists so that retry has a place to happen once the
 *     panel is on again.
 *   * enableHbm() no longer refuses while one-pulse PWM is active; it parks PWM
 *     for the duration of the latch and restores it on release. The panel driver
 *     forbids HBM and one-pulse at the same time, and this ROM enables one-pulse
 *     at boot through anti-flicker, so upstream's refusal would disable the boost
 *     entirely (the park keeps the driver's rule: the two never overlap).
 */
package org.lineageos.device.sunlightboost;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

public class HbmController {
    private static final String TAG = "HbmController";
    private static HbmController sInstance;
    private final Context mContext;
    private final SharedPreferences mSharedPrefs;

    private static final float MIN = 60.0f;
    private static final float MAX = 120.0f;
    // While HBM is on the refresh rate is pinned to 120Hz: the kernel freezes ADFR
    // min-fps during HBM (register conflict), but SF timing switches (60<->120) still
    // reprogram the panel and re-latching HBM afterwards shows as a visible flash.
    // Pinning removes the switches entirely; 120 because HBM runs at max min-fps anyway.
    private static final float HBM_FRAMERATE = MAX;
    private static final String KEY_BACKUP_MIN_REFRESH_RATE = "hbm_backup_min_refresh_rate";
    private static final String KEY_BACKUP_MAX_REFRESH_RATE = "hbm_backup_max_refresh_rate";
    private static final String KEY_BACKUP_AUTO_BRIGHTNESS = "hbm_backup_auto_brightness";
    private static final String KEY_BACKUP_PWM = "hbm_backup_pwm";

    private HbmController(Context context) {
        mContext = context.getApplicationContext();
        mSharedPrefs = mContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized HbmController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HbmController(context);
        }
        return sInstance;
    }

    public boolean isHbmEnabled() {
        // The panel always boots with HBM off while the preference may still say
        // enabled (e.g. reboot with HBM on), so the node is the source of truth
        String value = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        if (value != null) {
            return "1".equals(value);
        }
        return mSharedPrefs.getBoolean(Constants.KEY_HBM, false);
    }

    public boolean enableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        // One-pulse PWM is not a refusal here, it is parked for the duration of
        // the latch inside enableHbmInternal() - see the note there.
        return setHbm(true);
    }

    public boolean disableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        return setHbm(false);
    }

    /**
     * Undo anything a crashed session left behind: while HBM was latched
     * auto-brightness is parked MANUAL and the refresh rate is pinned to 120Hz,
     * and the node write that would release them is refused while the panel is
     * off. Call this once the panel is on again.
     */
    public void reconcileBackups() {
        if (!mSharedPrefs.contains(KEY_BACKUP_MIN_REFRESH_RATE)
                && !mSharedPrefs.contains(KEY_BACKUP_MAX_REFRESH_RATE)
                && !mSharedPrefs.contains(KEY_BACKUP_PWM)
                && !mSharedPrefs.getBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, false)) {
            return;
        }
        Log.w(TAG, "Reconciling auto-brightness / refresh-rate / PWM backups left "
                + "by a previous session");
        if (FileUtils.isFileWritable(Constants.NODE_HBM)) {
            disableHbmInternal();
        }
    }

    private boolean setHbm(boolean enable) {
        if (enable) {
            return enableHbmInternal();
        }
        return disableHbmInternal();
    }

    private boolean enableHbmInternal() {
        // 1.Backup and disable auto-brightness
        boolean autoBrightnessEnabled = isAutoBrightnessEnabled();
        mSharedPrefs.edit()
                .putBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, autoBrightnessEnabled)
                .apply();

        if (autoBrightnessEnabled) {
            setAutoBrightness(false);
            Log.i(TAG, "Auto-brightness disabled for HBM");
        }

        // 2.Backup refresh rates and pin to 120Hz so SF stops timing-switching
        // (each switch re-latches HBM in the kernel, which flashes visibly)
        float currentMinRefreshRate = Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE,
                MIN,
                UserHandle.USER_CURRENT);

        float currentMaxRefreshRate = Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE,
                MAX,
                UserHandle.USER_CURRENT);

        mSharedPrefs.edit()
                .putFloat(KEY_BACKUP_MIN_REFRESH_RATE, currentMinRefreshRate)
                .putFloat(KEY_BACKUP_MAX_REFRESH_RATE, currentMaxRefreshRate)
                .apply();

        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, HBM_FRAMERATE,
                UserHandle.USER_CURRENT);
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, HBM_FRAMERATE,
                UserHandle.USER_CURRENT);

        Log.i(TAG, "HBM: pinned refresh rate to " + HBM_FRAMERATE
                + " (backup MIN: " + currentMinRefreshRate + ", MAX: " + currentMaxRefreshRate + ")");

        // 3.Park one-pulse PWM (DC dimming, what "anti-flicker" drives on this
        // ROM) while the HBM region is held. HBM and one-pulse are mutually
        // exclusive in the panel driver: oplus_display_device_ioctl.c refuses the
        // hbm max ioctl outright while pwm_switch_state is set ("onepulse mode
        // don't support apl"), and latching anyway leaves the panel in that
        // forbidden combination - measured on dodge, the gain register 0x51 takes
        // the HBM value while the picture only shifts colour with no brightness
        // gain. Upstream's device-settings refuses the boost instead, but this ROM
        // enables one-pulse at boot (Settings.System display_anti_flicker ->
        // livedisplay AntiFlicker HAL -> PANEL_IOCTL_SET_PWM_PULSE), which would
        // disable the feature entirely, so park it and give it back on release
        // exactly like the auto-brightness and refresh-rate backups.
        PwmController pwmController = PwmController.getInstance(mContext);
        boolean pwmWasEnabled = pwmController.isPwmEnabled();
        mSharedPrefs.edit().putBoolean(KEY_BACKUP_PWM, pwmWasEnabled).apply();
        if (pwmWasEnabled) {
            pwmController.setPwmTemporary(false);
            Log.i(TAG, "One-pulse PWM parked for HBM");
        }

        // 4.Write HBM sysfs node; the kernel additionally freezes ADFR min-fps
        // at max while hbm_max is active. The store is refused whenever the panel
        // is not DPMS_ON, so a failure here is possible - and by this point
        // auto-brightness is already parked and the refresh rate pinned. Undo
        // that immediately rather than leaving adaptive brightness off and the
        // display at a fixed 120Hz until some later sensor event notices.
        if (!FileUtils.writeLine(Constants.NODE_HBM, "1")) {
            Log.w(TAG, "HBM node write failed; undoing the parked settings");
            disableHbmInternal();
            return false;
        }
        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, true).commit();
        Log.i(TAG, "HBM sysfs node enabled");
        return true;
    }

    private boolean disableHbmInternal() {
        // 1. Restore auto-brightness if it was enabled before - but only if it is
        // still parked where we left it. If the user turned adaptive brightness
        // back on themselves while HBM was latched, they win and we leave it.
        boolean wasAutoBrightnessEnabled = mSharedPrefs.getBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, false);
        if (wasAutoBrightnessEnabled && !isAutoBrightnessEnabled()) {
            setAutoBrightness(true);
            Log.i(TAG, "Auto-brightness restored");
        }

        // 2.Restore the refresh rates that were pinned to 120Hz while HBM was on
        // (also covers backups left behind by older builds)
        if (mSharedPrefs.contains(KEY_BACKUP_MIN_REFRESH_RATE)
                || mSharedPrefs.contains(KEY_BACKUP_MAX_REFRESH_RATE)) {
            float backedUpMinRefreshRate = mSharedPrefs.getFloat(KEY_BACKUP_MIN_REFRESH_RATE, MIN);
            float backedUpMaxRefreshRate = mSharedPrefs.getFloat(KEY_BACKUP_MAX_REFRESH_RATE, MAX);

            Log.i(TAG, "Restoring refresh rates - MIN: "
                    + backedUpMinRefreshRate + ", MAX: " + backedUpMaxRefreshRate);

            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE, backedUpMinRefreshRate,
                    UserHandle.USER_CURRENT);
            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE, backedUpMaxRefreshRate,
                    UserHandle.USER_CURRENT);
        }

        // 3. Disable HBM sysfs node. The driver refuses this while the panel is
        // not DPMS_ON (-EFAULT, "display panel is not on"), which is exactly the
        // state ACTION_SCREEN_OFF arrives in, and it never resets
        // panel->oplus_panel.hbm_max_state, so the latch survives the power
        // cycle. If the write did not land, keep the backups: the caller must
        // stay engaged and retry once the panel is back on, otherwise the panel
        // is stuck at max HBM with no way to release it until a reboot.
        boolean written = FileUtils.writeLine(Constants.NODE_HBM, "0");
        if (!written) {
            Log.w(TAG, "HBM node write failed (panel off?); keeping backups for a retry");
            return false;
        }
        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, false).commit();

        // 4.Give one-pulse PWM back only now that the panel has left the HBM
        // region - the two must never overlap. If the user turned it off while the
        // boost was held they win, so only restore what we parked.
        PwmController pwmController = PwmController.getInstance(mContext);
        if (mSharedPrefs.getBoolean(KEY_BACKUP_PWM, false) && !pwmController.isPwmEnabled()) {
            pwmController.setPwmTemporary(true);
            Log.i(TAG, "One-pulse PWM restored");
        }

        // Clear backed up values
        mSharedPrefs.edit()
                .remove(KEY_BACKUP_MIN_REFRESH_RATE)
                .remove(KEY_BACKUP_MAX_REFRESH_RATE)
                .remove(KEY_BACKUP_AUTO_BRIGHTNESS)
                .remove(KEY_BACKUP_PWM)
                .apply();

        Log.i(TAG, "HBM sysfs node disabled, backup cleared");
        return true;
    }

    private boolean isAutoBrightnessEnabled() {
        try {
            int mode = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get auto-brightness state", e);
            return false;
        }
    }

    private void setAutoBrightness(boolean enabled) {
        Settings.System.putIntForUser(
                mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                enabled ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT);
    }
}
