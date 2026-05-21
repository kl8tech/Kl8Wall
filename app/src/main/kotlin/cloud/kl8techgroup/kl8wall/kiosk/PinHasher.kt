package cloud.kl8techgroup.kl8wall.kiosk

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.SecureRandom

/**
 * Argon2id hashing and verification for the optional settings PIN.
 *
 * Uses the argon2kt native library. Parameters are tuned for mobile
 * devices: moderate memory cost (16 MiB) with 3 iterations. The encoded
 * output includes algorithm parameters and salt, so only a single string
 * needs to be persisted.
 */
object PinHasher {

    private const val T_COST = 3
    private const val M_COST_KIB = 16384
    private const val HASH_LENGTH = 32
    private const val SALT_LENGTH = 16

    /**
     * Hash a PIN using Argon2id.
     *
     * Returns the full encoded hash string which embeds the salt,
     * algorithm parameters, and derived key.
     */
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val argon2 = Argon2Kt()
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = pin.toByteArray(),
            salt = salt,
            tCostInIterations = T_COST,
            mCostInKibibyte = M_COST_KIB,
            hashLengthInBytes = HASH_LENGTH
        )
        return result.encodedOutputAsString()
    }

    /**
     * Verify a PIN against a stored Argon2id encoded hash.
     *
     * Returns false if the hash is empty or verification fails.
     */
    fun verify(pin: String, encodedHash: String): Boolean {
        if (encodedHash.isEmpty()) return false
        return try {
            val argon2 = Argon2Kt()
            argon2.verify(
                mode = Argon2Mode.ARGON2_ID,
                encoded = encodedHash,
                password = pin.toByteArray()
            )
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            false
        }
    }
}
