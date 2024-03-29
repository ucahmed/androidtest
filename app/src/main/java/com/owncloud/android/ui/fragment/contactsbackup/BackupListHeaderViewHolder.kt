/*
 *
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * Copyright (C) 2021 Tobias Kaminsky
 * Copyright (C) 2021 Nextcloud GmbH
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

package com.owncloud.android.ui.fragment.contactsbackup

import android.content.Context
import android.widget.ArrayAdapter
import com.afollestad.sectionedrecyclerview.SectionedViewHolder
import com.owncloud.android.databinding.BackupListItemHeaderBinding

class BackupListHeaderViewHolder(
    val binding: BackupListItemHeaderBinding,
    val context: Context
) : SectionedViewHolder(binding.root) {
    val adapter = ArrayAdapter<ContactsAccount?>(
        context,
        android.R.layout.simple_spinner_dropdown_item,
        ArrayList()
    )

    init {
        binding.spinner.adapter = adapter
    }

    fun setContactsAccount(accounts: List<ContactsAccount>) {
        adapter.clear()
        adapter.addAll(accounts)
    }
}
