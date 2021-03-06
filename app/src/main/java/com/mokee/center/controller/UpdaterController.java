/*
 * Copyright (C) 2018-2019 The MoKee Open Source Project
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
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.SystemClock;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;

import com.lzy.okgo.OkGo;
import com.lzy.okgo.exception.HttpException;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.request.base.Request;
import com.lzy.okserver.OkDownload;
import com.lzy.okserver.download.DownloadListener;
import com.lzy.okserver.download.DownloadTask;
import com.mokee.center.MKCenterApplication;
import com.mokee.center.R;
import com.mokee.center.misc.State;
import com.mokee.center.model.UpdateInfo;
import com.mokee.center.util.BuildInfoUtil;
import com.mokee.center.util.CommonUtil;
import com.mokee.center.util.FileUtil;
import com.mokee.center.util.OkGoUtil;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

public class UpdaterController {

    public static final String ACTION_DOWNLOAD_PROGRESS = "action_download_progress";
    public static final String ACTION_INSTALL_PROGRESS = "action_install_progress";
    public static final String ACTION_UPDATE_REMOVED = "action_update_removed";
    public static final String ACTION_UPDATE_STATUS = "action_update_status_change";
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";

    private final String TAG = UpdaterController.class.getName();

    private static UpdaterController sUpdaterController;

    private final Context mContext;
    private final LocalBroadcastManager mBroadcastManager;
    private OkDownload mOkDownload;

    private final PowerManager.WakeLock mWakeLock;

    private String mActiveDownloadTag;

    public static synchronized UpdaterController getInstance() {
        return sUpdaterController;
    }

    protected static synchronized UpdaterController getInstance(Context context) {
        if (sUpdaterController == null) {
            sUpdaterController = new UpdaterController(context);
        }
        return sUpdaterController;
    }

    private UpdaterController(Context context) {
        mBroadcastManager = LocalBroadcastManager.getInstance(context);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "system:Updater");
        mWakeLock.setReferenceCounted(false);
        mContext = context.getApplicationContext();
        mOkDownload = OkDownload.getInstance();

        CommonUtil.cleanupDownloadsDir(context);

        Map<String, DownloadTask> downloadTaskMap = CommonUtil.getDownloadTaskMap();
        for (UpdateInfo updateInfo : State.loadState(FileUtil.getCachedUpdateList(context))) {
            if (!BuildInfoUtil.isCompatible(updateInfo.getName())) continue;
            DownloadTask downloadTask = downloadTaskMap.get(updateInfo.getName());
            if (downloadTask != null) {
                // File already deleted
                if (TextUtils.isEmpty(downloadTask.progress.filePath) || !new File(downloadTask.progress.filePath).exists()) {
                    downloadTask.remove();
                } else {
                    updateInfo.setProgress(downloadTask.progress);
                }
            }
            mAvailableUpdates.put(updateInfo.getName(), updateInfo);
        }

    }

    public LinkedList<UpdateInfo> getUpdates() {
        LinkedList<UpdateInfo> availableUpdates = new LinkedList<>();
        for (UpdateInfo updateInfo : mAvailableUpdates.values()) {
            availableUpdates.add(updateInfo);
        }
        return availableUpdates;
    }

    private Map<String, UpdateInfo> mAvailableUpdates = new TreeMap<>((o1, o2) -> CommonUtil.compare(o1, o2));

    public void cleanAvailableUpdates() {
        mAvailableUpdates.clear();
    }

    public void setUpdatesAvailableOnline(List<String> downloadIds) {
        for (Iterator<Entry<String, UpdateInfo>> iterator = mAvailableUpdates.entrySet().iterator(); iterator.hasNext();) {
            Entry<String, UpdateInfo> item = iterator.next();
            if (!downloadIds.contains(item.getKey())) {
                Log.d(TAG, item.getKey() + " no longer available online, removing");
                iterator.remove();
            }
        }
    }

    public boolean addUpdate(UpdateInfo updateInfo) {
        Log.d(TAG, "Adding download: " + updateInfo.getName());
        if (mAvailableUpdates.containsKey(updateInfo.getName())) {
            Log.d(TAG, "Download (" + updateInfo.getName() + ") already added");
            return false;
        }
        if (!BuildInfoUtil.isCompatible(updateInfo.getName())) {
            Log.d(TAG, "Download (" + updateInfo.getName() + ") is deprecated");
            return false;
        }
        DownloadTask downloadedTask = mOkDownload.getTask(updateInfo.getName());
        if (downloadedTask != null) {
            updateInfo.setProgress(downloadedTask.progress);
        }
        mAvailableUpdates.put(updateInfo.getName(), updateInfo);
        return true;
    }

    private void verifyUpdateAsync(final String downloadId) {
        new Thread(() -> {
            DownloadTask downloadTask = mOkDownload.getTask(downloadId);
            File partialFile = new File(downloadTask.progress.filePath);
            Progress progress = downloadTask.progress;
            if (!partialFile.exists() || !verifyPackage(partialFile)) {
                progress.status = Progress.ERROR;
                progress.exception = new UnsupportedOperationException("Verification failed");
            }
            progress.fileName = downloadId;
            downloadTask.save();
            partialFile.renameTo(new File(downloadTask.progress.filePath));
            notifyUpdateChange(downloadId);
        }).start();
    }

    private boolean verifyPackage(File file) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            return true;
        }
        try {
            RecoverySystem.verifyPackage(file, null, null);
            Log.e(TAG, "Verification successful");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Verification failed", e);
            if (file.exists()) {
                file.delete();
            } else {
                // The download was probably stopped. Exit silently
                Log.e(TAG, "Error while verifying the file", e);
            }
            return false;
        }
    }

    void notifyUpdateChange(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE_STATUS);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    void notifyUpdateDelete(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE_REMOVED);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    void notifyDownloadProgress(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_DOWNLOAD_PROGRESS);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    void notifyInstallProgress(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_INSTALL_PROGRESS);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void tryReleaseWakelock() {
        if (!hasActiveDownloads()) {
            mWakeLock.release();
        }
    }

    public void startDownload(String downloadId) {
        Log.d(TAG, "Starting " + downloadId);
        Request<File, ? extends Request> request;
        if (BuildInfoUtil.isIncrementalUpdate(downloadId)) {
            request = OkGo.get(mAvailableUpdates.get(downloadId).getDownloadUrl());
        } else {
            request = OkGo.post(mAvailableUpdates.get(downloadId).getDownloadUrl());
            if (MKCenterApplication.getInstance().getDonationInfo().isBasic()) {
                request.params(OkGoUtil.buildParams(mContext));
            }
        }
        DownloadTask task = OkDownload.request(mAvailableUpdates.get(downloadId).getName(), request)
                .fileName(FileUtil.getPartialName(downloadId)).save()
                .register(new LogDownloadListener());
        task.start();
        mAvailableUpdates.get(downloadId).setProgress(task.progress);
    }

    public void resumeDownload(String downloadId) {
        Log.d(TAG, "Resuming " + downloadId);
        DownloadTask downloadTask = mOkDownload.getTask(downloadId);

        if (MKCenterApplication.getInstance().getDonationInfo().isBasic()) {
            if (downloadTask.progress.request != null) {
                if (!BuildInfoUtil.isIncrementalUpdate(downloadId)) {
                    downloadTask.progress.request.params(OkGoUtil.buildParams(mContext));
                }
            } else {
                restartDownload(downloadId);
                return;
            }
        }
        downloadTask.register(new LogDownloadListener()).start();
    }

    public void restartDownload(String downloadId) {
        Log.d(TAG, "Restarting " + downloadId);
        mOkDownload.getTask(downloadId).restart();
    }

    public void pauseDownload(String downloadId) {
        Log.d(TAG, "Pausing " + downloadId);
        mOkDownload.getTask(downloadId).pause();
        mActiveDownloadTag = null;
    }

    public void deleteDownload(String downloadId) {
        Log.d(TAG, "Deleting " + downloadId);
        mOkDownload.getTask(downloadId).remove(true);
        mAvailableUpdates.get(downloadId).setProgress(null);
        notifyUpdateDelete(downloadId);
    }

    public String getActiveDownloadTag() {
        return mActiveDownloadTag;
    }

    public boolean hasActiveDownloads() {
        return !TextUtils.isEmpty(mActiveDownloadTag);
    }

    public UpdateInfo getUpdate(String downloadId) {
        return mAvailableUpdates.get(downloadId);
    }

    public class LogDownloadListener extends DownloadListener {

        private long mLastUpdate = 0;
        private int mStatus = 0;

        public LogDownloadListener() {
            super(LogDownloadListener.class.getName());
        }

        @Override
        public void onStart(Progress progress) {
            mActiveDownloadTag = progress.tag;
            mWakeLock.acquire();
        }

        @Override
        public void onProgress(Progress progress) {
            if (mStatus != progress.status) {
                Log.i(TAG, "Status changed: " + mStatus + " to " + progress.status);
                if (progress.status != Progress.FINISH
                        && progress.status != Progress.ERROR) {
                    notifyUpdateChange(progress.tag);
                }
                mStatus = progress.status;
            } else {
                final long now = SystemClock.elapsedRealtime();
                if (now - DateUtils.SECOND_IN_MILLIS >= mLastUpdate && progress.currentSize != 0) {
                    mLastUpdate = now;

                    long spendTime = (System.currentTimeMillis() - progress.date) / DateUtils.SECOND_IN_MILLIS;
                    long speed = 0;
                    if (spendTime > 0) {
                        speed = progress.speed != 0 ? progress.speed : progress.currentSize / spendTime;
                    }
                    if (speed != 0) {
                        CharSequence eta = CommonUtil.calculateEta(mContext, speed, progress.totalSize, progress.currentSize);
                        CharSequence etaWithSpeed = mContext.getString(R.string.download_speed, eta, Formatter.formatFileSize(mContext, speed));
                        progress.extra1 = mContext.getString(R.string.download_progress_eta_new, etaWithSpeed);
                    }

                    notifyDownloadProgress(progress.tag);
                }
            }
        }

        @Override
        public void onError(Progress progress) {
            notifyUpdateChange(progress.tag);
            if (progress.exception instanceof HttpException) {
                mActiveDownloadTag = null;
            }
        }

        @Override
        public void onFinish(File file, Progress progress) {
            mActiveDownloadTag = null;
            verifyUpdateAsync(progress.tag);
            tryReleaseWakelock();
        }

        @Override
        public void onRemove(Progress progress) {
        }
    }

    public boolean isInstallingUpdate() {
        return UpdateInstaller.isInstalling() ||
                ABUpdateInstaller.isInstallingUpdate(mContext);
    }

    public boolean isInstallingUpdate(String downloadId) {
        return UpdateInstaller.isInstalling(downloadId) ||
                ABUpdateInstaller.isInstallingUpdate(mContext, downloadId);
    }

    public boolean isInstallingABUpdate() {
        return ABUpdateInstaller.isInstallingUpdate(mContext);
    }

    public boolean isWaitingForReboot(String downloadId) {
        return ABUpdateInstaller.isWaitingForReboot(mContext, downloadId);
    }

    public void setPerformanceMode(boolean enable) {
        if (!CommonUtil.isABDevice()) {
            return;
        }
        ABUpdateInstaller.getInstance(mContext, this).setPerformanceMode(enable);
    }
}
