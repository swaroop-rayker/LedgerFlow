package com.ledgerflow.core.crypto.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.ledgerflow.core.crypto.AesGcm
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * KEK-A backed by the Android Keystore.
 *
 * Two properties here are load-bearing and must not be "improved":
 *
 * **`setUserAuthenticationRequired(false)`.** Setting it true would tie the
 * DEK-wrapping key to the lock screen, and adding a fingerprint or re-enrolling
 * biometrics permanently invalidates such keys. A routine phone-settings change
 * would become total data loss. Biometrics gate the UI only (SPEC.md §7.6);
 * they never gate the DEK.
 *
 * **StrongBox is best-effort.** Many devices have no StrongBox, and some
 * advertise it then fail at generation time. A hard requirement would make the
 * app uninstallable on those devices for no security gain over TEE-backed keys.
 */
public class AndroidKeystoreKek(
    private val alias: String = DEFAULT_ALIAS,
) : KeystoreKek {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
    }

    override fun exists(): Boolean = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    override fun create(): Boolean = runCatching {
        // StrongBox first, then TEE. Both use identical parameters otherwise.
        generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        true
    }.recoverCatching { error ->
        if (error.isStrongBoxUnavailable()) {
            generate(strongBox = false)
            true
        } else {
            throw error
        }
    }.getOrDefault(false)

    /**
     * The retry test, with the API level it needs.
     *
     * `StrongBoxUnavailableException` arrived in API 28 and `minSdk` here is 26,
     * so on 26/27 the bare `is` check was a reference to a class the platform
     * does not have. **Nothing on the happy path ever reached it** —
     * [recoverCatching] below is `fold(onSuccess = …, onFailure = …)`, so the
     * predicate is evaluated only once [generate] has already thrown — and the
     * value [create] returns is the same either way, because the other branch
     * rethrows. What it cost was the *cause*: a genuine `KeyStoreException` or
     * `ProviderException` from a keystore that could not generate a key came
     * back from that `instanceof` as a class-resolution failure instead, and
     * `DekManager` then discards it. The one thing this file must never do
     * quietly is lose the reason a key could not be made.
     *
     * `&&` short-circuits, so below 28 the `instanceof` is never executed at
     * all; at 28 and above the guard is always true and the behaviour is
     * unchanged. Caught by `:core:crypto:lintSmsFullDebug`, which `preMergeCheck`
     * did not run until S13 — it depended on `:app`'s lint alone.
     */
    private fun Throwable.isStrongBoxUnavailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && this is StrongBoxUnavailableException

    override fun seal(plaintext: ByteArray, aad: ByteArray): AesGcm.Sealed? {
        val key = loadKey() ?: return null
        return runCatching { AesGcm.encrypt(key, plaintext, aad) }.getOrNull()
    }

    override fun open(sealed: AesGcm.Sealed, aad: ByteArray): KeystoreOpen {
        val key = try {
            loadKey() ?: return KeystoreOpen.Invalidated
        } catch (_: KeyPermanentlyInvalidatedException) {
            return KeystoreOpen.Invalidated
        } catch (_: UnrecoverableKeyException) {
            return KeystoreOpen.Invalidated
        }

        return try {
            AesGcm.decrypt(key, sealed, aad)
                ?.let(KeystoreOpen::Success)
                ?: KeystoreOpen.AuthenticationFailed
        } catch (_: KeyPermanentlyInvalidatedException) {
            KeystoreOpen.Invalidated
        }
    }

    override fun delete() {
        runCatching { keyStore.deleteEntry(alias) }
    }

    private fun loadKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // NEVER true. See the class comment -- this is the single line that
            // stands between a biometric re-enrollment and total data loss.
            .setUserAuthenticationRequired(false)
            // Forces a fresh IV per operation; blocks accidental nonce reuse.
            .setRandomizedEncryptionRequired(true)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun <T> Result<T>.recoverCatching(transform: (Throwable) -> T): Result<T> =
        fold(onSuccess = { Result.success(it) }, onFailure = { runCatching { transform(it) } })

    public companion object {
        public const val DEFAULT_ALIAS: String = "ledgerflow_dek_kek_a"
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}

/** Signals a Keystore failure that callers treat as [KeystoreOpen.Invalidated]. */
internal fun Throwable.isKeystoreInvalidation(): Boolean =
    this is KeyPermanentlyInvalidatedException ||
        this is UnrecoverableKeyException ||
        (this is GeneralSecurityException && cause?.isKeystoreInvalidation() == true)
