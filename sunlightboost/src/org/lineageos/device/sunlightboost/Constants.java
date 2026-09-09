/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Automatic sunlight brightness boost for the OnePlus dodge's AA569 panel.
 * Ported out of OSM's device-settings (org.lineageos.device.settings) so it
 * runs without the OnePlus Settings app. The enabled/disabled state is exposed
 * through Settings.System (written by Settings > Display > Adaptive brightness
 * > "Auto sunlight boost").
 */

package org.lineageos.device.sunlightboost;

import android.provider.Settings;

public final class Constants {

    /** Verbose logging toggle (set false for release builds). */
    public static final boolean DEBUG = true;

    /** OPLUS HBM_MAX sysfs node: write 1 to latch peak panel brightness
     *  (DBV 4333, ~1118 nits full-white), write 0 to exit. */
    public static final String NODE_HBM = "/sys/kernel/oplus_display/hbm_max";

    /** Settings.System key backing the "Auto sunlight boost" toggle (1/0). */
    public static final String KEY_SUNLIGHT_BOOST = "sunlight_boost";

    /** SharedPreferences key recording that HBM is currently auto-engaged, so a
     *  service restart can reconcile a boost left latched by a crash/reboot. */
    public static final String KEY_AUTO_ENGAGED = "sunlight_boost_auto_engaged";

    /** Default for the toggle when it has not been set yet (OSM ships on). */
    public static final boolean DEFAULT_SUNLIGHT_BOOST = true;

    private Constants() {
    }
}