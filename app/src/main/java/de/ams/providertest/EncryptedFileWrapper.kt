package de.ams.providertest

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.io.OutputStream

/**
 * EncryptedFileWrapper
 *
 * Convenience wrapper for working with encrypted files.
 * Provides methods to write to and read from an encrypted file and to create a streaming
 * ParcelFileDescriptor. It uses underlying AndroidX Security library classes to provide a secure
 * encryption with StreamingAED with AES256_GCM_HKDF_4KB scheme.
 *
 * @param context The application context.
 * @param targetFile The encrypted file.
 * @param ioDispatcher The dispatcher to use for IO operations.
 *
 * @author Felix Leyva
 * @since 06.11.24
 */

class EncryptedFileWrapper(
    private val context: Context,
    private val targetFile: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val coroutineScope =
        CoroutineScope(ioDispatcher + SupervisorJob() + CoroutineName("EncryptedFileWrapper"))
    private val masterKey =
        MasterKey
            .Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val encrypted =
        EncryptedFile
            .Builder(
                context,
                targetFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()

    private var writeChannel = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Use the encrypted file as an input stream.
     */
    suspend fun useOutputStream(block: suspend (OutputStream) -> Unit) {
        encrypted.openFileOutput().use { block(it) }
    }

    /**
     * Decrypt the encrypted file to a temporary unencrypted file.
     * IMPORTANT: The decrypted file should be deleted after use. If possible prefer to use the
     * [getStreamingParcelFileDescriptor] method, which allows for streaming the file, without the need to
     * create a temporary file.
     */
    fun decryptToFile(): File {
        // File saved in the cache directory
        val decryptedFile = File.createTempFile("___", null)
        Timber.d("Decrypting file to $decryptedFile")
        encrypted.openFileInput().use { inputStream ->
            decryptedFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return decryptedFile
    }

    /**
     * Create a ParcelFileDescriptor for the encrypted file.
     * The ParcelFileDescriptor is created using a pipe, which allows for streaming the file.
     * Unfortunately, not all applications can use this in some cases, e.g. when the file size is required.
     */
    fun getStreamingParcelFileDescriptor(): ParcelFileDescriptor {
        Timber.d("Creating ParcelFileDescriptor for ${targetFile.name}")
        val (readFd, writeFd) = ParcelFileDescriptor.createReliablePipe()

        // We create a channel to synchronize the reading and writing, due that the pipe block
        //  when the buffer is full with large files.
        val channel = Channel<ByteArray>(Channel.UNLIMITED)

        Timber.d("Starting coroutine to write to pipe")
        coroutineScope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { outputStream ->
                for (bytes in channel) {
                    Timber.d("Writing ${bytes.size} bytes")
                    outputStream.write(bytes)
                }
            }
        }

        // Read from the file and send data to the channel, we need to block until the file is read
        // before closing the channel and returning the read file descriptor.
        runBlocking {
            encrypted.openFileInput().use { inputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                    Timber.d("Read $bytesRead bytes")
                    channel.send(buffer.copyOf(bytesRead))
                }
                channel.close()
            }
        }
        return readFd
    }
    

    fun getWriteParcelFileDescriptor(): ParcelFileDescriptor {
        Timber.d("Creating ParcelFileDescriptor for ${targetFile.name}")
        val (readFd, writeFd) = ParcelFileDescriptor.createReliablePipe()

        Timber.d("Starting coroutine to write to pipe")
        coroutineScope.launch {
            writeChannel = Channel(Channel.UNLIMITED)
            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { inputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                    Timber.d("Read $bytesRead bytes")
                    writeChannel.send(buffer.copyOf(bytesRead))
                }
                writeChannel.close()
            }
        }

        Timber.d("Starting coroutine to write to file")
        coroutineScope.launch {
            encrypted.openFileOutput().use { outputStream ->
                for (bytes in writeChannel) {
                    Timber.d("Writing ${bytes.size} bytes")
                    outputStream.write(bytes)
                }
            }
        }
        return writeFd
    }
}
