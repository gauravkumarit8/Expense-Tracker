package com.autoexpensetracker.util

import android.content.ContentResolver
import android.net.Uri
import java.io.OutputStream

object FileWrite {
    /**
     * Opens [uri] for writing and empties it first.
     *
     * `ContentResolver.openOutputStream(uri)` opens in mode "w", and from
     * Android 10 that mode is no longer guaranteed to truncate. When the user
     * saves over a file that already exists, a shorter new file can then
     * keep the old file's tail, which corrupts a backup, CSV or PDF while the
     * app reports success. Mode "wt" truncates; a provider that rejects it
     * falls back to "w" (right for a brand-new file, the common case).
     */
    fun openForRewrite(resolver: ContentResolver, uri: Uri): OutputStream? =
        try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            resolver.openOutputStream(uri, "w")
        }
}
