package net.droopia.xmouse_ng.secrets

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.*
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.PEMKeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.Security
import java.util.Random

class BoundedSshKeyTest {
    private fun asset(name: String) = javaClass.classLoader!!.getResourceAsStream("ssh-crypto/$name")!!.use { it.readBytes() }
    private fun reject(bytes: ByteArray, phrase: ByteArray = byteArrayOf()): PrivateKeyImportException {
        try { BoundedSshKey.validate(bytes, phrase) } catch (failure: PrivateKeyImportException) {
            assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
            return failure
        }
        throw AssertionError("Expected a bounded key-validation failure")
    }

    @Test fun deterministicMalformedPlainLengthIsRejectedWithoutJschAllocation() {
        val der = BoundedKeyFixtures.malformedDer()
        val claimed = java.nio.ByteBuffer.wrap(der, 7, 4).int
        assertTrue(claimed.toLong() > der.size.toLong())
        assertEquals(PrivateKeyImportException.Reason.INVALID_KEY,
            reject(BoundedKeyFixtures.pem("RSA PRIVATE KEY", der)).reason)
    }

    @Test fun deterministicMalformedDecryptedLengthIsRejectedWithValidCipherPadding() {
        val phrase = byteArrayOf(9, 8, 7, 0, -1)
        val encrypted = BoundedKeyFixtures.encryptedMalformed(phrase)
        val pem = org.bouncycastle.util.io.pem.PemReader(InputStreamReader(ByteArrayInputStream(encrypted))).use { it.readPemObject() }
        val iv = org.bouncycastle.util.encoders.Hex.decode((pem.headers[1] as org.bouncycastle.util.io.pem.PemHeader).value.split(',')[1])
        val chars = CharArray(phrase.size) { (phrase[it].toInt() and 255).toChar() }
        val decoded = try { org.bouncycastle.openssl.bc.BcPEMDecryptorProvider(chars).get("AES-128-CBC").decrypt(pem.content, iv) }
        finally { chars.fill('\u0000') }
        try { assertTrue("Fixture must reach structural validation after successful cipher unpadding", decoded.contentEquals(BoundedKeyFixtures.malformedDer())) }
        finally { decoded.fill(0) }
        assertEquals(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID, reject(encrypted, phrase).reason)
    }

    @Test fun fixedExternalEncryptedKeysRejectMissingAndWrongPassphrases() {
        listOf("rsa1024-encrypted.pem", "ecdsa-p256-encrypted.pem").forEach { name ->
            listOf(byteArrayOf(), byteArrayOf(1, 2, 3, 4)).forEach { phrase ->
                assertEquals(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID, reject(asset(name), phrase).reason)
            }
        }
    }

    @Test fun allFourExternalPemFormsReachSafeIdentityAndSignWithoutProviderMutation() {
        val providers = Security.getProviders().map { it.name to it.javaClass.name }
        val config = JSch.getConfig()
        listOf("rsa1024.pem", "ecdsa-p256.pem", "rsa1024-encrypted.pem", "ecdsa-p256-encrypted.pem").forEach { name ->
            val phrase = if (name.contains("encrypted")) "NOT_REAL_XMOUSE_FIXTURE".toByteArray() else byteArrayOf()
            val bytes = asset(name)
            BoundedSshKey.validate(bytes, phrase)
            val jsch = JSch()
            try {
                BoundedSshKey.addIdentity(jsch, bytes, phrase)
                val identity = jsch.identityRepository.identities.single() as com.jcraft.jsch.Identity
                assertFalse(identity.isEncrypted)
                assertNotNull(identity.getSignature(byteArrayOf(1, 2, 3)))
            } finally { jsch.removeAllIdentity(); bytes.fill(0); phrase.fill(0) }
        }
        assertEquals(providers, Security.getProviders().map { it.name to it.javaClass.name })
        assertEquals(config, JSch.getConfig())
    }

    @Test fun bytePassphrasesAreNotReencodedOrTruncatedIncludingNonAsciiAndNul() {
        val phrase = byteArrayOf(-1, -128, 0, 1, 42)
        val bytes = PrivateKeyRepositoryTest.syntheticKey(KeyPair.RSA, 1024, phrase)
        BoundedSshKey.validate(bytes, phrase)
        reject(bytes, phrase.copyOfRange(0, 2))
    }

    @Test fun boundedDerRejectsTruncationOverflowIndefiniteDepthAndNodeExhaustion() {
        val cases = mutableListOf(byteArrayOf(), byteArrayOf(0x30, 0x80.toByte(), 0, 0),
            byteArrayOf(0x30, 0x84.toByte(), -1, -1, -1, -1), byteArrayOf(2, 2, 1))
        var nested = byteArrayOf(5, 0)
        repeat(20) { nested = byteArrayOf(0x30, nested.size.toByte()) + nested }
        cases += nested
        cases += ByteArray(260) { if (it % 2 == 0) 5 else 0 }
        cases.forEach { bytes ->
            try { KeyInputBounds.der(bytes); fail("Malformed DER should be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun boundedStructuralPropertyCasesNeverAllocateClaimedLengthsOrRecurse() {
        val random = Random(41)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(512)); random.nextBytes(bytes)
            try { KeyInputBounds.der(bytes) } catch (_: IllegalArgumentException) { }
        }
        val fixed = asset("rsa1024.pem")
        for (length in 0..fixed.size step 13) reject(fixed.copyOf(length))
    }

    @Test fun unsupportedFormatsAndResourceExcessHaveSanitizedReasons() {
        assertEquals(PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT,
            reject(BoundedKeyFixtures.pem("UNKNOWN PRIVATE KEY", byteArrayOf(1))).reason)
        assertEquals(PrivateKeyImportException.Reason.TOO_LARGE,
            reject(ByteArray(PrivateKeyRepository.MAX_KEY_BYTES + 1)).reason)
        assertEquals(PrivateKeyImportException.Reason.RESOURCE_LIMIT,
            reject(asset("rsa1024.pem"), ByteArray(KeyInputBounds.MAX_PASSPHRASE_BYTES + 1)).reason)
    }

    @Test fun pkcs8RsaAndEcRemainUsableWithoutOriginalStructure() {
        listOf("rsa1024.pem", "ecdsa-p256.pem").forEach { name ->
            val info = PEMParser(InputStreamReader(ByteArrayInputStream(asset(name)))).use { (it.readObject() as PEMKeyPair).privateKeyInfo }
            BoundedSshKey.validate(BoundedKeyFixtures.pem("PRIVATE KEY", info.encoded), byteArrayOf())
        }
    }

    @Test fun encryptedPkcs8RsaAndEcRoundTripAndRejectWrongPhrase() {
        val phrase = "NOT_REAL_PKCS8_fixture".toCharArray()
        listOf("rsa1024.pem", "ecdsa-p256.pem").forEach { name ->
            val info = PEMParser(InputStreamReader(ByteArrayInputStream(asset(name)))).use { (it.readObject() as PEMKeyPair).privateKeyInfo }
            val encryptor = org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(org.bouncycastle.openssl.PKCS8Generator.AES_256_CBC)
                .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider()).setIterationCount(2048).setPassword(phrase).build()
            val encrypted = org.bouncycastle.openssl.PKCS8Generator(info, encryptor).generate()
            val bytes = BoundedKeyFixtures.pem(encrypted.type, encrypted.content)
            BoundedSshKey.validate(bytes, String(phrase).toByteArray())
            assertEquals(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID, reject(bytes, byteArrayOf(1)).reason)
        }
        phrase.fill('\u0000')
    }

    @Test fun pkcs8ExcessiveKdfIsRejectedBeforeDerivation() {
        val kdf = KeyDerivationFunc(PKCSObjectIdentifiers.id_PBKDF2, PBKDF2Params(ByteArray(16), Int.MAX_VALUE))
        val scheme = EncryptionScheme(NISTObjectIdentifiers.id_aes128_CBC, DEROctetString(ByteArray(16)))
        val pbes = AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, PBES2Parameters(kdf, scheme))
        val encrypted = EncryptedPrivateKeyInfo(pbes, ByteArray(16))
        assertEquals(PrivateKeyImportException.Reason.RESOURCE_LIMIT,
            reject(BoundedKeyFixtures.pem("ENCRYPTED PRIVATE KEY", encrypted.encoded), byteArrayOf(1)).reason)
    }

    @Test fun pkcs8ScryptUsesBoundedLibraryDerivationAndRejectsExcessiveMemoryParameters() {
        val info = PEMParser(InputStreamReader(ByteArrayInputStream(asset("rsa1024.pem")))).use { (it.readObject() as PEMKeyPair).privateKeyInfo }
        val salt = ByteArray(16) { it.toByte() }
        val phrase = byteArrayOf(-1, 0, 1)
        val key = org.bouncycastle.crypto.generators.SCrypt.generate(phrase, salt, 16, 1, 1, 16)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS7Padding", org.bouncycastle.jce.provider.BouncyCastleProvider())
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(ByteArray(16)))
        val ciphertext = try { cipher.doFinal(info.encoded) } finally { key.fill(0) }
        fun encrypted(n: Int) : ByteArray {
            val kdf = KeyDerivationFunc(org.bouncycastle.asn1.misc.MiscObjectIdentifiers.id_scrypt,
                org.bouncycastle.asn1.misc.ScryptParams(salt, n, 1, 1))
            val scheme = EncryptionScheme(NISTObjectIdentifiers.id_aes128_CBC, DEROctetString(ByteArray(16)))
            val pbes = AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, PBES2Parameters(kdf, scheme))
            return BoundedKeyFixtures.pem("ENCRYPTED PRIVATE KEY", EncryptedPrivateKeyInfo(pbes, ciphertext).encoded)
        }
        BoundedSshKey.validate(encrypted(16), phrase)
        assertEquals(PrivateKeyImportException.Reason.RESOURCE_LIMIT, reject(encrypted(Int.MAX_VALUE), phrase).reason)
    }

    @Test fun openSshStringsAndBcryptWorkAreCheckedBeforeLibraryParse() {
        val output = java.io.ByteArrayOutputStream()
        val writer = java.io.DataOutputStream(output)
        writer.write("openssh-key-v1\u0000".toByteArray())
        writer.writeInt(Int.MAX_VALUE)
        reject(BoundedKeyFixtures.pem("OPENSSH PRIVATE KEY", output.toByteArray()))
        val info = PEMParser(InputStreamReader(ByteArrayInputStream(asset("ecdsa-p256.pem")))).use { (it.readObject() as PEMKeyPair).privateKeyInfo }
        val valid = OpenSSHPrivateKeyUtil.encodePrivateKey(PrivateKeyFactory.createKey(info))
        BoundedSshKey.validate(BoundedKeyFixtures.pem("OPENSSH PRIVATE KEY", valid), byteArrayOf())
    }

    @Test fun excessiveOpenSshBcryptRoundsFailBeforeKdfOrPublicKeyParsing() {
        fun block(bytes: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also {
            java.io.DataOutputStream(it).apply { writeInt(bytes.size); write(bytes) }
        }.toByteArray()
        val options = block(ByteArray(16)) + java.nio.ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        val bytes = "openssh-key-v1\u0000".toByteArray() + block("aes256-ctr".toByteArray()) +
            block("bcrypt".toByteArray()) + block(options)
        assertEquals(PrivateKeyImportException.Reason.RESOURCE_LIMIT,
            reject(BoundedKeyFixtures.pem("OPENSSH PRIVATE KEY", bytes), byteArrayOf(1)).reason)
    }

    @Test fun encryptedOpenSshRsaAndEcUseMaintainedParserAndBytePassphrases() {
        listOf(KeyPair.RSA, KeyPair.ECDSA).forEach { type ->
            val key = KeyPair.genKeyPair(JSch(), type, if (type == KeyPair.RSA) 1024 else 256)
            val phrase = byteArrayOf(-1, 0, 4, 2)
            try {
                val bytes = java.io.ByteArrayOutputStream().also { key.writeOpenSSHv1PrivateKey(it, phrase, "aes256-ctr", 2) }.toByteArray()
                BoundedSshKey.validate(bytes, phrase)
                reject(bytes, byteArrayOf(1))
            } finally { key.dispose() }
        }
    }

    @Test fun invalidMaterialNeverReachesJschIdentityLoader() {
        val jsch = org.mockito.Mockito.mock(JSch::class.java)
        val phrase = byteArrayOf(4, 3, 2)
        listOf(BoundedKeyFixtures.pem("RSA PRIVATE KEY", BoundedKeyFixtures.malformedDer()),
            BoundedKeyFixtures.encryptedMalformed(phrase)).forEach { bytes ->
            try { BoundedSshKey.addIdentity(jsch, bytes, phrase); fail("Invalid key accepted") }
            catch (_: PrivateKeyImportException) { }
        }
        org.mockito.Mockito.verifyNoInteractions(jsch)
    }

    @Test fun additionalSupportedEcCurvesAndOpenSshEd25519RemainUsable() {
        listOf(384, 521).forEach { bits ->
            val key = PrivateKeyRepositoryTest.syntheticKey(KeyPair.ECDSA, bits)
            BoundedSshKey.validate(key, byteArrayOf())
        }
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ED25519, 256)
        try {
            val bytes = java.io.ByteArrayOutputStream().also { key.writeOpenSSHv1PrivateKey(it, null as ByteArray?) }.toByteArray()
            BoundedSshKey.validate(bytes, byteArrayOf())
        } finally { key.dispose() }
    }
}
