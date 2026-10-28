package net.droopia.xmouse_ng;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;
import net.droopia.xmouse_ng.connection.ConnectionFailure;
import net.droopia.xmouse_ng.connection.Endpoint;
import net.droopia.xmouse_ng.connection.EndpointHost;
import net.droopia.xmouse_ng.connection.EndpointLocator;
import net.droopia.xmouse_ng.discovery.SshServiceDiscovery;
import net.droopia.xmouse_ng.permissions.LocalNetworkPermissionCoordinator;
import net.droopia.xmouse_ng.secrets.AndroidCredentials;
import net.droopia.xmouse_ng.secrets.CredentialHost;
import net.droopia.xmouse_ng.secrets.CredentialRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

public class ListEditActivity extends AppCompatActivity implements MyInterface {
    public interface DiscoveryHandle {
        void start();
        void close();
    }

    public interface DiscoveryFactory {
        DiscoveryHandle create(Context context, SshServiceDiscovery.Listener listener);
    }

    private static final DiscoveryFactory DEFAULT_DISCOVERY_FACTORY = (context, listener) -> {
        SshServiceDiscovery discovery = new SshServiceDiscovery(context, listener);
        return new DiscoveryHandle() {
            @Override public void start() { discovery.start(); }
            @Override public void close() { discovery.close(); }
        };
    };
    private static volatile DiscoveryFactory discoveryFactory = DEFAULT_DISCOVERY_FACTORY;
    private static volatile Boolean permissionRequiredForTesting;

    @VisibleForTesting
    public static void setDiscoveryFactoryForTests(DiscoveryFactory factory) {
        discoveryFactory = factory == null ? DEFAULT_DISCOVERY_FACTORY : factory;
    }

    @VisibleForTesting
    public static void setPermissionRequiredForTests(Boolean required) {
        permissionRequiredForTesting = required;
    }

    private ListView mList;
    private View findComputersButton;
    private List<HostItem> mHostItems = new LinkedList<>();
    public enum ActivityType { type_host, type_script }
    private ActivityType type;
    private HostListItemAdapter adapter;
    private LayoutInflater layoutInflater;
    private DatabaseHandler database;
    private CredentialRepository credentials;
    private int generation;
    private AlertDialog editing;
    private AlertDialog discoveryDialog;
    private ListView discoveryResults;
    private TextView discoveryStatus;
    private ArrayAdapter<String> discoveryAdapter;
    private DiscoveryHandle discoveryHandle;
    private ActivityResultLauncher<String> localNetworkPermissionLauncher;
    private final Map<SshServiceDiscovery.Identity, SshServiceDiscovery.Candidate> discoveredCandidates = new LinkedHashMap<>();
    private SshServiceDiscovery.Candidate transientDiscoveryCandidate;
    private int discoveryGeneration;
    private String discoveryError;
    private boolean permissionInFlight;
    private int permissionGeneration = -1;
    private String pendingScriptId;
    private boolean pendingScriptEditAttempted;
    private boolean pendingAddScript;
    private int scriptEditGeneration;
    private final ExecutorService scriptWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "xmouse-script-crud");
        thread.setDaemon(true);
        return thread;
    });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        localNetworkPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::onLocalNetworkPermissionResult);
        type = getIntent().getIntExtra("intVariableType", 0) == 0 ? ActivityType.type_host : ActivityType.type_script;
        setContentView(type == ActivityType.type_host
                ? R.layout.activity_list_edit : R.layout.activity_script_manager);
        SystemWindowInsets.applyTo(getWindow(), findViewById(R.id.list_edit_root));
        if (type == ActivityType.type_host) {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationContentDescription(R.string.ng_back);
            setTitle("Computers");
            ((FloatingActionButton) findViewById(R.id.fab)).setOnClickListener(
                    view -> startActivity(ComputerEditorActivity.intent(this, 0)));
        } else {
            setTitle(R.string.ng_script_manager_title);
            findViewById(R.id.scripts_manager_back).setOnClickListener(view -> finish());
            findViewById(R.id.scripts_manager_add).setOnClickListener(view -> xMouseManage(null));
        }
        layoutInflater = getLayoutInflater();
        database = new DatabaseHandler(getApplicationContext());
        credentials = AndroidCredentials.get(this);
        pendingScriptId = type == ActivityType.type_script
                ? getIntent().getStringExtra(EXTRA_SCRIPT_ID) : null;
        pendingAddScript = type == ActivityType.type_script && getIntent().getBooleanExtra(EXTRA_ADD_SCRIPT, false);
        mList = findViewById(R.id.myListView);
        findComputersButton = findViewById(R.id.find_computers);
        if (findComputersButton != null) {
            findComputersButton.setVisibility(type == ActivityType.type_host ? View.VISIBLE : View.GONE);
            findComputersButton.setOnClickListener(view -> {
                if (type == ActivityType.type_host) showFindComputers();
            });
        }
        if (type == ActivityType.type_script) {
            TextView status = findViewById(R.id.scripts_manager_count);
            status.setText(R.string.ng_script_manager_loading);
        }
        if (savedInstanceState == null && type == ActivityType.type_host && getIntent().getBooleanExtra("findComputers", false))
            showFindComputers();
    }

    @Override protected void onStart() { super.onStart(); loadCurrentHostList(); }
    @Override protected void onStop() {
        generation++;
        closeDiscoveryDialog();
        if (editing != null) editing.dismiss();
        super.onStop();
    }
    @Override protected void onDestroy() { scriptWorker.shutdown(); database.close(); super.onDestroy(); }
    private boolean current(int token) { return token == generation && !isFinishing() && !isDestroyed(); }
    private void failure() { Toast.makeText(this, CredentialRepository.RECOVERY_MESSAGE, Toast.LENGTH_LONG).show(); }

    private void showFindComputers() {
        if (discoveryDialog != null && discoveryDialog.isShowing()) return;
        View layout = layoutInflater.inflate(R.layout.dialog_find_computers, null);
        discoveryStatus = layout.findViewById(R.id.find_computers_status);
        discoveryResults = layout.findViewById(R.id.find_computers_results);
        discoveryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        discoveryResults.setAdapter(discoveryAdapter);
        discoveryResults.setOnItemClickListener((parent, row, position, id) -> {
            List<SshServiceDiscovery.Candidate> candidates = new ArrayList<>(discoveredCandidates.values());
            if (position >= 0 && position < candidates.size()) selectDiscoveredCandidate(candidates.get(position));
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ssh_discovery_title)
                .setView(layout)
                .setPositiveButton(R.string.ssh_discovery_retry, null)
                .setNegativeButton(R.string.ssh_discovery_close, null)
                .setNeutralButton(R.string.ssh_discovery_manual_entry, null)
                .create();
        discoveryDialog = dialog;
        discoveredCandidates.clear();
        discoveryError = null;
        renderDiscoveryState();
        dialog.setOnDismissListener(ignored -> {
            if (discoveryDialog == dialog) discoveryDialog = null;
            discoveryGeneration++;
            closeDiscoveryHandle();
            discoveredCandidates.clear();
            discoveryAdapter = null;
            discoveryResults = null;
            discoveryStatus = null;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> restartDiscovery());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            closeDiscoveryDialog();
            transientDiscoveryCandidate = null;
            startActivity(ComputerEditorActivity.intent(this, 0));
        });
        startDiscoveryOrRequestPermission();
    }

    private void restartDiscovery() {
        if (discoveryDialog == null || !discoveryDialog.isShowing()) return;
        discoveryGeneration++;
        closeDiscoveryHandle();
        discoveredCandidates.clear();
        discoveryError = null;
        renderDiscoveryState();
        startDiscoveryOrRequestPermission();
    }

    private boolean permissionRequired() {
        Boolean testOverride = permissionRequiredForTesting;
        return testOverride != null ? testOverride :
                LocalNetworkPermissionCoordinator.forContext(getApplicationContext()).requiresRuntimePermission();
    }

    private void startDiscoveryOrRequestPermission() {
        if (discoveryDialog == null || !discoveryDialog.isShowing()) return;
        if (Build.VERSION.SDK_INT >= LocalNetworkPermissionCoordinator.REQUIRED_API && permissionRequired()) {
            if (permissionInFlight) {
                renderDiscoveryState("Finish the previous permission request, then Retry.");
                return;
            }
            permissionInFlight = true;
            permissionGeneration = discoveryGeneration;
            discoveryError = null;
            renderDiscoveryState(getString(R.string.ssh_discovery_permission_requesting));
            localNetworkPermissionLauncher.launch(LocalNetworkPermissionCoordinator.PERMISSION);
            return;
        }
        startDiscoverySession();
    }

    private void onLocalNetworkPermissionResult(boolean granted) {
        permissionInFlight = false;
        if (permissionGeneration != discoveryGeneration || discoveryDialog == null || !discoveryDialog.isShowing()) return;
        if (granted) {
            startDiscoverySession();
        } else {
            onDiscoveryFailure(new SshServiceDiscovery.Failure(
                    SshServiceDiscovery.Operation.PERMISSION,
                    null,
                    null,
                    new ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED)));
        }
    }

    private void startDiscoverySession() {
        if (discoveryDialog == null || !discoveryDialog.isShowing()) return;
        closeDiscoveryHandle();
        int token = ++discoveryGeneration;
        discoveryError = null;
        renderDiscoveryState();
        SshServiceDiscovery.Listener listener = new SshServiceDiscovery.Listener() {
            @Override public void onServiceFound(SshServiceDiscovery.Candidate candidate) {
                runOnUiThread(() -> updateDiscoveredCandidate(token, candidate));
            }
            @Override public void onServiceUpdated(SshServiceDiscovery.Candidate candidate) {
                runOnUiThread(() -> updateDiscoveredCandidate(token, candidate));
            }
            @Override public void onServiceLost(SshServiceDiscovery.Identity identity) {
                runOnUiThread(() -> removeDiscoveredCandidate(token, identity));
            }
            @Override public void onFailure(SshServiceDiscovery.Failure failure) {
                runOnUiThread(() -> showDiscoveryFailure(token, failure));
            }
        };
        try {
            discoveryHandle = discoveryFactory.create(this, listener);
            discoveryHandle.start();
        } catch (RuntimeException ignored) {
            showDiscoveryFailure(token, new SshServiceDiscovery.Failure(
                    SshServiceDiscovery.Operation.START, null, null, null));
        }
    }

    private void updateDiscoveredCandidate(int token, SshServiceDiscovery.Candidate candidate) {
        if (!isCurrentDiscovery(token)) return;
        discoveredCandidates.put(candidate.getIdentity(), candidate);
        discoveryError = null;
        renderDiscoveryState();
    }

    private void removeDiscoveredCandidate(int token, SshServiceDiscovery.Identity identity) {
        if (!isCurrentDiscovery(token)) return;
        discoveredCandidates.remove(identity);
        renderDiscoveryState();
    }

    private void showDiscoveryFailure(int token, SshServiceDiscovery.Failure failure) {
        if (!isCurrentDiscovery(token)) return;
        boolean permissionDenied = failure.getOperation() == SshServiceDiscovery.Operation.PERMISSION
                || failure.getConnectionFailure() instanceof ConnectionFailure.Permission;
        discoveryError = getString(permissionDenied
                ? R.string.ssh_discovery_permission_denied : R.string.ssh_discovery_error);
        renderDiscoveryState();
    }

    private void onDiscoveryFailure(SshServiceDiscovery.Failure failure) {
        showDiscoveryFailure(discoveryGeneration, failure);
    }

    private boolean isCurrentDiscovery(int token) {
        return token == discoveryGeneration && discoveryDialog != null && discoveryDialog.isShowing();
    }

    private void renderDiscoveryState() {
        String status;
        if (discoveryError != null) {
            status = discoveryError;
        } else if (discoveredCandidates.isEmpty()) {
            status = getString(R.string.ssh_discovery_searching);
        } else {
            status = getString(R.string.ssh_discovery_select_result);
        }
        renderDiscoveryState(status);
    }

    private void renderDiscoveryState(String status) {
        if (discoveryStatus == null || discoveryAdapter == null) return;
        discoveryStatus.setText(status);
        discoveryAdapter.clear();
        for (SshServiceDiscovery.Candidate candidate : discoveredCandidates.values()) {
            String row = getString(R.string.ssh_discovery_result,
                    candidate.getLocator().getInstanceName(), candidate.getPort());
            String endpoint = candidateEndpoint(candidate);
            if (!endpoint.isEmpty()) row += "\n" + getString(R.string.ssh_discovery_endpoint, endpoint);
            discoveryAdapter.add(row);
        }
    }

    private String candidateEndpoint(SshServiceDiscovery.Candidate candidate) {
        EndpointHost stableHost = candidate.getLocator().getStableTargetHostname();
        if (stableHost != null) return stableHost.getOriginal();
        List<java.net.InetAddress> addresses = candidate.getAddresses();
        return addresses.isEmpty() ? "" : addresses.get(0).getHostAddress();
    }

    private void selectDiscoveredCandidate(SshServiceDiscovery.Candidate candidate) {
        closeDiscoveryDialog();
        transientDiscoveryCandidate = null;
        startActivity(ComputerEditorActivity.discoveredIntent(this, candidate));
    }

    private void closeDiscoveryDialog() {
        AlertDialog dialog = discoveryDialog;
        if (dialog != null) dialog.dismiss();
        else {
            discoveryGeneration++;
            closeDiscoveryHandle();
        }
    }

    private void closeDiscoveryHandle() {
        DiscoveryHandle handle = discoveryHandle;
        discoveryHandle = null;
        if (handle != null) {
            try { handle.close(); } catch (RuntimeException ignored) { }
        }
    }

    public void loadCurrentHostList() {
        if (type == ActivityType.type_host) {
            final int token = generation;
            credentials.execute(() -> {
                List<HostItem> items = new LinkedList<>();
                try {
                    for (CredentialHost host : credentials.hosts()) {
                        HostItem item = new HostItem();
                        item.setCredentialHost(host);
                        item.setAlias(host.getAlias() == null || host.getAlias().trim().isEmpty() ? host.displayEndpoint() : host.getAlias());
                        item.setIP(host.displayEndpoint());
                        item.setUsername(host.getUser()); item.setPort(host.getPort()); item.setDbId((int) host.getId());
                        items.add(item);
                    }
                    runOnUiThread(() -> { if (current(token)) showItems(items); });
                } catch (Exception ignored) { runOnUiThread(() -> { if (current(token)) failure(); }); }
            });
        } else {
            final int token = generation;
            final Context context = getApplicationContext();
            final WeakReference<ListEditActivity> owner = new WeakReference<>(this);
            final Handler handler = new Handler(Looper.getMainLooper());
            scriptWorker.execute(() -> {
                List<HostItem> items = new LinkedList<>();
                boolean failed = false;
                try (DatabaseHandler database = new DatabaseHandler(context)) {
                    ArrayList<ArrayList<String>> rows = database.listAll(DatabaseHandler.SCRIPT_TABLE_NAME,
                            new String[]{"Alias", "Script", "id"});
                    for (int i = rows.size() - 1; i >= 0; i--) {
                        ArrayList<String> row = rows.get(i);
                        HostItem item = new HostItem(); item.setAlias(row.get(0)); item.setIP(row.get(1)); item.setUsername(row.get(2)); items.add(item);
                    }
                } catch (Exception ignored) { failed = true; }
                final boolean readFailed = failed;
                handler.post(() -> {
                    ListEditActivity activity = owner.get();
                    if (activity == null || !activity.current(token)) return;
                    if (readFailed) {
                        activity.showScriptManagerReadFailure();
                        Toast.makeText(activity, R.string.ng_script_read_failed, Toast.LENGTH_LONG).show();
                    }
                    else activity.showItems(items);
                });
            });
        }
    }

    private void showItems(List<HostItem> items) {
        mHostItems = items;
        adapter = new HostListItemAdapter(this, items, this, type);
        mList.setAdapter(adapter);
        if (type == ActivityType.type_script) {
            TextView count = findViewById(R.id.scripts_manager_count);
            count.setText(getResources().getQuantityString(R.plurals.ng_scripts_count, items.size(), items.size()));
            TextView empty = findViewById(R.id.scripts_manager_empty);
            empty.setText(R.string.ng_script_manager_empty);
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (type == ActivityType.type_script && pendingAddScript) {
            pendingAddScript = false;
            xMouseManage(null);
        }
        if (type == ActivityType.type_script && pendingScriptId != null && !pendingScriptEditAttempted) {
            pendingScriptEditAttempted = true;
            int position = -1;
            for (int index = 0; index < items.size(); index++) {
                if (pendingScriptId.equals(items.get(index).getUsername())) {
                    position = index;
                    break;
                }
            }
            pendingScriptId = null;
            if (position >= 0) xMouseManage(position);
            else Toast.makeText(this, "That saved script is no longer available.", Toast.LENGTH_LONG).show();
        }
    }

    public void xMouseManage(final Integer pos) {
        xMouseManage(pos, null);
    }

    public static final String EXTRA_SCRIPT_ID = "net.droopia.xmouse_ng.script.id";
    public static final String EXTRA_ADD_SCRIPT = "net.droopia.xmouse_ng.script.add";

    private void showScriptManagerReadFailure() {
        TextView count = findViewById(R.id.scripts_manager_count);
        TextView empty = findViewById(R.id.scripts_manager_empty);
        if (count != null) count.setText(R.string.ng_script_manager_read_failed);
        if (empty != null) empty.setVisibility(View.GONE);
    }

    private void xMouseManage(final Integer pos, SshServiceDiscovery.Candidate candidate) {
        final boolean script = type == ActivityType.type_script;
        final HostItem existing = pos == null ? null : mHostItems.get(pos);
        transientDiscoveryCandidate = candidate;
        final View layout = layoutInflater.inflate(script ? R.layout.manage_script_item : R.layout.manage_host_item, null);
        final EditText nameBox = layout.findViewById(script ? R.id.new_script_alias : R.id.new_host_alias);
        final EditText hostBox = layout.findViewById(script ? R.id.new_script_command : R.id.new_host_ip);
        final TextInputLayout scriptNameLayout = script ? layout.findViewById(R.id.new_script_alias_layout) : null;
        final TextInputLayout scriptCommandLayout = script ? layout.findViewById(R.id.new_script_command_layout) : null;
        final EditText userBox = script ? null : layout.findViewById(R.id.new_host_username);
        final EditText portBox = script ? null : layout.findViewById(R.id.new_host_port);
        final EditText passwordBox = script ? null : layout.findViewById(R.id.new_host_password);
        if (script) {
            // Raw script commands are sensitive input: they are never saved in view state/Bundle.
            hostBox.setSaveEnabled(false);
            hostBox.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            hostBox.setTypeface(Typeface.MONOSPACE);
        }
        if (!script) {
            passwordBox.setSaveEnabled(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) passwordBox.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        if (existing != null) {
            nameBox.setText(existing.getAlias());
            if (script) hostBox.setText(existing.getIP());
            else {
                CredentialHost credentialHost = existing.getCredentialHost();
                hostBox.setText(credentialHost == null ? existing.getIP() : credentialHost.editableEndpoint());
                if (credentialHost != null && credentialHost.getServiceLocator() != null &&
                        credentialHost.getServiceLocator().getStableTargetHostname() == null) {
                    hostBox.setHint(credentialHost.displayEndpoint());
                }
                userBox.setText(existing.getUsername()); portBox.setText(existing.getPort());
            }
        } else if (!script && candidate != null) {
            nameBox.setText(candidate.getLocator().getInstanceName());
            hostBox.setText(candidateEndpoint(candidate));
            portBox.setText(String.valueOf(candidate.getPort()));
        }
        String dialogTitle = script
                ? getString(existing == null ? R.string.ng_script_manager_add_title : R.string.ng_script_manager_edit_title)
                : (existing == null ? "New Host" : "Edit Host");
        String saveLabel = script ? getString(R.string.ng_script_manager_save) : "Save";
        String negativeLabel = existing == null
                ? (script ? getString(R.string.ng_script_manager_cancel) : "Cancel")
                : (script ? getString(R.string.ng_script_manager_delete) : "Delete");
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setView(layout)
                .setTitle(dialogTitle).setPositiveButton(saveLabel, null)
                .setNegativeButton(negativeLabel, null);
        if (existing != null) builder.setNeutralButton(script
                ? getString(R.string.ng_script_manager_cancel) : "Cancel", null);
        AlertDialog dialog = builder.create();
        scriptEditGeneration++;
        editing = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        final int token = generation;
        dialog.setOnDismissListener(ignored -> {
            scriptEditGeneration++;
            if (passwordBox != null) passwordBox.getText().clear();
            if (editing == dialog) editing = null;
            if (transientDiscoveryCandidate == candidate) transientDiscoveryCandidate = null;
        });
        if (!script && existing != null) passwordBox.setHint("Leave blank to keep the saved password unchanged");
        if (script) styleScriptEditorButtons(dialog, existing != null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String alias = nameBox.getText().toString(); String endpoint = hostBox.getText().toString();
            if (script) {
                scriptNameLayout.setError(null);
                scriptCommandLayout.setError(null);
                if (alias.trim().isEmpty()) { scriptNameLayout.setError("Enter a script name"); return; }
                if (endpoint.trim().isEmpty()) { scriptCommandLayout.setError("Enter an SSH command"); return; }
                mutateScript(existing == null ? null : existing.getUsername(), alias, endpoint, false);
            } else {
                String user = userBox.getText().toString(); String port = portBox.getText().toString();
                if (!net.droopia.xmouse_ng.connection.ComputerDraft.validUsername(user)) {
                    userBox.setError("Enter a username without leading or trailing whitespace"); return;
                }
                EndpointLocator.DiscoveredService serviceLocator = serviceLocatorForSave(existing, candidate, endpoint);
                if (serviceLocator == null ? !validHostInput(hostBox, portBox) : !validPortInput(portBox)) return;
                byte[] password = existing != null && passwordBox.length() == 0 ? null : passwordBox.getText().toString().getBytes(StandardCharsets.UTF_8);
                saveHost(dialog, token, existing, alias, endpoint, user, port, password, serviceLocator);
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            if (existing == null) { dialog.dismiss(); return; }
            if (script) {
                AlertDialog confirmation = new AlertDialog.Builder(this)
                        .setTitle(R.string.ng_script_delete_title)
                        .setMessage(getString(R.string.ng_script_delete_message, existing.getAlias()))
                        .setNegativeButton(R.string.ng_script_manager_cancel, null)
                        .setPositiveButton(R.string.ng_script_manager_delete, (confirm, which) -> {
                            if (current(token) && dialog.isShowing()) mutateScript(existing.getUsername(), null, null, true);
                        }).create();
                confirmation.show();
                styleScriptConfirmationButtons(confirmation);
            } else new AlertDialog.Builder(this).setTitle("Delete computer?")
                    .setMessage("Remove this saved computer? Protected credentials and active control sessions are not reset.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (confirmation, which) -> {
                        if (!current(token) || !dialog.isShowing()) return;
                        credentials.execute(() -> {
                        try {
                            if (!credentials.isWritable() && !credentials.prepare()) throw new net.droopia.xmouse_ng.secrets.CredentialRepositoryException();
                            credentials.deleteHost(existing.getCredentialHost());
                            runOnUiThread(() -> { if (current(token)) { dialog.dismiss(); loadCurrentHostList(); } });
                        } catch (Exception ignored) { runOnUiThread(() -> { if (current(token)) failure(); }); }
                    }); }).show();
        });
    }

    @VisibleForTesting
    void styleScriptEditorButtons(AlertDialog dialog, boolean editingExisting) {
        styleScriptDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                R.color.ng_accent, R.color.ng_accent, R.color.ng_on_accent);
        styleScriptDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                editingExisting ? R.color.ng_danger_soft : R.color.ng_surface,
                editingExisting ? R.color.ng_danger : R.color.ng_border,
                editingExisting ? R.color.ng_danger : R.color.ng_text_primary);
        if (editingExisting) {
            styleScriptDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
                    R.color.ng_surface, R.color.ng_border, R.color.ng_text_primary);
        }
    }

    private void styleScriptConfirmationButtons(AlertDialog dialog) {
        styleScriptDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                R.color.ng_danger_soft, R.color.ng_danger, R.color.ng_danger);
        styleScriptDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                R.color.ng_surface, R.color.ng_border, R.color.ng_text_primary);
    }

    private void styleScriptDialogButton(Button button, int background, int stroke, int foreground) {
        if (button == null) return;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getColor(background));
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), getColor(stroke));
        button.setBackground(shape);
        button.setTextColor(getColor(foreground));
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setElevation(0f);
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params != null && params.height > 0) {
            params.height = Math.max(params.height, dp(48));
            button.setLayoutParams(params);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Existing SQLite script CRUD, serialized without retaining this Activity or its form widgets. */
    private void mutateScript(String id, String alias, String command, boolean delete) {
        final int token = generation;
        final int editToken = scriptEditGeneration;
        final Context context = getApplicationContext();
        final WeakReference<ListEditActivity> owner = new WeakReference<>(this);
        final Handler handler = new Handler(Looper.getMainLooper());
        if (editing != null) {
            editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            editing.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }
        scriptWorker.execute(() -> {
            boolean saved = false;
            try (DatabaseHandler database = new DatabaseHandler(context)) {
                if (delete) saved = database.deleteRow(DatabaseHandler.SCRIPT_TABLE_NAME, Integer.parseInt(id)) == 1;
                else if (id != null) saved = database.updateRow(DatabaseHandler.SCRIPT_TABLE_NAME,
                        new String[]{"Alias", "Script"}, new String[]{alias, command}, id) == 1;
                else saved = database.addScript(alias, command) > 0;
            } catch (Exception ignored) { /* Never expose script text or SQLite/provider messages. */ }
            final boolean success = saved;
            handler.post(() -> {
                ListEditActivity activity = owner.get();
                if (activity == null || !activity.current(token) || activity.editing == null ||
                        editToken != activity.scriptEditGeneration) return;
                if (success) { activity.editing.dismiss(); activity.loadCurrentHostList(); }
                else {
                    Toast.makeText(activity, R.string.ng_script_save_failed, Toast.LENGTH_LONG).show();
                    activity.editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    activity.editing.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                }
            });
        });
    }

    private boolean validHostInput(EditText hostBox, EditText portBox) {
        hostBox.setError(null);
        boolean valid = true;
        try { new EndpointHost(hostBox.getText().toString()); }
        catch (IllegalArgumentException ignored) {
            hostBox.setError("Enter a valid hostname or IP address");
            valid = false;
        }
        return validPortInput(portBox) && valid;
    }

    private boolean validPortInput(EditText portBox) {
        portBox.setError(null);
        try { Endpoint.parsePort(portBox.getText().toString()); }
        catch (IllegalArgumentException ignored) {
            portBox.setError("Port must be between 1 and 65535");
            return false;
        }
        return true;
    }

    private EndpointLocator.DiscoveredService serviceLocatorForSave(
            HostItem existing, SshServiceDiscovery.Candidate candidate, String enteredEndpoint) {
        if (candidate != null && enteredEndpoint.equals(candidateEndpoint(candidate))) return candidate.getLocator();
        if (existing == null || existing.getCredentialHost() == null) return null;
        CredentialHost host = existing.getCredentialHost();
        EndpointLocator.DiscoveredService saved = host.getServiceLocator();
        return saved != null && enteredEndpoint.equals(host.editableEndpoint()) ? saved : null;
    }

    private void saveHost(AlertDialog dialog, int token, HostItem existing, String alias,
                          String endpoint, String user, String port, byte[] password,
                          EndpointLocator.DiscoveredService serviceLocator) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        credentials.execute(() -> {
            boolean saved;
            try {
                if (existing != null && password == null) {
                    Endpoint candidateEndpoint = serviceLocator == null ? Endpoint.manual(endpoint, port)
                            : new Endpoint(serviceLocator, Endpoint.parsePort(port));
                    credentials.updateHostMetadata(existing.getCredentialHost(), alias, candidateEndpoint, user);
                } else if (serviceLocator == null) {
                    if (!credentials.isWritable() && !credentials.prepare()) throw new net.droopia.xmouse_ng.secrets.CredentialRepositoryException();
                    credentials.saveHost(existing == null ? null : existing.getCredentialHost(), alias, endpoint, user, port, password);
                } else {
                    if (!credentials.isWritable() && !credentials.prepare()) throw new net.droopia.xmouse_ng.secrets.CredentialRepositoryException();
                    credentials.saveDiscoveredHost(existing == null ? null : existing.getCredentialHost(),
                            alias, serviceLocator, user, port, password);
                }
                saved = true;
            }
            catch (Exception ignored) { saved = false; }
            finally { if (password != null) Arrays.fill(password, (byte) 0); }
            final boolean success = saved;
            runOnUiThread(() -> {
                if (current(token) && dialog.isShowing()) {
                    if (success) { dialog.dismiss(); loadCurrentHostList(); }
                    else { failure(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                }
            });
        });
    }
    @Override public void performCallback() { }
    @Override public void performCallback2(Integer i) {
        if (type == ActivityType.type_host) startActivity(ComputerEditorActivity.intent(this, mHostItems.get(i).getCredentialHost().getId()));
        else xMouseManage(i);
    }
}
