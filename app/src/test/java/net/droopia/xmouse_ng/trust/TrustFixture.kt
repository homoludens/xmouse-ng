package net.droopia.xmouse_ng.trust

import net.droopia.xmouse_ng.secrets.SecretFileIo
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal open class TrustFileIo : SecretFileIo {
    override fun privateDirectory(directory: File) { }
    override fun privateFile(file: File) { }
    override fun sync(output: FileOutputStream) = output.fd.sync()
    override fun replace(source: File, destination: File) { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    override fun syncDirectory(directory: File) { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
}

internal fun syntheticHostKey(seed: Int = 1): ApprovedHostKey = ApprovedHostKey.parse(
    org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil.encodePublicKey(
        org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ByteArray(32) { (it + seed).toByte() }, 0).generatePublicKey(),
    ),
)
