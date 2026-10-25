package com.stripe1.xmouse;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
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
import com.stripe1.xmouse.connection.ConnectionFailure;
import com.stripe1.xmouse.connection.Endpoint;
import com.stripe1.xmouse.connection.EndpointHost;
import com.stripe1.xmouse.connection.EndpointLocator;
import com.stripe1.xmouse.discovery.SshServiceDiscovery;
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator;
import com.stripe1.xmouse.secrets.AndroidCredentials;
import com.stripe1.xmouse.secrets.CredentialHost;
import com.stripe1.xmouse.secrets.CredentialRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        localNetworkPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::onLocalNetworkPermissionResult);
        setContentView(R.layout.activity_list_edit);
        SystemWindowInsets.applyTo(getWindow(), findViewById(R.id.list_edit_root));
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        layoutInflater = getLayoutInflater();
        database = new DatabaseHandler(getApplicationContext());
        credentials = AndroidCredentials.get(this);
        type = getIntent().getIntExtra("intVariableType", 0) == 0 ? ActivityType.type_host : ActivityType.type_script;
        setTitle(type == ActivityType.type_host ? "Computers" : "Manage Scripts");
        ((FloatingActionButton) findViewById(R.id.fab)).setOnClickListener(view -> {
            if (type == ActivityType.type_host) startActivity(ComputerEditorActivity.intent(this, 0));
            else xMouseManage(null);
        });
        mList = findViewById(R.id.myListView);
        findComputersButton = findViewById(R.id.find_computers);
        findComputersButton.setVisibility(type == ActivityType.type_host ? View.VISIBLE : View.GONE);
        findComputersButton.setOnClickListener(view -> {
            if (type == ActivityType.type_host) showFindComputers();
        });
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
    @Override protected void onDestroy() { database.close(); super.onDestroy(); }
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
            List<HostItem> items = new LinkedList<>();
            ArrayList<ArrayList<String>> rows = database.listAll(DatabaseHandler.SCRIPT_TABLE_NAME, new String[]{"Alias", "Script", "id"});
            for (int i = rows.size() - 1; i >= 0; i--) {
                ArrayList<String> row = rows.get(i);
                HostItem item = new HostItem(); item.setAlias(row.get(0)); item.setIP(row.get(1)); item.setUsername(row.get(2)); items.add(item);
            }
            showItems(items);
        }
    }

    private void showItems(List<HostItem> items) {
        mHostItems = items;
        adapter = new HostListItemAdapter(this, items, this, type);
        mList.setAdapter(adapter);
    }

    public void xMouseManage(final Integer pos) {
        xMouseManage(pos, null);
    }

    private void xMouseManage(final Integer pos, SshServiceDiscovery.Candidate candidate) {
        final boolean script = type == ActivityType.type_script;
        final HostItem existing = pos == null ? null : mHostItems.get(pos);
        transientDiscoveryCandidate = candidate;
        final View layout = layoutInflater.inflate(script ? R.layout.manage_script_item : R.layout.manage_host_item, null);
        final EditText nameBox = layout.findViewById(script ? R.id.new_script_alias : R.id.new_host_alias);
        final EditText hostBox = layout.findViewById(script ? R.id.new_script_command : R.id.new_host_ip);
        final EditText userBox = script ? null : layout.findViewById(R.id.new_host_username);
        final EditText portBox = script ? null : layout.findViewById(R.id.new_host_port);
        final EditText passwordBox = script ? null : layout.findViewById(R.id.new_host_password);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setView(layout)
                .setTitle((existing == null ? "New " : "Edit ") + (script ? "Script" : "Host"))
                .setPositiveButton("Save", null).setNegativeButton(existing == null ? "Cancel" : "Delete", null);
        if (existing != null) builder.setNeutralButton("Cancel", null);
        AlertDialog dialog = builder.create();
        editing = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        final int token = generation;
        dialog.setOnDismissListener(ignored -> {
            if (passwordBox != null) passwordBox.getText().clear();
            if (editing == dialog) editing = null;
            if (transientDiscoveryCandidate == candidate) transientDiscoveryCandidate = null;
        });
        if (!script && existing != null) passwordBox.setHint("Leave blank to keep the saved password unchanged");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String alias = nameBox.getText().toString(); String endpoint = hostBox.getText().toString();
            if (script) {
                if (existing != null) database.updateRow(DatabaseHandler.SCRIPT_TABLE_NAME, new String[]{"Alias", "Script"}, new String[]{alias, endpoint}, existing.getUsername());
                else {
                    ArrayList<ArrayList<String>> values = new ArrayList<>();
                    values.add(new ArrayList<>(Arrays.asList("Alias", alias))); values.add(new ArrayList<>(Arrays.asList("Script", endpoint)));
                    database.addRow(DatabaseHandler.SCRIPT_TABLE_NAME, values);
                }
                dialog.dismiss(); loadCurrentHostList();
            } else {
                String user = userBox.getText().toString(); String port = portBox.getText().toString();
                if (!com.stripe1.xmouse.connection.ComputerDraft.validUsername(user)) {
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
            if (script) { database.deleteRow(DatabaseHandler.SCRIPT_TABLE_NAME, Integer.parseInt(existing.getUsername())); dialog.dismiss(); loadCurrentHostList(); }
            else new AlertDialog.Builder(this).setTitle("Delete computer?")
                    .setMessage("Remove this saved computer? Protected credentials and active control sessions are not reset.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (confirmation, which) -> {
                        if (!current(token) || !dialog.isShowing()) return;
                        credentials.execute(() -> {
                        try {
                            if (!credentials.isWritable() && !credentials.prepare()) throw new com.stripe1.xmouse.secrets.CredentialRepositoryException();
                            credentials.deleteHost(existing.getCredentialHost());
                            runOnUiThread(() -> { if (current(token)) { dialog.dismiss(); loadCurrentHostList(); } });
                        } catch (Exception ignored) { runOnUiThread(() -> { if (current(token)) failure(); }); }
                    }); }).show();
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
                    if (!credentials.isWritable() && !credentials.prepare()) throw new com.stripe1.xmouse.secrets.CredentialRepositoryException();
                    credentials.saveHost(existing == null ? null : existing.getCredentialHost(), alias, endpoint, user, port, password);
                } else {
                    if (!credentials.isWritable() && !credentials.prepare()) throw new com.stripe1.xmouse.secrets.CredentialRepositoryException();
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
