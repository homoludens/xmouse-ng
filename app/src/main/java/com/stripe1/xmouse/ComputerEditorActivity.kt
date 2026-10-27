package com.stripe1.xmouse

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout
import com.stripe1.xmouse.connection.*
import com.stripe1.xmouse.discovery.SshServiceDiscovery
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import com.stripe1.xmouse.secrets.*
import com.stripe1.xmouse.session.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Full-height native form. Passwords are never populated from storage or bundled on recreation. */
class ComputerEditorActivity : AppCompatActivity(), ConnectionProbe.Listener, SessionInteractions.Listener {
    private lateinit var repository: CredentialRepository
    private lateinit var probe: AndroidComputerProbe
    private lateinit var credentialUi: CredentialUi
    private val main = Handler(Looper.getMainLooper())
    private var existing: CredentialHost? = null
    private var locator: EndpointLocator.DiscoveredService? = null
    private var originalEndpoint = ""
    private var loaded = false
    private var active = false
    private var saving = false
    private var operation = AtomicBoolean(true)
    private var pendingPermission = false
    private var permissionInFlight = false
    private var stoppedTest = false
    private var prompt: SessionInteractions.Prompt? = null
    private var trustDialog: AlertDialog? = null
    private var deleteDialog: AlertDialog? = null
    private var restored: Bundle? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionInFlight = false
        if (active && pendingPermission) {
            pendingPermission = false
            if (granted) testConnection() else status("Local network permission denied. Allow access in Android settings, then Retry.")
        }
        if (active) controls()
    }
    private fun field(id: Int) = findViewById<EditText>(id)
    private fun box(id: Int) = findViewById<TextInputLayout>(id)
    private fun text(id: Int) = field(id).text.toString()
    private fun status(message: String) { findViewById<TextView>(R.id.computer_test_status).text = message }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_computer_editor)
        SystemWindowInsets.applyTo(window, findViewById(R.id.computer_editor_root))
        setSupportActionBar(findViewById<Toolbar>(R.id.computer_editor_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(if (intent.hasExtra(HOST_ID)) R.string.computer_editor_edit_title
            else R.string.computer_editor_add_title)
        repository = AndroidCredentials.get(this)
        probe = AndroidComputerProbe(this)
        credentialUi = CredentialUi(this)
        restored = state
        locator = intent.getStringExtra(SERVICE_NAME)?.let {
            EndpointLocator.DiscoveredService(it, intent.getStringExtra(SERVICE_TYPE)!!,
                intent.getStringExtra(SERVICE_DOMAIN)!!, intent.getStringExtra(STABLE_HOST)?.let(::EndpointHost))
        }
        findViewById<View>(R.id.computer_advanced).setOnClickListener {
            val port = findViewById<View>(R.id.computer_port_field)
            setAdvancedVisible(port.visibility != View.VISIBLE)
        }
        findViewById<View>(R.id.computer_save).setOnClickListener { save() }
        findViewById<View>(R.id.computer_test).setOnClickListener {
            if (probe.owner.busy || pendingPermission) cancelTest() else testConnection()
        }
        findViewById<View>(R.id.computer_delete).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.computer_auth_settings).setOnClickListener {
            cancelTest(); startActivity(Intent(this, NativeSettingsActivity::class.java))
        }
        for (id in FORM_FIELDS) field(id).doAfterTextChanged {
            if (loaded && (probe.owner.busy || pendingPermission)) cancelTest()
        }
        controls()
    }

    override fun onStart() {
        super.onStart(); active = true; operation = AtomicBoolean(true)
        probe.owner.attach(this); probe.interactions.attach(this); credentialUi.start()
        renderAuth()
        if (!loaded) load()
        if (stoppedTest) { stoppedTest = false; status("Test cancelled when this screen left the foreground. Retry when ready.") }
        controls()
    }
    override fun onStop() {
        stoppedTest = probe.owner.busy || pendingPermission
        active = false; operation.set(false); pendingPermission = false
        probe.stop(); credentialUi.stop()
        trustDialog?.dismiss(); trustDialog = null; deleteDialog?.dismiss(); deleteDialog = null
        field(R.id.new_host_password).text.clear()
        saving = false
        super.onStop()
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun load() {
        val repo = repository; val weak = WeakReference(this); val live = operation; val handler = main
        val id = intent.getLongExtra(HOST_ID, -1)
        repo.execute {
            val host = try { if (id == -1L) null else repo.hosts().single { it.id == id } }
                catch (_: Exception) { handler.post { weak.get()?.takeIf { live.get() }?.status("Computer unavailable. Close and reopen Computers to retry.") }; return@execute }
            handler.post {
                val screen = weak.get()?.takeIf { live.get() } ?: return@post
                screen.populate(host)
            }
        }
    }

    private fun populate(host: CredentialHost?) {
        existing = host
        if (host != null) locator = host.serviceLocator
        originalEndpoint = host?.editableEndpoint() ?: locator?.stableTargetHostname?.original.orEmpty()
        val defaults = mapOf(R.id.new_host_alias to (host?.alias ?: intent.getStringExtra(SERVICE_NAME).orEmpty()),
            R.id.new_host_ip to originalEndpoint, R.id.new_host_username to host?.user.orEmpty(),
            R.id.new_host_port to (host?.port ?: intent.getStringExtra(PORT) ?: "22"))
        defaults.forEach { (id, value) -> field(id).setText(restored?.getString("field_$id") ?: value) }
        setAdvancedVisible(restored?.getBoolean("advanced") == true)
        restored = null
        val hasSavedPassword = host?.passwordReference != null || host?.legacyPasswordPresent == true
        field(R.id.new_host_password).hint = getString(if (hasSavedPassword)
            R.string.computer_editor_password_saved_hint else R.string.computer_editor_password_new_hint)
        box(R.id.computer_password_field).helperText = when {
            host == null -> "Used only with global password authentication."
            host.credentialReentryRequired -> "Leave blank to keep the backup's password re-entry requirement."
            hasSavedPassword -> "Leave blank to keep the saved password unchanged."
            else -> "Leave blank to keep password authentication unset."
        }
        locator?.let { box(R.id.computer_host_field).helperText = "Discovered service: ${it.instanceName}. Keep this field unchanged to resolve the service afresh." }
        findViewById<View>(R.id.computer_delete).visibility = if (host == null) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.computer_save).setText(if (host == null)
            R.string.computer_editor_save_new else R.string.computer_editor_save_edit)
        loaded = true; controls()
    }

    private fun setAdvancedVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.computer_port_field).visibility = visibility
        findViewById<View>(R.id.computer_port_label).visibility = visibility
        findViewById<View>(R.id.computer_port_help).visibility = visibility
        findViewById<Button>(R.id.computer_advanced).apply {
            isSelected = visible
            setText(if (visible) R.string.computer_editor_advanced_hide else R.string.computer_editor_advanced)
            contentDescription = getString(if (visible) R.string.computer_editor_advanced_hide
                else R.string.computer_editor_advanced)
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        // Explicit allow-list: no password, attempt, request, material, or capability is serialized.
        for (id in FORM_FIELDS.filter { it != R.id.new_host_password }) out.putString("field_$id", text(id))
        out.putBoolean("advanced", findViewById<View>(R.id.computer_port_field).visibility == View.VISIBLE)
        super.onSaveInstanceState(out)
    }

    private fun useKey() = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_usekeyauth", false)
    private fun renderAuth() {
        findViewById<TextView>(R.id.computer_auth_description).text = if (useKey())
            "Global authentication: SSH private key and optional passphrase from Settings. This computer has no separate key mode; the password below is not used by Test in key mode."
        else "Global authentication: password. Private key and passphrase options apply to all computers and can be changed in Settings."
    }

    private fun candidate(): CredentialHost? {
        var valid = true
        box(R.id.computer_user_field).error = null; box(R.id.computer_host_field).error = null; box(R.id.computer_port_field).error = null
        val user = text(R.id.new_host_username)
        if (!ComputerDraft.validUsername(user)) { box(R.id.computer_user_field).error = "Enter a username without leading or trailing whitespace"; valid = false }
        val port = try { Endpoint.parsePort(text(R.id.new_host_port)) } catch (_: IllegalArgumentException) {
            setAdvancedVisible(true)
            box(R.id.computer_port_field).error = "Port must be between 1 and 65535"; valid = false; 22
        }
        val entered = text(R.id.new_host_ip)
        val service = locator?.takeIf { entered == originalEndpoint }
        val endpoint = try { if (service == null) Endpoint.manual(entered, port) else Endpoint(service, port) }
        catch (_: IllegalArgumentException) { box(R.id.computer_host_field).error = "Enter a valid hostname or IP address"; valid = false; null }
        if (!valid || endpoint == null) return null
        val old = existing
        return CredentialHost(old?.id ?: 0, text(R.id.new_host_alias),
            if (service == null) entered else service.stableTargetHostname?.original, user, port.toString(),
            old?.passwordReference, old?.legacyPasswordPresent ?: false, old?.credentialReentryRequired ?: false, service)
    }

    private fun testConnection() {
        if (!active || !loaded || saving || probe.owner.busy || probe.owner.blocked) return
        val candidate = candidate() ?: return
        val gate = LocalNetworkPermissionCoordinator.forContext(applicationContext)
        if (gate.requiresRuntimePermission()) {
            if (permissionInFlight) { status("Finish the previous permission request, then Retry."); return }
            permissionInFlight = true
            pendingPermission = true; status("Allow local network access to test this computer."); controls()
            permissionRequest.launch(LocalNetworkPermissionCoordinator.PERMISSION); return
        }
        val replacement = if (existing != null && field(R.id.new_host_password).length() == 0) null
            else text(R.id.new_host_password).toByteArray(Charsets.UTF_8)
        probe.start(candidate, useKey(), replacement)
        controls()
    }
    private fun cancelTest() {
        pendingPermission = false; probe.cancel(); credentialUi.stop(); credentialUi.start()
        trustDialog?.dismiss(); trustDialog = null
        status("Test cancelled. Nothing was saved; the control session was not changed."); controls()
    }
    override fun phase(phase: ConnectionState.Phase) {
        if (!active) return
        status(when (phase) {
            ConnectionState.Phase.AWAITING_TRUST -> "Review this computer's host-key fingerprint before authentication."
            ConnectionState.Phase.CLOSING -> "Closing test connection…"
            else -> "Testing: ${phase.name.lowercase().replace('_', ' ')}…"
        }); controls()
    }
    override fun finished(failure: ConnectionFailure?, cancelled: Boolean) {
        if (!active) return
        status(if (failure != null) failureMessage(failure) else if (cancelled) "Test cancelled. Nothing was saved."
            else "SSH authentication and channel check succeeded. Test connection closed; nothing was saved.")
        controls()
    }

    private fun save() {
        if (!active || !loaded || saving || probe.owner.busy || pendingPermission) return
        val candidate = candidate() ?: return
        val old = existing
        val password = if (old != null && field(R.id.new_host_password).length() == 0) null
            else text(R.id.new_host_password).toByteArray(Charsets.UTF_8)
        write { repo ->
            try {
                if (password == null && old != null) repo.updateHostMetadata(old, candidate.alias.orEmpty(), candidate.endpoint(), candidate.user!!)
                else {
                    if (!repo.isWritable && !repo.prepare()) throw CredentialRepositoryException()
                    if (candidate.serviceLocator != null) repo.saveDiscoveredHost(old, candidate.alias.orEmpty(), candidate.serviceLocator,
                        candidate.user!!, candidate.port!!, password!!)
                    else repo.saveHost(old, candidate.alias.orEmpty(), candidate.host!!, candidate.user!!, candidate.port!!, password!!)
                }
            } finally { password?.fill(0) }
        }
    }

    private fun confirmDelete() {
        val host = existing ?: return
        val consent = operation
        cancelTest()
        deleteDialog = AlertDialog.Builder(this).setTitle("Delete computer?")
            .setMessage("Remove this saved computer? This does not disconnect an active control session or reset protected credentials.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> if (consent.get()) write {
                if (!it.isWritable && !it.prepare()) throw CredentialRepositoryException()
                it.deleteHost(host)
            } }.create()
        deleteDialog?.show()
    }
    private fun write(action: (CredentialRepository) -> Unit) {
        if (!active || !loaded || saving) return
        saving = true; controls()
        val weak = WeakReference(this); val live = operation; val handler = main; val repo = repository
        repo.execute {
            // An explicit Save may complete after leaving, but its callback cannot attach to a new form.
            val success = try { action(repo); true } catch (_: Exception) { false }
            handler.post {
                val screen = weak.get()?.takeIf { live.get() } ?: return@post
                screen.saving = false
                if (success) screen.finish() else { screen.status(CredentialRepository.RECOVERY_MESSAGE); screen.controls() }
            }
        }
    }
    private fun controls() {
        val busy = probe.owner.busy || pendingPermission
        findViewById<Button>(R.id.computer_test).apply {
            isEnabled = loaded && !saving && !probe.owner.blocked
            text = if (busy) "Cancel test" else getString(R.string.computer_editor_test)
        }
        findViewById<View>(R.id.computer_save).isEnabled = loaded && !saving && !busy
        findViewById<View>(R.id.computer_delete).isEnabled = loaded && !saving && !busy
    }

    override fun show(prompt: SessionInteractions.Prompt) {
        if (!active) return
        this.prompt = prompt
        when (prompt) {
            is SessionInteractions.Prompt.Credential -> credentialUi.request(prompt.kind, prompt.request)
            is SessionInteractions.Prompt.Trust -> {
                val request = prompt.request
                trustDialog = AlertDialog.Builder(this).setTitle("Verify computer identity")
                    .setMessage("${request.endpoint.identity}\n${request.key.algorithm}\n${request.key.fingerprint}\nApprove only after checking this fingerprint with the computer owner.")
                    .setPositiveButton("Trust this key") { _, _ -> request.accept() }
                    .setNegativeButton("Reject") { _, _ -> request.reject() }
                    .setOnCancelListener { request.cancel() }.create().also { it.show() }
            }
            is SessionInteractions.Prompt.Mismatch -> probe.interactions.invalidate()
        }
    }
    override fun dismiss(prompt: SessionInteractions.Prompt) {
        if (this.prompt !== prompt) return
        this.prompt = null
        when (prompt) {
            is SessionInteractions.Prompt.Credential -> credentialUi.dismiss(prompt.request)
            is SessionInteractions.Prompt.Trust -> { trustDialog?.dismiss(); trustDialog = null }
            else -> Unit
        }
    }
    @Deprecated("Legacy Activity result for attempt-only protected-key re-entry")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!credentialUi.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        private const val HOST_ID = "computer_id"
        private const val SERVICE_NAME = "service_name"
        private const val SERVICE_TYPE = "service_type"
        private const val SERVICE_DOMAIN = "service_domain"
        private const val STABLE_HOST = "stable_host"
        private const val PORT = "computer_port"
        private val FORM_FIELDS = intArrayOf(R.id.new_host_alias, R.id.new_host_ip, R.id.new_host_username, R.id.new_host_port, R.id.new_host_password)
        @JvmStatic fun intent(context: Context, id: Long) = Intent(context, ComputerEditorActivity::class.java).apply {
            if (id > 0) putExtra(HOST_ID, id)
        }
        @JvmStatic fun discoveredIntent(context: Context, candidate: SshServiceDiscovery.Candidate) = intent(context, 0).apply {
            putExtra(SERVICE_NAME, candidate.locator.instanceName); putExtra(SERVICE_TYPE, candidate.locator.serviceType)
            putExtra(SERVICE_DOMAIN, candidate.locator.domain); putExtra(STABLE_HOST, candidate.locator.stableTargetHostname?.original)
            putExtra(PORT, candidate.port.toString())
        }
        internal fun failureMessage(failure: ConnectionFailure): String = when (failure) {
            is ConnectionFailure.Permission -> "Local network permission denied or revoked. Allow access in Android settings, then Retry."
            is ConnectionFailure.Resolution -> "Computer lookup failed (${failure.reason.name.lowercase()}). Check the hostname/network, then Retry."
            is ConnectionFailure.Trust -> if (failure.reason == ConnectionFailure.TrustReason.KEY_MISMATCH)
                "Host key changed. Authentication was blocked. Verify the identity through the existing connection trust-review flow; no key was replaced."
                else "Host identity was not approved or trust storage was unavailable. Verify the fingerprint, then Retry."
            is ConnectionFailure.Authentication -> if (failure.reason == ConnectionFailure.AuthenticationReason.KEY_UNAVAILABLE)
                "Saved credentials or private key unavailable. Re-enter for this attempt or check global Authentication settings; nothing was reset."
                else "Authentication failed (${failure.reason.name.lowercase()}). Check credentials, then Retry."
            is ConnectionFailure.Transport -> if (failure.reason == ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED)
                "Test cleanup could not be confirmed. Further tests in this form are blocked. The control session was not changed."
                else "SSH connection failed (${failure.reason.name.lowercase()}). Check the computer/network, then Retry."
        }
    }
}
