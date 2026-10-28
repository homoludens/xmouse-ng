package net.droopia.xmouse_ng;

import net.droopia.xmouse_ng.secrets.CredentialHost;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** One metadata-only read; selection and legacy fallbacks refer to the same host snapshot. */
final class ComputerMetadataSnapshot {
    final List<CredentialHost> hosts;
    final CredentialHost selected;
    final String host;
    final String user;
    final String port;

    private ComputerMetadataSnapshot(List<CredentialHost> hosts, Map<String, ?> preferences) {
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
        long selectedId;
        try { selectedId = Long.parseLong(string(preferences, "hostPreferenceList", "0")); }
        catch (NumberFormatException ignored) { selectedId = Long.MIN_VALUE; }
        CredentialHost selected = null;
        for (CredentialHost candidate : hosts) {
            if (candidate.getId() == selectedId) { selected = candidate; break; }
        }
        this.selected = selected;
        host = selected == null ? string(preferences, "setting_host", "") : selected.getHost();
        user = selected == null ? string(preferences, "setting_user", "") : selected.getUser();
        // Display/edit metadata remains available even when a retained legacy port needs repair.
        port = selected == null ? string(preferences, "setting_port", "22") : selected.getPort();
    }

    static ComputerMetadataSnapshot load(Supplier<List<CredentialHost>> readHosts, Map<String, ?> preferences) {
        return new ComputerMetadataSnapshot(readHosts.get(), preferences);
    }

    private static String string(Map<String, ?> preferences, String key, String fallback) {
        Object value = preferences.get(key);
        return value == null ? fallback : (String) value;
    }
}
