/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Constants for the standalone auto sunlight boost. Subset of OSM's
 * device-settings Constants (android_device_oneplus_dodge, branch 16.2)
 * covering only the nodes this app touches.
 */

package org.lineageos.device.sunlightboost;

public final class Constants {

    /* Debug flag */
    public static final boolean DEBUG = true;

    /* SharedPreferences file. Upstream reads PreferenceManager's default file,
     * which would pull in androidx.preference for a single call. */
    public static final String PREFS = "sunlightboost";

    /* HBM */
    public static final String NODE_HBM = "/sys/kernel/oplus_display/hbm_max";
    public static final String KEY_HBM = "hbm_max";

    /** Automatic sunlight boost: drives hbm_max from the light sensor with the
     *  stock hbm_lux_table thresholds (enter 40000 lux, exit 20000 lux). */
    public static final String KEY_SUNLIGHT_BOOST = "sunlight_boost";

    /* OnePulse PWM */
    public static final String NODE_ONEPULSE_PWM = "/sys/kernel/oplus_display/pwm_onepulse";
    public static final String KEY_ONEPULSE_PWM = "onepulse_pwm";

    private Constants() {
    }
}
