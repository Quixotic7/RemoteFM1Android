// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/PickerFolder.swift (836a983, 391497e)
// Where a document picker last picked from (Add firmware…, the cartridge page's .syx, Restore, a web editor's file
// inputs), so the next one opens there. Android: the last picked document's URI, given to the picker as
// DocumentsContract.EXTRA_INITIAL_URI (the system picker then opens in that document's folder). Not checked for
// existence: the picker copes with a document gone.
package com.quixotic7.remotefm1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

class PickerFolder(val key: String, private val store: Store) {
    /** where the last pick is kept (SharedPreferences in the app; a map in the tests) */
    interface Store {
        fun get(key: String): String?
        fun set(key: String, value: String)
    }

    /** remembers where [picked] came from (the first one) */
    fun remember(picked: List<Uri>) = remember(picked.firstOrNull()?.toString())

    fun remember(picked: String?) {
        if (picked.isNullOrEmpty() || !picked.startsWith("content:")) return
        store.set("picker.folder.$key", picked)
    }

    /** the document to open beside, null: none yet */
    val last: String? get() = store.get("picker.folder.$key")

    val initial: Uri? get() = last?.let { runCatching { Uri.parse(it) }.getOrNull() }

    companion object {
        const val FIRMWARE = "firmware"
        const val CART = "cart"
        const val EDITOR = "editor"
        const val RESTORE = "restore"

        fun of(ctx: Context, key: String): PickerFolder {
            val p = ctx.applicationContext.getSharedPreferences("rfm1", Context.MODE_PRIVATE)
            return PickerFolder(key, object : Store {
                override fun get(key: String) = p.getString(key, null)
                override fun set(key: String, value: String) { p.edit().putString(key, value).apply() }
            })
        }
    }

    /** OpenMultipleDocuments that opens where [initial] says (read at each launch) */
    class Open(private val initial: () -> Uri?, private val multiple: Boolean = true) : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            val i = super.createIntent(context, input)
            if (!multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            initial()?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            return i
        }
    }
}
