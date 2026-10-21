package com.stripe1.xmouse.transport;

import com.stripe1.xmouse.CommandTransport;
import com.stripe1.xmouse.secrets.AuthenticationMaterial;
import com.stripe1.xmouse.secrets.PrivateKeyImportException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UI-independent asynchronous boundary around blocking SSH. Owns its dedicated SERIAL worker and all
 * network resources. Accepted-operation callbacks run on that worker (disposed submissions reject
 * synchronously); presentation must use a detachable dispatcher.
 * No caller waits for connect, writes/flush, exec, SCP, or close. No input is replayed across a loss.
 * Comprehensive deadlines, bounded output, keepalives and failure classification remain task 4.6.
 */
public final class SshTransport implements CommandTransport, AutoCloseable {
    public enum Operation { CONNECT, WRITE, EXEC, SCP, DISCONNECT }
    public enum Failure { CONNECTION, PRIVATE_KEY, IO, CLOSED }
    public interface Callback<T> {
        void success(T value);
        void failure(Failure failure);
    }

    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int value) { }
        @Override public void write(byte[] bytes, int offset, int length) { }
    };
    private final SshBackend backend;
    private final ExecutorService worker;
    private final Object submission = new Object();
    private volatile boolean ready;
    private volatile boolean connecting;
    private boolean disposed;
    private volatile long generation;
    // Accessed on the worker only, never exposed to a caller/UI.
    private SshBackend.Session session;
    private SshBackend.Channel shell;
    private OutputStream shellInput;

    public static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(action -> new Thread(action, "xmouse-ssh-io"));
    }

    /** Ownership of the dedicated serial executor transfers here; never supply an inline/main executor. */
    public SshTransport(SshBackend backend, ExecutorService worker) { this.backend = backend; this.worker = worker; }

    /** Snapshot only: no provider/network call occurs on the calling thread. */
    public boolean isConnected() { return ready; }
    public boolean isConnecting() { return connecting; }

    /** Takes ownership of authentication, including rejected/cancelled submissions. */
    public void connect(SshConnectionRequest request, AuthenticationMaterial authentication, Callback<Void> callback) {
        synchronized (submission) {
            if (disposed) { authentication.close(); callback.failure(Failure.CLOSED); return; }
            ready = false;
            long token = ++generation;
            connecting = true;
            worker.execute(() -> {
                Failure failure = null;
                try {
                    closeResources();
                    if (token != generation) return;
                    session = backend.create(request, authentication);
                    if (token != generation) return;
                    session.connect();
                    if (token != generation) return;
                    session.clearAuthentication();
                    shell = session.openChannel(request.shell.isEmpty() ? "shell" : "exec");
                    if (!request.shell.isEmpty()) shell.command(request.shell);
                    shell.output(DISCARD, DISCARD); // No unconsumed shell output pipe.
                    shellInput = shell.input();
                    shell.connect(3000); // Preserve the existing finite command-channel setup deadline.
                    if (token != generation) return;
                    if (request.initialCommand != null) write(request.initialCommand);
                    if (!session.isConnected() || shell.isClosed()) throw new IOException();
                    synchronized (submission) {
                        if (token == generation) ready = true;
                    }
                } catch (PrivateKeyImportException ignored) { failure = Failure.PRIVATE_KEY; }
                catch (Exception ignored) { failure = Failure.CONNECTION; }
                finally {
                    authentication.close();
                    if (failure != null || token != generation) closeResources();
                    if (token == generation) connecting = false;
                }
                if (token == generation) {
                    if (failure == null) callback.success(null); else callback.failure(failure);
                }
            });
        }
    }

    @Override public boolean executeShellCommand(String command) { return send(command, null); }

    /** True means accepted into this session's worker queue, NOT local write or remote completion. */
    public boolean send(String command, Callback<Void> callback) {
        if (command == null) return false;
        return submit(callback, () -> { write(command); return null; });
    }

    public boolean exec(String command, Callback<String> callback) {
        if (command == null || command.isEmpty()) return false;
        return submit(callback, () -> {
            SshBackend.Channel channel = session.openChannel("exec");
            try {
                channel.command(command);
                // JSch drains both streams concurrently, rather than blocking on stderr before stdout.
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                channel.output(stdout, stderr);
                channel.connect(0); // Legacy exec had no deadline; task 4.6 supplies comprehensive limits.
                while (!channel.isClosed()) {
                    if (!session.isConnected()) throw new IOException();
                    Thread.sleep(10);
                }
                return withoutLineSeparators(stderr) + withoutLineSeparators(stdout);
            } finally { channel.close(); }
        });
    }

    /** Retains the dormant legacy SCP receive operation without any Context/file-provider dependency. */
    public boolean downloadScp(String command, File destination, Callback<Void> callback) {
        return submit(callback, () -> {
            SshBackend.Channel channel = session.openChannel("exec");
            try {
                channel.command(command);
                channel.output(null, DISCARD);
                try (OutputStream input = channel.input(); InputStream output = channel.output()) {
                    channel.connect(0);
                    acknowledge(input);
                    if (output.read() != 'C') throw new IOException();
                    String header = readHeader(output);
                    int separator = header.indexOf(' ', 5);
                    if (!header.matches("[0-7]{4} [0-9]+ .+") || separator < 0) throw new IOException();
                    long remaining = Long.parseLong(header.substring(5, separator));
                    acknowledge(input);
                    try (OutputStream file = new FileOutputStream(destination)) {
                        byte[] buffer = new byte[8192];
                        while (remaining > 0) {
                            int count = output.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (count <= 0) throw new IOException();
                            file.write(buffer, 0, count);
                            remaining -= count;
                        }
                    }
                    if (output.read() != 0) throw new IOException();
                    acknowledge(input);
                }
                return null;
            } finally { channel.close(); }
        });
    }

    public void disconnect(Callback<Void> callback) {
        synchronized (submission) {
            ready = false;
            connecting = false;
            ++generation; // Invalidate queued interactions immediately; never send them to a later session.
            if (disposed) return;
            worker.execute(() -> {
                boolean clean = closeResources();
                if (callback != null) {
                    if (clean) callback.success(null); else callback.failure(Failure.IO);
                }
            });
        }
    }

    @Override public void close() {
        synchronized (submission) {
            if (disposed) return;
            disposed = true;
            ready = false;
            connecting = false;
            ++generation;
            worker.execute(this::closeResources);
            worker.shutdown(); // Queued cleanup still runs, never awaitTermination on the UI.
        }
    }

    private interface Work<T> { T run() throws Exception; }

    private <T> boolean submit(Callback<T> callback, Work<T> action) {
        synchronized (submission) {
            if (!ready || disposed) return false;
            long token = generation;
            worker.execute(() -> {
                if (token != generation || !ready) return;
                T result;
                try {
                    if (!session.isConnected() || shell.isClosed()) throw new IOException();
                    result = action.run();
                } catch (Exception ignored) {
                    boolean current;
                    synchronized (submission) {
                        current = token == generation;
                        if (current) { ready = false; ++generation; }
                    }
                    closeResources();
                    if (callback != null && current) callback.failure(Failure.IO);
                    return;
                }
                if (callback != null && token == generation) callback.success(result);
            });
            return true;
        }
    }

    private void write(String command) throws IOException {
        shellInput.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
        shellInput.flush();
    }

    /** Best effort, but attempt EVERY established resource even if an earlier close fails. */
    private boolean closeResources() {
        boolean clean = true;
        if (shellInput != null) { try { shellInput.close(); } catch (Exception ignored) { clean = false; } shellInput = null; }
        if (shell != null) { try { shell.close(); } catch (Exception ignored) { clean = false; } shell = null; }
        if (session != null) { try { session.close(); } catch (Exception ignored) { clean = false; } session = null; }
        return clean;
    }

    private static void acknowledge(OutputStream input) throws IOException { input.write(0); input.flush(); }

    private static String readHeader(InputStream output) throws IOException {
        StringBuilder header = new StringBuilder();
        for (int value; (value = output.read()) != '\n'; ) {
            if (value < 0 || header.length() >= 1024) throw new IOException();
            header.append((char) value);
        }
        return header.toString();
    }

    private static String withoutLineSeparators(ByteArrayOutputStream bytes) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new StringReader(bytes.toString(StandardCharsets.UTF_8.name())))) {
            for (String line; (line = reader.readLine()) != null; ) result.append(line);
        }
        return result.toString();
    }
}
