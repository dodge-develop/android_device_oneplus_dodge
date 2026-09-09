/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Automatic sunlight brightness boost, mirroring stock OOS behavior: the stock
 * hbm_lux_table for this panel enters its HBM brightness bands at 40000 lux and
 * exits at 20000 lux. The backlight path tops out at ~798 nits (level 4094), so
 * the boost drives the oplus hbm_max interface (DSI_CMD_HBM_MAX = DBV 4333,
 * ~1118 nits full-white on AA569).
 *
 * Straight port of OSM's SunlightBoostController (device_oneplus_dodge), with
 * the enabled switch read from Settings.System instead of the OnePlus Settings
 * app's SharedPreferences, and the auto-engaged HBM flag persisted in our own
 * SharedPreferences so a crash/service restart can reconcile a latched boost.
 *
 * Known simplification vs the full device-settings app: the ADFR min-fps freeze
 * and the PWM one-pulse interlock (HbmController bookkeeping) are not
 * replicated. The kernel already latches DSI_CMD_HBM_MAX correctly, and the
 * interlock only matters if a user has PWM one-pulse strobing active while in
 * max-sunlight auto-brightness, which the panel/driver would not combine
 * anyway. This controller also never engages on top of a manually enabled HBM.
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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

public class SunlightBoostController {

    private static final String TAG = "SunlightBoostController";

    /** Stock hbm_lux_table id 31 first band: enter 40000 lux, exit 20000 lux */
    private static final float ENTER_LUX = 40000f;
    private static final float EXIT_LUX = 20000f;
    /** Sustained-condition debounce so a camera flash or a shadow doesn't flap the panel */
    private static final long ENTER_DEBOUNCE_MS = 2000;
    private static final long EXIT_DEBOUNCE_MS = 5000;

    private static SunlightBoostController sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final PowerManager mPowerManager;
    // Initialized inline (not in the constructor) because mSettingObserver below
    // is a field initializer that needs a non-null handler; field initializers
    // run in declaration order, before the constructor body.
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
        mPrefs = context.getSharedPreferences("sunlightboost", Context.MODE_PRIVATE);
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

    /** Re-read the toggle after Settings > Display changed it */
    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
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

        // Reconcile a boost left latched by a crash or service restart: restore
        // our persisted engaged flag, and if the node no longer reads enabled
        // (user/reboot already cleared it) drop the stale state so we do not
        // think we are still holding HBM.
        mAutoEngaged = mPrefs.getBoolean(Constants.KEY_AUTO_ENGAGED, false);
        if (mAutoEngaged && !isHbmNodeOn()) {
            mAutoEngaged = false;
            mPrefs.edit().putBoolean(Constants.KEY_AUTO_ENGAGED, false).apply();
        }

        updateState();
        if (Constants.DEBUG) Log.i(TAG, "Initialized, autoEngaged=" + mAutoEngaged);
    }

    /** Re-evaluate after the toggle changes or the screen comes on */
    public void updateState() {
        boolean featureEnabled = isFeatureEnabled();

        if (!featureEnabled) {
            if (mAutoEngaged) {
                disengage("feature disabled");
            }
            stopListening();
            return;
        }

        boolean screenOn = mPowerManager == null || mPowerManager.isInteractive();
        if (screenOn && isHbmNodeWritable()) {
            startListening();
        } else {
            stopListening();
        }
    }

    private boolean isFeatureEnabled() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Constants.KEY_SUNLIGHT_BOOST,
                Constants.DEFAULT_SUNLIGHT_BOOST ? 1 : 0,
                UserHandle.USER_CURRENT) != 0;
    }

    private void startListening() {
        if (mListening || mLightSensor == null) {
            return;
        }
        // The ALS is already powered for auto-brightness; batching keeps this a
        // passenger on the existing sensor traffic
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
        final long now = SystemClock.elapsedRealtime();

        if (mAutoEngaged) {
            if (!isHbmNodeOn()) {
                // The user turned HBM off underneath us: they win
                mAutoEngaged = false;
                mPrefs.edit().putBoolean(Constants.KEY_AUTO_ENGAGED, false).apply();
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
        // Never stack on top of a manually enabled HBM
        if (isHbmNodeOn()) {
            if (Constants.DEBUG) Log.i(TAG, "Not engaging: HBM already on");
            return;
        }

        if (writeHbmNode(true)) {
            mAutoEngaged = true;
            mExitSince = 0;
            mPrefs.edit().putBoolean(Constants.KEY_AUTO_ENGAGED, true).apply();
            Log.i(TAG, "Sunlight boost engaged at " + lux + " lux");
        }
    }

    private void disengage(String reason) {
        writeHbmNode(false);
        mAutoEngaged = false;
        mExitSince = 0;
        mPrefs.edit().putBoolean(Constants.KEY_AUTO_ENGAGED, false).apply();
        Log.i(TAG, "Sunlight boost disengaged: " + reason);
    }

    private boolean isHbmNodeWritable() {
        return new File(Constants.NODE_HBM).canWrite();
    }

    private boolean isHbmNodeOn() {
        String value = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        return "1".equals(value);
    }

    private boolean writeHbmNode(boolean on) {
        return FileUtils.writeLine(Constants.NODE_HBM, on ? "1" : "0");
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