package com.stripe1.xmouse;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import com.stripe1.xmouse.connection.Endpoint;
import com.stripe1.xmouse.secrets.AndroidCredentials;
import com.stripe1.xmouse.secrets.AuthenticationMaterial;
import com.stripe1.xmouse.secrets.CredentialRepository;
import com.stripe1.xmouse.secrets.CredentialUi;
import com.stripe1.xmouse.session.SessionForegroundService;
import com.stripe1.xmouse.transport.JschSshBackend;
import com.stripe1.xmouse.trust.AndroidHostTrust;
import com.stripe1.xmouse.trust.HostTrustPolicy;
import com.stripe1.xmouse.trust.HostTrustRequests;
import com.stripe1.xmouse.trust.HostTrustUi;
import com.stripe1.xmouse.trust.HostKeyMismatch;
import com.stripe1.xmouse.trust.HostTrustReplacementUi;
import com.stripe1.xmouse.transport.SshConnectionRequest;
import com.stripe1.xmouse.transport.SshTransport;
import com.stripe1.xmouse.transport.TransportPresentation;

/**
 * Activity-local compatibility presenter, NOT an SSH resource owner. Only presentation runs here.
 * Service/controller ownership and the legacy toggle/recreation behavior remain tasks 5.x.
 */
public class MyConnectionHandler implements CommandTransport, TransportPresentation.Listener {
    private static final String TAG = "MyConnectionHandler";
    public interface LegacyConnectAttemptProbe { boolean interceptAttempt(); }
    private static volatile LegacyConnectAttemptProbe legacyConnectAttemptProbeForTests;

    /** Test-only safe boundary: intercepts a legacy connect attempt before credential or transport I/O. */
    public static AutoCloseable installLegacyConnectAttemptProbeForTests(LegacyConnectAttemptProbe probe) {
        legacyConnectAttemptProbeForTests = probe;
        return () -> {
            if (legacyConnectAttemptProbeForTests == probe) legacyConnectAttemptProbeForTests = null;
        };
    }

    private Activity activity;
    private MyInterface screen;
    private volatile CredentialUi credentialUi;
    private final CredentialRepository credentials;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SshTransport transport;
    private final TransportPresentation presentation;
    private ProgressDialog progress;
    private AlertDialog output;
    private int authenticationGeneration;
    private boolean readingCredentials;
    private HostTrustUi trustUi;
    private HostTrustReplacementUi replacementUi;
    private HostKeyMismatch replacingMismatch;

    public MyConnectionHandler(Activity activity, MyInterface screen) {
        this(activity, screen, productionTrust(activity));
    }

    private static HostTrustRequests productionTrust(Activity activity) {
        Handler main = new Handler(Looper.getMainLooper());
        return new HostTrustRequests(AndroidHostTrust.open(activity), main::post);
    }

    MyConnectionHandler(Activity activity, MyInterface screen, HostTrustRequests trust) {
        this(activity, screen, new SshTransport(new JschSshBackend(new HostTrustPolicy(trust)), SshTransport.newWorker()));
        trustUi = new HostTrustUi(activity, trust);
    }

    MyConnectionHandler(Activity activity, MyInterface screen, SshTransport transport) {
        this.activity = activity;
        this.screen = screen;
        this.transport = transport;
        credentials = AndroidCredentials.get(activity);
        credentialUi = new CredentialUi(activity);
        presentation = new TransportPresentation(main::post);
        presentation.attach(this);
        TransportPresentation dispatch = presentation;
        transport.setLossListener(failure -> dispatch.completion(SshTransport.Operation.MONITOR).failure(failure));
        replacementUi = new HostTrustReplacementUi(activity, mismatch -> {
            if (this.activity == null || !mismatch.isCurrent()) return;
            replacingMismatch = mismatch;
            transport.replaceHostKey(mismatch, presentation.trustReplacement());
        });
    }

    public void startCredentialUi() {
        if (activity == null) return;
        credentialUi.start();
        if (trustUi != null) trustUi.start();
        presentation.attach(this);
    }

    public void stopCredentialUi() {
        if (trustUi != null) trustUi.stop();
        dismissMismatchReview();
        presentation.detach();
        dismissDialogs();
        if (credentialUi != null && !credentialUi.hasPicker()) {
            authenticationGeneration++;
            readingCredentials = false;
            credentialUi.stop();
        }
    }

    public int credentialGeneration() { return credentialUi.getGeneration(); }
    public boolean credentialsCurrent(int token) { return credentialUi != null && credentialUi.isCurrent(token); }
    /** Worker-side stale-work gate: reads only the volatile generation, never Activity/UI state. */
    public boolean credentialWorkCurrent(int token) {
        CredentialUi prompts = credentialUi;
        return prompts != null && prompts.getGeneration() == token;
    }
    public boolean onCredentialResult(int code, int result, Intent data) {
        return credentialUi != null && credentialUi.onActivityResult(code, result, data);
    }
    public boolean isConnected() { return transport.isConnected(); }

    /** Used by the staged foreground-session entry point to avoid parallel legacy/service sessions. */
    public boolean hasPendingConnection() {
        return readingCredentials || transport.isConnecting() || transport.isConnected();
    }

    public void xMouseTryConnect() {
        if (activity == null || readingCredentials || transport.isConnecting()) return;
        if (SessionForegroundService.blocksLegacyConnect(activity)) {
            showForegroundSessionGuidance();
            return;
        }
        LegacyConnectAttemptProbe probe = legacyConnectAttemptProbeForTests;
        if (probe != null && probe.interceptAttempt()) return;
        dismissMismatchReview();
        if (isConnected()) { xMouseDisconnect(); return; } // Legacy toggle; 5.1/5.4 replace it.
        if (TextUtils.isEmpty(MainActivity.setting_user) || TextUtils.isEmpty(MainActivity.setting_host)) {
            Toast.makeText(activity, "A connection setting is blank", Toast.LENGTH_LONG).show();
            return;
        }
        final int attempt = ++authenticationGeneration;
        final CredentialUi prompts = credentialUi;
        final int token = prompts.getGeneration();
        final String shell = MainActivity.setting_shell;
        final String initialCommand = MainActivity.setting_xdotool_initial;
        readingCredentials = true;
        credentials.execute(() -> {
            try {
                if (!credentialWorkCurrent(token)) return;
                credentials.prepare();
                credentials.authenticate(prompts, (material, error) -> main.post(() -> {
                    if (attempt == authenticationGeneration) readingCredentials = false;
                    if (attempt != authenticationGeneration || !credentialsCurrent(token)) {
                        if (material != null) material.close();
                        return;
                    }
                    if (material == null) {
                        if (error != null) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (SessionForegroundService.blocksLegacyConnect(activity)) {
                        material.close();
                        showForegroundSessionGuidance();
                        return;
                    }
                    try {
                        Endpoint endpoint = Endpoint.manual(material.getHost().getHost(), material.getHost().getPort());
                        beginSsh(material, new SshConnectionRequest(endpoint, material.getHost().getHost(), shell, initialCommand));
                    } catch (Exception ignored) {
                        material.close();
                        Toast.makeText(activity, "Connection settings are invalid. Existing configuration was kept.", Toast.LENGTH_LONG).show();
                    }
                }));
            } catch (Exception ignored) {
                main.post(() -> {
                    if (attempt == authenticationGeneration) readingCredentials = false;
                    if (credentialsCurrent(token)) credentialUi.message();
                });
            }
        });
    }

    void beginSsh(AuthenticationMaterial material, SshConnectionRequest request) {
        if (activity == null || SessionForegroundService.blocksLegacyConnect(activity)) {
            material.close();
            showForegroundSessionGuidance();
            return;
        }
        dismissMismatchReview();
        showProgress("Connecting to " + material.getHost().getUser() + "@" + request.address + ":" + request.endpoint.getPort());
        Log.d(TAG, "connection_attempt");
        transport.connect(request, material, presentation.connection(request.initialCommand));
    }

    private void showForegroundSessionGuidance() {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showLegacyConnectBlockedGuidance();
        } else if (activity != null) {
            Toast.makeText(activity, R.string.session_controls_pending_legacy_guidance, Toast.LENGTH_LONG).show();
        }
    }

    public void xMouseDisconnect() {
        authenticationGeneration++;
        readingCredentials = false;
        dismissMismatchReview();
        if (trustUi != null) { trustUi.stop(); trustUi.start(); }
        if (credentialUi != null) { credentialUi.stop(); credentialUi.start(); }
        presentation.detach(); // Invalidate outstanding output/connect callbacks, not only credentials.
        if (activity != null) presentation.attach(this);
        dismissDialogs();
        transport.disconnect(presentation.completion(SshTransport.Operation.DISCONNECT));
        if (screen != null) screen.performCallback();
    }

    /** Terminal screen disposal. Never blocks on network close and cannot revive the old Activity. */
    public void destroy() {
        authenticationGeneration++;
        presentation.detach();
        if (credentialUi != null) credentialUi.stop();
        if (trustUi != null) trustUi.stop();
        dismissMismatchReview();
        trustUi = null;
        replacementUi = null;
        replacingMismatch = null;
        dismissDialogs();
        credentialUi = null;
        screen = null;
        activity = null;
        transport.close();
    }

    public boolean executeExecCommand(String command) {
        if (activity == null || !isConnected() || TextUtils.isEmpty(command)) return false;
        boolean accepted = transport.exec(command, presentation.exec(command));
        if (accepted) showProgress("Executing: " + command);
        return accepted;
    }

    @Override public boolean executeShellCommand(String command) {
        // Legacy adapters may be called by sensors/input workers; presentation still belongs to main.
        presentation.recentCommand(command);
        return transport.send(command, presentation.completion(SshTransport.Operation.WRITE));
    }

    @Override public void connected(String initialCommand) {
        dismissProgress();
        Toast.makeText(activity, "Connection established", Toast.LENGTH_LONG).show();
        if (initialCommand != null) screen.showRecentCommand(initialCommand);
        screen.performCallback();
    }

    @Override public void execCompleted(String command, String result) {
        dismissProgress();
        if (output != null) output.dismiss();
        output = new AlertDialog.Builder(activity).setTitle(command).setMessage(result)
                .setNeutralButton("Ok", (dialog, which) -> { })
                .setPositiveButton("Copy", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("xmouse script output", result));
                }).create();
        output.show();
        screen.performCallback();
    }

    @Override public void scpCompleted() { screen.performCallback(); }
    @Override public void execFailed(String command, SshTransport.Failure failure) {
        Log.w(TAG, "exec_command_failed");
        execCompleted(command, "Command execution failed.");
    }
    @Override public void recentCommand(String command) { screen.showRecentCommand(command); }
    @Override public void disconnected() { screen.performCallback(); }
    @Override public void failed(SshTransport.Operation operation, SshTransport.Failure failure) {
        dismissProgress();
        Log.w(TAG, "ssh_operation_failed");
        if (operation == SshTransport.Operation.TRUST_REPLACEMENT) {
            HostKeyMismatch mismatch = replacingMismatch;
            replacingMismatch = null;
            if (replacementUi != null && mismatch != null) replacementUi.finish(mismatch);
        }
        String message = failure == SshTransport.Failure.PRIVATE_KEY
                ? "Private key unavailable or invalid. Open Settings > Private Key to re-import; check Optional Key Passphrase. Existing configuration was kept."
                : operation == SshTransport.Operation.TRUST_REPLACEMENT ? "Host-key replacement could not be verified. Connection remains blocked; review trust before retrying."
                : failure == SshTransport.Failure.HOST_MISMATCH ? "Host identity changed. Connection blocked. The saved trusted key was kept."
                : failure == SshTransport.Failure.TRUST_STORAGE ? "Host trust could not be read or saved. Connection blocked; existing records were kept."
                : failure == SshTransport.Failure.HOST_TRUST ? "Host trust was not approved. No authentication or commands were sent."
                : failure == SshTransport.Failure.RESOLUTION || failure == SshTransport.Failure.RESOLUTION_TIMEOUT ? "Name resolution failed or timed out. Check the hostname and network, then retry."
                : failure == SshTransport.Failure.SESSION_LOST || failure == SshTransport.Failure.CHANNEL_CLOSED ? "SSH connection closed or stopped responding. Unsent input was discarded; connect again to retry."
                : operation == SshTransport.Operation.WRITE ? "Command could not be sent."
                : operation == SshTransport.Operation.EXEC ? "Command execution failed."
                : "Connection or authentication failed. Check the selected password/key and network, then retry. Existing configuration was kept.";
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        screen.performCallback();
    }

    @Override public void hostKeyMismatch(HostKeyMismatch mismatch) {
        if (activity == null || replacementUi == null || !mismatch.isCurrent()) return;
        replacementUi.show(mismatch);
    }

    @Override public void trustReplacementCompleted() {
        HostKeyMismatch mismatch = replacingMismatch;
        replacingMismatch = null;
        if (activity == null || replacementUi == null || mismatch == null) return;
        replacementUi.finish(mismatch);
        Toast.makeText(activity, "Trusted host key replaced. Connect again to authenticate with the new key.", Toast.LENGTH_LONG).show();
    }

    private void dismissMismatchReview() {
        if (replacementUi != null) replacementUi.stop();
        replacingMismatch = null;
    }

    private void showProgress(String message) {
        dismissProgress();
        progress = new ProgressDialog(activity);
        progress.setMessage(message);
        progress.show();
    }
    private void dismissProgress() { if (progress != null) { progress.dismiss(); progress = null; } }
    private void dismissDialogs() {
        dismissProgress();
        if (output != null) { output.dismiss(); output = null; }
    }
}
