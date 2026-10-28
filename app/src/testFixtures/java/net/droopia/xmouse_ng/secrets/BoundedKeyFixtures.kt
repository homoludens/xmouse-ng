package net.droopia.xmouse_ng.secrets

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.crypto.generators.OpenSSLPBEParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic regression structures, not real private keys. Never passed to the old JSch parser. */
internal object BoundedKeyFixtures {
    // A tiny definite-length SEQUENCE whose modulus claims a length exceeding its remaining input.
    // Reading the encoded length in the test is safe; no test allocates that claimed length.
    fun malformedDer(): ByteArray = byteArrayOf(0x30, 9, 2, 1, 0, 2, 0x84.toByte(), 0x7f, -1, -1, -1)

    fun pem(type: String, bytes: ByteArray): ByteArray =
        "-----BEGIN $type-----\n".toByteArray() + Base64.encode(bytes) + "\n-----END $type-----\n".toByteArray()

    /** Models malformed plaintext after decryption, with valid cipher padding so DER checks must run. */
    fun encryptedMalformed(phrase: ByteArray): ByteArray {
        val der = malformedDer()
        val iv = ByteArray(16) { (it + 1).toByte() }
        val generator = OpenSSLPBEParametersGenerator()
        generator.init(phrase, iv.copyOf(8), 1)
        val key = (generator.generateDerivedParameters(128) as KeyParameter).key
        try {
            val encryptor = Cipher.getInstance("AES/CBC/PKCS7Padding", BouncyCastleProvider())
            encryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val content = encryptor.doFinal(der)
            try {
                return ("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC," +
                    Hex.toHexString(iv).uppercase() + "\n\n").toByteArray() +
                    Base64.encode(content) + "\n-----END RSA PRIVATE KEY-----\n".toByteArray()
            } finally { content.fill(0) }
        } finally { key.fill(0); iv.fill(0); der.fill(0) }
    }
}
