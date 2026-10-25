package com.octaviantocan.pulls.data.personal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.octaviantocan.pulls.data.PersonalAuthError
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.data.PersonalSessionBlobStorage
import com.octaviantocan.pulls.data.PersonalSessionVault
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Non-exportable Android Keystore key; neither the ciphertext nor its key participates in backup. */
internal class PersonalSecureStorage(context: Context) : PersonalSessionBlobStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "personal-account-v1"))
    private val alias = "${context.packageName}.personal-account-v1"
    private val associatedData = "Pully personal sessions v1".toByteArray(Charsets.UTF_8)

    override fun read(): ByteArray? = synchronized(storageLock) { readStored() }

    private fun readStored(): ByteArray? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() <= PersonalSessionVault.MAX_BYTES + 29 - count)
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { require(it.size >= 29) }
            }
            require(bytes[0] == 1.toByte())
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                updateAAD(associatedData)
                doFinal(bytes, 13, bytes.size - 13)
            }
        } catch (_: Exception) { throw PersonalAuthError(PersonalAuthProblem.Unavailable) }
    }

    override fun write(contents: ByteArray) = synchronized(storageLock) { writeStored(contents) }

    private fun writeStored(contents: ByteArray) {
        require(contents.size <= PersonalSessionVault.MAX_BYTES)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(associatedData)
            require(cipher.iv.size == 12)
            val ciphertext = byteArrayOf(1) + cipher.iv + cipher.doFinal(contents)
            val stream = file.startWrite()
            try {
                stream.write(ciphertext)
                // AtomicFile may log a failed sync/rename instead of throwing. A stage must
                // report failure before the server is asked to confirm its bearer.
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            file.openRead().use { input ->
                val committed = ByteArray(ciphertext.size)
                var offset = 0
                while (offset < committed.size) {
                    val count = input.read(committed, offset, committed.size - offset)
                    check(count > 0)
                    offset += count
                }
                check(input.read() == -1 && committed.contentEquals(ciphertext))
            }
        } catch (_: Exception) { throw PersonalAuthError(PersonalAuthProblem.Unavailable) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
    companion object { private val storageLock = Any() }

}
