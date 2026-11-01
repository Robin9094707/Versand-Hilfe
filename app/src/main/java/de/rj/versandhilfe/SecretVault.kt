package de.rj.versandhilfe

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable data class SecretBundle(val openAi: String = "", val trackingMore: String = "")
@Serializable data class VaultEnvelope(val version: Int = 1, val iterations: Int = 210000, val salt: String, val iv: String, val ciphertext: String)
object BackupCrypto {
    private val random = SecureRandom()
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String) = Base64.getDecoder().decode(value)
    private fun derive(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun encrypt(bundle: SecretBundle, password: String): String {
        require(password.length >= 10 && password.isNotBlank()) { "Bitte ein Backup-Passwort mit mindestens 10 Zeichen wählen." }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, 210000), GCMParameterSpec(128, iv))
        cipher.updateAAD("Versandhilfe-Schluessel-v1".toByteArray())
        val data = cipher.doFinal(AppJson.encodeToString(bundle).toByteArray())
        return AppJson.encodeToString(VaultEnvelope(salt = encode(salt), iv = encode(iv), ciphertext = encode(data)))
    }
    fun decrypt(value: String, password: String): SecretBundle {
        require(value.length <= 30000) { "Ungültiges Schlüsselbackup." }
        val envelope = AppJson.decodeFromString<VaultEnvelope>(value)
        require(envelope.version == 1 && envelope.iterations == 210000) { "Unbekanntes Schlüsselbackup." }
        val salt = decode(envelope.salt); val iv = decode(envelope.iv)
        require(salt.size == 16 && iv.size == 12) { "Ungültiges Schlüsselbackup." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, envelope.iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD("Versandhilfe-Schluessel-v1".toByteArray())
        return AppJson.decodeFromString(String(cipher.doFinal(decode(envelope.ciphertext)), Charsets.UTF_8))
    }
}
class SecretVault(private val context: Context) {
    private val passwordStore get() = ApiKeyStore(context, "backup-password")
    val openAi get() = ApiKeyStore(context, "openai")
    val tracking get() = ApiKeyStore(context)
    private val marker get() = ApiKeyStore(context, "vault-marker")
    fun unlocked(state: AssistantState) = state.encryptedKeys.isNotBlank() && passwordStore.hasKey() && marker.read() == digest(state.encryptedKeys.toByteArray())
    fun mark(envelope: String) { marker.save(digest(envelope.toByteArray())) }
    fun clear() { openAi.save(""); tracking.save(""); passwordStore.save(""); marker.save("") }
    fun ready(state: AssistantState) = state.enabled && openAi.hasKey() && unlocked(state)
    fun hasBackupPassword() = passwordStore.hasKey()
    fun envelope(password: String? = null, openAiValue: String? = null, trackingValue: String? = null): String {
        val pass = password?.takeIf { it.isNotBlank() } ?: passwordStore.read()
        require(pass.length >= 10) { "Bitte zuerst dein Backup-Passwort eingeben (mindestens 10 Zeichen)." }
        return BackupCrypto.encrypt(SecretBundle(openAiValue ?: openAi.read(), trackingValue ?: tracking.read()), pass)
    }
    fun rememberPassword(password: String) { if (password.isNotBlank()) passwordStore.save(password) }
    fun unlock(envelope: String, password: String) {
        val bundle = try { BackupCrypto.decrypt(envelope, password) } catch (e: Exception) { throw IllegalArgumentException("Passwort falsch oder Schlüsselbackup beschädigt.") }
        openAi.save(bundle.openAi); tracking.save(bundle.trackingMore); passwordStore.save(password); mark(envelope)
    }
}
