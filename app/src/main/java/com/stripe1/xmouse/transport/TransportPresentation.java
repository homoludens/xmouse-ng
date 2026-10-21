package com.stripe1.xmouse.transport;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Weak, detach-aware presentation bridge. No UI object is retained by a transport callback. */
public final class TransportPresentation {
    public interface Listener {
        void connected(String initialCommand);
        void recentCommand(String command);
        void execCompleted(String command, String output);
        void execFailed(String command, SshTransport.Failure failure);
        void scpCompleted();
        void disconnected();
        void failed(SshTransport.Operation operation, SshTransport.Failure failure);
    }

    private final Executor presentation;
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private long generation;

    public TransportPresentation(Executor presentation) { this.presentation = presentation; }
    public synchronized void attach(Listener listener) { this.listener = new WeakReference<>(listener); }
    public synchronized void detach() { ++generation; listener.clear(); }

    public synchronized void recentCommand(String command) {
        post(generation, target -> target.recentCommand(command));
    }

    public SshTransport.Callback<Void> connection(String initialCommand) {
        return callback(SshTransport.Operation.CONNECT, ignored -> target -> target.connected(initialCommand));
    }
    public synchronized SshTransport.Callback<String> exec(String command) {
        long token = generation;
        return new SshTransport.Callback<String>() {
            @Override public void success(String output) { post(token, target -> target.execCompleted(command, output)); }
            @Override public void failure(SshTransport.Failure failure) { post(token, target -> target.execFailed(command, failure)); }
        };
    }
    public SshTransport.Callback<Void> completion(SshTransport.Operation operation) {
        return callback(operation, ignored -> target -> {
            if (operation == SshTransport.Operation.SCP) target.scpCompleted();
            if (operation == SshTransport.Operation.DISCONNECT) target.disconnected();
        });
    }

    private interface Event<T> { Consumer<Listener> event(T value); }
    private synchronized <T> SshTransport.Callback<T> callback(SshTransport.Operation operation, Event<T> event) {
        long token = generation;
        return new SshTransport.Callback<T>() {
            @Override public void success(T value) { post(token, event.event(value)); }
            @Override public void failure(SshTransport.Failure failure) { post(token, target -> target.failed(operation, failure)); }
        };
    }
    private void post(long token, Consumer<Listener> event) {
        presentation.execute(() -> {
            Listener target;
            synchronized (this) { target = token == generation ? listener.get() : null; }
            if (target != null) event.accept(target);
        });
    }
}
