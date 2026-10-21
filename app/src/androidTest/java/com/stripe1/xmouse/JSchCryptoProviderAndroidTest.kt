package com.stripe1.xmouse

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature as JcaSignature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

@RunWith(AndroidJUnit4::class)
class JSchCryptoProviderAndroidTest {
    @Test fun initializesModernAlgorithmsAndSignsWithoutChangingGlobalProviders() =
        JSchTestDiagnostics.withCapture { diagnostics ->
            val providersBefore = Security.getProviders().map { it.name to it.javaClass.name }
            val configBefore = JSch.getConfig()
            val jsch = JSch()

            assertEquals("2.28.8", JSch.VERSION)
            val hostKeys = JSch.getConfig("server_host_key")
            assertTrue(hostKeys.contains("rsa-sha2-256"))
            assertTrue(hostKeys.contains("rsa-sha2-512"))
            assertTrue(hostKeys.contains("ecdsa-sha2-nistp256"))
            assertTrue(hostKeys.contains("ssh-ed25519"))
            assertTrue(!hostKeys.split(',').contains("ssh-rsa"))
            assertTrue(JSch.getConfig("PubkeyAcceptedAlgorithms").contains("rsa-sha2-256"))
            assertEquals("com.jcraft.jsch.UserAuthPassword", JSch.getConfig("userauth.password"))
            assertNotNull(jsch.getSession("NOT_REAL_user", "127.0.0.1", 22))

            val message = "XMouse synthetic crypto initialization".toByteArray(Charsets.US_ASCII)
            val rsa = KeyPair.genKeyPair(jsch, KeyPair.RSA, 1024)
            try {
                assertCanSignAndVerify(rsa, message, diagnostics, algorithm = "rsa-sha2-256")
                assertCanSignAndVerify(rsa, message, diagnostics, algorithm = "rsa-sha2-512")
            } finally {
                rsa.dispose()
            }

            val jcaProbe = "Default JCA providers:\n${probeDefaultEcdsaJcaStages()}\n" +
                "BC AlgorithmParameters with platform KeyFactory/Signature:\n${probeBcParametersWithPlatformEcdsaServices()}\n" +
                "Explicit Bouncy Castle provider instance (not registered globally):\n${probeBouncyCastleJcaStages()}"
            android.util.Log.i("XMouseJSchTest", "Synthetic default-JCA ECDSA stage probe:\n$jcaProbe")
            assertEquals(providersBefore, Security.getProviders().map { it.name to it.javaClass.name })
            assertEquals(configBefore, JSch.getConfig())
            val ecdsa = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
            try {
                assertCanSignAndVerify(ecdsa, message, diagnostics, jcaProbe)
            } finally {
                ecdsa.dispose()
            }

            // JSch's Java 8 path uses Bouncy Castle's lightweight API for Ed25519 on Android.
            val ed25519 = KeyPair.genKeyPair(jsch, KeyPair.ED25519, 256)
            try {
                assertCanSignAndVerify(ed25519, message, diagnostics)
            } finally {
                ed25519.dispose()
            }

            assertEquals(providersBefore, Security.getProviders().map { it.name to it.javaClass.name })
            assertEquals(configBefore, JSch.getConfig())
        }

    private fun assertCanSignAndVerify(
        key: KeyPair,
        message: ByteArray,
        diagnostics: JSchTestDiagnostics.Capture,
        jcaProbe: String = "",
        algorithm: String? = null,
    ) {
        val signature = if (algorithm == null) key.getSignature(message) else key.getSignature(message, algorithm)
        assertNotNull(
            "Signing failed for ${key.keyTypeString} / $algorithm\nJSch log:\n${diagnostics.renderSince()}\n" +
                "Default JCA ECDSA stage probe:\n$jcaProbe",
            signature,
        )
        val verifier: Signature? = if (algorithm == null) key.getVerifier() else key.getVerifier(algorithm)
        assertNotNull(
            "Verifier unavailable for ${key.keyTypeString} / $algorithm\nJSch log:\n${diagnostics.renderSince()}\n" +
                "Default JCA ECDSA stage probe:\n$jcaProbe",
            verifier,
        )
        verifier!!.update(message)
        assertTrue(
            "Signature rejected for ${key.keyTypeString} / $algorithm\nJSch log:\n${diagnostics.renderSince()}\n" +
                "Default JCA ECDSA stage probe:\n$jcaProbe",
            verifier.verify(signature),
        )
        signature.fill(0)
    }

    /** Replays the JSch ECDSA default-JCA stages using a fresh synthetic EC key. */
    private fun probeDefaultEcdsaJcaStages(): String {
        val outcomes = mutableListOf<String>()
        fun <T> stage(name: String, action: () -> T): T? = try {
            action().also { outcomes += "$name: OK" }
        } catch (failure: Exception) {
            outcomes += "$name: FAILED (${failure.javaClass.name}: ${failure.message})\n${failure.stackTraceToString()}"
            null
        } catch (failure: LinkageError) {
            outcomes += "$name: FAILED (${failure.javaClass.name}: ${failure.message})\n${failure.stackTraceToString()}"
            null
        }

        outcomes += "Registered AlgorithmParameters.EC services=" +
            Security.getProviders().mapNotNull { provider ->
                provider.getService("AlgorithmParameters", "EC")?.let { "${provider.name}/${provider.javaClass.name}" }
            }
        val signature = stage("Signature.getInstance(SHA256withECDSA)") {
            JcaSignature.getInstance("SHA256withECDSA").also {
                outcomes += "Signature provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")
        val keyFactory = stage("KeyFactory.getInstance(EC)") {
            KeyFactory.getInstance("EC").also {
                outcomes += "KeyFactory provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")

        val generator = stage("KeyPairGenerator.getInstance(EC)") {
            KeyPairGenerator.getInstance("EC").also {
                outcomes += "KeyPairGenerator provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")
        if (stage("KeyPairGenerator.initialize(secp256r1)") { generator.initialize(ECGenParameterSpec("secp256r1")) } == null) {
            return outcomes.joinToString("\n")
        }
        val pair = stage("KeyPairGenerator.generateKeyPair") { generator.generateKeyPair() }
            ?: return outcomes.joinToString("\n")

        val parameters = stage("AlgorithmParameters.getInstance(EC)") {
            AlgorithmParameters.getInstance("EC").also {
                outcomes += "AlgorithmParameters provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")
        if (stage("AlgorithmParameters.init(secp256r1)") { parameters.init(ECGenParameterSpec("secp256r1")) } == null) {
            return outcomes.joinToString("\n")
        }
        val ecParameters = stage("AlgorithmParameters.getParameterSpec(EC)") {
            parameters.getParameterSpec(ECParameterSpec::class.java)
        } ?: return outcomes.joinToString("\n")

        val privateKey = stage("KeyFactory.generatePrivate(ECPrivateKeySpec)") {
            val scalar = (pair.private as ECPrivateKey).s
            keyFactory.generatePrivate(ECPrivateKeySpec(scalar, ecParameters))
        } ?: return outcomes.joinToString("\n")
        val publicKey = stage("KeyFactory.generatePublic(ECPublicKeySpec)") {
            val public = pair.public as ECPublicKey
            keyFactory.generatePublic(ECPublicKeySpec(public.w, ecParameters))
        } ?: return outcomes.joinToString("\n")

        if (stage("Signature.initSign(EC private key)") { signature.initSign(privateKey) } == null) {
            return outcomes.joinToString("\n")
        }
        val message = "XMouse synthetic ECDSA JCA stage probe".toByteArray(Charsets.US_ASCII)
        val encodedSignature = stage("Signature.update/sign") {
            signature.update(message)
            signature.sign()
        } ?: return outcomes.joinToString("\n")
        try {
            val verifier = stage("Signature.getInstance(SHA256withECDSA) for verify") {
                JcaSignature.getInstance("SHA256withECDSA")
            } ?: return outcomes.joinToString("\n")
            if (stage("Signature.initVerify(EC public key)") { verifier.initVerify(publicKey) } == null) {
                return outcomes.joinToString("\n")
            }
            val verification = stage("Signature.verify") {
                verifier.update(message)
                verifier.verify(encodedSignature)
            } ?: return outcomes.joinToString("\n")
            outcomes += "Signature.verify returned $verification"
        } finally {
            encodedSignature.fill(0)
        }
        return outcomes.joinToString("\n")
    }

    /** Verifies whether the bundled maintained BC provider itself offers the missing JCA stages. */
    private fun probeBouncyCastleJcaStages(): String {
        val outcomes = mutableListOf<String>()
        fun <T> stage(name: String, action: () -> T): T? = try {
            action().also { outcomes += "$name: OK" }
        } catch (failure: Exception) {
            outcomes += "$name: FAILED (${failure.javaClass.name}: ${failure.message})\n${failure.stackTraceToString()}"
            null
        } catch (failure: LinkageError) {
            outcomes += "$name: FAILED (${failure.javaClass.name}: ${failure.message})\n${failure.stackTraceToString()}"
            null
        }

        val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        outcomes += "Provider instance=${provider.name}/${provider.javaClass.name}; registered=${Security.getProvider(provider.name) === provider}"
        val parameters = stage("BC AlgorithmParameters.getInstance(EC)") {
            AlgorithmParameters.getInstance("EC", provider)
        } ?: return outcomes.joinToString("\n")
        if (stage("BC AlgorithmParameters.init(secp256r1)") { parameters.init(ECGenParameterSpec("secp256r1")) } == null) {
            return outcomes.joinToString("\n")
        }
        val ecParameters = stage("BC AlgorithmParameters.getParameterSpec(EC)") {
            parameters.getParameterSpec(ECParameterSpec::class.java)
        } ?: return outcomes.joinToString("\n")
        val keyFactory = stage("BC KeyFactory.getInstance(EC)") { KeyFactory.getInstance("EC", provider) }
            ?: return outcomes.joinToString("\n")
        val generator = stage("BC KeyPairGenerator.getInstance(EC)") {
            KeyPairGenerator.getInstance("EC", provider)
        } ?: return outcomes.joinToString("\n")
        if (stage("BC KeyPairGenerator.initialize(secp256r1)") { generator.initialize(ECGenParameterSpec("secp256r1")) } == null) {
            return outcomes.joinToString("\n")
        }
        val pair = stage("BC KeyPairGenerator.generateKeyPair") { generator.generateKeyPair() }
            ?: return outcomes.joinToString("\n")
        val privateKey = stage("BC KeyFactory.generatePrivate(ECPrivateKeySpec)") {
            keyFactory.generatePrivate(ECPrivateKeySpec((pair.private as ECPrivateKey).s, ecParameters))
        } ?: return outcomes.joinToString("\n")
        val publicKey = stage("BC KeyFactory.generatePublic(ECPublicKeySpec)") {
            keyFactory.generatePublic(ECPublicKeySpec((pair.public as ECPublicKey).w, ecParameters))
        } ?: return outcomes.joinToString("\n")
        val signer = stage("BC Signature.getInstance(SHA256withECDSA)") {
            JcaSignature.getInstance("SHA256withECDSA", provider)
        } ?: return outcomes.joinToString("\n")
        if (stage("BC Signature.initSign(EC private key)") { signer.initSign(privateKey) } == null) {
            return outcomes.joinToString("\n")
        }
        val message = "XMouse synthetic BC-provider diagnostic".toByteArray(Charsets.US_ASCII)
        val encodedSignature = stage("BC Signature.update/sign") {
            signer.update(message)
            signer.sign()
        } ?: return outcomes.joinToString("\n")
        try {
            val verifier = stage("BC Signature.getInstance(SHA256withECDSA) for verify") {
                JcaSignature.getInstance("SHA256withECDSA", provider)
            } ?: return outcomes.joinToString("\n")
            if (stage("BC Signature.initVerify(EC public key)") { verifier.initVerify(publicKey) } == null) {
                return outcomes.joinToString("\n")
            }
            val verified = stage("BC Signature.verify") {
                verifier.update(message)
                verifier.verify(encodedSignature)
            } ?: return outcomes.joinToString("\n")
            outcomes += "BC Signature.verify returned $verified"
        } finally {
            encodedSignature.fill(0)
        }
        return outcomes.joinToString("\n")
    }

    /** Tests the smallest provider-routing change without registering a provider or changing JSch. */
    private fun probeBcParametersWithPlatformEcdsaServices(): String {
        val outcomes = mutableListOf<String>()
        fun <T> stage(name: String, action: () -> T): T? = try {
            action().also { outcomes += "$name: OK" }
        } catch (failure: Exception) {
            outcomes += "$name: FAILED (${failure.javaClass.name}: ${failure.message})\n${failure.stackTraceToString()}"
            null
        } catch (failure: LinkageError) {
            outcomes += "$name: FAILED (${failure.javaClass.name}: ${failure.message})\n${failure.stackTraceToString()}"
            null
        }

        val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val parameters = stage("BC AlgorithmParameters.getInstance(EC)") {
            AlgorithmParameters.getInstance("EC", provider)
        } ?: return outcomes.joinToString("\n")
        if (stage("BC AlgorithmParameters.init(secp256r1)") { parameters.init(ECGenParameterSpec("secp256r1")) } == null) {
            return outcomes.joinToString("\n")
        }
        val ecParameters = stage("BC AlgorithmParameters.getParameterSpec(EC)") {
            parameters.getParameterSpec(ECParameterSpec::class.java)
        } ?: return outcomes.joinToString("\n")

        val generator = stage("platform KeyPairGenerator.getInstance(EC)") {
            KeyPairGenerator.getInstance("EC").also {
                outcomes += "KeyPairGenerator provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")
        if (stage("platform KeyPairGenerator.initialize(secp256r1)") { generator.initialize(ECGenParameterSpec("secp256r1")) } == null) {
            return outcomes.joinToString("\n")
        }
        val pair = stage("platform KeyPairGenerator.generateKeyPair") { generator.generateKeyPair() }
            ?: return outcomes.joinToString("\n")
        val keyFactory = stage("platform KeyFactory.getInstance(EC)") {
            KeyFactory.getInstance("EC").also {
                outcomes += "KeyFactory provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")
        val privateKey = stage("platform KeyFactory.generatePrivate(ECPrivateKeySpec)") {
            keyFactory.generatePrivate(ECPrivateKeySpec((pair.private as ECPrivateKey).s, ecParameters))
        } ?: return outcomes.joinToString("\n")
        val publicKey = stage("platform KeyFactory.generatePublic(ECPublicKeySpec)") {
            keyFactory.generatePublic(ECPublicKeySpec((pair.public as ECPublicKey).w, ecParameters))
        } ?: return outcomes.joinToString("\n")
        val signer = stage("platform Signature.getInstance(SHA256withECDSA)") {
            JcaSignature.getInstance("SHA256withECDSA").also {
                outcomes += "Signature provider=${it.provider.name}/${it.provider.javaClass.name}"
            }
        } ?: return outcomes.joinToString("\n")
        if (stage("platform Signature.initSign(EC private key)") { signer.initSign(privateKey) } == null) {
            return outcomes.joinToString("\n")
        }
        val message = "XMouse synthetic mixed-provider diagnostic".toByteArray(Charsets.US_ASCII)
        val encodedSignature = stage("platform Signature.update/sign") {
            signer.update(message)
            signer.sign()
        } ?: return outcomes.joinToString("\n")
        try {
            val verifier = stage("platform Signature.getInstance(SHA256withECDSA) for verify") {
                JcaSignature.getInstance("SHA256withECDSA")
            } ?: return outcomes.joinToString("\n")
            if (stage("platform Signature.initVerify(EC public key)") { verifier.initVerify(publicKey) } == null) {
                return outcomes.joinToString("\n")
            }
            val verified = stage("platform Signature.verify") {
                verifier.update(message)
                verifier.verify(encodedSignature)
            } ?: return outcomes.joinToString("\n")
            outcomes += "platform Signature.verify returned $verified"
        } finally {
            encodedSignature.fill(0)
        }
        return outcomes.joinToString("\n")
    }
}
