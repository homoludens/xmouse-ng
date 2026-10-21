package com.stripe1.xmouse.secrets

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.*
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.asn1.misc.MiscObjectIdentifiers
import org.bouncycastle.asn1.misc.ScryptParams
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.bc.BcPEMDecryptorProvider
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import org.bouncycastle.util.io.pem.PemHeader
import org.bouncycastle.util.io.pem.PemReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.Destroyable

/**
 * The only production entry to JSch private-key parsing. Never hand original or decrypted untrusted
 * bytes to JSch: BC parses them first, then emits canonical, unencrypted, bounded key material.
 * Persistence continues to store the original bytes encrypted at rest, not the normalization.
 */
object BoundedSshKey {
    private val provider by lazy { BouncyCastleProvider() } // Instance only; never registered globally.

    @JvmStatic
    @Throws(PrivateKeyImportException::class)
    fun addIdentity(jsch: JSch, bytes: ByteArray, passphrase: ByteArray) {
        val normalized = normalize(bytes, passphrase)
        try {
            jsch.addIdentity("xmouse-private-key", normalized, null, null as ByteArray?)
        } catch (_: Exception) {
            keyImportFail(PrivateKeyImportException.Reason.INVALID_KEY)
        } finally { normalized.fill(0) }
    }

    internal fun validate(bytes: ByteArray, passphrase: ByteArray) {
        val normalized = normalize(bytes, passphrase)
        var key: KeyPair? = null
        try {
            key = KeyPair.load(JSch(), normalized, null)
            require(key != null && !key.isEncrypted && key.publicKeyBlob != null)
            val message = "XMouse synthetic import validation".toByteArray(Charsets.US_ASCII)
            val signature = if (key.keyType == KeyPair.RSA) key.getSignature(message, "rsa-sha2-256") else key.getSignature(message)
            require(signature != null)
            try {
                val verifier = if (key.keyType == KeyPair.RSA) key.getVerifier("rsa-sha2-256") else key.verifier
                require(verifier != null)
                verifier.update(message)
                require(verifier.verify(signature))
            } finally { signature.fill(0) }
        } catch (_: Exception) {
            keyImportFail(PrivateKeyImportException.Reason.INVALID_KEY)
        } finally { key?.dispose(); normalized.fill(0) }
    }

    internal fun normalize(bytes: ByteArray, passphrase: ByteArray): ByteArray {
        if (bytes.size > PrivateKeyRepository.MAX_KEY_BYTES) keyImportFail(PrivateKeyImportException.Reason.TOO_LARGE)
        if (passphrase.size > KeyInputBounds.MAX_PASSPHRASE_BYTES) keyImportFail(PrivateKeyImportException.Reason.RESOURCE_LIMIT)
        var encrypted = false
        var content: ByteArray? = null
        var plain: ByteArray? = null
        var key: AsymmetricKeyParameter? = null
        try {
            val pem = PemReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.US_ASCII)).use { reader ->
                val result = reader.readPemObject() ?: throw IllegalArgumentException()
                require(reader.readPemObject() == null)
                result
            }
            content = pem.content
            if (content.size > KeyInputBounds.MAX_BINARY_BYTES) keyImportFail(PrivateKeyImportException.Reason.RESOURCE_LIMIT)
            val headers = pem.headers.map { it as PemHeader }
            require(headers.map { it.name }.distinct().size == headers.size)
            encrypted = headers.any { it.name == "Proc-Type" && it.value == "4,ENCRYPTED" }
            if (pem.type == "OPENSSH PRIVATE KEY") {
                require(headers.isEmpty())
                encrypted = KeyInputBounds.openSsh(content)
                if (encrypted && passphrase.isEmpty()) keyImportFail(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID)
                key = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(content, passphrase)
            } else {
                if (pem.type !in setOf("RSA PRIVATE KEY", "EC PRIVATE KEY", "PRIVATE KEY", "ENCRYPTED PRIVATE KEY")) {
                    keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
                }
                plain = if (encrypted) {
                    require(headers.size == 2 && headers.all { it.name == "Proc-Type" || it.name == "DEK-Info" })
                    if (passphrase.isEmpty()) keyImportFail(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID)
                    val dek = headers.single { it.name == "DEK-Info" }.value.split(',')
                    require(dek.size == 2)
                    val ivBytes = when (dek[0]) {
                        "DES-EDE3-CBC" -> 8
                        "AES-128-CBC", "AES-192-CBC", "AES-256-CBC" -> 16
                        else -> keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
                    }
                    require(dek[1].length == ivBytes * 2)
                    val iv = Hex.decode(dek[1])
                    // BC's traditional PEM KDF uses the low 8 bits of each char. This mapping preserves
                    // every original passphrase byte (including UTF-8/non-ASCII), without re-encoding.
                    val chars = CharArray(passphrase.size) { (passphrase[it].toInt() and 255).toChar() }
                    try { BcPEMDecryptorProvider(chars).get(dek[0]).decrypt(content, iv) }
                    finally { chars.fill('\u0000'); iv.fill(0) }
                } else { require(headers.isEmpty()); content.copyOf() }
                KeyInputBounds.der(plain)
                if (pem.type == "ENCRYPTED PRIVATE KEY") {
                    encrypted = true
                    val decrypted = decryptPkcs8(plain, passphrase)
                    plain.fill(0)
                    plain = decrypted
                    KeyInputBounds.der(plain)
                }
                key = when (pem.type) {
                    "PRIVATE KEY", "ENCRYPTED PRIVATE KEY" -> parsePkcs8(plain)
                    "EC PRIVATE KEY" -> {
                        val ec = ECPrivateKey.getInstance(ASN1Primitive.fromByteArray(plain))
                        val oid = ASN1ObjectIdentifier.getInstance(ec.parametersObject)
                        require(oid.id in CURVES)
                        val encoded = PrivateKeyInfo(org.bouncycastle.asn1.x509.AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, oid), ec).encoded
                        try { parsePkcs8(encoded) } finally { encoded.fill(0) }
                    }
                    else -> {
                        val sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(plain))
                        require(sequence.size() == 9)
                        OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(plain)
                    }
                }
            }
            checkKeyBounds(key)
            // Encode using BC, not the hostile source structure. JSch sees no encryption/KDF/header
            // metadata and no unchecked source-derived lengths.
            val der = if (key is RSAPrivateCrtKeyParameters || key is ECPrivateKeyParameters) {
                OpenSSHPrivateKeyUtil.encodePrivateKey(key)
            } else {
                val info = PrivateKeyInfoFactory.createPrivateKeyInfo(key)
                // JSch accepts RFC 5208, not RFC 5958's optional publicKey field. Emit the minimal
                // equivalent PrivateKeyInfo; BC has already interpreted and checked the source.
                PrivateKeyInfo(info.privateKeyAlgorithm, info.parsePrivateKey()).encoded
            }
            val label = when (key) {
                is RSAPrivateCrtKeyParameters -> "RSA PRIVATE KEY"
                is ECPrivateKeyParameters -> "OPENSSH PRIVATE KEY"
                else -> "PRIVATE KEY"
            }
            try { return pem(label, der) } finally { der.fill(0) }
        } catch (failure: PrivateKeyImportException) {
            throw failure
        } catch (_: Exception) {
            keyImportFail(if (encrypted) PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID else PrivateKeyImportException.Reason.INVALID_KEY)
        } finally {
            content?.fill(0); plain?.fill(0)
            if (key is Destroyable) key.destroy()
        }
    }

    private fun parsePkcs8(der: ByteArray): AsymmetricKeyParameter {
        KeyInputBounds.der(der)
        val info = PrivateKeyInfo.getInstance(ASN1Primitive.fromByteArray(der))
        val algorithm = info.privateKeyAlgorithm
        when (algorithm.algorithm.id) {
            PKCSObjectIdentifiers.rsaEncryption.id -> Unit
            X9ObjectIdentifiers.id_ecPublicKey.id -> require(ASN1ObjectIdentifier.getInstance(algorithm.parameters).id in CURVES)
            "1.3.101.112", "1.3.101.113" -> Unit // Ed25519/Ed448
            else -> keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
        }
        val inner = info.privateKey.octets
        try {
            KeyInputBounds.der(inner)
            return PrivateKeyFactory.createKey(info)
        } finally { inner.fill(0) }
    }

    private fun decryptPkcs8(der: ByteArray, phrase: ByteArray): ByteArray {
        val encrypted = EncryptedPrivateKeyInfo.getInstance(ASN1Primitive.fromByteArray(der))
        if (encrypted.encryptionAlgorithm.algorithm != PKCSObjectIdentifiers.id_PBES2) keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
        val parameters = PBES2Parameters.getInstance(encrypted.encryptionAlgorithm.parameters)
        val bits = when (parameters.encryptionScheme.algorithm) {
            NISTObjectIdentifiers.id_aes128_CBC -> 128
            NISTObjectIdentifiers.id_aes192_CBC -> 192
            NISTObjectIdentifiers.id_aes256_CBC -> 256
            else -> keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
        }
        val iv = ASN1OctetString.getInstance(parameters.encryptionScheme.parameters).octets
        require(iv.size == 16)
        val key = when (parameters.keyDerivationFunc.algorithm) {
            PKCSObjectIdentifiers.id_PBKDF2 -> derivePbkdf2(PBKDF2Params.getInstance(parameters.keyDerivationFunc.parameters), phrase, bits)
            MiscObjectIdentifiers.id_scrypt -> deriveScrypt(ScryptParams.getInstance(parameters.keyDerivationFunc.parameters), phrase, bits)
            else -> keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
        }
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", provider)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(encrypted.encryptedData)
        } finally { key.fill(0); iv.fill(0) }
    }

    private fun derivePbkdf2(kdf: PBKDF2Params, phrase: ByteArray, bits: Int): ByteArray {
        val iterations = kdf.iterationCount
        if (iterations.signum() <= 0 || iterations.bitLength() > 31 || iterations.toInt() > KeyInputBounds.MAX_PBKDF2_ROUNDS) keyImportFail(PrivateKeyImportException.Reason.RESOURCE_LIMIT)
        require(kdf.salt.size in 8..64)
        require(kdf.keyLength == null || kdf.keyLength == java.math.BigInteger.valueOf((bits / 8).toLong()))
        val digest: Digest = when (kdf.prf.algorithm) {
            PKCSObjectIdentifiers.id_hmacWithSHA1 -> SHA1Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA224 -> SHA224Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA256 -> SHA256Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA384 -> SHA384Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA512 -> SHA512Digest()
            else -> keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
        }
        if (phrase.isEmpty()) keyImportFail(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID)
        val generator = PKCS5S2ParametersGenerator(digest)
        generator.init(phrase, kdf.salt, iterations.toInt())
        return (generator.generateDerivedParameters(bits) as KeyParameter).key
    }

    private fun deriveScrypt(kdf: ScryptParams, phrase: ByteArray, bits: Int): ByteArray {
        val n = kdf.costParameter
        val r = kdf.blockSize
        val p = kdf.parallelizationParameter
        val one = java.math.BigInteger.ONE
        // Check using arbitrary precision before narrowing, multiplying or invoking the library.
        if (n <= one || n.bitLength() > 21 || n.and(n.subtract(one)).signum() != 0 ||
            r < one || r > java.math.BigInteger.valueOf(8) || p < one || p > java.math.BigInteger.valueOf(16) ||
            n.multiply(r).multiply(java.math.BigInteger.valueOf(128)) > java.math.BigInteger.valueOf(32L * 1024 * 1024) ||
            n.multiply(r).multiply(p) > java.math.BigInteger.valueOf(1L shl 20)) {
            keyImportFail(PrivateKeyImportException.Reason.RESOURCE_LIMIT)
        }
        require(kdf.salt.size in 8..64)
        require(kdf.keyLength == null || kdf.keyLength == java.math.BigInteger.valueOf((bits / 8).toLong()))
        if (phrase.isEmpty()) keyImportFail(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID)
        return SCrypt.generate(phrase, kdf.salt, n.toInt(), r.toInt(), p.toInt(), bits / 8)
    }

    private fun checkKeyBounds(key: AsymmetricKeyParameter) {
        when (key) {
            is RSAPrivateCrtKeyParameters -> {
                require(key.modulus.bitLength() in 512..16384)
                listOf(key.exponent, key.publicExponent, key.p, key.q, key.dp, key.dq, key.qInv).forEach {
                    require(it.signum() > 0 && it.bitLength() <= 16384)
                }
            }
            is ECPrivateKeyParameters -> require(key.parameters.n.bitLength() in 256..521)
            is Ed25519PrivateKeyParameters, is Ed448PrivateKeyParameters -> Unit
            else -> keyImportFail(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT)
        }
    }

    private fun pem(label: String, der: ByteArray): ByteArray {
        val prefix = "-----BEGIN $label-----\n".toByteArray(Charsets.US_ASCII)
        val suffix = "\n-----END $label-----\n".toByteArray(Charsets.US_ASCII)
        val encoded = Base64.encode(der)
        try { return prefix + encoded + suffix } finally { encoded.fill(0) }
    }

    private val CURVES = setOf("1.2.840.10045.3.1.7", "1.3.132.0.34", "1.3.132.0.35")
}
