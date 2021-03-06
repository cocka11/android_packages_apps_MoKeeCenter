/*
 * Copyright (C) 2019 The MoKee Open Source Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mokee.center.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.text.TextUtils;
import android.util.Log;

import com.mokee.center.misc.Constants;
import com.mokee.center.model.UpdateInfo;
import com.mokee.center.model.UpdateStatus;
import com.mokee.center.util.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import androidx.preference.PreferenceManager;

class ABUpdateInstaller {

    private static final String TAG = "ABUpdateInstaller";

    private static final String PREF_INSTALLING_AB_ID = "installing_ab_id";

    private static ABUpdateInstaller sInstance = null;

    private final UpdaterController mUpdaterController;
    private final Context mContext;
    private String mDownloadId;

    private UpdateEngine mUpdateEngine;
    private boolean mBound;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {

        @Override
        public void onStatusUpdate(int status, float percent) {
            UpdateInfo update = mUpdaterController.getUpdate(mDownloadId);
            if (update == null) {
                // We read the id from a preference, the update could no longer exist
                installationDone(status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT);
                return;
            }

            switch (status) {
                case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                case UpdateEngine.UpdateStatusConstants.FINALIZING: {
                    if (update.getStatus() != UpdateStatus.INSTALLING) {
                        update.setStatus(UpdateStatus.INSTALLING);
                        mUpdaterController.notifyUpdateChange(mDownloadId);
                    }
                    update.setInstallProgress(percent);
                    boolean finalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING;
                    update.setFinalizing(finalizing);
                    mUpdaterController.notifyInstallProgress(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT: {
                    installationDone(true);
                    update.setInstallProgress(0);
                    update.setStatus(UpdateStatus.INSTALLED);
                    mUpdaterController.notifyUpdateChange(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.IDLE: {
                    // The service was restarted because we thought we were installing an
                    // update, but we aren't, so clear everything.
                    installationDone(false);
                }
                break;
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                installationDone(false);
                UpdateInfo update = mUpdaterController.getUpdate(mDownloadId);
                update.setInstallProgress(0);
                update.setStatus(UpdateStatus.INSTALLATION_FAILED);
                mUpdaterController.notifyUpdateChange(mDownloadId);
            }
        }
    };

    static synchronized boolean isInstallingUpdate(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null) != null ||
                pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null;
    }

    static synchronized boolean isInstallingUpdate(Context context, String downloadId) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return downloadId.equals(pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null)) ||
                TextUtils.equals(pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null), downloadId);
    }

    static synchronized boolean isWaitingForReboot(Context context, String downloadId) {
        String waitingId = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null);
        return TextUtils.equals(waitingId, downloadId);
    }

    private ABUpdateInstaller(Context context, UpdaterController updaterController) {
        mUpdaterController = updaterController;
        mContext = context.getApplicationContext();
        mUpdateEngine = new UpdateEngine();
    }

    static synchronized ABUpdateInstaller getInstance(Context context,
                                                      UpdaterController updaterController) {
        if (sInstance == null) {
            sInstance = new ABUpdateInstaller(context, updaterController);
        }
        return sInstance;
    }

    public boolean install(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return false;
        }

        mDownloadId = downloadId;

        File file = mUpdaterController.getUpdate(mDownloadId).getFile();
        if (!file.exists()) {
            Log.e(TAG, "The given update doesn't exist");
            mUpdaterController.getUpdate(downloadId)
                    .setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(downloadId);
            return false;
        }

        long offset;
        String[] headerKeyValuePairs;
        try {
            ZipFile zipFile = new ZipFile(file);
            offset = FileUtil.getZipEntryOffset(zipFile, Constants.AB_PAYLOAD_BIN_PATH);
            ZipEntry payloadPropEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH);
            try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                 InputStreamReader isr = new InputStreamReader(is);
                 BufferedReader br = new BufferedReader(isr)) {
                List<String> lines = new ArrayList<>();
                for (String line; (line = br.readLine()) != null;) {
                    lines.add(line);
                }
                headerKeyValuePairs = new String[lines.size()];
                headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
            }
            zipFile.close();
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not prepare " + file, e);
            mUpdaterController.getUpdate(mDownloadId)
                    .setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(mDownloadId);
            return false;
        }

        if (!mBound) {
            mBound = mUpdateEngine.bind(mUpdateEngineCallback);
            if (!mBound) {
                Log.e(TAG, "Could not bind");
                mUpdaterController.getUpdate(downloadId)
                        .setStatus(UpdateStatus.INSTALLATION_FAILED);
                mUpdaterController.notifyUpdateChange(downloadId);
                return false;
            }
        }

        boolean enableABPerfMode = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(Constants.PREF_AB_PERF_MODE, false);
        mUpdateEngine.setPerformanceMode(enableABPerfMode);

        String zipFileUri = "file://" + file.getAbsolutePath();
        mUpdateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs);

        mUpdaterController.getUpdate(mDownloadId).setStatus(UpdateStatus.INSTALLING);
        mUpdaterController.notifyUpdateChange(mDownloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_AB_ID, mDownloadId)
                .apply();

        return true;
    }

    public boolean reconnect() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "reconnect: Not installing any update");
            return false;
        }

        if (mBound) {
            return true;
        }

        mDownloadId = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(PREF_INSTALLING_AB_ID, null);

        // We will get a status notification as soon as we are connected
        mBound = mUpdateEngine.bind(mUpdateEngineCallback);
        if (!mBound) {
            Log.e(TAG, "Could not bind");
            return false;
        }

        return true;
    }

    private void installationDone(boolean needsReboot) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String id = needsReboot ? prefs.getString(PREF_INSTALLING_AB_ID, null) : null;
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(Constants.PREF_NEEDS_REBOOT_ID, id)
                .remove(PREF_INSTALLING_AB_ID)
                .apply();
    }

    public boolean cancel() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update");
            return false;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return false;
        }

        mUpdateEngine.cancel();
        installationDone(false);

        mUpdaterController.getUpdate(mDownloadId)
                .setStatus(UpdateStatus.INSTALLATION_CANCELLED);
        mUpdaterController.notifyUpdateChange(mDownloadId);

        return true;
    }

    public void setPerformanceMode(boolean enable) {
        mUpdateEngine.setPerformanceMode(enable);
    }
}
