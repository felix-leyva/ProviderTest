package de.ams.providertest

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

class Provider : FileProvider() {
    companion object {
        private const val AUTHORITY_SUFFIX = ".postbox.provider"

        fun getUri(
            context: Context,
            file: File,
        ): Uri = getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? =
        context?.let { context ->
            val file = File(context.cacheDir, uri.lastPathSegment ?: "")
            if (!file.exists()) {
                Timber.e("File does not exist")
                return null
            }
            val encryptedFileWrapper = EncryptedFileWrapper(context = context, targetFile = file)
            encryptedFileWrapper.getStreamingParcelFileDescriptor()
        } ?: run {
            Timber.e("Context is null")
            null
        }
}
