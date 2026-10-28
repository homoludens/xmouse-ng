package net.droopia.xmouse_ng.secrets

/** Structural preflight only, not a cryptographic parser. DER uses a fixed stack; SSH copies are bounded. */
internal object KeyInputBounds {
    const val MAX_BINARY_BYTES = 16 * 1024
    const val MAX_INTEGER_BYTES = 2049 // Up to 16384-bit RSA, including DER sign octet.
    const val MAX_PASSPHRASE_BYTES = 1024
    const val MAX_BCRYPT_ROUNDS = 64
    const val MAX_PBKDF2_ROUNDS = 600_000

    /** Strict definite DER, <=16 levels, <=128 nodes. BC interprets only preflighted structures. */
    fun der(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BINARY_BYTES)
        val ends = IntArray(16)
        var depth = 0
        var end = bytes.size
        var pos = 0
        var nodes = 0
        while (pos < end || depth > 0) {
            if (pos == end) { end = ends[--depth]; continue }
            require(++nodes <= 128 && pos + 2 <= end)
            val tag = bytes[pos++].toInt() and 255
            require(tag and 31 != 31 && tag != 0) // No high tags, EOC or indefinite BER.
            var length = bytes[pos++].toInt() and 255
            if (length >= 128) {
                val count = length and 127
                require(count in 1..4 && count <= end - pos && bytes[pos] != 0.toByte())
                var value = 0L
                repeat(count) { value = (value shl 8) or (bytes[pos++].toLong() and 255) }
                require(value in 128..(end - pos).toLong())
                length = value.toInt()
            }
            require(length <= end - pos)
            if (tag == 2) require(length in 1..MAX_INTEGER_BYTES)
            if (tag and 32 != 0) {
                require(depth < ends.size)
                ends[depth++] = end
                end = pos + length
            } else pos += length
        }
        require(pos == bytes.size)
    }

    /** OpenSSH's outer strings/KDF parameters are checked before the maintained BC decryptor runs. */
    fun openSsh(bytes: ByteArray): Boolean {
        require(bytes.size <= MAX_BINARY_BYTES)
        val cursor = SshCursor(bytes)
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        require(bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] })
        cursor.pos = magic.size
        val cipher = cursor.string(64).toString(Charsets.US_ASCII)
        val kdf = cursor.string(16).toString(Charsets.US_ASCII)
        val options = cursor.string(128)
        val encrypted = cipher != "none"
        if (encrypted) {
            require(cipher in setOf("aes128-ctr", "aes192-ctr", "aes256-ctr", "aes128-cbc", "aes192-cbc",
                "aes256-cbc", "3des-cbc", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com",
                "chacha20-poly1305@openssh.com"))
            require(kdf == "bcrypt")
            val parameters = SshCursor(options)
            require(parameters.string(64).size in 16..64)
            if (parameters.u32() !in 1..MAX_BCRYPT_ROUNDS.toLong()) keyImportFail(PrivateKeyImportException.Reason.RESOURCE_LIMIT)
            require(parameters.pos == options.size)
        } else require(kdf == "none" && options.isEmpty())
        require(cursor.u32() == 1L)
        val publicKey = SshCursor(cursor.string(4096))
        val type = publicKey.string(64).toString(Charsets.US_ASCII)
        require(type in setOf("ssh-rsa", "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"))
        cursor.string(MAX_BINARY_BYTES)
        val tagBytes = if (cipher.contains("gcm@") || cipher == "chacha20-poly1305@openssh.com") 16 else 0
        require(cursor.pos + tagBytes == bytes.size)
        return encrypted
    }

    private class SshCursor(private val bytes: ByteArray) {
        var pos = 0
        fun u32(): Long {
            require(bytes.size - pos >= 4)
            var value = 0L
            repeat(4) { value = (value shl 8) or (bytes[pos++].toLong() and 255) }
            return value
        }
        fun string(max: Int): ByteArray {
            val length = u32()
            require(length <= max && length <= bytes.size - pos)
            val start = pos
            pos += length.toInt()
            return bytes.copyOfRange(start, pos)
        }
    }
}
