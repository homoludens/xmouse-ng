package com.stripe1.xmouse.transport;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Process-wide bounded quarantine: a stuck native resolver/provider cannot create unlimited threads.
 * No executor runs work on its caller. Saturation rejects, never falls back to caller-runs.
 * The scheduler only posts bookkeeping; it never performs provider I/O or close.
 */
public final class SshRuntime implements AutoCloseable {
    public static final SshRuntime SHARED = new SshRuntime();
    final ThreadPoolExecutor operations = pool(4, 0, "xmouse-ssh-io");
    final ThreadPoolExecutor resolution = pool(2, 0, "xmouse-ssh-dns");
    // One worker for EVERY possible retained close slot, not just one per owner. Otherwise hung
    // closes could fill the pool and prevent even attempting other owners' independent resources.
    // The bounded queue absorbs only completed workers' short retirement/admission races.
    final ThreadPoolExecutor cleanup = pool(32, 32, "xmouse-ssh-io");
    final ScheduledExecutorService scheduler;
    final LongSupplier clock;
    // At most four live/quarantined resource owners, each with at most eight close slots.
    final Semaphore owners = new Semaphore(4);

    public SshRuntime() { this(System::nanoTime, Executors.newSingleThreadScheduledExecutor(action -> daemon(action, "xmouse-ssh-timer"))); }

    public SshRuntime(LongSupplier clock, ScheduledExecutorService scheduler) {
        this.clock = clock; this.scheduler = scheduler;
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); return thread;
    }

    private static ThreadPoolExecutor pool(int size, int queue, String name) {
        ThreadPoolExecutor result = new ThreadPoolExecutor(size, size, 10, TimeUnit.SECONDS,
                queue == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queue),
                action -> daemon(action, name), new ThreadPoolExecutor.AbortPolicy());
        result.allowCoreThreadTimeOut(true);
        return result;
    }

    /** Diagnostics contain counts only, never endpoint/authentication/command contents. */
    public int activeOperations() { return operations.getActiveCount(); }
    public int activeResolvers() { return resolution.getActiveCount(); }
    public int queuedCleanup() { return cleanup.getQueue().size(); }
    public int retainedOwners() { return 4 - owners.availablePermits(); }
    /** Test-owned runtimes only. Production owners share SHARED for the process lifetime. */
    @Override public void close() {
        if (this == SHARED) throw new IllegalStateException("Process runtime is shared");
        operations.shutdownNow(); resolution.shutdownNow(); cleanup.shutdown(); scheduler.shutdownNow();
    }
}
