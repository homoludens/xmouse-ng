package com.stripe1.xmouse.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** Runs only against isolated fake helper binaries; the host desktop clipboard is never accessed. */
class LinuxClipboardHelperTest {
    private data class RunResult(val exitCode: Int, val root: Path, val processOutput: String)

    @Test fun xclipReceivesExactStdinUsesClipboardSelectionAndDefaultsDisplayWithoutShellEvaluation() {
        val payload = "Unicode ☃ 😀\nquotes ' \" ` and literal \$(touch marker)  \n"
        val run = runWithTools(payload, tools = setOf("xclip"))
        val marker = run.root.resolve("marker")
        try {
            assertEquals(0, run.exitCode)
            assertEquals(listOf("-selection", "clipboard", "-in"),
                Files.readAllLines(run.root.resolve("args")))
            assertEquals(":0", String(Files.readAllBytes(run.root.resolve("display")), Charsets.UTF_8))
            assertArrayEquals(payload.toByteArray(Charsets.UTF_8), Files.readAllBytes(run.root.resolve("stdin")))
            assertFalse("clipboard content must be data, never shell code", Files.exists(marker))
            assertFalse(run.processOutput.contains("touch marker"))
        } finally {
            deleteTestTree(run.root)
        }
    }

    @Test fun xselIsUsedWhenXclipIsMissing() {
        val payload = "xsel fallback\n  trailing spaces  "
        val run = runWithTools(payload, tools = setOf("xsel"))
        try {
            assertEquals(0, run.exitCode)
            assertEquals(listOf("--clipboard", "--input"), Files.readAllLines(run.root.resolve("args")))
            assertArrayEquals(payload.toByteArray(Charsets.UTF_8), Files.readAllBytes(run.root.resolve("stdin")))
            assertFalse(Files.exists(run.root.resolve("xclip-ran")))
        } finally {
            deleteTestTree(run.root)
        }
    }

    @Test fun oneCurrentUserWaylandSocketPrefersWlCopyAndDoesNotTryXclip() {
        val root = Files.createTempDirectory("xmouse-clipboard-wayland-")
        val runtime = Files.createDirectory(root.resolve("runtime"))
        val socketPath = runtime.resolve("wayland-test")
        val socket = bindUnixSocket(socketPath)
        try {
            val run = runWithTools("wayland text", tools = setOf("wl-copy", "xclip"), root = root,
                runtime = runtime)
            assertEquals(0, run.exitCode)
            assertTrue(Files.exists(root.resolve("wl-copy-ran")))
            assertEquals("wayland-test", String(Files.readAllBytes(root.resolve("wayland")), Charsets.UTF_8))
            assertArrayEquals("wayland text".toByteArray(), Files.readAllBytes(root.resolve("stdin")))
            assertFalse(Files.exists(root.resolve("xclip-ran")))
        } finally {
            socket.close()
            if (Files.exists(root)) deleteTestTree(root)
        }
    }

    @Test fun autodetectionIgnoresNormalWaylandLockFileAndSelectsTheSingleOwnedSocket() {
        val root = Files.createTempDirectory("xmouse-clipboard-wayland-lock-")
        val runtime = Files.createDirectory(root.resolve("runtime"))
        val socketPath = runtime.resolve("wayland-0")
        val socket = bindUnixSocket(socketPath)
        Files.createFile(runtime.resolve("wayland-0.lock"))
        try {
            val run = runWithTools("wayland text", tools = setOf("wl-copy", "xclip"), root = root,
                runtime = runtime)
            assertEquals(0, run.exitCode)
            assertTrue(Files.exists(root.resolve("wl-copy-ran")))
            assertEquals("wayland-0", String(Files.readAllBytes(root.resolve("wayland")), Charsets.UTF_8))
            assertArrayEquals("wayland text".toByteArray(), Files.readAllBytes(root.resolve("stdin")))
            assertFalse(Files.exists(root.resolve("xclip-ran")))
        } finally {
            socket.close()
            if (Files.exists(root)) deleteTestTree(root)
        }
    }

    @Test fun absoluteWaylandSocketOwnedByAnotherUserFallsBackToXclip() {
        val root = Files.createTempDirectory("xmouse-clipboard-foreign-wayland-")
        val runtime = Files.createDirectory(root.resolve("runtime"))
        val socketPath = runtime.resolve("wayland-foreign")
        val socket = bindUnixSocket(socketPath)
        try {
            val run = runWithTools("do not send to another user's socket", tools = setOf("wl-copy", "xclip"),
                root = root, runtime = runtime, waylandDisplay = socketPath.toString(),
                foreignSocketOwner = socketPath)
            assertEquals(0, run.exitCode)
            assertFalse("wl-copy must not connect to a socket not owned by the SSH user",
                Files.exists(root.resolve("wl-copy-ran")))
            assertTrue("the X11 fallback should be attempted", Files.exists(root.resolve("xclip-ran")))
            assertArrayEquals("do not send to another user's socket".toByteArray(),
                Files.readAllBytes(root.resolve("stdin")))
        } finally {
            socket.close()
            if (Files.exists(root)) deleteTestTree(root)
        }
    }

    @Test fun multipleWaylandSocketsAreNotGuessed() {
        val root = Files.createTempDirectory("xmouse-clipboard-ambiguous-wayland-")
        val runtime = Files.createDirectory(root.resolve("runtime"))
        val sockets = listOf(
            bindUnixSocket(runtime.resolve("wayland-0")),
            bindUnixSocket(runtime.resolve("wayland-1")),
        )
        try {
            val run = runWithTools("do not guess", tools = setOf("wl-copy"), root = root, runtime = runtime)
            assertEquals(71, run.exitCode)
            assertEquals(ClipboardTransferResult.DESKTOP_UNAVAILABLE,
                LinuxClipboardHelper.resultForExitStatus(run.exitCode))
            assertFalse(Files.exists(root.resolve("wl-copy-ran")))
        } finally {
            sockets.forEach(AutoCloseable::close)
            if (Files.exists(root)) deleteTestTree(root)
        }
    }

    @Test fun missingHelpersAndFailedDisplayReturnTypedExitCodesAfterConsumingInput() {
        val payload = "drain me"
        val missing = runWithTools(payload, tools = emptySet())
        try {
            assertEquals(70, missing.exitCode)
            assertEquals(ClipboardTransferResult.MISSING_HELPER,
                LinuxClipboardHelper.resultForExitStatus(missing.exitCode))
        } finally {
            deleteTestTree(missing.root)
        }

        val failed = runWithTools(payload, tools = setOf("xclip"), helperExit = 9)
        try {
            assertEquals(71, failed.exitCode)
            assertEquals(ClipboardTransferResult.DESKTOP_UNAVAILABLE,
                LinuxClipboardHelper.resultForExitStatus(failed.exitCode))
            assertArrayEquals(payload.toByteArray(), Files.readAllBytes(failed.root.resolve("stdin")))
        } finally {
            deleteTestTree(failed.root)
        }
        assertEquals(ClipboardTransferResult.FAILED, LinuxClipboardHelper.resultForExitStatus(72))
    }

    private fun runWithTools(
        payload: String,
        tools: Set<String>,
        root: Path = Files.createTempDirectory("xmouse-clipboard-helper-"),
        runtime: Path = Files.createDirectory(root.resolve("runtime")),
        waylandDisplay: String? = null,
        foreignSocketOwner: Path? = null,
        helperExit: Int = 0,
    ): RunResult {
        val bin = Files.createDirectory(root.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("cat"), Paths.get("/bin/cat"))
        Files.createSymbolicLink(bin.resolve("id"), Paths.get("/usr/bin/id"))
        if (foreignSocketOwner == null) {
            Files.createSymbolicLink(bin.resolve("stat"), Paths.get("/usr/bin/stat"))
        } else {
            writeExecutable(bin.resolve("stat"), """
                #!/bin/sh
                if [ "${'$'}3" = "${'$'}FOREIGN_SOCKET" ]; then
                    printf '%s\n' 999999
                else
                    exec /usr/bin/stat "${'$'}@"
                fi
            """.trimIndent())
        }
        tools.forEach { name ->
            writeExecutable(bin.resolve(name), fakeTool(name))
        }
        val process = ProcessBuilder("/bin/sh", "-c", LinuxClipboardHelper.COMMAND).apply {
            val env = environment()
            env.clear()
            env["PATH"] = bin.toString()
            env["XDG_RUNTIME_DIR"] = runtime.toString()
            env["CAPTURE_ARGS"] = root.resolve("args").toString()
            env["CAPTURE_DISPLAY"] = root.resolve("display").toString()
            env["CAPTURE_STDIN"] = root.resolve("stdin").toString()
            env["CAPTURE_WAYLAND"] = root.resolve("wayland").toString()
            env["CAPTURE_TOOL"] = root.toString()
            env["FAKE_EXIT"] = helperExit.toString()
            if (waylandDisplay != null) env["WAYLAND_DISPLAY"] = waylandDisplay
            if (foreignSocketOwner != null) env["FOREIGN_SOCKET"] = foreignSocketOwner.toString()
        }.start()
        process.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        assertTrue("fixed helper shell timed out", process.waitFor(5, TimeUnit.SECONDS))
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8) +
            process.errorStream.readBytes().toString(Charsets.UTF_8)
        return RunResult(process.exitValue(), root, output)
    }

    private fun fakeTool(name: String): String = """
        #!/bin/sh
        printf '%s\n' "${'$'}@" > "${'$'}CAPTURE_ARGS"
        printf '%s' "${'$'}{DISPLAY-}" > "${'$'}CAPTURE_DISPLAY"
        printf '%s' "${'$'}{WAYLAND_DISPLAY-}" > "${'$'}CAPTURE_WAYLAND"
        printf '%s' "$name" > "${'$'}CAPTURE_TOOL/$name-ran"
        cat > "${'$'}CAPTURE_STDIN"
        exit "${'$'}FAKE_EXIT"
    """.trimIndent()

    /** JDK 21 exposes Unix sockets at runtime; reflection avoids requiring host-JDK APIs in android.jar. */
    private fun bindUnixSocket(path: Path): AutoCloseable {
        val protocolFamilyClass = Class.forName("java.net.ProtocolFamily")
        val unixFamily = Class.forName("java.net.StandardProtocolFamily").getField("UNIX").get(null)
        val channelClass = Class.forName("java.nio.channels.ServerSocketChannel")
        val channel = channelClass.getMethod("open", protocolFamilyClass).invoke(null, unixFamily)
        val addressClass = Class.forName("java.net.UnixDomainSocketAddress")
        val address = addressClass.getMethod("of", Path::class.java).invoke(null, path)
        val socketAddressClass = Class.forName("java.net.SocketAddress")
        channelClass.getMethod("bind", socketAddressClass).invoke(channel, address)
        return AutoCloseable { channelClass.getMethod("close").invoke(channel) }
    }

    private fun writeExecutable(path: Path, text: String) {
        Files.write(path, text.toByteArray(Charsets.UTF_8))
        Files.setPosixFilePermissions(path, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        ))
    }

    private fun deleteTestTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
