package com.stripe1.xmouse.transport;

/** Finite monotonic budgets. Trust approval (at most 60s) fits inside the 90s SSH budget. */
public final class SshLimits {
    public final int resolution, preparation, tcp, connection, channel, write, exec, scp, cleanup;
    public final int keepaliveInterval, keepaliveMisses, healthInterval, health, outputBytes;

    public SshLimits(int resolution, int preparation, int tcp, int connection, int channel, int write,
                     int exec, int scp, int cleanup, int keepaliveInterval, int keepaliveMisses,
                     int healthInterval, int health, int outputBytes) {
        int[] values = {resolution, preparation, tcp, connection, channel, write, exec, scp, cleanup,
                keepaliveInterval, keepaliveMisses, healthInterval, health, outputBytes};
        for (int value : values) if (value <= 0) throw new IllegalArgumentException("Finite positive limit required");
        this.resolution = resolution; this.preparation = preparation; this.tcp = tcp;
        this.connection = connection; this.channel = channel; this.write = write; this.exec = exec;
        this.scp = scp; this.cleanup = cleanup; this.keepaliveInterval = keepaliveInterval;
        this.keepaliveMisses = keepaliveMisses; this.healthInterval = healthInterval;
        this.health = health; this.outputBytes = outputBytes;
    }

    public static SshLimits production() {
        return new SshLimits(10_000, 10_000, 15_000, 90_000, 5_000, 5_000, 30_000, 60_000,
                2_000, 15_000, 3, 500, 2_000, 1024 * 1024);
    }
}
