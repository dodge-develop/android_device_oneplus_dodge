/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.sunlightboost;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/** Minimal sysfs helpers (subset of OSM's FileUtils). */
public final class FileUtils {

    private static final String TAG = "SunlightBoostFileUtils";

    private FileUtils() {
    }

    /** Read the first line of a node, trimmed, or null on failure. */
    public static String readLineTrimmed(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Write value to node, returning true on success. */
    public static boolean writeLine(String path, String value) {
        File file = new File(path);
        if (!file.exists()) {
            if (Constants.DEBUG) Log.w(TAG, "writeLine: node missing " + path);
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(value.getBytes());
            fos.write('\n');
            return true;
        } catch (IOException e) {
            Log.e(TAG, "writeLine failed for " + path, e);
            return false;
        }
    }
}