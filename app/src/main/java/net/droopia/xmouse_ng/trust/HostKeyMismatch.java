package net.droopia.xmouse_ng.trust;

import net.droopia.xmouse_ng.connection.Endpoint;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Bounded, attempt-scoped observation. It is never a first-trust approval or an authentication token. */
public final class HostKeyMismatch {
    private enum State { REVIEW, CONFIRMED, PUBLISHING, CANCELLED, COMPLETE }

    public final Endpoint endpoint;
    public final ApprovedHostKey trustedKey;
    public final ApprovedHostKey presentedKey;
    private final HostTrustRequests owner;
    private final BooleanSupplier attemptCurrent;
    private final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    private final AtomicReference<State> state = new AtomicReference<>(State.REVIEW);

    HostKeyMismatch(HostTrustRequests owner, Endpoint endpoint, ApprovedHostKey trustedKey,
                    ApprovedHostKey presentedKey, BooleanSupplier attemptCurrent) {
        this.owner = owner;
        this.endpoint = endpoint;
        this.trustedKey = trustedKey;
        this.presentedKey = presentedKey;
        this.attemptCurrent = attemptCurrent;
    }

    /** The review is valid only briefly and while the SSH attempt that observed it remains current. */
    public boolean isCurrent() {
        State value = state.get();
        return value != State.CANCELLED && value != State.COMPLETE
                && System.nanoTime() < deadlineNanos && attemptCurrent.getAsBoolean();
    }

    /** Called only by the explicit final Replace action, never by ordinary connection/retry code. */
    public boolean confirm() {
        if (!isCurrent() || !state.compareAndSet(State.REVIEW, State.CONFIRMED)) return false;
        if (isCurrent()) return true;
        state.compareAndSet(State.CONFIRMED, State.CANCELLED);
        return false;
    }

    /** Invalidates this mismatch until completion, including a worker already staging publication. */
    public void cancel() {
        State value;
        do {
            value = state.get();
            if (value == State.CANCELLED || value == State.COMPLETE) return;
        } while (!state.compareAndSet(value, State.CANCELLED));
    }

    boolean belongsTo(HostTrustRequests requests) { return owner == requests; }

    boolean claimReplacement(HostTrustRequests requests) {
        return owner == requests && isCurrent() && state.compareAndSet(State.CONFIRMED, State.PUBLISHING);
    }

    boolean publicationCurrent() {
        return state.get() == State.PUBLISHING && System.nanoTime() < deadlineNanos
                && attemptCurrent.getAsBoolean();
    }

    /** Linearizes successful completion against cancellation; false means cancellation won the race. */
    boolean finishReplacement() { return state.compareAndSet(State.PUBLISHING, State.COMPLETE); }
}
