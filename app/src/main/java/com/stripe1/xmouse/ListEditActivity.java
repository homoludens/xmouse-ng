package com.stripe1.xmouse;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.stripe1.xmouse.secrets.AndroidCredentials;
import com.stripe1.xmouse.secrets.CredentialHost;
import com.stripe1.xmouse.secrets.CredentialRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class ListEditActivity extends AppCompatActivity implements MyInterface {
    private ListView mList;
    private List<HostItem> mHostItems = new LinkedList<>();
    public enum ActivityType { type_host, type_script }
    private ActivityType type;
    private HostListItemAdapter adapter;
    private LayoutInflater layoutInflater;
    private DatabaseHandler database;
    private CredentialRepository credentials;
    private int generation;
    private AlertDialog editing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_edit);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        layoutInflater = getLayoutInflater();
        database = new DatabaseHandler(getApplicationContext());
        credentials = AndroidCredentials.get(this);
        type = getIntent().getIntExtra("intVariableType", 0) == 0 ? ActivityType.type_host : ActivityType.type_script;
        setTitle(type == ActivityType.type_host ? "Manage Hosts" : "Manage Scripts");
        ((FloatingActionButton) findViewById(R.id.fab)).setOnClickListener(view -> xMouseManage(null));
        mList = findViewById(R.id.myListView);
    }

    @Override protected void onStart() { super.onStart(); loadCurrentHostList(); }
    @Override protected void onStop() { generation++; if (editing != null) editing.dismiss(); super.onStop(); }
    @Override protected void onDestroy() { database.close(); super.onDestroy(); }
    private boolean current(int token) { return token == generation && !isFinishing() && !isDestroyed(); }
    private void failure() { Toast.makeText(this, CredentialRepository.RECOVERY_MESSAGE, Toast.LENGTH_LONG).show(); }

    public void loadCurrentHostList() {
        if (type == ActivityType.type_host) {
            final int token = generation;
            credentials.execute(() -> {
                boolean ready = credentials.prepare();
                List<HostItem> items = new LinkedList<>();
                try {
                    for (CredentialHost host : credentials.hosts()) {
                        HostItem item = new HostItem();
                        item.setCredentialHost(host); item.setAlias(host.getAlias()); item.setIP(host.getHost());
                        item.setUsername(host.getUser()); item.setPort(host.getPort()); item.setDbId((int) host.getId());
                        items.add(item);
                    }
                    runOnUiThread(() -> { if (current(token)) { showItems(items); if (!ready) failure(); } });
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
        final boolean script = type == ActivityType.type_script;
        final HostItem existing = pos == null ? null : mHostItems.get(pos);
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
            nameBox.setText(existing.getAlias()); hostBox.setText(existing.getIP());
            if (!script) { userBox.setText(existing.getUsername()); portBox.setText(existing.getPort()); }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setView(layout)
                .setTitle((existing == null ? "New " : "Edit ") + (script ? "Script" : "Host"))
                .setPositiveButton("Save", null).setNegativeButton(existing == null ? "Cancel" : "Delete", null);
        if (existing != null) builder.setNeutralButton("Cancel", null);
        AlertDialog dialog = builder.create();
        editing = dialog;
        dialog.show();
        final int token = generation;
        dialog.setOnDismissListener(ignored -> { if (passwordBox != null) passwordBox.getText().clear(); if (editing == dialog) editing = null; });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(script);
        if (!script) {
            credentials.execute(() -> {
                byte[] password = null;
                try {
                    if (!credentials.prepare()) throw new com.stripe1.xmouse.secrets.CredentialRepositoryException();
                    password = existing == null ? new byte[0] : credentials.readPassword(existing.getCredentialHost());
                    final byte[] loaded = password;
                    runOnUiThread(() -> {
                        try {
                            if (current(token) && dialog.isShowing()) {
                                passwordBox.setText(new String(loaded, StandardCharsets.UTF_8));
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            }
                        } finally { Arrays.fill(loaded, (byte) 0); }
                    });
                } catch (Exception ignored) {
                    if (password != null) Arrays.fill(password, (byte) 0);
                    runOnUiThread(() -> { if (current(token) && dialog.isShowing()) failure(); });
                }
            });
        }
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
                byte[] password = passwordBox.getText().toString().getBytes(StandardCharsets.UTF_8);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                credentials.execute(() -> {
                    boolean saved;
                    try { credentials.saveHost(existing == null ? null : existing.getCredentialHost(), alias, endpoint, user, port, password); saved = true; }
                    catch (Exception ignored) { saved = false; }
                    finally { Arrays.fill(password, (byte) 0); }
                    final boolean success = saved;
                    runOnUiThread(() -> {
                        if (current(token) && dialog.isShowing()) {
                            if (success) { dialog.dismiss(); loadCurrentHostList(); }
                            else { failure(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                        }
                    });
                });
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            if (existing == null) { dialog.dismiss(); return; }
            if (script) { database.deleteRow(DatabaseHandler.SCRIPT_TABLE_NAME, Integer.parseInt(existing.getUsername())); dialog.dismiss(); loadCurrentHostList(); }
            else credentials.execute(() -> {
                try { credentials.deleteHost(existing.getCredentialHost()); runOnUiThread(() -> { if (current(token)) { dialog.dismiss(); loadCurrentHostList(); } }); }
                catch (Exception ignored) { runOnUiThread(() -> { if (current(token)) failure(); }); }
            });
        });
    }
    @Override public void performCallback() { }
    @Override public void performCallback2(Integer i) { xMouseManage(i); }
}
