package net.droopia.xmouse_ng;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.text.Editable;
import android.widget.EditText;
import android.widget.Button;
import com.google.android.material.textfield.TextInputLayout;
import net.droopia.xmouse_ng.connection.EndpointLocator;
import net.droopia.xmouse_ng.discovery.SshServiceDiscovery;
import net.droopia.xmouse_ng.secrets.CredentialRepository;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;

@RunWith(DataProviderRunner.class)
public class ListEditActivityDialogTest {
    @DataProvider
    public static Object[][] dialogCases() {
        return new Object[][]{
                {ListEditActivity.ActivityType.type_host, false},
                {ListEditActivity.ActivityType.type_host, true},
                {ListEditActivity.ActivityType.type_script, false},
                {ListEditActivity.ActivityType.type_script, true},
        };
    }

    @Test
    @UseDataProvider("dialogCases")
    public void bindsOnlySelectedLayoutAndPrefillsExistingRecord(
            ListEditActivity.ActivityType type, boolean editing) throws Exception {
        boolean script = type == ListEditActivity.ActivityType.type_script;
        ListEditActivity activity = mock(ListEditActivity.class, CALLS_REAL_METHODS);
        LayoutInflater inflater = mock(LayoutInflater.class);
        View layout = mock(View.class);
        AlertDialog dialog = mock(AlertDialog.class);
        EditText alias = mock(EditText.class);
        EditText endpointOrCommand = mock(EditText.class);
        EditText username = mock(EditText.class);
        EditText port = mock(EditText.class);
        EditText password = mock(EditText.class);
        TextInputLayout scriptNameLayout = script ? mock(TextInputLayout.class) : null;
        TextInputLayout scriptCommandLayout = script ? mock(TextInputLayout.class) : null;
        Button positive = mock(Button.class);
        Button negative = mock(Button.class);
        when(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).thenReturn(positive);
        when(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)).thenReturn(negative);
        if (script) {
            doNothing().when(activity).styleScriptEditorButtons(any(AlertDialog.class), anyBoolean());
            doReturn("Add script").when(activity).getString(R.string.ng_script_manager_add_title);
            doReturn("Edit script").when(activity).getString(R.string.ng_script_manager_edit_title);
            doReturn("Cancel").when(activity).getString(R.string.ng_script_manager_cancel);
            doReturn("Save").when(activity).getString(R.string.ng_script_manager_save);
            doReturn("Delete").when(activity).getString(R.string.ng_script_manager_delete);
        }
        int layoutId = script ? R.layout.manage_script_item : R.layout.manage_host_item;
        when(inflater.inflate(layoutId, null)).thenReturn(layout);
        Map<Integer, EditText> fields = new LinkedHashMap<>();
        fields.put(script ? R.id.new_script_alias : R.id.new_host_alias, alias);
        fields.put(script ? R.id.new_script_command : R.id.new_host_ip, endpointOrCommand);
        if (!script) {
            fields.put(R.id.new_host_username, username);
            fields.put(R.id.new_host_port, port);
            fields.put(R.id.new_host_password, password);
        }
        for (Map.Entry<Integer, EditText> field : fields.entrySet()) {
            when(layout.findViewById(field.getKey())).thenReturn(field.getValue());
        }
        if (script) {
            when(layout.findViewById(R.id.new_script_alias_layout)).thenReturn(scriptNameLayout);
            when(layout.findViewById(R.id.new_script_command_layout)).thenReturn(scriptCommandLayout);
        }

        HostItem record = new HostItem();
        record.setAlias("fixture alias");
        record.setIP(script ? "printf fixture" : "fixture.example");
        record.setUsername("fixture-user");
        record.setPort("2222");
        Field typeField = accessibleField("type");
        Field inflaterField = accessibleField("layoutInflater");
        Field itemsField = accessibleField("mHostItems");
        typeField.set(activity, type);
        inflaterField.set(activity, inflater);
        itemsField.set(activity, Collections.singletonList(record));
        CredentialRepository repository = mock(CredentialRepository.class);
        accessibleField("credentials").set(activity, repository);

        try (MockedConstruction<AlertDialog.Builder> builders = mockConstruction(
                AlertDialog.Builder.class,
                withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.create()).thenReturn(dialog))) {
            activity.xMouseManage(editing ? 0 : null);

            assertEquals(1, builders.constructed().size());
            AlertDialog.Builder builder = builders.constructed().get(0);
            verify(builder).setView(layout);
            verify(builder).setTitle(script ? (editing ? "Edit script" : "Add script")
                    : (editing ? "Edit Host" : "New Host"));
            verify(builder).setPositiveButton("Save", null);
            verify(builder).setNegativeButton(editing ? "Delete" : "Cancel", null);
            if (editing) verify(builder).setNeutralButton("Cancel", null);
            else verify(builder, never()).setNeutralButton("Cancel", null);
            verify(dialog).show();
            verify(inflater).inflate(layoutId, null);
            verifyNoMoreInteractions(inflater);
            for (Integer id : fields.keySet()) {
                verify(layout).findViewById(id);
            }
            if (script) {
                verify(layout).findViewById(R.id.new_script_alias_layout);
                verify(layout).findViewById(R.id.new_script_command_layout);
            }
            verifyNoMoreInteractions(layout);

            if (editing) {
                verify(alias).setText(record.getAlias());
                verify(endpointOrCommand).setText(record.getIP());
                if (!script) {
                    verify(username).setText(record.getUsername());
                    verify(port).setText(record.getPort());
                    // Only metadata is prefilled; no plaintext is read into the form or list model.
                    verify(password).setSaveEnabled(false);
                }
            } else {
                verifyNoInteractions(alias);
                if (!script) {
                    verifyNoInteractions(endpointOrCommand, username, port);
                    verify(password).setSaveEnabled(false);
                }
            }
            if (script) {
                verifyNoInteractions(username, port, password);
                verify(endpointOrCommand).setSaveEnabled(false);
                verify(endpointOrCommand).setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            }
            if (!script && !editing) verifyNoInteractions(repository);
        }
    }

    @Test
    public void discoveredProfileIsNotPersistedUntilExplicitSaveAndSaveRetainsItsLocator() throws Exception {
        ListEditActivity activity = mock(ListEditActivity.class, CALLS_REAL_METHODS);
        LayoutInflater inflater = mock(LayoutInflater.class);
        View layout = mock(View.class);
        AlertDialog dialog = mock(AlertDialog.class);
        EditText alias = mock(EditText.class);
        EditText endpoint = mock(EditText.class);
        EditText username = mock(EditText.class);
        EditText port = mock(EditText.class);
        EditText password = mock(EditText.class);
        Button positive = mock(Button.class);
        Button negative = mock(Button.class);
        when(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).thenReturn(positive);
        when(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)).thenReturn(negative);
        when(dialog.isShowing()).thenReturn(false);
        when(inflater.inflate(R.layout.manage_host_item, null)).thenReturn(layout);
        when(layout.findViewById(R.id.new_host_alias)).thenReturn(alias);
        when(layout.findViewById(R.id.new_host_ip)).thenReturn(endpoint);
        when(layout.findViewById(R.id.new_host_username)).thenReturn(username);
        when(layout.findViewById(R.id.new_host_port)).thenReturn(port);
        when(layout.findViewById(R.id.new_host_password)).thenReturn(password);
        Editable aliasText = editable("Office workstation");
        Editable endpointText = editable("192.0.2.60");
        Editable usernameText = editable("fixture-user");
        Editable portText = editable("2222");
        Editable passwordText = editable("NOT_REAL_fixture");
        when(alias.getText()).thenReturn(aliasText);
        when(endpoint.getText()).thenReturn(endpointText);
        when(username.getText()).thenReturn(usernameText);
        when(port.getText()).thenReturn(portText);
        when(password.getText()).thenReturn(passwordText);

        CredentialRepository repository = mock(CredentialRepository.class);
        doReturn(true).when(repository).isWritable();
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(repository).execute(any(Runnable.class));
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(activity).runOnUiThread(any(Runnable.class));
        doReturn(false).when(activity).isFinishing();
        doReturn(false).when(activity).isDestroyed();

        EndpointLocator.DiscoveredService locator = new EndpointLocator.DiscoveredService(
                "Office workstation", "_ssh._tcp", "local");
        SshServiceDiscovery.Candidate candidate = new SshServiceDiscovery.Candidate(
                new SshServiceDiscovery.Identity("wifi-1", "office workstation", "_ssh._tcp", "local"),
                locator, 2222, Collections.singletonList(InetAddress.getByName("192.0.2.60")));
        accessibleField("type").set(activity, ListEditActivity.ActivityType.type_host);
        accessibleField("layoutInflater").set(activity, inflater);
        accessibleField("credentials").set(activity, repository);

        try (MockedConstruction<AlertDialog.Builder> builders = mockConstruction(
                AlertDialog.Builder.class,
                withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.create()).thenReturn(dialog))) {
            java.lang.reflect.Method manage = ListEditActivity.class.getDeclaredMethod(
                    "xMouseManage", Integer.class, SshServiceDiscovery.Candidate.class);
            manage.setAccessible(true);
            manage.invoke(activity, null, candidate);

            // Selecting a result and opening the form are transient; only the Save listener writes.
            verifyNoInteractions(repository);
            ArgumentCaptor<View.OnClickListener> save = ArgumentCaptor.forClass(View.OnClickListener.class);
            verify(positive).setOnClickListener(save.capture());
            save.getValue().onClick(mock(View.class));

            verify(repository).saveDiscoveredHost(isNull(), eq("Office workstation"), eq(locator),
                    eq("fixture-user"), eq("2222"), any(byte[].class));
            verify(repository, never()).saveHost(any(), any(String.class), any(String.class), any(String.class),
                    any(String.class), any(byte[].class));
        }
    }

    private static Editable editable(String value) {
        Editable editable = mock(Editable.class);
        when(editable.toString()).thenReturn(value);
        return editable;
    }

    private static Field accessibleField(String name) throws NoSuchFieldException {
        Field field = ListEditActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
