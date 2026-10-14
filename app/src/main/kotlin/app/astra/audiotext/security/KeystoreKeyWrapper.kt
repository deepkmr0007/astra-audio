package app.astra.audiotext.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import app.astra.core.security.KeyWrapper
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Master key in the Android Keystore (hardware-backed where the phone supports it; StrongBox is
 * NOT required because it is slow and absent on many phones). It never leaves the keystore and
 * only wraps per-file data keys (AES-256-GCM, keystore-generated IVs).
 *
 * Limits (shown in the privacy dashboard): encryption protects data at rest, e.g. if the storage
 * is copied. It does not protect data while the phone is unlocked and the app is open, nor on a
 * rooted or compromised OS.
 */
class KeystoreKeyWrapper(private val alias: String = ALIAS) : KeyWrapper {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun wrap(dataKey: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv to c.doFinal(dataKey)
    }

    override fun unwrap(iv: ByteArray, wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(wrapped)
    }

    /** Human-readable description of where the key lives, for the privacy dashboard. */
    fun describeProtection(): String = try {
        val k = key()
        val info = SecretKeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox secure element"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Hardware-backed keystore (TEE)"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software keystore (this phone has no secure hardware)"
                else -> "Android Keystore"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "Hardware-backed keystore" else "Software keystore"
        }
    } catch (e: Exception) {
        "Android Keystore (level unknown)"
    }

    /** Crypto-erase: deleting the master key makes every encrypted file permanently unreadable. */
    fun destroy() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    companion object {
        const val ALIAS = "astra.audiotext.master.v1"
    }
}
