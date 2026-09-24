/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (C) 2025 AlphaDroid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.device.sunlightboost;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * From OSM's device-settings utils/FileUtils (branch 16.2), with writeLine()
 * rewritten - see its comment. Everything else is verbatim.
 */
public final class FileUtils {
    private static final String TAG = "FileUtils";

    private FileUtils() {
        // This class is not supposed to be instantiated
    }

    /**
     * Reads the first line of text from the given file. Returns null on failure.
     *
     * NOTE: this method preserves raw content (no trimming). Use readLineTrimmed(...) if you
     * want whitespace trimmed automatically.
     */
    public static String readLine(String fileName) {
        return readLine(fileName, false);
    }

    /**
     * Reads the first line of text from the given file, with optional trimming
     * Reference {@link BufferedReader#readLine()} for clarification on what a line is
     *
     * @return the read line contents, or null on failure
     */
    public static String readLine(String fileName, boolean trim) {
        String line = null;
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(fileName), 512);
            line = reader.readLine();
        } catch (FileNotFoundException e) {
            Log.w(TAG, "No such file " + fileName + " for reading", e);
        } catch (IOException e) {
            Log.e(TAG, "Could not read from file " + fileName, e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Ignored, not much we can do anyway
            }
        }

        return (trim && line != null) ? line.trim() : line;
    }

    /**
     * Reads the first line and returns the trimmed result (null on failure).
     */
    public static String readLineTrimmed(String fileName) {
        return readLine(fileName, true);
    }

    /**
     * Writes the given value into the given file
     *
     * <p>Deliberately unbuffered, and the failure is allowed to propagate out of
     * close(). The upstream version wrapped the FileWriter in a BufferedWriter
     * and swallowed the exception from close() in the finally block: a one-byte
     * value stays in the 8 KiB buffer, the real write(2) only happens at close(),
     * and the error is discarded - so writeLine() reported success for a write
     * that never landed. On sysfs that is the normal rejection path: the oplus
     * hbm_max store returns -EFAULT ("display panel is not on") whenever the
     * panel is not DPMS_ON, which is exactly the state ACTION_SCREEN_OFF arrives
     * in. Callers rely on this result to decide whether to keep the HBM latch
     * bookkeeping, so a false success strands the panel at max brightness.
     * Verified against /dev/full: the buffered form returns true, this one false.
     *
     * @return true on success, false on failure
     */
    public static boolean writeLine(String fileName, String value) {
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write(value.getBytes(StandardCharsets.UTF_8));
            fos.write('\n');
            fos.flush();
            return true;
        } catch (FileNotFoundException e) {
            Log.w(TAG, "No such file " + fileName + " for writing", e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Could not write to file " + fileName, e);
            return false;
        }
    }

    /**
     * Checks whether the given file exists
     *
     * @return true if exists, false if not
     */
    public static boolean fileExists(String fileName) {
        final File file = new File(fileName);
        return file.exists();
    }

    /**
     * Checks whether the given file is readable
     *
     * @return true if readable, false if not
     */
    public static boolean isFileReadable(String fileName) {
        final File file = new File(fileName);
        return file.exists() && file.canRead();
    }

    /**
     * Checks whether the given file is writable
     *
     * @return true if writable, false if not
     */
    public static boolean isFileWritable(String fileName) {
        final File file = new File(fileName);
        return file.exists() && file.canWrite();
    }

    /**
     * Deletes an existing file
     *
     * @return true if the delete was successful, false if not
     */
    public static boolean delete(String fileName) {
        final File file = new File(fileName);
        boolean ok = false;
        try {
            ok = file.delete();
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException trying to delete " + fileName, e);
        }
        return ok;
    }

    /**
     * Renames an existing file
     *
     * @return true if the rename was successful, false if not
     */
    public static boolean rename(String srcPath, String dstPath) {
        final File srcFile = new File(srcPath);
        final File dstFile = new File(dstPath);
        boolean ok = false;
        try {
            ok = srcFile.renameTo(dstFile);
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException trying to rename " + srcPath + " to " + dstPath, e);
        } catch (NullPointerException e) {
            Log.e(TAG, "NullPointerException trying to rename " + srcPath + " to " + dstPath, e);
        }
        return ok;
    }

    /**
     * Reads file content or returns defValue if read fails.
     */
    public static String getFileValue(String filename, String defValue) {
        String v = readLine(filename);
        return v != null ? v : defValue;
    }

    public static boolean getFileValueAsBoolean(String filename, boolean defValue) {
        String v = readLine(filename);
        if (v != null) {
            return !"0".equals(v);
        }
        return defValue;
    }

    public static int getFileValueAsInt(String filename, int defValue) {
        String v = readLineTrimmed(filename);
        if (v == null) return defValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Unable to parse int from " + filename + ": " + v, e);
            return defValue;
        }
    }
}
