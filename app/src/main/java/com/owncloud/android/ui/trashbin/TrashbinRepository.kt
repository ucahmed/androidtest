/*
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * Copyright (C) 2018 Tobias Kaminsky
 * Copyright (C) 2018 Nextcloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.owncloud.android.ui.trashbin

import com.owncloud.android.lib.resources.trashbin.model.TrashbinFile

/**
 * Contract between presenter and model
 */
interface TrashbinRepository {
    interface LoadFolderCallback {
        fun onSuccess(files: List<TrashbinFile?>?)
        fun onError(error: Int)
    }

    interface OperationCallback {
        fun onResult(success: Boolean)
    }

    fun getFolder(remotePath: String?, callback: LoadFolderCallback?)
    fun restoreFile(file: TrashbinFile?, callback: OperationCallback?)
    fun emptyTrashbin(callback: OperationCallback?)
    fun removeTrashbinFile(file: TrashbinFile?, callback: OperationCallback?)
}
