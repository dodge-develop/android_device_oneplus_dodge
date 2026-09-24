/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Automatic sunlight brightness boost, mirroring stock OOS behavior: the stock
 * hbm_lux_table for this panel enters its HBM brightness bands at 40000 lux and
 * exits at 20000 lux. The backlight path tops out at ~798 nits (level 4094), so
 * the boost drives the oplus hbm_max interface (DSI_CMD_HBM_MAX = DBV 4333,
 * ~1118 nits full-white on AA569) through HbmController, which also pins the
 * refresh rate and parks auto-brightness while the panel is latched.
 *
 * Ported verbatim from OSM's device-settings display/SunlightBoostController
 * (android_device_oneplus_dodge, branch 16.2) with two changes, both because
 * this runs as a standalone app instead of inside the device-settings app:
 *
 *   1. The enabled flag is read from Settings.System (written by Settings >
 *      Display > "Auto sunlight boost") instead of the device-settings
 *      SharedPreferences, plus a ContentObserver so flipping that switch takes
 *      effect immediately.
 *   2. SharedPreferences come from getSharedPreferences() rather than androidx
 *      PreferenceManager, to avoid pulling androidx.preference in for one call.
 *
 * It also gains a shutdown() so SunlightBoostService.onDestroy() can release a
 * latched boost; the device-settings app has no equivalent because its service
 * never goes away, and two fixes for failure paths upstream does not handle:
 *
 *   3. disengage() checks the result of HbmController.disableHbm() instead of
 *      dropping mAutoEngaged unconditionally. The driver refuses the node write
 *      while the panel is not DPMS_ON (-EFAULT, "display panel is not on"), which
 *      is exactly the state ACTION_SCREEN_OFF arrives in, and it never resets
 *      panel->oplus_panel.hbm_max_state, so the latch outlives the power cycle.
 *      Clearing the flag anyway stranded the panel at max HBM - tryEngage() then
 *      saw "HBM already on" and refused to touch it - until the next reboot.
 *      Measured on device: write 1 with the panel on, screen off/on, node still
 *      reads 1; a write while the panel is off fails with the WARN above.
 *
 *   5. updateState(), evaluate() and disengage() swallow exceptions: the app is
 *      persistent, so an uncaught exception on the main thread makes AMS restart
 *      it in a loop until RescueParty reboots the device.
 *
 *   4. A bounded retry when the node is not writable yet at init(): the init rc
 *      chowns it at on boot and again at sys.boot_completed and the display
 *      driver creates the oplus_display group lazily (-EPROBE_DEFER), so the
 *      first updateState() can land too early and the app would otherwise not
 *      start listening until the user's next screen cycle.
 */

package org.lineageos.device.sunlightboost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

public class SunlightBoostController {

    private static final String TAG = "SunlightBoostController";

    /** Stock hbm_lux_table id 31 first band: enter 40000 lux, exit 20000 lux */
    private static final float ENTER_LUX = 40000f;
    private static final float EXIT_LUX = 20000f;
    /** Sustained-condition debounce so a camera flash or a shadow doesn't flap the panel */
    private static final long ENTER_DEBOUNCE_MS = 2000;
    private static final long EXIT_DEBOUNCE_MS = 5000;

    private static final String KEY_AUTO_ENGAGED = "sunlight_boost_auto_engaged";
    /** The init rc chowns the node at on boot and again at sys.boot_completed and
     *  the display driver creates the oplus_display group lazily (-EPROBE_DEFER),
     *  so the first updateState() can land before the node is writable. Retry
     *  briefly rather than waiting for the user's next screen cycle. */
    private static final int WRITABLE_RETRY_ATTEMPTS = 12;
    private static final long WRITABLE_RETRY_DELAY_MS = 5000;

    private static SunlightBoostController sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final PowerManager mPowerManager;
    /** Initialised at declaration: the field initialisers below (the settings
     *  observer) run before the constructor body, so a blank final would be read
     *  before it is assigned. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mListening = false;
    private boolean mAutoEngaged = false;
    private long mEnterSince = 0;
    private long mExitSince = 0;
    private boolean mReceiverRegistered = false;
    private boolean mObserverRegistered = false;

    public static synchronized SunlightBoostController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SunlightBoostController(context.getApplicationContext());
        }
        return sInstance;
    }

    private SunlightBoostController(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
        mSensorManager = context.getSystemService(SensorManager.class);
        mLightSensor = mSensorManager != null
                ? mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            evaluate(event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // The panel resets on power cycle, so a latched boost must not
                // leave stale settings backups behind
                if (mAutoEngaged) {
                    disengage("screen off");
                }
                stopListening();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                updateState();
            }
        }
    };

    /** Re-read the Settings.System toggle after Settings changed it. Upstream gets
     *  this for free because its own preference UI calls updateState() itself. */
    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateState();
        }
    };

    /** Called once from the service at boot / service start */
    public void init() {
        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenReceiver, filter);
            mReceiverRegistered = true;
        }
        if (!mObserverRegistered) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Constants.KEY_SUNLIGHT_BOOST),
                    false, mSettingObserver, UserHandle.USER_ALL);
            mObserverRegistered = true;
        }

        // Reconcile a boost left latched by a crash or service restart: if the
        // node no longer reads enabled the user (or a reboot) already cleared it
        mAutoEngaged = mPrefs.getBoolean(KEY_AUTO_ENGAGED, false);
        if (mAutoEngaged && !isHbmNodeOn()) {
            mAutoEngaged = false;
            mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, false).apply();
        }

        updateState();
        if (!mListening && isFeatureEnabled()) {
            retryUntilWritable(0);
        }
        if (Constants.DEBUG) Log.i(TAG, "Initialized, autoEngaged=" + mAutoEngaged);
    }

    private void retryUntilWritable(final int attempt) {
        if (mListening || attempt >= WRITABLE_RETRY_ATTEMPTS) {
            return;
        }
        mHandler.postDelayed(() -> {
            if (mListening) {
                return;
            }
            updateState();
            if (!mListening) {
                retryUntilWritable(attempt + 1);
            }
        }, WRITABLE_RETRY_DELAY_MS);
    }

    /**
     * Re-evaluate after the toggle changes or the screen comes on.
     *
     * Wrapped because a persistent app that throws on the main thread is
     * restarted by AMS in a loop and eventually trips RescueParty, which reboots
     * the whole device; a transient failure here (settings provider not up yet,
     * sensor gone) must not take the device down.
     */
    public void updateState() {
        try {
            updateStateInternal();
        } catch (Exception e) {
            Log.e(TAG, "updateState failed", e);
        }
    }

    private void updateStateInternal() {
        boolean featureEnabled = isFeatureEnabled();

        if (!featureEnabled) {
            if (mAutoEngaged) {
                disengage("feature disabled");
            }
            stopListening();
            return;
        }

        boolean screenOn = mPowerManager == null || mPowerManager.isInteractive();
        if (screenOn && FileUtils.isFileWritable(Constants.NODE_HBM)) {
            startListening();
        } else {
            stopListening();
        }
    }

    /** Upstream reads this from the device-settings SharedPreferences. */
    private boolean isFeatureEnabled() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Constants.KEY_SUNLIGHT_BOOST,
                1,
                UserHandle.USER_CURRENT) != 0;
    }

    private void startListening() {
        if (mListening || mLightSensor == null) {
            return;
        }
        // The ALS is already powered for auto-brightness; batching keeps this a
        // passenger on the existing sensor traffic
        // The panel is on here, so this is the first point where a latch left by
        // a crashed session can actually be released.
        HbmController.getInstance(mContext).reconcileBackups();
        mSensorManager.registerListener(mLightListener, mLightSensor,
                SensorManager.SENSOR_DELAY_NORMAL, 2_000_000 /* maxReportLatencyUs */);
        mListening = true;
        mEnterSince = 0;
        mExitSince = 0;
        if (Constants.DEBUG) Log.i(TAG, "Light sensor listening");
    }

    private void stopListening() {
        if (!mListening) {
            return;
        }
        mSensorManager.unregisterListener(mLightListener);
        mListening = false;
        mEnterSince = 0;
        mExitSince = 0;
        if (Constants.DEBUG) Log.i(TAG, "Light sensor stopped");
    }

    private void evaluate(float lux) {
        try {
            evaluateInternal(lux);
        } catch (Exception e) {
            Log.e(TAG, "evaluate failed", e);
        }
    }

    private void evaluateInternal(float lux) {
        final long now = SystemClock.elapsedRealtime();

        if (mAutoEngaged) {
            if (!isHbmNodeOn()) {
                // The user turned HBM off underneath us (tile/settings): they win.
                // reconcileBackups() also undoes the auto-brightness / refresh-rate
                // park in case our own enable write never landed, which would
                // otherwise leave the display stuck at a pinned 120Hz with
                // adaptive brightness off.
                HbmController.getInstance(mContext).reconcileBackups();
                mAutoEngaged = false;
                mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, false).apply();
                mExitSince = 0;
                return;
            }
            if (lux <= EXIT_LUX) {
                if (mExitSince == 0) {
                    mExitSince = now;
                } else if (now - mExitSince >= EXIT_DEBOUNCE_MS) {
                    disengage("lux " + lux + " below exit threshold");
                }
            } else {
                mExitSince = 0;
            }
            return;
        }

        if (lux >= ENTER_LUX) {
            if (mEnterSince == 0) {
                mEnterSince = now;
            } else if (now - mEnterSince >= ENTER_DEBOUNCE_MS) {
                mEnterSince = 0;
                tryEngage(lux);
            }
        } else {
            mEnterSince = 0;
        }
    }

    private void tryEngage(float lux) {
        // Only take over from auto brightness; a manual slider user chose their level
        if (!isAutoBrightnessEnabled()) {
            if (Constants.DEBUG) Log.i(TAG, "Not engaging: auto brightness off");
            return;
        }
        // Never stack on top of a manually enabled HBM (we would restore over
        // the user's own backups on exit)
        if (isHbmNodeOn()) {
            if (Constants.DEBUG) Log.i(TAG, "Not engaging: HBM already on");
            return;
        }

        // enableHbm() refuses while PWM one-pulse is active
        if (HbmController.getInstance(mContext).enableHbm()) {
            mAutoEngaged = true;
            mExitSince = 0;
            mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, true).apply();
            Log.i(TAG, "Sunlight boost engaged at " + lux + " lux");
        }
    }

    private void disengage(String reason) {
        try {
            disengageInternal(reason);
        } catch (Exception e) {
            Log.e(TAG, "disengage failed", e);
        }
    }

    private void disengageInternal(String reason) {
        if (!HbmController.getInstance(mContext).disableHbm()) {
            // Node write did not land (panel already off). Stay engaged so the
            // engaged branch retries once the panel is back on; dropping the flag
            // here would strand the panel at max HBM until a reboot.
            Log.w(TAG, "disengage failed, staying engaged: " + reason);
            return;
        }
        mAutoEngaged = false;
        mExitSince = 0;
        mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, false).apply();
        Log.i(TAG, "Sunlight boost disengaged: " + reason);
    }

    private boolean isHbmNodeOn() {
        String value = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        return "1".equals(value);
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
            return false;
        }
    }

    /** Shut down and drop listeners (service destroy) */
    public void shutdown() {
        if (mAutoEngaged) {
            disengage("service shutdown");
        }
        stopListening();
    }
}
