package com.stripe1.xmouse;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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

        HostItem record = new HostItem();
        record.setAlias("fixture alias");
        record.setIP(script ? "printf fixture" : "fixture.example");
        record.setUsername("fixture-user");
        record.setPort("2222");
        record.setPassword("synthetic-test-password");
        Field typeField = accessibleField("type");
        Field inflaterField = accessibleField("layoutInflater");
        Field itemsField = accessibleField("mHostItems");
        Object previousInflater = inflaterField.get(null);
        Object previousItems = itemsField.get(null);
        typeField.set(activity, type);
        inflaterField.set(null, inflater);
        itemsField.set(null, Collections.singletonList(record));

        try (MockedConstruction<AlertDialog.Builder> builders = mockConstruction(
                AlertDialog.Builder.class,
                (builder, context) -> when(builder.create()).thenReturn(dialog))) {
            activity.xMouseManage(editing ? 0 : null);

            assertEquals(1, builders.constructed().size());
            AlertDialog.Builder builder = builders.constructed().get(0);
            verify(builder).setView(layout);
            verify(builder).setTitle((editing ? "Edit " : "New ") + (script ? "Script" : "Host"));
            verify(dialog).show();
            verify(inflater).inflate(layoutId, null);
            verifyNoMoreInteractions(inflater);
            for (Integer id : fields.keySet()) {
                verify(layout).findViewById(id);
            }
            verifyNoMoreInteractions(layout);

            if (editing) {
                verify(alias).setText(record.getAlias());
                verify(endpointOrCommand).setText(record.getIP());
                if (!script) {
                    verify(username).setText(record.getUsername());
                    verify(port).setText(record.getPort());
                    verify(password).setText(record.getPassword());
                }
            } else {
                verifyNoInteractions(alias, endpointOrCommand, username, port, password);
            }
            if (script) {
                verifyNoInteractions(username, port, password);
            }
        } finally {
            inflaterField.set(null, previousInflater);
            itemsField.set(null, previousItems);
        }
    }

    private static Field accessibleField(String name) throws NoSuchFieldException {
        Field field = ListEditActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
