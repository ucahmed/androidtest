/*
 * Nextcloud Android client application
 *
 * @author Alper Ozturk
 * Copyright (C) 2023 Alper Ozturk
 * Copyright (C) 2023 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.nextcloud.client.files.downloader

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.app.PendingIntent
import android.content.Context
import androidx.core.util.component1
import androidx.core.util.component2
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.java.util.Optional
import com.nextcloud.model.WorkerState
import com.nextcloud.model.WorkerStateLiveData
import com.nextcloud.utils.ForegroundServiceHelper
import com.owncloud.android.R
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.ForegroundServiceType
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.files.services.IndexedForest
import com.owncloud.android.lib.common.OwnCloudAccount
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.operations.DownloadFileOperation
import com.owncloud.android.operations.DownloadType
import com.owncloud.android.utils.theme.ViewThemeUtils
import java.security.SecureRandom
import java.util.AbstractList
import java.util.Vector

@Suppress("LongParameterList", "TooManyFunctions")
class FileDownloadWorker(
    private val viewThemeUtils: ViewThemeUtils,
    private val accountManager: UserAccountManager,
    private var localBroadcastManager: LocalBroadcastManager,
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params), OnAccountsUpdateListener, OnDatatransferProgressListener {

    companion object {
        private val TAG = FileDownloadWorker::class.java.simpleName

        private val pendingDownloads = IndexedForest<DownloadFileOperation>()

        fun cancelOperation(accountName: String, fileId: Long) {
            pendingDownloads.all.forEach {
                it.value?.payload?.cancelMatchingOperation(accountName, fileId)
            }
        }

        fun isDownloading(accountName: String, fileId: Long): Boolean {
            return pendingDownloads.all.any { it.value?.payload?.isMatching(accountName, fileId) == true }
        }

        const val WORKER_ID = "WORKER_ID"
        const val FILE_REMOTE_PATH = "FILE_REMOTE_PATH"
        const val ACCOUNT_NAME = "ACCOUNT_NAME"
        const val BEHAVIOUR = "BEHAVIOUR"
        const val DOWNLOAD_TYPE = "DOWNLOAD_TYPE"
        const val ACTIVITY_NAME = "ACTIVITY_NAME"
        const val PACKAGE_NAME = "PACKAGE_NAME"
        const val CONFLICT_UPLOAD_ID = "CONFLICT_UPLOAD_ID"

        const val EXTRA_DOWNLOAD_RESULT = "EXTRA_DOWNLOAD_RESULT"
        const val EXTRA_REMOTE_PATH = "EXTRA_REMOTE_PATH"
        const val EXTRA_LINKED_TO_PATH = "EXTRA_LINKED_TO_PATH"
        const val EXTRA_ACCOUNT_NAME = "EXTRA_ACCOUNT_NAME"

        fun getDownloadAddedMessage(): String {
            return FileDownloadWorker::class.java.name + "DOWNLOAD_ADDED"
        }

        fun getDownloadFinishMessage(): String {
            return FileDownloadWorker::class.java.name + "DOWNLOAD_FINISH"
        }
    }

    private var currentDownload: DownloadFileOperation? = null

    private var conflictUploadId: Long? = null
    private var lastPercent = 0

    private val intents = FileDownloadIntents(context)
    private lateinit var notificationManager: DownloadNotificationManager
    private var downloadProgressListener = FileDownloadProgressListener()

    private var user: User? = null
    private var currentUser = Optional.empty<User>()

    private var currentUserFileStorageManager: FileDataStorageManager? = null
    private var fileDataStorageManager: FileDataStorageManager? = null

    private var workerId: Int? = null
    private var downloadError: FileDownloadError? = null

    @Suppress("TooGenericExceptionCaught")
    override fun doWork(): Result {
        return try {
            val requestDownloads = getRequestDownloads()

            notificationManager =
                DownloadNotificationManager(workerId ?: SecureRandom().nextInt(), context, viewThemeUtils)
            addAccountUpdateListener()

            val foregroundInfo = ForegroundServiceHelper.createWorkerForegroundInfo(
                notificationManager.getId(),
                notificationManager.getNotification(),
                ForegroundServiceType.DataSync
            )
            setForegroundAsync(foregroundInfo)

            requestDownloads.forEach {
                downloadFile(it)
            }

            downloadError?.let {
                showDownloadErrorNotification(it)
                notificationManager.dismissNotification()
            }

            setIdleWorkerState()

            Log_OC.e(TAG, "FilesDownloadWorker successfully completed")
            Result.success()
        } catch (t: Throwable) {
            notificationManager.dismissNotification()
            notificationManager.showNewNotification(context.getString(R.string.downloader_unexpected_error))
            Log_OC.e(TAG, "Error caught at FilesDownloadWorker(): " + t.localizedMessage)
            setIdleWorkerState()
            Result.failure()
        }
    }

    override fun onStopped() {
        Log_OC.e(TAG, "FilesDownloadWorker stopped")

        notificationManager.dismissNotification()
        setIdleWorkerState()

        super.onStopped()
    }

    private fun setWorkerState(user: User?) {
        WorkerStateLiveData.instance().setWorkState(WorkerState.Download(user, currentDownload))
    }

    private fun setIdleWorkerState() {
        WorkerStateLiveData.instance().setWorkState(WorkerState.Idle)
    }

    private fun removePendingDownload(accountName: String?) {
        pendingDownloads.remove(accountName)
    }

    private fun getRequestDownloads(): AbstractList<String> {
        workerId = inputData.keyValueMap[WORKER_ID] as Int
        Log_OC.e(TAG, "FilesDownloadWorker started for $workerId")

        setUser()
        val files = getFiles()
        val downloadType = getDownloadType()

        conflictUploadId = inputData.keyValueMap[CONFLICT_UPLOAD_ID] as Long?

        val behaviour = inputData.keyValueMap[BEHAVIOUR] as String? ?: ""
        val activityName = inputData.keyValueMap[ACTIVITY_NAME] as String? ?: ""
        val packageName = inputData.keyValueMap[PACKAGE_NAME] as String? ?: ""

        val requestedDownloads: AbstractList<String> = Vector()

        return try {
            files.forEach { file ->
                val operation = DownloadFileOperation(
                    user,
                    file,
                    behaviour,
                    activityName,
                    packageName,
                    context,
                    downloadType
                )

                operation.addDownloadDataTransferProgressListener(this)
                operation.addDownloadDataTransferProgressListener(downloadProgressListener)
                val (downloadKey, linkedToRemotePath) = pendingDownloads.putIfAbsent(
                    user?.accountName,
                    file.remotePath,
                    operation
                )

                if (downloadKey != null) {
                    requestedDownloads.add(downloadKey)
                    localBroadcastManager.sendBroadcast(intents.newDownloadIntent(operation, linkedToRemotePath))
                }
            }

            requestedDownloads
        } catch (e: IllegalArgumentException) {
            Log_OC.e(TAG, "Not enough information provided in intent: " + e.message)
            requestedDownloads
        }
    }

    private fun setUser() {
        val accountName = inputData.keyValueMap[ACCOUNT_NAME] as String
        user = accountManager.getUser(accountName).get()
        fileDataStorageManager = FileDataStorageManager(user, context.contentResolver)
    }

    private fun getFiles(): List<OCFile> {
        val remotePath = inputData.keyValueMap[FILE_REMOTE_PATH] as String?
        val file = fileDataStorageManager?.getFileByEncryptedRemotePath(remotePath) ?: return listOf()

        return if (file.isFolder) {
            fileDataStorageManager?.getAllFilesRecursivelyInsideFolder(file) ?: listOf()
        } else {
            listOf(file)
        }
    }

    private fun getDownloadType(): DownloadType? {
        val typeAsString = inputData.keyValueMap[DOWNLOAD_TYPE] as String?
        return if (typeAsString != null) {
            if (typeAsString == DownloadType.DOWNLOAD.toString()) {
                DownloadType.DOWNLOAD
            } else {
                DownloadType.EXPORT
            }
        } else {
            null
        }
    }

    private fun addAccountUpdateListener() {
        val am = AccountManager.get(context)
        am.addOnAccountsUpdatedListener(this, null, false)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun downloadFile(downloadKey: String) {
        currentDownload = pendingDownloads.get(downloadKey)

        if (currentDownload == null) {
            return
        }

        setWorkerState(user)
        Log_OC.e(TAG, "FilesDownloadWorker downloading: $downloadKey")

        val isAccountExist = accountManager.exists(currentDownload?.user?.toPlatformAccount())
        if (!isAccountExist) {
            removePendingDownload(currentDownload?.user?.accountName)
            return
        }

        notifyDownloadStart(currentDownload!!)
        var downloadResult: RemoteOperationResult<*>? = null
        try {
            val ocAccount = getOCAccountForDownload()
            val downloadClient =
                OwnCloudClientManagerFactory.getDefaultSingleton().getClientFor(ocAccount, context)

            downloadResult = currentDownload?.execute(downloadClient)
            if (downloadResult?.isSuccess == true && currentDownload?.downloadType === DownloadType.DOWNLOAD) {
                getCurrentFile()?.let {
                    FileDownloadHelper.instance().saveFile(it, currentDownload, currentUserFileStorageManager)
                }
            }
        } catch (e: Exception) {
            Log_OC.e(TAG, "Error downloading", e)
            downloadResult = RemoteOperationResult<Any?>(e)
        } finally {
            cleanupDownloadProcess(downloadResult)
        }
    }

    private fun notifyDownloadStart(download: DownloadFileOperation) {
        lastPercent = 0

        notificationManager.run {
            prepareForStart(download)
            setContentIntent(intents.detailsIntent(download), PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private fun getOCAccountForDownload(): OwnCloudAccount {
        val currentDownloadAccount = currentDownload?.user?.toPlatformAccount()
        val currentDownloadUser = accountManager.getUser(currentDownloadAccount?.name)
        if (currentUser != currentDownloadUser) {
            currentUser = currentDownloadUser
            currentUserFileStorageManager = FileDataStorageManager(currentUser.get(), context.contentResolver)
        }
        return currentDownloadUser.get().toOwnCloudAccount()
    }

    private fun getCurrentFile(): OCFile? {
        var file: OCFile? = currentDownload?.file?.fileId?.let { currentUserFileStorageManager?.getFileById(it) }

        if (file == null) {
            file = currentUserFileStorageManager?.getFileByDecryptedRemotePath(currentDownload?.file?.remotePath)
        }

        if (file == null) {
            Log_OC.e(this, "Could not save " + currentDownload?.file?.remotePath)
            return null
        }

        return file
    }

    private fun cleanupDownloadProcess(result: RemoteOperationResult<*>?) {
        result?.let {
            checkDownloadError(it)
        }

        val removeResult = pendingDownloads.removePayload(
            currentDownload?.user?.accountName,
            currentDownload?.remotePath
        )

        val downloadResult = result ?: RemoteOperationResult<Any?>(RuntimeException("Error downloading…"))

        currentDownload?.run {
            notifyDownloadResult(this, downloadResult)

            val downloadFinishedIntent = intents.downloadFinishedIntent(
                this,
                downloadResult,
                removeResult.second
            )

            localBroadcastManager.sendBroadcast(downloadFinishedIntent)
        }
    }

    private fun checkDownloadError(result: RemoteOperationResult<*>) {
        if (result.isSuccess || downloadError != null) {
            return
        }

        downloadError = if (result.isCancelled) {
            FileDownloadError.Cancelled
        } else {
            FileDownloadError.Failed
        }
    }

    private fun showDownloadErrorNotification(downloadError: FileDownloadError) {
        val text = when (downloadError) {
            FileDownloadError.Cancelled -> {
                context.getString(R.string.downloader_file_download_cancelled)
            }
            FileDownloadError.Failed -> {
                context.getString(R.string.downloader_file_download_failed)
            }
        }

        notificationManager.showNewNotification(text)
    }

    private fun notifyDownloadResult(
        download: DownloadFileOperation,
        downloadResult: RemoteOperationResult<*>
    ) {
        if (downloadResult.isCancelled) {
            return
        }

        val needsToUpdateCredentials = (ResultCode.UNAUTHORIZED == downloadResult.code)
        notificationManager.run {
            prepareForResult()

            if (needsToUpdateCredentials) {
                showNewNotification(context.getString(R.string.downloader_download_failed_credentials_error))
                setContentIntent(
                    intents.credentialContentIntent(download.user),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                setContentIntent(intents.detailsIntent(null), PendingIntent.FLAG_IMMUTABLE)
            }
        }
    }

    override fun onAccountsUpdated(accounts: Array<out Account>?) {
        if (!accountManager.exists(currentDownload?.user?.toPlatformAccount())) {
            currentDownload?.cancel()
        }
    }

    @Suppress("MagicNumber")
    override fun onTransferProgress(
        progressRate: Long,
        totalTransferredSoFar: Long,
        totalToTransfer: Long,
        filePath: String
    ) {
        val percent: Int = (100.0 * totalTransferredSoFar.toDouble() / totalToTransfer.toDouble()).toInt()

        if (percent != lastPercent) {
            notificationManager.run {
                updateDownloadProgress(filePath, percent, totalToTransfer)
            }
        }

        lastPercent = percent
    }

    inner class FileDownloadProgressListener : OnDatatransferProgressListener {
        private val boundListeners: MutableMap<Long, OnDatatransferProgressListener> = HashMap()

        fun isDownloading(user: User?, file: OCFile?): Boolean {
            return FileDownloadHelper.instance().isDownloading(user, file)
        }

        fun addDataTransferProgressListener(listener: OnDatatransferProgressListener?, file: OCFile?) {
            if (file == null || listener == null) {
                return
            }

            boundListeners[file.fileId] = listener
        }

        fun removeDataTransferProgressListener(listener: OnDatatransferProgressListener?, file: OCFile?) {
            if (file == null || listener == null) {
                return
            }

            val fileId = file.fileId
            if (boundListeners[fileId] === listener) {
                boundListeners.remove(fileId)
            }
        }

        override fun onTransferProgress(
            progressRate: Long,
            totalTransferredSoFar: Long,
            totalToTransfer: Long,
            fileName: String
        ) {
            val listener = boundListeners[currentDownload?.file?.fileId]
            listener?.onTransferProgress(
                progressRate,
                totalTransferredSoFar,
                totalToTransfer,
                fileName
            )
        }
    }
}
