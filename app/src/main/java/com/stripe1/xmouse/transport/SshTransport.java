package com.stripe1.xmouse.transport;

import com.stripe1.xmouse.CommandTransport;
import com.stripe1.xmouse.secrets.AuthenticationMaterial;
import com.stripe1.xmouse.secrets.PrivateKeyImportException;
import com.stripe1.xmouse.trust.HostTrustException;
import com.stripe1.xmouse.trust.HostKeyMismatch;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Serial asynchronous command boundary. The coordinator never performs blocking provider I/O.
 * Finite waits, bounded process-wide quarantine, and per-attempt resource ledgers allow cancellation
 * and cleanup even when a provider ignores interruption. No late result can attach to another owner.
 */
public final class SshTransport implements CommandTransport, AutoCloseable {
    public enum Operation { CONNECT, WRITE, EXEC, SCP, DISCONNECT, TRUST_REPLACEMENT, MONITOR }
    public enum Failure { CONNECTION, PRIVATE_KEY, IO, CLOSED, HOST_TRUST, HOST_MISMATCH, TRUST_STORAGE,
        RESOLUTION, RESOLUTION_TIMEOUT, CONNECTION_TIMEOUT, CHANNEL_TIMEOUT, WRITE_TIMEOUT,
        EXEC_TIMEOUT, SCP_TIMEOUT, CLEANUP_TIMEOUT, OUTPUT_LIMIT, SESSION_LOST, CHANNEL_CLOSED, CAPACITY }
    public interface Callback<T> {
        void success(T value);
        void failure(Failure failure);
        default void mismatch(HostKeyMismatch mismatch) { }
    }
    public interface LossListener { void lost(Failure failure); }
    public enum Progress { RESOLVING, CONNECTING }
    public interface ProgressListener { void changed(Progress progress); }
    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int value) { }
        @Override public void write(byte[] bytes, int offset, int length) { }
    };
    private final SshBackend backend;
    private final ExecutorService worker;
    private final SshLimits limits;
    private final SshRuntime runtime;
    private final Object submission = new Object();
    private volatile boolean ready, connecting, disposed;
    private volatile long generation;
    private volatile Scope owner;
    private volatile Future<?> active;
    private volatile LossListener lossListener;
    private volatile ProgressListener progressListener;
    private final Set<Scope> retained = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Failure> termination = new CompletableFuture<>();
    private volatile boolean cleanupUncertain;
    private int queued;

    public static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(action -> new Thread(action, "xmouse-ssh-coordinator"));
    }
    /** Ownership of the dedicated serial coordinator transfers here; never supply main/inline. */
    public SshTransport(SshBackend backend, ExecutorService worker) { this(backend, worker, SshLimits.production(), SshRuntime.SHARED); }
    public SshTransport(SshBackend backend, ExecutorService worker, SshLimits limits, SshRuntime runtime) {
        this.backend = backend; this.worker = worker; this.limits = limits; this.runtime = runtime;
    }
    public void setLossListener(LossListener listener) { lossListener = listener; }
    public void setProgressListener(ProgressListener listener) { progressListener = listener; }
    private void progress(Scope scope, Progress progress) {
        synchronized (submission) {
            if (scope.current() && progressListener != null) progressListener.changed(progress);
        }
    }
    public boolean isConnected() { return ready; }
    public boolean isConnecting() { return connecting; }

    public void connect(SshConnectionRequest request, AuthenticationMaterial authentication, Callback<Void> callback) {
        synchronized (submission) {
            if (disposed) { authentication.close(); callback.failure(Failure.CLOSED); return; }
            invalidate();
            long token = generation;
            connecting = true;
            worker.execute(() -> {
                Scope scope;
                synchronized (submission) {
                    if (token != generation) { authentication.close(); return; }
                    if (!runtime.owners.tryAcquire()) {
                        authentication.close(); connecting = false; callback.failure(Failure.CAPACITY); return;
                    }
                    scope = new Scope(token);
                    retained.add(scope);
                    owner = scope;
                }
                Failure failure = null;
                HostKeyMismatch mismatch = null;
                AtomicBoolean authenticationClaimed = new AtomicBoolean();
                try {
                    progress(scope, Progress.RESOLVING);
                    SshConnectionRequest resolved = await(scope, runtime.resolution, limits.resolution,
                            Failure.RESOLUTION_TIMEOUT, () -> backend.resolve(request));
                    scope.session = await(scope, runtime.operations, limits.preparation, Failure.CONNECTION_TIMEOUT, () -> {
                        if (!authenticationClaimed.compareAndSet(false, true)) throw new Outcome(Failure.CLOSED);
                        try {
                            SshBackend.Session created = backend.create(resolved, authentication, () -> scope.trustCurrent());
                            scope.add(created::abort);
                            scope.add(created::close);
                            return created;
                        } finally { authentication.close(); }
                    });
                    await(scope, runtime.operations, limits.connection, Failure.CONNECTION_TIMEOUT, () -> {
                        progress(scope, Progress.CONNECTING);
                        scope.session.configure(limits);
                        scope.session.connect(limits.connection);
                        check(scope);
                        scope.session.clearAuthentication();
                        return null;
                    });
                    await(scope, runtime.operations, limits.channel, Failure.CHANNEL_TIMEOUT, () -> {
                        SshBackend.Channel shell = scope.session.openChannel(request.shell.isEmpty() ? "shell" : "exec");
                        scope.add(shell::close);
                        scope.shell = shell;
                        check(scope);
                        if (!request.shell.isEmpty()) shell.command(request.shell);
                        shell.output(DISCARD, DISCARD);
                        scope.input = shell.input();
                        scope.add(scope.input::close);
                        check(scope);
                        shell.connect(limits.channel);
                        return null;
                    });
                    if (request.initialCommand != null) await(scope, runtime.operations, limits.write, Failure.WRITE_TIMEOUT,
                            () -> { write(scope, request.initialCommand); return null; });
                    await(scope, runtime.operations, limits.health, Failure.CONNECTION_TIMEOUT, () -> { healthy(scope); return null; });
                    synchronized (submission) {
                        check(scope);
                        ready = true; connecting = false;
                        scope.monitor = runtime.scheduler.scheduleWithFixedDelay(() -> monitor(scope),
                                limits.healthInterval, limits.healthInterval, TimeUnit.MILLISECONDS);
                    }
                } catch (HostTrustException rejected) {
                    mismatch = rejected.getMismatch();
                    switch (rejected.getReason()) {
                        case MISMATCH: failure = Failure.HOST_MISMATCH; break;
                        case STORAGE: case INVALID_REPOSITORY: failure = Failure.TRUST_STORAGE; break;
                        default: failure = Failure.HOST_TRUST;
                    }
                } catch (PrivateKeyImportException ignored) { failure = Failure.PRIVATE_KEY; }
                catch (Outcome outcome) { failure = outcome.failure; }
                catch (java.net.UnknownHostException ignored) { failure = Failure.RESOLUTION; }
                catch (Exception rejected) { failure = socketTimedOut(rejected) ? Failure.CONNECTION_TIMEOUT : Failure.CONNECTION; }
                finally {
                    if (authenticationClaimed.compareAndSet(false, true)) authentication.close();
                    if (failure != null || token != generation) scope.close();
                    if (token == generation) { connecting = false; if (failure != null) ready = false; }
                }
                synchronized (submission) {
                    if (token == generation) {
                        if (failure == null) callback.success(null);
                        else { if (mismatch != null) callback.mismatch(mismatch); callback.failure(failure); }
                    }
                }
            });
        }
    }

    /** Trust replacement is finite and never resumes the failed connection. */
    public void replaceHostKey(HostKeyMismatch mismatch, Callback<Void> callback) {
        synchronized (submission) {
            if (disposed) { callback.failure(Failure.CLOSED); return; }
            long token = generation;
            worker.execute(() -> {
                if (token != generation) return;
                AtomicBoolean current = new AtomicBoolean(true);
                Future<?> future = null;
                Failure failure = null;
                try {
                    synchronized (submission) {
                        if (token != generation) return;
                        future = runtime.operations.submit(() -> {
                            backend.replaceHostKey(mismatch, () -> token == generation && current.get() && mismatch.isCurrent());
                            return null;
                        });
                        active = future;
                    }
                    future.get(limits.preparation, TimeUnit.MILLISECONDS);
                } catch (Exception rejected) {
                    Throwable cause = rejected instanceof ExecutionException ? rejected.getCause() : rejected;
                    if (cause instanceof HostTrustException) {
                        HostTrustException.Reason reason = ((HostTrustException) cause).getReason();
                        failure = reason == HostTrustException.Reason.STORAGE || reason == HostTrustException.Reason.INVALID_REPOSITORY
                                ? Failure.TRUST_STORAGE : Failure.HOST_MISMATCH;
                    } else failure = Failure.TRUST_STORAGE;
                }
                finally { current.set(false); if (future != null) future.cancel(true); active = null; }
                synchronized (submission) {
                    if (token == generation) { if (failure == null) callback.success(null); else callback.failure(failure); }
                }
            });
        }
    }

    @Override public boolean executeShellCommand(String command) { return send(command, null); }
    public boolean send(String command, Callback<Void> callback) {
        return command != null && submit(callback, limits.write, Failure.WRITE_TIMEOUT, scope -> { write(scope, command); return null; });
    }
    public boolean exec(String command, Callback<String> callback) {
        if (command == null || command.isEmpty()) return false;
        return submit(callback, limits.exec, Failure.EXEC_TIMEOUT, scope -> {
            SshBackend.Channel channel = scope.session.openChannel("exec");
            Slot owned = scope.add(channel::close);
            try {
                check(scope);
                channel.command(command);
                check(scope);
                Capture capture = new Capture(limits.outputBytes);
                channel.output(capture.stdout, capture.stderr);
                check(scope);
                channel.connect(limits.channel);
                check(scope);
                while (!channel.isClosed()) {
                    check(scope);
                    if (capture.exceeded()) throw new Outcome(Failure.OUTPUT_LIMIT);
                    if (!scope.session.isConnected()) throw new Outcome(Failure.SESSION_LOST);
                    Thread.sleep(10);
                }
                if (capture.exceeded()) throw new Outcome(Failure.OUTPUT_LIMIT);
                if (!scope.session.isConnected()) throw new Outcome(Failure.SESSION_LOST);
                if (channel.exitStatus() < 0) throw new Outcome(Failure.CHANNEL_CLOSED);
                return capture.result();
            } finally { owned.closeHere(); }
        });
    }

    public boolean downloadScp(String command, File destination, Callback<Void> callback) {
        return submit(callback, limits.scp, Failure.SCP_TIMEOUT, scope -> {
            SshBackend.Channel channel = scope.session.openChannel("exec");
            Slot owned = scope.add(channel::close);
            Slot inputSlot = null, outputSlot = null, fileSlot = null;
            try {
                check(scope); channel.command(command); channel.output(null, DISCARD);
                OutputStream input = channel.input(); inputSlot = scope.add(input::close);
                InputStream output = channel.output(); outputSlot = scope.add(output::close);
                check(scope); channel.connect(limits.channel);
                check(scope);
                acknowledge(input);
                if (output.read() != 'C') throw new IOException();
                String header = readHeader(output);
                int separator = header.indexOf(' ', 5);
                if (!header.matches("[0-7]{4} [0-9]+ .+") || separator < 0) throw new IOException();
                long remaining = Long.parseLong(header.substring(5, separator));
                check(scope);
                acknowledge(input);
                check(scope);
                OutputStream file = new FileOutputStream(destination); fileSlot = scope.add(file::close);
                byte[] buffer = new byte[8192];
                while (remaining > 0) {
                    check(scope);
                    int count = output.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count <= 0) throw new IOException();
                    check(scope); file.write(buffer, 0, count); remaining -= count;
                }
                if (output.read() != 0) throw new IOException();
                check(scope);
                acknowledge(input);
                return null;
            } finally {
                // Independent ledger cleanup still attempts every resource if one close blocks/fails.
                closeTemporary(fileSlot, outputSlot, inputSlot, owned);
            }
        });
    }

    public void disconnect(Callback<Void> callback) {
        synchronized (submission) {
            if (disposed) return;
            Scope old = invalidate();
            long token = generation;
            worker.execute(() -> {
                Failure failure = old == null ? null : old.close();
                synchronized (submission) {
                    if (token == generation && callback != null) { if (failure == null) callback.success(null); else callback.failure(failure); }
                }
            });
        }
    }
    @Override public void close() { closeConfirmed(null); }
    /** Terminal, bounded physical-ownership barrier. Unlike logical disconnect, success requires all
     * real operations and their late resource handoffs/cleanup to finish. Failure MUST NOT authorize
     * another host. Quarantined resources still close independently after the deadline. */
    public void closeConfirmed(Callback<Void> callback) {
        if (callback != null) termination.thenAccept(failure -> {
            if (failure == null) callback.success(null); else callback.failure(failure);
        });
        synchronized (submission) {
            if (disposed) return;
            disposed = true; lossListener = null; progressListener = null;
            invalidate();
            worker.execute(() -> {
                long deadline = runtime.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(limits.cleanup);
                Failure failure = null;
                for (Scope scope : retained) {
                    scope.startClose();
                    synchronized (scope) {
                        while (!scope.released) {
                            long remaining = deadline - runtime.clock.getAsLong();
                            if (remaining <= 0) { failure = Failure.CLEANUP_TIMEOUT; break; }
                            try { TimeUnit.NANOSECONDS.timedWait(scope, remaining); }
                            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); failure = Failure.CLEANUP_TIMEOUT; break; }
                        }
                    }
                }
                if (failure == null && cleanupUncertain) failure = Failure.IO;
                termination.complete(failure);
            });
            worker.shutdown();
        }
    }
    /** Non-blocking caller-side invalidation; close jobs NEVER run inline. */
    private Scope invalidate() {
        ready = false; connecting = false; ++generation;
        backend.cancelPendingTrust();
        Future<?> pending = active; if (pending != null) pending.cancel(true);
        Scope old = owner; owner = null;
        if (old != null) old.startClose();
        return old;
    }
    private interface Work<T> { T run(Scope scope) throws Exception; }
    private <T> boolean submit(Callback<T> callback, int budget, Failure timeout, Work<T> action) {
        synchronized (submission) {
            if (!ready || disposed || queued >= 128) return false;
            Scope scope = owner; queued++;
            long deadline = runtime.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(budget);
            worker.execute(() -> {
                try {
                    if (!scope.current()) return;
                    long remaining = deadline - runtime.clock.getAsLong();
                    if (remaining <= 0) throw new Outcome(timeout);
                    T value = await(scope, runtime.operations, (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), timeout,
                            () -> { healthy(scope); return action.run(scope); });
                    synchronized (submission) { if (scope.current() && callback != null) callback.success(value); }
                } catch (Exception failure) {
                    lose(scope, failure instanceof Outcome ? ((Outcome) failure).failure : Failure.IO, callback);
                } finally { synchronized (submission) { queued--; } }
            });
            return true;
        }
    }
    private void monitor(Scope scope) {
        if (!scope.current() || !scope.polling.compareAndSet(false, true)) return;
        synchronized (submission) {
            if (!scope.current()) { scope.polling.set(false); return; }
            worker.execute(() -> {
                try {
                    if (scope.current()) await(scope, runtime.operations, limits.health, Failure.SESSION_LOST,
                            () -> { healthy(scope); return null; });
                } catch (Exception failure) {
                    lose(scope, failure instanceof Outcome ? ((Outcome) failure).failure : Failure.SESSION_LOST, null);
                } finally { scope.polling.set(false); }
            });
        }
    }
    private void lose(Scope scope, Failure failure, Callback<?> callback) {
        synchronized (submission) {
            if (scope.token != generation || disposed) return;
            ready = false; connecting = false; ++generation; owner = null;
        }
        scope.close();
        LossListener listener = lossListener;
        // A disconnect/new connect during cleanup suppresses old loss delivery.
        synchronized (submission) {
            if (generation == scope.token + 1 && owner == null && !disposed) {
                if (callback != null) callback.failure(failure);
                if (listener != null) listener.lost(failure);
            }
        }
    }
    private <T> T await(Scope scope, ExecutorService lane, int budget, Failure timeout, Callable<T> action) throws Exception {
        check(scope);
        FutureTask<T> future = new FutureTask<T>(() -> { check(scope); return action.call(); }) {
            private boolean running, retired;
            private void retire() {
                if (!retired) { retired = true; scope.inFlight--; scope.releaseIfDone(); }
            }
            @Override public void run() {
                synchronized (scope) { if (retired) return; running = true; }
                try { super.run(); } finally { synchronized (scope) { retire(); } }
            }
            @Override protected void done() {
                synchronized (scope) { if (!running) retire(); }
            }
        };
        synchronized (scope) { scope.inFlight++; }
        try {
            synchronized (submission) {
                check(scope); active = future; lane.execute(future);
            }
        } catch (RejectedExecutionException ignored) {
            future.cancel(false);
            throw new Outcome(Failure.CAPACITY);
        } catch (Exception failure) {
            future.cancel(false); throw failure;
        }
        try {
            if (!scope.current()) { future.cancel(true); throw new CancellationException(); }
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            scope.expired = true; future.cancel(true); throw new Outcome(timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure; // Never catch OOM or retain provider contents in callbacks.
        } finally { if (active == future) active = null; }
    }
    private void check(Scope scope) throws Outcome { if (!scope.current()) throw new Outcome(Failure.CLOSED); }
    private void healthy(Scope scope) throws Exception {
        check(scope);
        if (!scope.session.isConnected()) throw new Outcome(Failure.SESSION_LOST);
        if (scope.shell.isClosed()) throw new Outcome(Failure.CHANNEL_CLOSED);
    }
    private void write(Scope scope, String command) throws Exception {
        check(scope); scope.input.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
        check(scope); scope.input.flush();
    }
    private interface Close { void run() throws Exception; }
    private static boolean socketTimedOut(Throwable failure) {
        // Inspect only bounded type metadata, never messages/remote/provider content.
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }
    private final class Slot {
        final Scope scope; final Close action;
        final AtomicBoolean claimed = new AtomicBoolean();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Slot(Scope scope, Close action) { this.scope = scope; this.action = action; }
        void perform() {
            try { action.run(); done.complete(null); } catch (Exception ignored) {
                cleanupUncertain = true; done.completeExceptionally(new IOException());
            }
            finally { synchronized (scope) { scope.slots.remove(this); scope.releaseIfDone(); } }
        }
        void schedule() { if (claimed.compareAndSet(false, true)) runtime.cleanup.execute(this::perform); }
        void closeHere() throws Exception {
            if (claimed.compareAndSet(false, true)) perform();
            done.get(limits.cleanup, TimeUnit.MILLISECONDS);
        }
    }
    private final class Scope {
        final long token;
        final List<Slot> slots = new ArrayList<>();
        final List<Slot> closingSlots = new ArrayList<>();
        final AtomicBoolean polling = new AtomicBoolean();
        volatile boolean closing, expired;
        int inFlight; boolean released;
        SshBackend.Session session; SshBackend.Channel shell; OutputStream input;
        volatile ScheduledFuture<?> monitor;
        Scope(long token) { this.token = token; }
        boolean current() { return token == generation && !closing && !expired && !disposed; }
        boolean trustCurrent() { return token == generation && !expired && !disposed; }
        synchronized Slot add(Close action) {
            Slot slot = new Slot(this, action); slots.add(slot);
            if (closing) { closingSlots.add(slot); slot.schedule(); }
            return slot;
        }
        synchronized void startClose() {
            if (closing) return;
            closing = true;
            if (monitor != null) monitor.cancel(false);
            closingSlots.addAll(slots);
            for (Slot slot : closingSlots) slot.schedule();
            releaseIfDone();
        }
        Failure close() {
            startClose();
            long deadline = runtime.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(limits.cleanup);
            List<Slot> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(closingSlots); }
            Failure failure = null;
            for (Slot slot : snapshot) {
                try { slot.done.get(Math.max(1, deadline - runtime.clock.getAsLong()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException ignored) { failure = Failure.CLEANUP_TIMEOUT; }
                catch (Exception ignored) { if (failure == null) failure = Failure.IO; }
            }
            return failure;
        }
        synchronized void releaseIfDone() {
            if (closing && inFlight == 0 && slots.isEmpty() && !released) {
                released = true; retained.remove(this); runtime.owners.release(); notifyAll();
            }
        }
    }
    private void closeTemporary(Slot... slots) throws Exception {
        for (Slot slot : slots) if (slot != null) slot.schedule();
        long deadline = runtime.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(limits.cleanup);
        Exception failure = null;
        for (Slot slot : slots) if (slot != null) {
            try { slot.done.get(Math.max(1, deadline - runtime.clock.getAsLong()), TimeUnit.NANOSECONDS); }
            catch (Exception ignored) { failure = new IOException(); }
        }
        if (failure != null) throw failure;
    }
    private static final class Outcome extends Exception {
        final Failure failure;
        Outcome(Failure failure) { this.failure = failure; }
    }
    /** Shared byte/work cap across both concurrently delivered streams. Excess is discarded until the
     * coordinator observes the flag and aborts; partial output is NEVER reported as successful output. */
    private static final class Capture {
        final int limit; int count; boolean overflow;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        final OutputStream stdout = sink(out), stderr = sink(err);
        Capture(int limit) { this.limit = limit; }
        OutputStream sink(ByteArrayOutputStream target) { return new OutputStream() {
            @Override public void write(int value) { byte[] one = {(byte) value}; write(one, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) {
                synchronized (Capture.this) {
                    if (length > limit - count) { overflow = true; return; }
                    target.write(bytes, offset, length); count += length;
                }
            }
        }; }
        synchronized boolean exceeded() { return overflow; }
        synchronized String result() throws IOException { return withoutLineSeparators(err) + withoutLineSeparators(out); }
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
