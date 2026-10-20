package com.stripe1.xmouse.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class EndpointHostTest {
    @Test
    fun dnsNormalizationPreservesDisplayInputAndRootedLookup() {
        val host = EndpointHost("  DESKTOP.Local.  ")
        assertEquals("  DESKTOP.Local.  ", host.original)
        assertEquals("desktop.local", host.normalized)
        assertEquals("desktop.local.", host.lookupHost)
        assertEquals(EndpointHost.Kind.DNS, host.kind)
    }

    @Test
    fun ordinarySingleLabelAndLocalNamesNeedNoServiceAdvertisement() {
        for (name in listOf("desktop", "desktop.example", "desktop.local")) {
            assertEquals(name, EndpointHost(name).normalized)
            assertEquals(EndpointHost.Kind.DNS, EndpointHost(name).kind)
        }
    }

    @Test
    fun dnsCaseNormalizationDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("linux.invalid", EndpointHost("LINUX.INVALID").normalized)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun internationalizedDnsUsesAsciiLookupWithoutChangingDisplay() {
        val host = EndpointHost("bücher.example.")
        assertEquals("bücher.example.", host.original)
        assertEquals("xn--bcher-kva.example", host.normalized)
        assertEquals("xn--bcher-kva.example.", host.lookupHost)
    }

    @Test
    fun ipv4UsesDecimalOctetsAndNoLookup() {
        for (literal in listOf("0.0.0.0", "192.0.2.10", "255.255.255.255")) {
            val host = EndpointHost(literal)
            assertEquals(literal, host.normalized)
            assertEquals(literal, host.lookupHost)
            assertEquals(EndpointHost.Kind.IPV4, host.kind)
        }
    }

    @Test
    fun ipv4SurroundingSpacesAndRootDotDoNotChangeLiteralIdentity() {
        assertEquals("192.0.2.1", EndpointHost(" 192.0.2.1. ").normalized)
    }

    @Test
    fun blankHostIsRejected() {
        reject("", " ", "\t", "\n")
    }

    @Test
    fun hostFieldRejectsUrisCredentialsPortsWhitespaceAndInvalidLabels() {
        reject(
            "ssh://desktop.local", "user@desktop.local", "user:password@desktop.local",
            "desktop.local:22", "[desktop.local]", "desktop local", "desktop\n.local",
            "desktop.local\n", ".", ".local", "desktop..local", "desktop.local..",
            "-desktop.local", "desktop-.local", "desktop_local", "desktop/local", "desktop?query",
        )
    }

    @Test
    fun dnsLengthLimitsAreValidatedWithoutResolution() {
        assertEquals("a".repeat(63) + ".local", EndpointHost("a".repeat(63) + ".local").normalized)
        reject("a".repeat(64) + ".local", List(4) { "a".repeat(63) }.joinToString("."))
    }

    @Test
    fun invalidOrAmbiguousNumericAddressesAreNotAcceptedAsDnsNames() {
        reject("256.0.0.1", "192.0.2.-1", "192.0.2", "127.1", "2130706433", "192.0.2.1.5", "01.2.3.4")
    }

    @Test
    fun bracketedCompressedAndExpandedIpv6ShareNormalization() {
        val variants = listOf("2001:DB8::1", "2001:0db8:0000:0:0:0:0:0001", "[2001:db8::1]")
        for (input in variants) {
            val host = EndpointHost(input)
            assertEquals(input, host.original)
            assertEquals("2001:db8:0:0:0:0:0:1", host.normalized)
            assertEquals(host.normalized, host.lookupHost)
            assertEquals(EndpointHost.Kind.IPV6, host.kind)
        }
    }

    @Test
    fun ipv6CompressionWorksAtEitherEndAndForAllZeroes() {
        assertEquals("0:0:0:0:0:0:0:0", EndpointHost("::").normalized)
        assertEquals("0:0:0:0:0:0:0:1", EndpointHost("::1").normalized)
        assertEquals("fe80:0:0:0:0:0:0:0", EndpointHost("fe80::").normalized)
        assertEquals("1:2:3:4:5:6:7:8", EndpointHost("1:2:3:4:5:6:7:8").normalized)
    }

    @Test
    fun embeddedIpv4AndHexOnlyIpv6ShareNormalization() {
        assertEquals(EndpointHost("::ffff:c000:201").normalized, EndpointHost("::ffff:192.0.2.1").normalized)
        assertEquals("2001:db8:0:0:0:0:c000:201", EndpointHost("2001:db8::192.0.2.1").normalized)
    }

    @Test
    fun scopedIpv6RetainsCaseSensitiveInterfaceNameWithoutInterfaceLookup() {
        val host = EndpointHost("[FE80::1%NoSuchInterface]")
        assertEquals("fe80:0:0:0:0:0:0:1%NoSuchInterface", host.normalized)
        assertEquals(host.normalized, host.lookupHost)
    }

    @Test
    fun numericIpv6ScopeIsNormalizedAsDecimal() {
        assertEquals("fe80:0:0:0:0:0:0:1%3", EndpointHost("fe80::1%003").normalized)
    }

    @Test
    fun invalidScopesAreRejected() {
        reject("fe80::1%", "fe80::1%eth0%2", "fe80::1%eth/0", "fe80::1%2147483648", "192.0.2.1%eth0")
    }

    @Test
    fun malformedIpv6AndEmbeddedIpv4AreRejected() {
        reject(
            ":", ":::1", "2001::db8::1", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7:8::", "12345::1", "2001:db8::g", "[::1", "::1]", "[::1]:22",
            "::ffff:192.0.2.256", "::ffff:192.0.2", "192.0.2.1::", "::ffff:019.0.2.1",
        )
    }

    @Test
    fun nonexistentDnsNameRemainsALocatorRatherThanBeingResolvedAtConstruction() {
        assertEquals("no-such-host.invalid", EndpointHost("No-Such-Host.Invalid.").normalized)
    }

    @Test
    fun normalizationIsIdempotentForAllHostKinds() {
        for (input in listOf("Desktop.Local.", "192.0.2.1", "[2001:DB8::1]", "::ffff:192.0.2.1", "fe80::1%03")) {
            val normalized = EndpointHost(input).normalized
            assertEquals(normalized, EndpointHost(normalized).normalized)
        }
    }

    private fun reject(vararg inputs: String) {
        for (input in inputs) {
            assertThrows("Invalid host: $input", IllegalArgumentException::class.java) { EndpointHost(input) }
        }
    }
}
