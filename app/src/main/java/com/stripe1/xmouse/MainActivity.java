package com.stripe1.xmouse;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;

import com.stripe1.xmouse.secrets.AndroidCredentials;
import com.stripe1.xmouse.secrets.CredentialUi;
import com.stripe1.xmouse.secrets.CredentialHost;
import com.stripe1.xmouse.clipboard.ClipboardPreparation;
import com.stripe1.xmouse.clipboard.ClipboardRead;
import com.stripe1.xmouse.clipboard.ClipboardShareCoordinator;
import com.stripe1.xmouse.clipboard.PlainTextClipboardSource;
import com.stripe1.xmouse.connection.ConnectionFailure;
import com.stripe1.xmouse.connection.ConnectionState;
import com.stripe1.xmouse.session.SessionActivityPresenter;
import com.stripe1.xmouse.session.SessionCommandTransport;
import com.stripe1.xmouse.session.SessionController;
import com.stripe1.xmouse.session.GyroscopeSamplingGate;
import com.stripe1.xmouse.session.ForegroundConnectRequestResult;
import com.stripe1.xmouse.session.ForegroundRecoveryWindow;
import com.stripe1.xmouse.session.SessionForegroundService;
import com.stripe1.xmouse.session.InputGestureToken;
import com.stripe1.xmouse.session.InputSurfaceToken;
import com.stripe1.xmouse.session.SessionInteractions;
import com.stripe1.xmouse.session.SessionSnapshot;
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator;
import com.stripe1.xmouse.layout.LegacyLayoutReader;
import com.stripe1.xmouse.layout.LegacyLayoutProfileMigrator;
import com.stripe1.xmouse.layout.LayoutButton;
import com.stripe1.xmouse.layout.LayoutEditorActivity;
import com.stripe1.xmouse.layout.LayoutProfile;
import com.stripe1.xmouse.layout.LayoutProfileManagerActivity;
import com.stripe1.xmouse.layout.LayoutProfileActivation;
import com.stripe1.xmouse.layout.LayoutProfileRepository;
import com.stripe1.xmouse.layout.LayoutProfileRuntime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public class MainActivity extends AppCompatActivity implements MyInterface,
		CoolDragAndDropGridView.DragAndDropListener,
		SpanVariableGridView.OnItemClickListener,
		SpanVariableGridView.OnItemLongClickListener,
		myDialogFragment.NoticeDialogListener{
	private static final String TAG = "MainActivity";
	public static final String ACTION_OPEN_SESSION_CONTROLS = "com.stripe1.xmouse.action.OPEN_SESSION_CONTROLS";
	private static final String PREF_NOTIFICATION_PERMISSION_REQUESTED = "session_notification_permission_requested";
	private static final String PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED = "local_network_auto_connect_blocked";
	private static final String PREF_GYRO_ENABLED = "control_gyro_enabled";
	private static final String POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS";

	MenuItem conDiscButton = null;
	MenuItem clipboardSendButton = null;
	MenuItem KeyLockButton = null;
	MenuItem InvScrollButton= null;
	FragmentManager fm = getSupportFragmentManager();

	public static DatabaseHandler db;
	/** This screen's proxy carries only its opaque presentation token across the service boundary. */
	public SessionCommandTransport commandTransport;
	private ActivityResultLauncher<String> notificationPermissionLauncher;
	private ActivityResultLauncher<String> localNetworkPermissionLauncher;
	private LocalNetworkPermissionCoordinator localNetworkPermissionCoordinator;
	private boolean suppressLegacyAutoConnectOnResume;
	private String pendingForegroundStartTicket;
	private String pendingNotificationPermissionTicket;
	private String pendingLocalNetworkPermissionTicket;
	private boolean pendingHostSelectionRequest;
	private SessionForegroundService.SessionBinder sessionBinder;
	private ServiceConnection sessionServiceConnection;
	private AutoCloseable sessionSubscription;
	private SessionController.Observer sessionObserver;
	private SessionInteractions promptInteractions;
	private SessionSnapshot sessionSnapshot;
	private ForegroundRecoveryWindow foregroundRecoveryWindow;
	private SessionActivityPresenter sessionPresenter;
	private final ClipboardShareCoordinator clipboardShareCoordinator = new ClipboardShareCoordinator();
	private CredentialUi credentialUi;
	private Toolbar toolbar;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private SensorManager sensorManager;
	private Sensor gyroscope;
	private GyroscopeSamplingGate gyroSamplingGate;
	private ToggleButton gyroToggle;
	private ShellNavigation shellNavigation;
	private View shellRoot;
	private long typingFocusGeneration;
	private long computerLoadGeneration;
	private static final class ComputerEntry {
		final long id;
		final String name;
		ComputerEntry(long id, String name) { this.id = id; this.name = name; }
	}
	private List<ComputerEntry> cachedComputers = new ArrayList<>();
	private boolean externalScreenPending;
	private final java.util.Set<AlertDialog> shellDialogs = new java.util.HashSet<>();
	private MyMouseView mouseControlView;
	private MyJSView joystickControlView;
	private boolean activityResumed;
	private boolean windowFocused;
	private InputSurfaceToken controlInputSurface;
	private final Map<Integer, InputGestureToken> volumeInputGestures = new HashMap<>();
	private static final class CapturedButtonGesture {
		final InputGestureToken token;
		CapturedButtonGesture(InputGestureToken token) { this.token = token; }
	}
	private final WeakHashMap<View, CapturedButtonGesture> mouseButtonGestures = new WeakHashMap<>();
	/** A canceled pointer owns subsequent unqualified clicks until a new DOWN; accessibility is explicit. */
	private final WeakHashMap<View, Boolean> cancelledButtonTouches = new WeakHashMap<>();
	private final WeakHashMap<View, Boolean> accessibilityButtonActivations = new WeakHashMap<>();
	/** Only non-null while an owned touch UP invokes the legacy XML click callback synchronously. */
	private View capturedTouchClickView;
	private final IdentityHashMap<GyroscopeSamplingGate.Listener, SensorEventListener> sensorListeners = new IdentityHashMap<>();
	private long bindingGeneration;
	private long activityGeneration;
	private long hostSelectionGeneration;
	private long lastShownErrorGeneration = -1L;
	public IDoTool doTool;
	FrameLayout mouseSurfaceHost;
	LinearLayout keyboardLayout;

	LinearLayout jsLayout;

	public static ArrayList<ArrayList<String>> hostDBKeys;
	public static String setting_host="";
	public static String setting_user="";
	public static int setting_port=22;
	public static String setting_shell = "";
	public static float setting_sensitivity=1.5f;
	public static int setting_delay=350;
	public static int setting_mdelay=0;



	public static float setting_gyro_z_sensitivity=1.5f;
	public static float setting_gyro_y_sensitivity=1.5f;
	public static float setting_gyro_z_threshold=1.5f;
	public static float setting_gyro_y_threshold=1.5f;

	public static float setting_js_sensitivity=1.0f;
	public static float setting_js_size=0.5f;
	public static float setting_js_dead_zone=0.0f;



	boolean setting_autoconnect=false;
	private static boolean setting_keyboard_locked=false;
	public static String setting_dotool_initial="";
	public static String setting_xdotool_initial="";
	//static boolean setting_mouse_background=false;
	private static boolean setting_keyboard_batch=true;
	private boolean setting_keyboard_autoclear=true;
	public static boolean setting_use_keys = false;
	public static boolean setting_invert_scroll = false;
	//public static boolean setting_keyboard_show_details;
	//static boolean setting_command_overlay=true;
	private String setting_host_all;

	private EditText ET;
	private CustomKeyboardButtonAdapter mItemAdapter;
	private CoolDragAndDropGridView mCoolDragAndDropGridView;
	private List<CustomKeyboardButton> mItems = new LinkedList<CustomKeyboardButton>();
	private String KEYLOAYOUTFILENAME = "keyLayoutFile.csv";
	private LayoutProfileRuntime layoutProfileRuntime;
	private LayoutProfileRepository layoutProfileRepository;
	private boolean legacyLayoutFallbackReadOnly = true;
	private boolean returningFromLayoutEditor;
	private boolean returningFromLayoutManager;
	private final IdentityHashMap<CustomKeyboardButton, String> layoutButtonIds = new IdentityHashMap<>();
	//static ImageView DeleteView;
	private RelativeLayout EditKeyboardButtonsLayout;
	private LinearLayout ETLayout;
	private int potentialDeletePosition;
	private int startPosition;
	//static ListView scriptList;
	private TextView recentCmdTextView;


	private Button jsSwitch;
	private Button js_jsSwitch;


	//private ArrayList<ArrayList<String>> scriptItems = new ArrayList<ArrayList<String>>();

	@Override
	public void onDialogPositiveClickValid(
			com.stripe1.xmouse.myDialogFragment dialog, String layoutText) {
		LinkedList<CustomKeyboardButton> temp = loadKeyboardLayout(MainActivity.this,layoutText);
		if(temp!=null){
			mItems = temp;
			assignFreshLayoutButtonIds(mItems);

			mItemAdapter = new CustomKeyboardButtonAdapter(MainActivity.this, mItems);
			mCoolDragAndDropGridView.setAdapter(mItemAdapter);
			mItemAdapter.notifyDataSetChanged();
			saveKeyboardLayout();
		}
	}
	@Override
	public void onDialogNegativeClick(com.stripe1.xmouse.myDialogFragment dialog) {


	}
	public void xMouseClickMouse(View v){
		InputGestureToken gesture = takeButtonActivationGesture(v);
		Object token = gesture != null ? gesture :
				(canUseActiveSurfaceForButtonClick(v) ? activePointerInputToken() : null);
		if (!isInputTokenActive(token)) {
			if (gesture != null && commandTransport != null) commandTransport.cancelInputGesture(gesture);
			return;
		}
		IDoTool inputTool = createDoToolForInput(token);
		if (inputTool == null) return;
		int id = v.getId();
		if (id == R.id.firstMouseButton || id == R.id.js_firstMouseButton) {
			inputTool.mouseClick(IDoTool.MOUSE_LEFT);
		} else if (id == R.id.secondMouseButton || id == R.id.js_secondMouseButton) {
			inputTool.mouseClick(IDoTool.MOUSE_MIDDLE);
		} else if (id == R.id.thirdMouseButton || id == R.id.js_thirdMouseButton) {
			inputTool.mouseClick(IDoTool.MOUSE_RIGHT);
		} else if (id == R.id.fourthMouseButton || id == R.id.js_fourthMouseButton) {
			inputTool.mouseWheelUp();
		} else if (id == R.id.fifthMouseButton || id == R.id.js_fifthMouseButton) {
			inputTool.mouseWheelDown();
		}
		if (gesture != null && commandTransport != null) commandTransport.finishInputGesture(gesture);
	}

	private InputGestureToken takeButtonActivationGesture(View view) {
		if (isAccessibilityButtonActivation(view)) return null;
		CapturedButtonGesture captured = mouseButtonGestures.remove(view);
		if (capturedTouchClickView == view) {
			return captured != null && isInputTokenActive(captured.token) ? captured.token : null;
		}
		// A click not dispatched from our currently-owned UP is a fresh accessibility/programmatic
		// activation. Never let an overlapping touch lease replace its current surface capability.
		if (captured != null && commandTransport != null) commandTransport.cancelInputGesture(captured.token);
		return null;
	}

	private boolean isAccessibilityButtonActivation(View view) {
		return Boolean.TRUE.equals(accessibilityButtonActivations.get(view));
	}

	private boolean canUseActiveSurfaceForButtonClick(View view) {
		return capturedTouchClickView != view &&
				(!cancelledButtonTouches.containsKey(view) || isAccessibilityButtonActivation(view));
	}
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		conDiscButton = menu.findItem(R.id.action_conn_disc);//getItem(0);
		clipboardSendButton = menu.findItem(R.id.action_send_clipboard);
		KeyLockButton = menu.findItem(R.id.action_lock_keys);
		InvScrollButton = menu.findItem(R.id.action_invert_scroll);
		showConnectionStat();




		return true;
		//super.onCreateOptionsMenu(menu);
	}
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		suppressLegacyAutoConnectOnResume = isNotificationOpenIntent(getIntent());
		localNetworkPermissionCoordinator = LocalNetworkPermissionCoordinator.forContext(getApplicationContext());
		notificationPermissionLauncher = registerForActivityResult(
				new ActivityResultContracts.RequestPermission(), granted -> {
					onNotificationPermissionResult(granted);
				});
		localNetworkPermissionLauncher = registerForActivityResult(
				new ActivityResultContracts.RequestPermission(), granted -> onLocalNetworkPermissionResult(granted));
		setContentView(R.layout.activity_main);
		SystemWindowInsets.applyToAndConsume(getWindow(), findViewById(R.id.main_safe_area));
		toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) getSupportActionBar().setDisplayShowTitleEnabled(false);
		shellRoot = findViewById(R.id.native_shell);
		shellNavigation = new ShellNavigation(new ShellNavigation.Listener() {
			@Override public void retireInput() { deactivateControlInputSurface(); }
			@Override public void render() { renderShell(); }
			@Override public void refreshInput() { updateControlInputSurface(); }
		});
		NativeShellViews.bind(shellRoot, shellNavigation);
		if (savedInstanceState != null) {
			ArrayList<String> overlays = savedInstanceState.getStringArrayList("shell.overlays");
			shellNavigation.restore(savedInstanceState.getString("shell.destination"),
					savedInstanceState.getBoolean("shell.joystick"), overlays == null ? new ArrayList<>() : overlays);
		}
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() {
				if (shellNavigation.topOverlay() == ShellNavigation.Overlay.ACTION_EDIT && shellDialogs.isEmpty()) {
					if (mCoolDragAndDropGridView != null) mCoolDragAndDropGridView.cancelLegacyDragAndDrop();
					shellNavigation.close(ShellNavigation.Overlay.ACTION_EDIT);
					return;
				}
				if (shellNavigation.back()) return;
				setEnabled(false);
				try { getOnBackPressedDispatcher().onBackPressed(); }
				finally { setEnabled(true); }
			}
		});
		commandTransport = new SessionCommandTransport(getApplicationContext());
		credentialUi = new CredentialUi(this);
		sessionPresenter = new SessionActivityPresenter(this, credentialUi, this);

		initializeGyroscopeSampling();



		mouseSurfaceHost = findViewById(R.id.mouse_surface_host);
		mouseSurfaceHost.setClipToOutline(true);
		keyboardLayout = (LinearLayout) findViewById(R.id.keyboard);

		jsLayout = (LinearLayout) findViewById(R.id.js);


		js_jsSwitch=findViewById(R.id.js_jsToggle);

		jsSwitch=findViewById(R.id.jsToggle);

		mouseControlView = new MyMouseView(this);
		mouseSurfaceHost.addView(mouseControlView, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		//keyboardLayout.addView(new MyKeyboardView(getBaseContext(), MainActivity.this));

		joystickControlView = new MyJSView(this);
		JoystickSurfaceLayout.addTo(jsLayout, joystickControlView);
		gyroToggle = findViewById(R.id.gyroToggle);
		gyroToggle.setChecked(PreferenceManager.getDefaultSharedPreferences(this)
				.getBoolean(PREF_GYRO_ENABLED, false));
		gyroToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
			PreferenceManager.getDefaultSharedPreferences(this).edit()
					.putBoolean(PREF_GYRO_ENABLED, isChecked).apply();
			refreshGyroscopeSampling();
		});
		installMouseButtonInputCapture(R.id.firstMouseButton);
		installMouseButtonInputCapture(R.id.secondMouseButton);
		installMouseButtonInputCapture(R.id.thirdMouseButton);
		installMouseButtonInputCapture(R.id.fourthMouseButton);
		installMouseButtonInputCapture(R.id.fifthMouseButton);
		installMouseButtonInputCapture(R.id.js_firstMouseButton);
		installMouseButtonInputCapture(R.id.js_secondMouseButton);
		installMouseButtonInputCapture(R.id.js_thirdMouseButton);
		installMouseButtonInputCapture(R.id.js_fourthMouseButton);
		installMouseButtonInputCapture(R.id.js_fifthMouseButton);
		installMouseButtonInputCapture(R.id.keyboard_send);

		findViewById(R.id.header_settings).setOnClickListener(v -> openSettings());
		findViewById(R.id.connection_status).setOnClickListener(v -> showSessionControls());
		findViewById(R.id.computers_manage).setOnClickListener(v -> openLegacyManager(0));
		findViewById(R.id.computers_connect).setOnClickListener(v -> {
			if (hasActiveOrPendingSession()) disconnectForegroundSessionFromUi();
			else requestForegroundSessionFromUi();
		});
		findViewById(R.id.actions_layouts).setOnClickListener(v -> openLayoutProfileManager());
		findViewById(R.id.actions_edit).setOnClickListener(v -> openLayoutEditor());
		findViewById(R.id.actions_scripts).setOnClickListener(v -> showSavedScripts());
		bindShellKey(R.id.key_escape, "Escape");
		bindShellKey(R.id.key_switch, "alt+Tab");
		bindShellKey(R.id.key_super, "super");
		bindShellKey(R.id.touchpad_key_escape, "Escape", ShellNavigation.InputRole.POINTER);
		bindShellKey(R.id.touchpad_key_switch, "alt+Tab", ShellNavigation.InputRole.POINTER);
		bindShellKey(R.id.touchpad_key_super, "super", ShellNavigation.InputRole.POINTER);
		bindShellKey(R.id.typing_key_escape, "Escape", ShellNavigation.InputRole.TYPING);
		bindShellKey(R.id.typing_key_tab, "Tab", ShellNavigation.InputRole.TYPING);
		bindShellKey(R.id.typing_key_backspace, "Backspace", ShellNavigation.InputRole.TYPING);
		bindShellKey(R.id.typing_key_enter, "Enter", ShellNavigation.InputRole.TYPING);

		ScrollView scrollView = (ScrollView) findViewById(R.id.key_drag_scrollView);
		mCoolDragAndDropGridView = (CoolDragAndDropGridView) findViewById(R.id.key_drag_DragAndDropGridView);


		LayoutProfileActivation layoutActivation = activateLayoutProfiles();
		legacyLayoutFallbackReadOnly = layoutActivation.getReadOnlyLegacyFallback();
		if (layoutActivation.getActivated()) {
			mItems = customButtonsFromProfile(layoutActivation.getProfile());
		} else {
			mItems = loadKeyboardLayout(this,false);//new LinkedList<Item>();
			if (layoutActivation.getWarning() != null) {
				Toast.makeText(this, layoutActivation.getWarning(), Toast.LENGTH_LONG).show();
			}
		}


		mItemAdapter = new CustomKeyboardButtonAdapter(this, mItems);
		mCoolDragAndDropGridView.setAdapter(mItemAdapter);
		mCoolDragAndDropGridView.setScrollingStrategy(new SimpleScrollingStrategy(scrollView));
		mCoolDragAndDropGridView.setDragAndDropListener(this);
		mCoolDragAndDropGridView.setOnItemLongClickListener(this);
		//RelativeLayout k = (RelativeLayout) rootView.findViewById(R.id.keyboard_full_layout);
		//FrameLayout sh = (FrameLayout) rootView.findViewById(R.id.content_frame_keyboard);
		//sh.addView(keyView);
		ET = (EditText) findViewById(R.id.keyboard_input);

		// Entering/editing a shell text field is never a remote input event, regardless of the
		// retained legacy batch preference. Only the explicit Send callback owns typing capability.

		EditKeyboardButtonsLayout = (RelativeLayout) findViewById(R.id.editKeyboardButtonsLayout);
		EditKeyboardButtonsLayout.setVisibility(View.GONE);
		//DeleteView = (ImageView) findViewById(R.id.deleteButton);
		//DeleteView.setVisibility(View.GONE);
		ETLayout = (LinearLayout) findViewById(R.id.keyboard_send_layout);
		//SlidingPane SH = (SlidingPane) rootView.findViewById(R.id.slideHolder_keyboard);
		////SH.setParallaxDistance(100);
		////SH.setEnabled(false);
		//SH.setCoveredFadeColor(Color.TRANSPARENT);
		////SH.setShadowResource(R.drawable.shadow);
        /*ListView drawerList = (ListView) rootView.findViewById(R.id.drawerList);

        ButtonListAdapter adapter = new ButtonListAdapter(getActivity());
        drawerList.setAdapter(adapter);*/
		recentCmdTextView = (TextView) findViewById(R.id.recentCmd);

		initDb();
		initMenu();
	}
	public void initDb(){

		hostDBKeys = new ArrayList<ArrayList<String>>();
		ArrayList<String> dataKey = new ArrayList<String>();
		dataKey.add("Alias");
		dataKey.add("TEXT");
		hostDBKeys.add(dataKey);
		dataKey = new ArrayList<String>();
		dataKey.add("Host");
		dataKey.add("TEXT");
		hostDBKeys.add(dataKey);
		dataKey = new ArrayList<String>();
		dataKey.add("Username");
		dataKey.add("TEXT");
		hostDBKeys.add(dataKey);
		dataKey = new ArrayList<String>();
		dataKey.add("Port");
		dataKey.add("TEXT");
		hostDBKeys.add(dataKey);
		dataKey = new ArrayList<String>();
		dataKey.add("Password");
		dataKey.add("TEXT");
		hostDBKeys.add(dataKey);


		ArrayList<ArrayList<String>> scriptDBKeys = new ArrayList<ArrayList<String>>();
		dataKey = new ArrayList<String>();
		dataKey.add("Alias");
		dataKey.add("TEXT");
		scriptDBKeys.add(dataKey);
		dataKey = new ArrayList<String>();
		dataKey.add("Script");
		dataKey.add("TEXT");
		scriptDBKeys.add(dataKey);

		db = new DatabaseHandler(getApplicationContext(), hostDBKeys,scriptDBKeys);
	}

	public void initMenu(){
		loadComputerMetadata();
	}

	/** Stable shell hook for destination controllers; changing destination never dispatches input. */
	public ShellNavigation shellNavigation() { return shellNavigation; }

	private void renderShell() {
		if (shellRoot == null || shellNavigation == null) return;
		NativeShellViews.render(shellRoot, shellNavigation);
		boolean typing = shellNavigation.topOverlay() == ShellNavigation.Overlay.TYPING;
		if (ET != null) {
			InputMethodManager inputMethod = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
			if (typing) {
				long generation = ++typingFocusGeneration;
				ET.requestFocus();
				ET.post(() -> {
					if (generation != typingFocusGeneration || shellNavigation.topOverlay() != ShellNavigation.Overlay.TYPING ||
							!activityResumed || isFinishing() || isDestroyed()) return;
					inputMethod.showSoftInput(ET, InputMethodManager.SHOW_IMPLICIT);
				});
			} else {
				typingFocusGeneration++;
				ET.clearFocus();
				inputMethod.hideSoftInputFromWindow(ET.getWindowToken(), 0);
				if (!shellNavigation.overlays().contains(ShellNavigation.Overlay.TYPING)) ET.setText("");
			}
		}
		if (shellNavigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS) renderComputers();
	}

	@Override protected void onSaveInstanceState(Bundle outState) {
		outState.putString("shell.destination", shellNavigation.destination().name());
		outState.putBoolean("shell.joystick", shellNavigation.joystick());
		ArrayList<String> overlays = new ArrayList<>();
		for (ShellNavigation.Overlay overlay : shellNavigation.overlays()) overlays.add(overlay.name());
		outState.putStringArrayList("shell.overlays", overlays);
		super.onSaveInstanceState(outState);
	}

	private void bindShellKey(int viewId, String key) {
		bindShellKey(viewId, key, ShellNavigation.InputRole.KEYS);
	}

	/** Reusable key/quick-key hook for Keys, Touchpad, and Typing; never borrow another role. */
	public void bindShellKey(int viewId, String key, ShellNavigation.InputRole role) {
		installInputButtonCapture(viewId, role);
		findViewById(viewId).setOnClickListener(view -> {
			InputGestureToken gesture = takeButtonActivationGesture(view);
			Object token = gesture != null ? gesture : canUseActiveSurfaceForButtonClick(view) ? inputTokenFor(role) : null;
			IDoTool tool = isInputTokenActive(token) ? createDoToolForInput(token) : null;
			if (tool != null) tool.key(key);
			if (gesture != null && commandTransport != null) commandTransport.finishInputGesture(gesture);
		});
	}

	/** Metadata-only worker path: no prepare/authenticate/readPassword or secret data in the UI. */
	private void loadComputerMetadata() {
		final long generation = ++computerLoadGeneration;
		com.stripe1.xmouse.secrets.CredentialRepository repository = AndroidCredentials.get(getApplicationContext());
		repository.execute(() -> {
			try {
				List<ComputerEntry> hosts = new ArrayList<>();
				for (CredentialHost host : repository.hosts()) hosts.add(new ComputerEntry(host.getId(), computerName(host)));
				String selectedName = computerName(repository.selectedHost());
				mainHandler.post(() -> {
					if (generation != computerLoadGeneration || isFinishing() || isDestroyed()) return;
					cachedComputers = hosts;
					((TextView) findViewById(R.id.computer_name)).setText(selectedName);
					findViewById(R.id.computer_selector).setContentDescription(getString(R.string.ng_select_computer, selectedName));
					((TextView) findViewById(R.id.computers_message)).setText(hosts.isEmpty() ? R.string.ng_no_computers : R.string.ng_computers);
					renderComputers();
				});
			} catch (Exception ignored) {
				mainHandler.post(() -> {
					if (generation == computerLoadGeneration && !isDestroyed())
						((TextView) findViewById(R.id.computers_message)).setText(R.string.ng_computers_failed);
				});
			}
		});
	}

	private String computerName(CredentialHost host) {
		if (host.getAlias() != null && !host.getAlias().trim().isEmpty()) return host.getAlias();
		String endpoint = host.displayEndpoint();
		return endpoint.isEmpty() ? getString(R.string.ng_choose_computer) : endpoint;
	}

	private void renderComputers() {
		LinearLayout list = findViewById(R.id.computers_list);
		list.removeAllViews();
		for (ComputerEntry host : cachedComputers) {
			Button button = new Button(this);
			button.setText(host.name);
			button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
			button.setOnClickListener(v -> {
				shellNavigation.closeTop();
				selectHostAndConnect((int) host.id);
			});
			list.addView(button, new LinearLayout.LayoutParams(-1, -2));
		}
	}

	private void openSettings() { openExternalScreen(new Intent(this, SettingsActivity.class)); }
	private void openLegacyManager(int type) {
		openExternalScreen(new Intent(this, ListEditActivity.class).putExtra("intVariableType", type));
	}
	private void openExternalScreen(Intent intent) {
		shellNavigation.open(ShellNavigation.Overlay.EXTERNAL);
		externalScreenPending = true;
		try { startActivity(intent); }
		catch (RuntimeException failure) {
			externalScreenPending = false;
			shellNavigation.closeTop();
			throw failure;
		}
	}

	private void showOwnedDialog(AlertDialog dialog) {
		shellNavigation.open(ShellNavigation.Overlay.ACTION_EDIT);
		shellDialogs.add(dialog);
		dialog.setOnDismissListener(dismissed -> {
			shellDialogs.remove(dialog);
			if (shellDialogs.isEmpty()) shellNavigation.close(ShellNavigation.Overlay.ACTION_EDIT);
		});
		dialog.show();
	}

	public void showSavedScripts() {
		ArrayList<ArrayList<String>> scripts = db.listAll(DatabaseHandler.SCRIPT_TABLE_NAME, new String[]{"Alias", "Script", "id"});
		String[] titles = new String[scripts.size()];
		for (int i = 0; i < scripts.size(); i++) titles[i] = scripts.get(i).get(0);
		AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(R.string.ng_scripts)
				.setNegativeButton(R.string.ng_close, null)
				.setNeutralButton(R.string.ng_manage_scripts, (dialog, which) -> openLegacyManager(1));
		if (titles.length == 0) builder.setMessage(R.string.ng_no_scripts);
		else builder.setItems(titles, (dialog, which) -> runSavedScript(Integer.parseInt(scripts.get(which).get(2))));
		showOwnedDialog(builder.create());
	}

	public void runSavedScript(int id) {
		ArrayList<ArrayList<String>> scripts = db.getRowWithId(DatabaseHandler.SCRIPT_TABLE_NAME,
				new String[]{"Alias", "Script", "id"}, String.valueOf(id));
		if (scripts.isEmpty()) return;
		SessionInteractions.PresentationToken presentation = sessionPresenter == null ? null : sessionPresenter.presentationToken();
		if (commandTransport == null || !commandTransport.executeExecCommand(scripts.get(0).get(1), presentation))
			Toast.makeText(this, "Connect to a computer before running a script.", Toast.LENGTH_LONG).show();
	}

	public void getPreferences(){

		try{
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

			setting_host= prefs.getString("setting_host", "");
			setting_user= prefs.getString("setting_user", "");
			setting_port= Integer.valueOf(prefs.getString("setting_port", "22"));

			setting_host_all = prefs.getString("hostPreferenceList", "0");//id of host in db

			ArrayList<ArrayList<String>> host = db.getRowWithId(DatabaseHandler.HOST_TABLE_NAME,new String[] {"Host","Username","Port"},setting_host_all);

			if(host.size()>0){

				setting_host= host.get(0).get(0);
				setting_user= host.get(0).get(1);
				setting_port= Integer.valueOf(host.get(0).get(2));
			}/*else{

                //Log.d("getPreferences", "Zero host records, will default to last used settings");
                Toast.makeText(getBaseContext(), "No Saved Host selected", Toast.LENGTH_LONG).show();
            }*/

			setting_sensitivity = Float.valueOf(prefs.getString("sensitivity_list", "1.0f"));
			setting_delay = Integer.valueOf(prefs.getString("delay_list", "350"));
			setting_mdelay = Integer.valueOf(prefs.getString("mdelay_list", "0"));


			setting_gyro_z_sensitivity = Float.valueOf(prefs.getString("gyro_z_sensitivity_list", "1.5f"));
			setting_gyro_y_sensitivity = Float.valueOf(prefs.getString("gyro_y_sensitivity_list", "1.5f"));
			setting_gyro_z_threshold = Float.valueOf(prefs.getString("gyro_z_threshold_list", "1.5f"));
			setting_gyro_y_threshold = Float.valueOf(prefs.getString("gyro_y_threshold_list", "1.5f"));

			setting_js_sensitivity = Float.valueOf(prefs.getString("js_sensitivity_list", "1.0f"));
			setting_js_size = Float.valueOf(prefs.getString("js_size_list", "0.5f"));
			setting_js_dead_zone = Float.valueOf(prefs.getString("js_dead_zone_list", "0.0f"));

			setting_autoconnect=prefs.getBoolean("autologin_checkbox", false);
			setting_dotool_initial=prefs.getString("setting_dotool_initial", "xdotool");
			setting_xdotool_initial=prefs.getString("setting_xdotool_initial", "export DISPLAY=':0' && unset HISTFILE");
			setting_shell = prefs.getString("setting_shell", "");
			setting_keyboard_autoclear=prefs.getBoolean("keyboard_autoclear", true);
			//setting_keyboard_show_details=prefs.getBoolean("show_commands_on_buttons",false);
			setting_use_keys = prefs.getBoolean("pref_usekeyauth",false);
			setting_keyboard_batch=prefs.getBoolean("setting_keyboard_batch",true);
			//setting_mouse_background=prefs.getBoolean("setting_mouse_background",false);
			setting_keyboard_locked=prefs.getBoolean("keyboard_layout_locked",false);
			//setting_log_staydown=prefs.getBoolean("log_staydown", true);
			//setting_command_overlay=prefs.getBoolean("command_overlay", false);
			setting_invert_scroll=prefs.getBoolean("mouse_invert_scroll",false);
		}catch(Exception ignored){
			Log.w(TAG, "settings_read_failed");
			Toast.makeText(getApplicationContext(), "There was a problem retrieving your settings.", Toast.LENGTH_LONG).show();
		}
	}
	public void xMouseKeyboardSend(View v){
		InputGestureToken gesture = takeButtonActivationGesture(v);
		Object token = gesture != null ? gesture :
				(canUseActiveSurfaceForButtonClick(v) ? activeTypingInputToken() : null);
		if (!isInputTokenActive(token)) {
			if (gesture != null && commandTransport != null) commandTransport.cancelInputGesture(gesture);
			return;
		}
		IDoTool inputTool = createDoToolForInput(token);
		if (inputTool != null) useKeyboardSendText(inputTool);
		if (gesture != null && commandTransport != null) commandTransport.finishInputGesture(gesture);
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.d(TAG, "activity_started");
		if (sessionPresenter != null) sessionPresenter.start();
		bindSessionService();
	}
	@Override
	protected void onResume() {
		super.onResume();
		activityResumed = true;
		if (externalScreenPending) {
			externalScreenPending = false;
			shellNavigation.close(ShellNavigation.Overlay.EXTERNAL);
		}
		if (returningFromLayoutEditor || returningFromLayoutManager) {
			returningFromLayoutEditor = false;
			returningFromLayoutManager = false;
			reloadSelectedSavedLayout();
		}
		foregroundRecoveryWindow = new ForegroundRecoveryWindow();
		Log.d(TAG, "activity_resumed");
		final boolean openedFromNotification = suppressLegacyAutoConnectOnResume;
		suppressLegacyAutoConnectOnResume = false;
		// The activity has become visible (it is now "resumed").
		getPreferences();



		//Toast.makeText(getBaseContext(), "setting_autoconnect "+String.valueOf(setting_autoconnect), Toast.LENGTH_SHORT).show();
		scheduleAutomaticForegroundSessionAfterVisibleFrame(activityGeneration, getWindow().getDecorView(),
				!openedFromNotification);

		if(mCoolDragAndDropGridView!=null){
			mItemAdapter = new CustomKeyboardButtonAdapter(MainActivity.this, mItems);
			mCoolDragAndDropGridView.setAdapter(mItemAdapter);
			mItemAdapter.notifyDataSetChanged();
		}
		initMenu();
		showConnectionStat();
		initDoTool();
		windowFocused = getWindow().getDecorView().hasWindowFocus();
		updateControlInputSurface();
	}

	private void requestAutomaticForegroundSession(boolean allowAutoConnect) {
		if (pendingForegroundStartTicket != null) {
			if (allowAutoConnect && setting_autoconnect) continueForegroundSessionRequest();
			return;
		}
		String ticket = foregroundRecoveryWindow == null ? null :
				SessionForegroundService.reserveForegroundRecoveryIfNeeded(this, foregroundRecoveryWindow);
		if (ticket != null) {
			// Recovery never prompts for or bypasses missing network permission. The owner checks it
			// again before creating an attempt; existing notification disclosure remains in force.
			SessionForegroundService.startReservedConnect(this, ticket);
			return;
		}
		if (!allowAutoConnect || !setting_autoconnect) return;
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (localNetworkPermissionCoordinator.requiresRuntimePermission() &&
				preferences.getBoolean(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED, false)) return;
		if (!localNetworkPermissionCoordinator.requiresRuntimePermission()) {
			preferences.edit().remove(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED).apply();
		}
		ticket = SessionForegroundService.reserveAutomaticConnectIfNeeded(this);
		if (ticket == null) return; // Existing intent, explicit Disconnect, or a newer request wins.
		pendingForegroundStartTicket = ticket;
		continueForegroundSessionRequest();
	}

	/** Wait for a laid-out visible frame before requesting an FGS start from this Activity. */
	private void scheduleAutomaticForegroundSessionAfterVisibleFrame(long resumedGeneration, View decor,
			boolean allowAutoConnect) {
		decor.postOnAnimation(new Runnable() {
			private boolean observedVisibleFrame;

			@Override public void run() {
				if (resumedGeneration != activityGeneration || isFinishing() || isDestroyed() ||
						!activityResumed ||
						!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;

				boolean visibleFrame = decor.isAttachedToWindow() && decor.isShown() &&
						decor.getWindowVisibility() == View.VISIBLE && decor.getWidth() > 0 && decor.getHeight() > 0;
				if (!visibleFrame) {
					observedVisibleFrame = false;
					decor.postOnAnimation(this);
					return;
				}
				if (!observedVisibleFrame) {
					// This callback precedes drawing; wait one more frame so the window is genuinely visible.
					observedVisibleFrame = true;
					decor.postOnAnimation(this);
					return;
				}
				requestAutomaticForegroundSession(allowAutoConnect);
			}
		});
	}

	private void bindSessionService() {
		if (sessionServiceConnection != null || isFinishing() || isDestroyed()) return;
		final long token = ++bindingGeneration;
		ServiceConnection connection = new ServiceConnection() {
			@Override public void onServiceConnected(ComponentName name, IBinder service) {
				if (token != bindingGeneration || isFinishing() || isDestroyed()) return;
				sessionBinder = (SessionForegroundService.SessionBinder) service;
				promptInteractions = sessionBinder.getInteractions();
				if (sessionPresenter != null) {
					sessionPresenter.attach(promptInteractions);
					if (commandTransport != null) {
						commandTransport.setPresentationToken(sessionPresenter.presentationToken());
					}
				}
				sessionSnapshot = sessionBinder.getSnapshot();
				sessionObserver = snapshot -> applySessionSnapshot(token, snapshot);
				sessionSubscription = sessionBinder.observe(command -> mainHandler.post(command), sessionObserver);
				applySessionSnapshot(token, sessionSnapshot);
			}
			@Override public void onServiceDisconnected(ComponentName name) {
				if (token != bindingGeneration) return;
				clearShellPresentationTarget();
				closeSessionObservation();
				sessionBinder = null;
				promptInteractions = null;
				sessionSnapshot = null;
				showConnectionStat();
			}
		};
		sessionServiceConnection = connection;
		if (!bindService(new Intent(this, SessionForegroundService.class), connection, Context.BIND_AUTO_CREATE)) {
			sessionServiceConnection = null;
		}
	}

	private void applySessionSnapshot(long token, SessionSnapshot snapshot) {
		if (token != bindingGeneration || isFinishing() || isDestroyed() ||
				!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
		sessionSnapshot = snapshot;
		showConnectionStat();
		updateControlInputSurface();
	}

	private void closeSessionObservation() {
		if (sessionSubscription != null) {
			try { sessionSubscription.close(); } catch (Exception ignored) { }
			sessionSubscription = null;
		}
		sessionObserver = null;
	}

	private void unbindSessionService() {
		clearShellPresentationTarget();
		bindingGeneration++;
		closeSessionObservation();
		ServiceConnection connection = sessionServiceConnection;
		sessionServiceConnection = null;
		if (connection != null) {
			try { unbindService(connection); } catch (IllegalArgumentException ignored) { }
		}
		sessionBinder = null;
		promptInteractions = null;
		sessionSnapshot = null;
	}

	private void clearShellPresentationTarget() {
		deactivateControlInputSurface();
		if (commandTransport != null && sessionPresenter != null) {
			commandTransport.clearPresentationToken(sessionPresenter.presentationToken());
		}
	}

	private void updateControlInputSurface() {
		boolean eligible = shellNavigation != null && shellNavigation.eligible(activityResumed, windowFocused,
				sessionSnapshot != null && sessionSnapshot.getConnection().isReady()) &&
				!isFinishing() && !isDestroyed() && sessionPresenter != null && commandTransport != null &&
				getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
				sessionSnapshot != null && sessionSnapshot.getConnection().isReady() &&
				sessionPresenter.presentationToken() != null;
		if (!eligible) {
			deactivateControlInputSurface();
			return;
		}
		if (controlInputSurface != null && commandTransport.isInputTokenActive(controlInputSurface)) {
			refreshGyroscopeSampling();
			return;
		}
		controlInputSurface = null;
		InputSurfaceToken activated = commandTransport.activateControlSurface(sessionPresenter.presentationToken());
		controlInputSurface = activated;
		refreshGyroscopeSampling();
	}

	private void deactivateControlInputSurface() {
		InputSurfaceToken old = controlInputSurface;
		controlInputSurface = null;
		for (Map.Entry<View, CapturedButtonGesture> entry : new ArrayList<>(mouseButtonGestures.entrySet())) {
			entry.getKey().setPressed(false);
			cancelledButtonTouches.put(entry.getKey(), Boolean.TRUE);
			if (commandTransport != null) commandTransport.cancelInputGesture(entry.getValue().token);
		}
		for (InputGestureToken gesture : new ArrayList<>(volumeInputGestures.values())) {
			if (commandTransport != null) commandTransport.cancelInputGesture(gesture);
		}
		if (commandTransport != null) commandTransport.deactivateControlSurface(old);
		if (mItemAdapter != null) mItemAdapter.resetForInactiveSurface();
		mouseButtonGestures.clear();
		volumeInputGestures.clear();
		if (mouseControlView != null) mouseControlView.resetForInactiveSurface();
		if (joystickControlView != null) joystickControlView.resetForInactiveSurface();
		refreshGyroscopeSampling();
	}

	private void refreshGyroscopeSampling() {
		if (gyroSamplingGate != null) {
			gyroSamplingGate.update(activityResumed, activePointerInputToken(),
					gyroToggle != null && gyroToggle.isChecked());
		}
	}

	private boolean isInputTokenActive(Object token) {
		return commandTransport != null && commandTransport.isInputTokenActive(token);
	}

	public boolean isControlInputTokenActive(Object token) {
		return isInputTokenActive(token);
	}

	private void installMouseButtonInputCapture(int viewId) {
		installInputButtonCapture(viewId, viewId == R.id.keyboard_send
				? ShellNavigation.InputRole.TYPING : ShellNavigation.InputRole.POINTER);
	}

	/** Same cancellation/accessibility policy for all destination buttons, including future typing keys. */
	public void installInputButtonCapture(int viewId, ShellNavigation.InputRole role) {
		View button = findViewById(viewId);
		if (button == null) return;
		button.setOnTouchListener((view, event) -> {
			switch (event.getActionMasked()) {
				case android.view.MotionEvent.ACTION_DOWN:
					cancelledButtonTouches.remove(view);
					CapturedButtonGesture old = mouseButtonGestures.remove(view);
					if (old != null && commandTransport != null) commandTransport.cancelInputGesture(old.token);
					InputSurfaceToken surface = inputTokenFor(role);
					InputGestureToken gesture = commandTransport == null ? null : commandTransport.beginInputGesture(surface);
					if (gesture != null) {
						mouseButtonGestures.put(view, new CapturedButtonGesture(gesture));
						view.setPressed(true);
					}
					break;
				case android.view.MotionEvent.ACTION_MOVE:
					CapturedButtonGesture moving = mouseButtonGestures.get(view);
					if (moving != null && (!isInputTokenActive(moving.token) || event.getX() < 0 || event.getY() < 0 ||
							event.getX() >= view.getWidth() || event.getY() >= view.getHeight())) {
						mouseButtonGestures.remove(view);
						view.setPressed(false);
						cancelledButtonTouches.put(view, Boolean.TRUE);
						if (commandTransport != null) commandTransport.cancelInputGesture(moving.token);
					}
					break;
				case android.view.MotionEvent.ACTION_CANCEL:
					CapturedButtonGesture cancelled = mouseButtonGestures.remove(view);
					view.setPressed(false);
					if (cancelled != null) cancelledButtonTouches.put(view, Boolean.TRUE);
					if (cancelled != null && commandTransport != null) commandTransport.cancelInputGesture(cancelled.token);
					break;
				case android.view.MotionEvent.ACTION_UP:
					CapturedButtonGesture completed = mouseButtonGestures.get(view);
					view.setPressed(false);
					boolean inside = event.getX() >= 0 && event.getY() >= 0 &&
						event.getX() < view.getWidth() && event.getY() < view.getHeight();
					if (completed != null && inside && isInputTokenActive(completed.token)) {
						capturedTouchClickView = view;
						boolean clicked;
						try { clicked = view.performClick(); }
						finally { capturedTouchClickView = null; }
						if (!clicked) {
							mouseButtonGestures.remove(view);
							if (commandTransport != null) commandTransport.cancelInputGesture(completed.token);
						}
					} else if (completed != null) {
						mouseButtonGestures.remove(view);
						cancelledButtonTouches.put(view, Boolean.TRUE);
						if (commandTransport != null) commandTransport.cancelInputGesture(completed.token);
					}
					return true;
			}
			// Consume pointer sequences. A validated touch UP above invokes performClick explicitly;
			// stale UP after lifecycle reset cannot be turned into a click on the next surface.
			return true;
		});
		button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			@Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
				if (action != android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
					return super.performAccessibilityAction(host, action, arguments);
				}
				accessibilityButtonActivations.put(host, Boolean.TRUE);
				try { return super.performAccessibilityAction(host, action, arguments); }
				finally { accessibilityButtonActivations.remove(host); }
			}
		});
	}

	public InputSurfaceToken activeControlInputToken() {
		InputSurfaceToken token = controlInputSurface;
		return isInputTokenActive(token) ? token : null;
	}

	/** Compatibility/volume route. New destination controls must use their role-specific helpers. */
	public InputGestureToken beginControlInputGesture() {
		return commandTransport == null ? null : commandTransport.beginInputGesture(activeControlInputToken());
	}

	private InputSurfaceToken inputTokenFor(ShellNavigation.InputRole role) {
		return shellNavigation != null && shellNavigation.inputRole() == role ? activeControlInputToken() : null;
	}
	public InputSurfaceToken activePointerInputToken() { return inputTokenFor(ShellNavigation.InputRole.POINTER); }
	public InputSurfaceToken activeKeyInputToken() { return inputTokenFor(ShellNavigation.InputRole.KEYS); }
	public InputSurfaceToken activeActionInputToken() { return inputTokenFor(ShellNavigation.InputRole.ACTIONS); }
	public InputSurfaceToken activeTypingInputToken() { return inputTokenFor(ShellNavigation.InputRole.TYPING); }
	public InputGestureToken beginPointerInputGesture() {
		return commandTransport == null ? null : commandTransport.beginInputGesture(activePointerInputToken());
	}
	public InputGestureToken beginActionInputGesture() {
		return commandTransport == null ? null : commandTransport.beginInputGesture(activeActionInputToken());
	}
	public InputGestureToken beginKeyInputGesture() {
		return commandTransport == null ? null : commandTransport.beginInputGesture(activeKeyInputToken());
	}
	public InputGestureToken beginTypingInputGesture() {
		return commandTransport == null ? null : commandTransport.beginInputGesture(activeTypingInputToken());
	}

	public IDoTool createDoToolForInput(Object token) {
		if (commandTransport == null || token == null) return null;
		CommandTransport scoped = commandTransport.forInputToken(token);
		return createDoTool(scoped);
	}

	public CommandTransport commandTransportForInput(Object token) {
		return commandTransport == null || token == null ? null : commandTransport.forInputToken(token);
	}

	public void finishControlInputGesture(InputGestureToken token) {
		if (commandTransport != null) commandTransport.finishInputGesture(token);
	}

	public void cancelControlInputGesture(InputGestureToken token) {
		if (commandTransport != null) commandTransport.cancelInputGesture(token);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		suppressLegacyAutoConnectOnResume = isNotificationOpenIntent(intent);
	}

	private static boolean isNotificationOpenIntent(Intent intent) {
		return intent != null && ACTION_OPEN_SESSION_CONTROLS.equals(intent.getAction());
	}

	/** In-app entry point retained when notification-tray visibility is denied or later revoked. */
	public void showSessionControls() {
		if (isFinishing() || isDestroyed()) return;
		boolean active = hasActiveOrPendingSession() && !SessionForegroundService.hasPendingStart(this);
		boolean pending = SessionForegroundService.hasPendingStart(this);
		boolean retry = sessionSnapshot != null && sessionSnapshot.getDesiredHost() != null &&
				sessionSnapshot.getConnection().getPhase() == ConnectionState.Phase.ERROR;
		if (pending && !active) {
			AlertDialog.Builder pendingDialog = new AlertDialog.Builder(this)
					.setTitle(R.string.session_controls_title)
					.setMessage(R.string.session_controls_pending_message);
			if (pendingForegroundStartTicket != null) {
				pendingDialog.setPositiveButton(R.string.session_controls_continue_request,
						(dialog, which) -> continueForegroundSessionRequest());
				pendingDialog.setNegativeButton(R.string.session_controls_cancel_request,
						(dialog, which) -> cancelPendingForegroundSessionRequest());
			} else {
				pendingDialog.setPositiveButton(R.string.session_controls_close, null)
						.setNegativeButton(R.string.session_controls_disconnect,
								(dialog, which) -> disconnectForegroundSessionFromUi());
			}
			showOwnedDialog(pendingDialog.create());
			return;
		}
		String message;
		if (retry) {
			message = connectionFailureMessage(sessionSnapshot.getConnection().getFailure());
		} else if (!active) {
			message = getString(R.string.session_controls_idle_message);
		} else if (SessionForegroundService.areSessionNotificationsVisible(this)) {
			message = getString(R.string.session_controls_active_message);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			message = getString(R.string.session_controls_hidden_api33_message);
		} else {
			message = getString(R.string.session_controls_hidden_older_message);
		}
		showOwnedDialog(new AlertDialog.Builder(this)
				.setTitle(R.string.session_controls_title)
				.setMessage(message)
				.setPositiveButton(retry ? R.string.session_controls_retry : active ? R.string.session_controls_open : R.string.session_controls_connect,
						(dialog, which) -> { if (!active) requestForegroundSessionFromUi(); })
				.setNegativeButton(active || retry ? R.string.session_controls_disconnect : R.string.session_controls_close,
						(dialog, which) -> { if (active || retry) disconnectForegroundSessionFromUi(); })
				.create());
	}

	/** Keeps an in-progress permission flow intact while directing a legacy connect tap to it. */
	public void showLegacyConnectBlockedGuidance() {
		if (pendingForegroundStartTicket != null) {
			android.widget.Toast.makeText(this, R.string.session_controls_pending_legacy_guidance,
					android.widget.Toast.LENGTH_LONG).show();
		} else {
			showSessionControls();
		}
	}

	private void requestForegroundSessionFromUi() {
		if (pendingForegroundStartTicket != null) {
			continueForegroundSessionRequest();
			return;
		}
		if (hasActiveOrPendingSession() && !SessionForegroundService.hasPendingStart(this)) {
			showSessionControls();
			return;
		}
		if (SessionForegroundService.hasPendingStart(this)) {
			showSessionControls();
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.remove(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED).apply();
		pendingForegroundStartTicket = SessionForegroundService.reserveConnectRequest(this, false);
		if (pendingForegroundStartTicket == null) {
			android.widget.Toast.makeText(this, R.string.session_start_rejected, android.widget.Toast.LENGTH_LONG).show();
			return;
		}
		continueForegroundSessionRequest();
	}

	private void continueForegroundSessionRequest() {
		if (pendingForegroundStartTicket == null) {
			pendingHostSelectionRequest = false;
			pendingForegroundStartTicket = SessionForegroundService.reserveConnectRequest(this, false);
			if (pendingForegroundStartTicket == null) {
				android.widget.Toast.makeText(this, R.string.session_start_rejected, android.widget.Toast.LENGTH_LONG).show();
				return;
			}
		}
		if (localNetworkPermissionCoordinator.requiresRuntimePermission()) {
			showLocalNetworkPermissionRationale();
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.remove(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED).apply();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
			boolean requestedBefore = preferences.getBoolean(PREF_NOTIFICATION_PERMISSION_REQUESTED, false);
			if (requestedBefore && !shouldShowRequestPermissionRationale(POST_NOTIFICATIONS_PERMISSION)) {
				showNotificationSettingsExplanation(true);
			} else {
				showNotificationPermissionRationale();
			}
			return;
		}

		if (!SessionForegroundService.areSessionNotificationsVisible(this)) {
			showNotificationSettingsExplanation(false);
			return;
		}
		startForegroundSession();
	}

	/** LAN access is requested only from this visible, ticket-owning Activity; services never prompt. */
	private void showLocalNetworkPermissionRationale() {
		String ticket = pendingForegroundStartTicket;
		if (ticket == null || pendingLocalNetworkPermissionTicket != null) return;
		if (!SessionForegroundService.hasReservedStart(this, ticket)) {
			pendingForegroundStartTicket = null;
			return;
		}
		pendingLocalNetworkPermissionTicket = ticket;
		showOwnedDialog(new AlertDialog.Builder(this)
				.setTitle(R.string.local_network_permission_title)
				.setMessage(R.string.local_network_permission_message)
				.setPositiveButton(R.string.local_network_permission_request, (dialog, which) -> {
					if (!ticket.equals(pendingLocalNetworkPermissionTicket) ||
							!ticket.equals(pendingForegroundStartTicket) ||
							!SessionForegroundService.hasReservedStart(this, ticket)) {
						if (ticket.equals(pendingLocalNetworkPermissionTicket)) pendingLocalNetworkPermissionTicket = null;
						return;
					}
					try {
						localNetworkPermissionLauncher.launch(LocalNetworkPermissionCoordinator.PERMISSION);
					} catch (RuntimeException rejected) {
						pendingLocalNetworkPermissionTicket = null;
						cancelPendingForegroundSessionRequest();
						android.widget.Toast.makeText(this, R.string.session_start_rejected,
								android.widget.Toast.LENGTH_LONG).show();
					}
				})
				.setNeutralButton(R.string.local_network_permission_open_settings,
						(dialog, which) -> openLocalNetworkSettings(ticket))
				.setNegativeButton(R.string.local_network_permission_not_now,
						(dialog, which) -> cancelLocalNetworkPermissionRequest(ticket))
				.setOnCancelListener(dialog -> cancelLocalNetworkPermissionRequest(ticket))
				.create());
	}

	private void cancelLocalNetworkPermissionRequest(String ticket) {
		if (!ticket.equals(pendingLocalNetworkPermissionTicket)) return;
		pendingLocalNetworkPermissionTicket = null;
		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.putBoolean(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED, true).apply();
		cancelPendingForegroundSessionRequest();
	}

	private void openLocalNetworkSettings(String ticket) {
		if (ticket.equals(pendingLocalNetworkPermissionTicket)) {
			pendingLocalNetworkPermissionTicket = null;
			PreferenceManager.getDefaultSharedPreferences(this).edit()
					.putBoolean(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED, true).apply();
			cancelPendingForegroundSessionRequest();
		}
		openApplicationSettings();
	}

	private void openApplicationSettings() {
		Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				Uri.fromParts("package", getPackageName(), null));
		try {
			startActivity(settings);
		} catch (RuntimeException ignored) {
			startActivity(new Intent(Settings.ACTION_SETTINGS));
		}
	}

	private void onLocalNetworkPermissionResult(boolean granted) {
		String ticket = pendingLocalNetworkPermissionTicket;
		if (ticket == null) return;
		boolean reservationPending = SessionForegroundService.hasReservedStart(this, ticket);
		if (!ticket.equals(pendingForegroundStartTicket) || !reservationPending) {
			if (ticket.equals(pendingLocalNetworkPermissionTicket)) pendingLocalNetworkPermissionTicket = null;
			if (ticket.equals(pendingForegroundStartTicket) && !reservationPending) {
				pendingForegroundStartTicket = null;
			}
			return;
		}
		pendingLocalNetworkPermissionTicket = null;
		if (isFinishing() || isDestroyed()) {
			cancelPendingForegroundSessionRequest();
			return;
		}
		if (granted && !localNetworkPermissionCoordinator.requiresRuntimePermission()) {
			PreferenceManager.getDefaultSharedPreferences(this).edit()
					.remove(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED).apply();
			continueForegroundSessionRequest();
		} else {
			PreferenceManager.getDefaultSharedPreferences(this).edit()
					.putBoolean(PREF_LOCAL_NETWORK_AUTO_CONNECT_BLOCKED, true).apply();
			cancelPendingForegroundSessionRequest();
			showLocalNetworkPermissionDenied();
		}
	}

	private void showLocalNetworkPermissionDenied() {
		showOwnedDialog(new AlertDialog.Builder(this)
				.setTitle(R.string.local_network_permission_denied_title)
				.setMessage(R.string.local_network_permission_denied_message)
				.setPositiveButton(R.string.local_network_permission_retry,
						(dialog, which) -> requestForegroundSessionFromUi())
				.setNeutralButton(R.string.local_network_permission_open_settings,
						(dialog, which) -> openApplicationSettings())
				.setNegativeButton(R.string.local_network_permission_not_now, null)
				.create());
	}

	private void showNotificationPermissionRationale() {
		showOwnedDialog(new AlertDialog.Builder(this)
				.setTitle(R.string.session_permission_rationale_title)
				.setMessage(R.string.session_permission_rationale_message)
				.setPositiveButton(R.string.session_permission_request, (dialog, which) -> {
					String ticket = pendingForegroundStartTicket;
					if (ticket == null || !SessionForegroundService.hasReservedStart(this, ticket) ||
							pendingNotificationPermissionTicket != null) {
						if (ticket != null && ticket.equals(pendingForegroundStartTicket) &&
								!SessionForegroundService.hasReservedStart(this, ticket)) {
							pendingForegroundStartTicket = null;
						}
						return;
					}
					pendingNotificationPermissionTicket = ticket;
					PreferenceManager.getDefaultSharedPreferences(this).edit()
						.putBoolean(PREF_NOTIFICATION_PERMISSION_REQUESTED, true).apply();
					notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION);
				})
				.setNegativeButton(R.string.session_permission_continue_without,
						(dialog, which) -> startForegroundSession())
				.setNeutralButton(R.string.session_permission_not_now,
						(dialog, which) -> cancelPendingForegroundSessionRequest())
				.setOnCancelListener(dialog -> cancelPendingForegroundSessionRequest())
				.create());
	}

	/** A permission result is only UI for the exact still-reserved request that launched it. */
	private void onNotificationPermissionResult(boolean granted) {
		handleNotificationPermissionResult(pendingNotificationPermissionTicket, granted);
	}

	private void handleNotificationPermissionResult(String requestTicket, boolean granted) {
		if (requestTicket == null || !requestTicket.equals(pendingNotificationPermissionTicket)) return;
		boolean reservationPending = SessionForegroundService.hasReservedStart(this, requestTicket);
		if (!requestTicket.equals(pendingForegroundStartTicket) || !reservationPending) {
			if (requestTicket.equals(pendingNotificationPermissionTicket)) {
				pendingNotificationPermissionTicket = null;
			}
			if (requestTicket.equals(pendingForegroundStartTicket) && !reservationPending) {
				pendingForegroundStartTicket = null;
			}
			return;
		}
		pendingNotificationPermissionTicket = null;
		if (isFinishing() || isDestroyed()) {
			cancelPendingForegroundSessionRequest();
			return;
		}
		if (granted && SessionForegroundService.areSessionNotificationsVisible(this)) {
			startForegroundSession(requestTicket);
		} else if (granted) {
			showNotificationSettingsExplanation(false);
		} else {
			showNotificationPermissionDenied();
		}
	}

	private void showNotificationPermissionDenied() {
		showOwnedDialog(new AlertDialog.Builder(this)
				.setTitle(R.string.session_permission_denied_title)
				.setMessage(R.string.session_permission_denied_message)
				.setPositiveButton(R.string.session_permission_continue_without,
						(dialog, which) -> startForegroundSession())
				.setNegativeButton(R.string.session_permission_not_now,
						(dialog, which) -> cancelPendingForegroundSessionRequest())
				.setNeutralButton(R.string.session_permission_open_settings,
						(dialog, which) -> openNotificationSettings())
				.setOnCancelListener(dialog -> cancelPendingForegroundSessionRequest())
				.create());
	}

	private void showNotificationSettingsExplanation(boolean permanentDenial) {
		int message = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
				? R.string.session_permission_older_settings_message
				: permanentDenial ? R.string.session_permission_settings_message
				: R.string.session_permission_denied_message;
		showOwnedDialog(new AlertDialog.Builder(this)
				.setTitle(permanentDenial ? R.string.session_permission_settings_title : R.string.session_permission_denied_title)
				.setMessage(message)
				.setPositiveButton(permanentDenial ? R.string.session_permission_open_settings
						: R.string.session_permission_continue_without,
						(dialog, which) -> {
							if (permanentDenial) openNotificationSettings();
							else startForegroundSession();
						})
				.setNegativeButton(permanentDenial ? R.string.session_permission_continue_without
						: R.string.session_permission_not_now,
						(dialog, which) -> {
							if (permanentDenial) startForegroundSession();
							else cancelPendingForegroundSessionRequest();
						})
				.setNeutralButton(permanentDenial ? R.string.session_permission_not_now
						: R.string.session_permission_open_settings,
						(dialog, which) -> {
							if (!permanentDenial) openNotificationSettings();
							else cancelPendingForegroundSessionRequest();
						})
				.setOnCancelListener(dialog -> cancelPendingForegroundSessionRequest())
				.create());
	}

	private void openNotificationSettings() {
		Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
				.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
		try {
			startActivity(settings);
		} catch (RuntimeException ignored) {
			startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
					Uri.fromParts("package", getPackageName(), null)));
		}
	}

	private void startForegroundSession() {
		if (!pendingHostSelectionRequest && pendingForegroundStartTicket == null && SessionForegroundService.hasRetainedSession(this)) {
			showSessionControls();
			return;
		}
		if (!pendingHostSelectionRequest && pendingForegroundStartTicket == null && SessionForegroundService.hasPendingStart(this)) {
			showSessionControls();
			return;
		}
		if (pendingForegroundStartTicket == null) {
			pendingHostSelectionRequest = false;
			pendingForegroundStartTicket = SessionForegroundService.reserveConnectRequest(this, false);
		}
		if (pendingForegroundStartTicket == null) {
			android.widget.Toast.makeText(this, R.string.session_start_rejected, android.widget.Toast.LENGTH_LONG).show();
			return;
		}
		String ticket = pendingForegroundStartTicket;
		ForegroundConnectRequestResult result = SessionForegroundService.startReservedConnect(this, ticket);
		pendingForegroundStartTicket = null;
		pendingLocalNetworkPermissionTicket = null;
		pendingHostSelectionRequest = false;
		if (result != ForegroundConnectRequestResult.ACCEPTED) {
			SessionForegroundService.cancelReservedConnect(this, ticket);
			android.widget.Toast.makeText(this, R.string.session_start_rejected, android.widget.Toast.LENGTH_LONG).show();
		}
	}

	/** Permission callbacks dispatch only their original reservation; they never create a new request. */
	private void startForegroundSession(String requiredTicket) {
		if (requiredTicket == null || !requiredTicket.equals(pendingForegroundStartTicket) ||
				!SessionForegroundService.hasReservedStart(this, requiredTicket)) return;
		startForegroundSession();
	}

	private void cancelPendingForegroundSessionRequest() {
		String ticket = pendingForegroundStartTicket;
		pendingForegroundStartTicket = null;
		pendingNotificationPermissionTicket = null;
		pendingLocalNetworkPermissionTicket = null;
		pendingHostSelectionRequest = false;
		if (ticket != null) SessionForegroundService.cancelReservedConnect(this, ticket);
	}

	private void disconnectForegroundSessionFromUi() {
		cancelPendingForegroundSessionRequest();
		if (!SessionForegroundService.requestDisconnect(this)) {
			android.widget.Toast.makeText(this, R.string.session_start_rejected, android.widget.Toast.LENGTH_LONG).show();
		}
	}
	@Override
	protected void onPause() {
		activityResumed = false;
		if (foregroundRecoveryWindow != null) foregroundRecoveryWindow.close();
		deactivateControlInputSurface();
		if (mCoolDragAndDropGridView != null) mCoolDragAndDropGridView.cancelLegacyDragAndDrop();
		if (shellDialogs.isEmpty()) shellNavigation.close(ShellNavigation.Overlay.ACTION_EDIT);
		// Navigation/lifecycle/Back never save an editor or layout. Legacy mutations persist at
		// their explicit Save/Drop/Delete boundary instead; repository editor drafts stay owned there.
		super.onPause();
		// Another activity is taking focus (this activity is about to be "paused").
	}
	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		windowFocused = hasFocus;
		if (hasFocus) updateControlInputSurface();
		else deactivateControlInputSurface();
	}
	@Override
	protected void onStop() {
		activityGeneration++;
		deactivateControlInputSurface();
		unbindSessionService();
		if (sessionPresenter != null) sessionPresenter.stop(credentialUi != null && credentialUi.hasPicker());
		super.onStop();
	}
	@Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (credentialUi == null || !credentialUi.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data);
	}
	@Override
	protected void onDestroy() {
		activityResumed = false;
		computerLoadGeneration++;
		for (AlertDialog dialog : new ArrayList<>(shellDialogs)) dialog.dismiss();
		deactivateControlInputSurface();
		cancelPendingForegroundSessionRequest();
		unbindSessionService();
		if (sessionPresenter != null) sessionPresenter.destroy();
		super.onDestroy();
	}

	public void showConnectionStat(){
		// Never reuse an Activity's pre-background connected snapshot while the service rebinds.
		if (sessionBinder != null) sessionSnapshot = sessionBinder.getSnapshot();
		ConnectionState state = sessionSnapshot == null ? new ConnectionState.Disconnected() : sessionSnapshot.getConnection();
		ConnectionState.Phase phase = state.getPhase();
		boolean active = state.isReady() || phase == ConnectionState.Phase.PREPARING_CREDENTIALS ||
				phase == ConnectionState.Phase.RESOLVING || phase == ConnectionState.Phase.CONNECTING ||
				phase == ConnectionState.Phase.AWAITING_TRUST || phase == ConnectionState.Phase.AUTHENTICATING ||
				phase == ConnectionState.Phase.CLOSING || SessionForegroundService.hasPendingStart(this) ||
				(sessionSnapshot == null && SessionForegroundService.hasRetainedSession(this));
		String status;
		switch (phase) {
			case PREPARING_CREDENTIALS: status = "Preparing credentials"; break;
			case RESOLVING: status = "Resolving host"; break;
			case CONNECTING: status = "Connecting"; break;
			case AWAITING_TRUST: status = "Waiting for host approval"; break;
			case AUTHENTICATING: status = "Authenticating"; break;
			case CONNECTED: status = "Connected"; break;
			case CLOSING: status = "Disconnecting"; break;
			case ERROR: status = "Connection failed"; break;
			default: status = "Disconnected"; break;
		}
		TextView headerStatus = findViewById(R.id.connection_status);
		if (headerStatus != null) {
			headerStatus.setText("● " + status);
			headerStatus.setTextColor(getColor(state.isReady() ? R.color.ng_accent :
					phase == ConnectionState.Phase.ERROR ? R.color.ng_danger : R.color.ng_text_secondary));
			headerStatus.setContentDescription(getString(R.string.ng_status_action, status));
		}
		Button computerConnect = findViewById(R.id.computers_connect);
		if (computerConnect != null) computerConnect.setText(active ? "Disconnect" : phase == ConnectionState.Phase.ERROR ? "Retry" : "Connect");
		if (conDiscButton != null) {
			conDiscButton.setIcon(active ? R.drawable.state_conn : R.drawable.state_disc);
			String action = active ? "Disconnect" : phase == ConnectionState.Phase.ERROR ? getString(R.string.session_controls_retry) : "Connect";
			conDiscButton.setTitle(action);
			conDiscButton.setContentDescription(status + ". " + action);
		}
		updateClipboardAction();
		if (state.getPhase() == ConnectionState.Phase.ERROR && sessionSnapshot != null &&
				sessionSnapshot.getGeneration() != lastShownErrorGeneration) {
			lastShownErrorGeneration = sessionSnapshot.getGeneration();
			Toast.makeText(this, connectionFailureMessage(state.getFailure()), Toast.LENGTH_LONG).show();
		}
	}

	private void updateClipboardAction() {
		if (clipboardSendButton == null) return;
		SessionInteractions.PresentationToken presentation = sessionPresenter == null
				? null : sessionPresenter.presentationToken();
		boolean ready = sessionSnapshot != null && sessionSnapshot.getConnection().isReady() &&
				commandTransport != null && commandTransport.canSendClipboard(presentation);
		String title = getString(R.string.clipboard_send_title);
		clipboardSendButton.setEnabled(ready);
		clipboardSendButton.setTitle(title);
		clipboardSendButton.setContentDescription(ready ? title : title + ". " +
				getString(R.string.clipboard_send_connect_first));
		clipboardSendButton.setTooltipText(getString(R.string.clipboard_send_tooltip));
	}

	private boolean isClipboardWindowForegroundVisible() {
		View decor = getWindow().getDecorView();
		return activityResumed && windowFocused && !isFinishing() && !isDestroyed() &&
				getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
				decor.isAttachedToWindow() && decor.isShown() && decor.getWindowVisibility() == View.VISIBLE;
	}

	private void sendClipboardToLinux() {
		boolean foreground = isClipboardWindowForegroundVisible();
		SessionInteractions.PresentationToken presentation = sessionPresenter == null
				? null : sessionPresenter.presentationToken();
		boolean ready = foreground && commandTransport != null && commandTransport.canSendClipboard(presentation);
		PlainTextClipboardSource source = this::readAndroidPrimaryPlainText;
		ClipboardPreparation preparation = clipboardShareCoordinator.prepareForSend(true, foreground, ready, source);
		if (preparation instanceof ClipboardPreparation.Ready) {
			String text = ((ClipboardPreparation.Ready) preparation).getText();
			if (commandTransport == null || !commandTransport.sendClipboard(text, presentation)) {
				Toast.makeText(this, R.string.clipboard_send_rejected, Toast.LENGTH_LONG).show();
			}
			return;
		}
		if (preparation == ClipboardPreparation.NotForeground.INSTANCE ||
				preparation == ClipboardPreparation.NotTapped.INSTANCE) return;
		if (preparation == ClipboardPreparation.ConnectFirst.INSTANCE) {
			Toast.makeText(this, R.string.clipboard_send_connect_first, Toast.LENGTH_LONG).show();
		} else if (preparation == ClipboardPreparation.Empty.INSTANCE) {
			Toast.makeText(this, R.string.clipboard_send_empty, Toast.LENGTH_LONG).show();
		} else if (preparation == ClipboardPreparation.NonText.INSTANCE) {
			Toast.makeText(this, R.string.clipboard_send_non_text, Toast.LENGTH_LONG).show();
		} else if (preparation == ClipboardPreparation.TooLarge.INSTANCE) {
			Toast.makeText(this, R.string.clipboard_send_too_large, Toast.LENGTH_LONG).show();
		} else if (preparation == ClipboardPreparation.Malformed.INSTANCE) {
			Toast.makeText(this, R.string.clipboard_send_malformed, Toast.LENGTH_LONG).show();
		} else if (preparation == ClipboardPreparation.Unavailable.INSTANCE) {
			Toast.makeText(this, R.string.clipboard_send_unavailable, Toast.LENGTH_LONG).show();
		}
	}

	/** Reads only a direct text item; URI/Intent coercion or content loading is deliberately absent. */
	private ClipboardRead readAndroidPrimaryPlainText() {
		try {
			Object service = getSystemService(Context.CLIPBOARD_SERVICE);
			if (!(service instanceof ClipboardManager)) return ClipboardRead.Unavailable.INSTANCE;
			ClipboardManager clipboard = (ClipboardManager) service;
			ClipData clip = clipboard.getPrimaryClip();
			if (clip == null || clip.getItemCount() == 0) return ClipboardRead.Empty.INSTANCE;
			ClipDescription description = clip.getDescription();
			if (clip.getItemCount() != 1 || description == null ||
					!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
				return ClipboardRead.NonText.INSTANCE;
			}
			CharSequence text = clip.getItemAt(0).getText();
			return text == null ? ClipboardRead.NonText.INSTANCE : new ClipboardRead.Text(text.toString());
		} catch (RuntimeException ignored) {
			return ClipboardRead.Unavailable.INSTANCE;
		}
	}

	private String connectionFailureMessage(ConnectionFailure failure) {
		if (failure instanceof ConnectionFailure.Permission) {
			return "Local network access was denied or revoked. SSH stopped without treating this as an authentication failure. Allow access in Android settings, then use Retry.";
		}
		if (failure instanceof ConnectionFailure.Resolution) {
			ConnectionFailure.ResolutionReason reason = ((ConnectionFailure.Resolution) failure).getReason();
			if (reason == ConnectionFailure.ResolutionReason.TIMED_OUT) return "Computer lookup timed out. Check the network or open Manage Hosts to enter its IP address, then retry.";
			if (reason == ConnectionFailure.ResolutionReason.NOT_FOUND) return "Computer could not be found. Check the hostname and same network, or open Manage Hosts to enter its IP address. The saved profile is unchanged until saved.";
			return "Computer lookup is unavailable. Check the network or open Manage Hosts to enter its IP address, then retry.";
		}
		if (failure instanceof ConnectionFailure.Trust) {
			ConnectionFailure.TrustReason reason = ((ConnectionFailure.Trust) failure).getReason();
			if (reason == ConnectionFailure.TrustReason.KEY_MISMATCH) {
				return "The host key changed. Authentication was blocked; review the host identity before reconnecting.";
			}
			if (reason == ConnectionFailure.TrustReason.STORAGE_UNAVAILABLE) return "Saved host trust could not be checked. No credentials were sent.";
			return "Host identity was not approved. Review the fingerprint and retry.";
		}
		if (failure instanceof ConnectionFailure.Authentication) {
			ConnectionFailure.AuthenticationReason reason = ((ConnectionFailure.Authentication) failure).getReason();
			if (reason == ConnectionFailure.AuthenticationReason.KEY_UNAVAILABLE) return "Saved credentials are unavailable. Re-enter credentials and retry.";
			if (reason == ConnectionFailure.AuthenticationReason.INVALID_PASSPHRASE) return "The key passphrase was not accepted. Check it and retry.";
			if (reason == ConnectionFailure.AuthenticationReason.TIMED_OUT) return "Credential entry timed out. Retry when ready.";
			if (reason == ConnectionFailure.AuthenticationReason.UNSUPPORTED_KEY) return "This private-key format is not supported.";
			return "Authentication was not accepted. Check the selected credentials and retry.";
		}
		if (failure instanceof ConnectionFailure.Transport) {
			ConnectionFailure.TransportReason reason = ((ConnectionFailure.Transport) failure).getReason();
			if (reason == ConnectionFailure.TransportReason.CONNECTION_REFUSED) return "The computer refused the SSH connection. Check that SSH is running and retry.";
			if (reason == ConnectionFailure.TransportReason.UNREACHABLE) return "The computer is unreachable. Check the network and retry.";
			if (reason == ConnectionFailure.TransportReason.TIMED_OUT) return "The SSH connection timed out. Check the network and retry.";
			if (reason == ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED) return "The previous SSH session could not be safely closed. Reconnect is blocked.";
			return "The SSH session failed. Check the network and retry.";
		}
		return "Connection failed. Check the host settings and retry.";
	}

	public void initDoTool() {
		doTool = createDoTool(commandTransport);
	}

	private IDoTool createDoTool(CommandTransport transport) {
		switch (setting_dotool_initial) {
			case "ydotool": return new YDoTool(transport);
			case "xdotool": return new XDoTool(transport);
			default:
				Log.w(TAG, "unknown_command_backend");
				return new XDoTool(transport);
		}
	}

	private void initializeGyroscopeSampling() {
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		gyroscope = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
		gyroSamplingGate = new GyroscopeSamplingGate(new GyroscopeSamplingGate.SensorFacade() {
			@Override public boolean hasGyroscope() { return sensorManager != null && gyroscope != null; }
			@Override public boolean register(GyroscopeSamplingGate.Listener listener) {
				if (sensorManager == null || gyroscope == null) return false;
				SensorEventListener platformListener = new SensorEventListener() {
					@Override public void onSensorChanged(SensorEvent event) {
						if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE && event.values != null && event.values.length >= 3) {
							listener.onSample(event.values[0], event.values[2]);
						}
					}
					@Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
				};
				boolean registered = sensorManager.registerListener(platformListener, gyroscope,
						SensorManager.SENSOR_DELAY_FASTEST, mainHandler);
				if (registered) sensorListeners.put(listener, platformListener);
				return registered;
			}
			@Override public void unregister(GyroscopeSamplingGate.Listener listener) {
				SensorEventListener platformListener = sensorListeners.remove(listener);
				if (platformListener != null && sensorManager != null) sensorManager.unregisterListener(platformListener);
			}
		}, this::onGyroscopeMotion);
	}

	private void onGyroscopeMotion(InputSurfaceToken token, float gyroscopeY, float gyroscopeZ) {
		gyroscopeY *= setting_gyro_y_sensitivity;
		gyroscopeZ *= setting_gyro_z_sensitivity;
		if (Math.abs(gyroscopeZ) < setting_gyro_z_threshold && Math.abs(gyroscopeY) < setting_gyro_y_threshold) return;
		IDoTool inputTool = createDoToolForInput(token);
		if (inputTool != null) inputTool.mouseMoveRelative(
				gyroscopeZ * -15, gyroscopeY * -15);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch(keyCode){
	 	/*case KeyEvent.KEYCODE_MENU:
	   case KeyEvent.KEYCODE_SEARCH:
	   case KeyEvent.KEYCODE_BACK:*/
			case KeyEvent.KEYCODE_VOLUME_UP:
				captureVolumeGesture(keyCode, event);
				event.startTracking();
				return true;
			case KeyEvent.KEYCODE_VOLUME_DOWN:
				captureVolumeGesture(keyCode, event);
				event.startTracking();
				return true;
		}
		return super.onKeyDown(keyCode, event);
	}
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		switch(keyCode){
       /*case KeyEvent.KEYCODE_MENU:
       case KeyEvent.KEYCODE_SEARCH:*/
			case KeyEvent.KEYCODE_VOLUME_UP:
				InputGestureToken volumeUp = volumeInputGestures.remove(keyCode);
				if(event.isTracking() && !event.isCanceled() && isInputTokenActive(volumeUp)){
					//Toast.makeText(this, "Volumen Up released", Toast.LENGTH_SHORT).show();
					createDoToolForInput(volumeUp).key("XF86AudioRaiseVolume");

				}
				if (volumeUp != null && commandTransport != null) commandTransport.finishInputGesture(volumeUp);
				return true;
			case KeyEvent.KEYCODE_VOLUME_DOWN:
				InputGestureToken volumeDown = volumeInputGestures.remove(keyCode);
				if(event.isTracking() && !event.isCanceled() && isInputTokenActive(volumeDown)){
					//Toast.makeText(this, "Volumen Down released", Toast.LENGTH_SHORT).show();case
					createDoToolForInput(volumeDown).key("XF86AudioLowerVolume");
				}
				if (volumeDown != null && commandTransport != null) commandTransport.finishInputGesture(volumeDown);
				return true;

		}
		return super.onKeyUp(keyCode, event);
	}

	private void captureVolumeGesture(int keyCode, KeyEvent event) {
		if (event.getRepeatCount() != 0) return;
		InputGestureToken old = volumeInputGestures.remove(keyCode);
		if (old != null && commandTransport != null) commandTransport.cancelInputGesture(old);
		InputGestureToken gesture = beginControlInputGesture();
		if (gesture != null) volumeInputGestures.put(keyCode, gesture);
	}

	public void confirmLayoutReload(String title, String msg, final boolean def, final String profileId){
		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		builder.setTitle(title);
		builder.setMessage(msg);
		builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (isRepositoryLayoutActive()) {
					try {
						LayoutProfile selected = layoutProfileRuntime.selectProfile(profileId);
						if (def) {
							mItems = loadDefaultKeyboardLayout(MainActivity.this);
							assignFreshLayoutButtonIds(mItems);
							saveCurrentRepositoryLayout();
						} else {
							mItems = customButtonsFromProfile(selected);
						}
					} catch (Exception ignored) {
						Log.w(TAG, "layout_profile_selection_failed");
						Toast.makeText(MainActivity.this,
								"The saved layout could not be loaded. No layout was changed.", Toast.LENGTH_LONG).show();
						return;
					}
				} else {
					mItems = loadKeyboardLayout(MainActivity.this,def);
					assignFreshLayoutButtonIds(mItems);
				}
				mItemAdapter = new CustomKeyboardButtonAdapter(MainActivity.this, mItems);
				mCoolDragAndDropGridView.setAdapter(mItemAdapter);
				mItemAdapter.notifyDataSetChanged();

			}
		});
		builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		AlertDialog dialog = builder.create();
		showOwnedDialog(dialog);
	}
	public void confirmSaveLayout(String title, String msg, final boolean def, final String presetProfileId){
		if (!isRepositoryLayoutActive() && legacyLayoutFallbackReadOnly) {
			Toast.makeText(this, "Legacy layouts are read-only while migration recovery is needed.", Toast.LENGTH_LONG).show();
			return;
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		builder.setTitle(title);
		builder.setMessage(msg);
		builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (isRepositoryLayoutActive()) {
					saveRepositoryLayoutAs(presetProfileId);
				} else {
					saveKeyboardLayout();
				}

			}
		});
		builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		AlertDialog dialog = builder.create();
		showOwnedDialog(dialog);
	}
	@Override

	public boolean onPrepareOptionsMenu(Menu menu) {
		if(conDiscButton!=null) {
			conDiscButton.setChecked(setting_keyboard_locked);
		}
		if(KeyLockButton!=null) {
			KeyLockButton.setChecked(setting_keyboard_locked);
		}
		if(InvScrollButton!=null){
			InvScrollButton.setChecked(setting_invert_scroll);
		}
		updateClipboardAction();
		//Log.d("prepMenu","menu prepared");
		return true;
	}

	@Override public boolean onMenuOpened(int featureId, Menu menu) {
		if (shellNavigation != null) shellNavigation.open(ShellNavigation.Overlay.MENU);
		return super.onMenuOpened(featureId, menu);
	}
	@Override public void onPanelClosed(int featureId, Menu menu) {
		super.onPanelClosed(featureId, menu);
		if (shellNavigation != null) shellNavigation.close(ShellNavigation.Overlay.MENU);
	}
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		shellNavigation.close(ShellNavigation.Overlay.MENU);

		int id = item.getItemId();
		if (id == R.id.action_settings) {
			openSettings();
			return true;
		} else if (id == R.id.action_scripts) {
			showSavedScripts();
			return true;
		} else if (id == R.id.action_joystick) {
			shellNavigation.select(ShellNavigation.Destination.TOUCHPAD);
			shellNavigation.setJoystick(!shellNavigation.joystick());
			return true;
		} else if (id == R.id.action_add_button) {
			xMouseNewCustomButton(null);
			return true;
		} else if (id == R.id.action_load_preset_one || id == R.id.action_load_preset_two || id == R.id.action_load_preset_three) {
			int preset = id == R.id.action_load_preset_one ? 1 : id == R.id.action_load_preset_two ? 2 : 3;
			KEYLOAYOUTFILENAME = preset == 1 ? "keyFileOne.csv" : preset == 2 ? "keyFileTwo.csv" : "keyFileThree.csv";
			confirmLayoutReload("Load Keyboard Layout " + preset, "Any unsaved buttons will be lost", false,
					preset == 1 ? LegacyLayoutProfileMigrator.PRESET_ONE_PROFILE_ID : preset == 2
							? LegacyLayoutProfileMigrator.PRESET_TWO_PROFILE_ID : LegacyLayoutProfileMigrator.PRESET_THREE_PROFILE_ID);
			return true;
		} else if (id == R.id.action_session_controls) {
			showSessionControls();
			return true;
		} else if (id == R.id.action_conn_disc) {
			if (hasActiveOrPendingSession()) disconnectForegroundSessionFromUi();
			else requestForegroundSessionFromUi();
			return true;
		} else if (id == R.id.action_send_clipboard) {
			sendClipboardToLinux();
			return true;
		} else if (id == R.id.action_restore_default_keys) {
			KEYLOAYOUTFILENAME = "keyLayoutFile.csv";
			confirmLayoutReload("Restore default Keyboard?","Any unsaved buttons will be lost",true,
					LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID);
		} else if (id == R.id.action_save_preset_one) {
			KEYLOAYOUTFILENAME = "keyFileOne.csv";
			confirmSaveLayout("Save as Keyboard Layout 1", "Any previous layout data will be overwritten", false,
					LegacyLayoutProfileMigrator.PRESET_ONE_PROFILE_ID);
		} else if (id == R.id.action_save_preset_two) {
			KEYLOAYOUTFILENAME = "keyFileTwo.csv";
			confirmSaveLayout("Save as Keyboard Layout 2", "Any previous layout data will be overwritten", false,
					LegacyLayoutProfileMigrator.PRESET_TWO_PROFILE_ID);
		} else if (id == R.id.action_save_preset_three) {
			KEYLOAYOUTFILENAME = "keyFileThree.csv";
			confirmSaveLayout("Save as Keyboard Layout 3", "Any previous layout data will be overwritten", false,
					LegacyLayoutProfileMigrator.PRESET_THREE_PROFILE_ID);
		} else if (id == R.id.action_lock_keys) {
			setting_keyboard_locked=!setting_keyboard_locked;
			item.setChecked(setting_keyboard_locked);
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			SharedPreferences.Editor editor = prefs.edit();
			editor.putBoolean("keyboard_layout_locked", setting_keyboard_locked);
			editor.commit();
		} else if (id == R.id.action_invert_scroll) {
			setting_invert_scroll=!setting_invert_scroll;
			item.setChecked(setting_invert_scroll);
			SharedPreferences prefs2 = PreferenceManager.getDefaultSharedPreferences(this);
			SharedPreferences.Editor editor2 = prefs2.edit();
			editor2.putBoolean("mouse_invert_scroll", setting_invert_scroll);
			editor2.commit();
		} else if (id == R.id.action_edit_layout) {
			openLayoutEditor();
		} else if (id == R.id.action_manage_layouts) {
			openLayoutProfileManager();
			return true;
		} else if (id == R.id.action_exit) {
			// Exit is an explicit session dismissal, unlike ordinary Activity pause/destruction.
			disconnectForegroundSessionFromUi();
			this.finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private boolean hasActiveOrPendingSession() {
		if (pendingForegroundStartTicket != null || SessionForegroundService.hasPendingStart(this)) return true;
		if (sessionSnapshot == null) return SessionForegroundService.hasRetainedSession(this);
		ConnectionState.Phase phase = sessionSnapshot.getConnection().getPhase();
		return phase == ConnectionState.Phase.PREPARING_CREDENTIALS || phase == ConnectionState.Phase.RESOLVING ||
				phase == ConnectionState.Phase.CONNECTING || phase == ConnectionState.Phase.AWAITING_TRUST ||
				phase == ConnectionState.Phase.AUTHENTICATING || phase == ConnectionState.Phase.CONNECTED ||
				phase == ConnectionState.Phase.CLOSING;
	}

	private void requestForegroundSessionForSelectedHost() {
		cancelPendingForegroundSessionRequest();
		pendingHostSelectionRequest = true;
		pendingForegroundStartTicket = SessionForegroundService.reserveConnectRequest(this, false);
		if (pendingForegroundStartTicket == null) {
			pendingHostSelectionRequest = false;
			android.widget.Toast.makeText(this, R.string.session_start_rejected, android.widget.Toast.LENGTH_LONG).show();
			return;
		}
		continueForegroundSessionRequest();
	}
	public void showNoticeDialog(Context mCont) {
		// Create an instance of the dialog fragment and show it
		myDialogFragment dialog = new myDialogFragment();
		dialog.setmCont(mCont);
		dialog.setText(getKeyboardTextValue());

		dialog.show(fm, "NoticeDialogFragment");
	}
	public boolean onNavigationItemSelected(MenuItem item) {
		// Compatibility bridge for existing callers; primary UI routes are native shell controls.
		int id = item.getItemId();
		int group = item.getGroupId();
		//Toast.makeText(getBaseContext(),String.valueOf(id),Toast.LENGTH_LONG).show();
		switch(group) {
			case 1: //hosts

				if(id==999999999){
					//manage sripts ID

					openLegacyManager(0);

				}else {
					selectHostAndConnect(id);
				}

				break;
			case 3: //keyboard layout files group
					switch (id) {

					case 61:
						KEYLOAYOUTFILENAME = "keyFileOne.csv";
						confirmLayoutReload("Load Keyboard Layout 1", "Any unsaved buttons will be lost", false,
								LegacyLayoutProfileMigrator.PRESET_ONE_PROFILE_ID);
						break;
					case 62:
						KEYLOAYOUTFILENAME = "keyFileTwo.csv";
						confirmLayoutReload("Load Keyboard Layout 2", "Any unsaved buttons will be lost", false,
								LegacyLayoutProfileMigrator.PRESET_TWO_PROFILE_ID);
						break;
					case 63:
						KEYLOAYOUTFILENAME = "keyFileThree.csv";
						confirmLayoutReload("Load Keyboard Layout 3", "Any unsaved buttons will be lost", false,
								LegacyLayoutProfileMigrator.PRESET_THREE_PROFILE_ID);
						break;
					case 64:
						xMouseNewCustomButton(null);
						break;
					default:
						break;
				}
				break;

			case 5: //scripts;

				if(id==19999999){
					//manage sripts ID

					openLegacyManager(1);

				}else {

					ArrayList<ArrayList<String>> script = db.getRowWithId(DatabaseHandler.SCRIPT_TABLE_NAME,
							new String[]{"Alias", "Script", "id"}, String.valueOf(id));

					if (script.size() > 0) {
						String cmd = script.get(0).get(1);
						SessionInteractions.PresentationToken presentation = sessionPresenter == null
								? null : sessionPresenter.presentationToken();
						if (commandTransport == null || !commandTransport.executeExecCommand(cmd, presentation)) {
							Toast.makeText(this, "Connect to a computer before running a script.", Toast.LENGTH_LONG).show();
						}
					} else {

						Toast.makeText(getBaseContext(), "Error finding Script command with id=" + id, Toast.LENGTH_LONG).show();
					}
				}
				break;
			default:
				break;
		}

		return true;
	}

	public void selectHostAndConnect(int id) {
		final long activityToken = activityGeneration;
		final long selectionToken = ++hostSelectionGeneration;
		if (promptInteractions != null) promptInteractions.invalidate();
		final com.stripe1.xmouse.secrets.CredentialRepository repository = AndroidCredentials.get(getApplicationContext());
		repository.execute(() -> {
			try {
				repository.selectHost(id);
				mainHandler.post(() -> {
					if (activityToken != activityGeneration || selectionToken != hostSelectionGeneration ||
							isFinishing() || isDestroyed() || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
					getPreferences();
					loadComputerMetadata();
					requestForegroundSessionForSelectedHost();
				});
			} catch (Exception ignored) {
				mainHandler.post(() -> {
					if (activityToken == activityGeneration && selectionToken == hostSelectionGeneration &&
							!isFinishing() && !isDestroyed()) {
						Toast.makeText(this, "Host selection could not be saved. Existing credentials were kept.", Toast.LENGTH_LONG).show();
					}
				});
			}
		});
	}


	@SuppressLint("RestrictedApi")
	@Override
	public boolean onItemLongClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
		if(!MainActivity.setting_keyboard_locked){
			shellNavigation.open(ShellNavigation.Overlay.ACTION_EDIT);
			mCoolDragAndDropGridView.startDragAndDrop();
			EditKeyboardButtonsLayout.setVisibility(View.VISIBLE);
			ETLayout.setVisibility(View.GONE);
		}
		return false;
	}

	@Override
	public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {

	}

	@Override
	public void onDragItem(int from) {

	}

	@Override
	public void onDraggingItem(int from, int to) {

	}

	@SuppressLint("RestrictedApi")
	@Override
	public void onDropItem(int from, int to) {
		if (from != to) {

			mItems.add(to, mItems.remove(from));
			mItemAdapter.notifyDataSetChanged();
			saveKeyboardLayout();
		}

		potentialDeletePosition = to;
		startPosition = from;
		EditKeyboardButtonsLayout.setVisibility(View.GONE);
		ETLayout.setVisibility(View.VISIBLE);
		shellNavigation.close(ShellNavigation.Overlay.ACTION_EDIT);
	}

	@Override public void onDragCancelled() {
		if (mItemAdapter != null) mItemAdapter.notifyDataSetChanged();
		if (EditKeyboardButtonsLayout != null) EditKeyboardButtonsLayout.setVisibility(View.GONE);
		if (shellNavigation != null) shellNavigation.close(ShellNavigation.Overlay.ACTION_EDIT);
	}

	@Override
	public boolean isDragAndDropEnabled(int position) {
		return true;
	}
	@Override
	public void onEditItem(int mDragPosition) {
		//Log.d("onDeleteItem", ""+mDragPosition);
		//mItems.remove(mDragPosition);
		//mItemAdapter.notifyDataSetChanged();

		onDropItem(potentialDeletePosition,startPosition);
		//Toast.makeText(getBaseContext(),"Edit item "+potentialDeletePosition,Toast.LENGTH_LONG).show();
		xMouseNewCustomButton(potentialDeletePosition);

	}
	@Override
	public void onDeleteItem(int mDragPosition) {
		//Log.d("onDeleteItem", ""+mDragPosition);

		try {
			mItems.remove(potentialDeletePosition);
			mItemAdapter.notifyDataSetChanged();
			saveKeyboardLayout();
		}catch (Exception ignored){
			Log.w(TAG, "layout_button_delete_failed");
			Toast.makeText(getBaseContext(),"Error during delete",Toast.LENGTH_LONG).show();
			mItemAdapter.notifyDataSetChanged();
		}
		Toast.makeText(getBaseContext(),"Remove item "+potentialDeletePosition,Toast.LENGTH_LONG).show();
	}
	private LayoutProfileActivation activateLayoutProfiles() {
		try {
			layoutProfileRepository = new LayoutProfileRepository(new File(getFilesDir(), "layout-profiles"));
			layoutProfileRuntime = new LayoutProfileRuntime(
					layoutProfileRepository,
					new LegacyLayoutProfileMigrator(getFilesDir(), layoutProfileRepository));
			return layoutProfileRuntime.activate();
		} catch (Exception ignored) {
			layoutProfileRepository = null;
			Log.w(TAG, "layout_profile_activation_failed");
			return new LayoutProfileActivation(null,
					"Layout profiles could not be activated. Check retained layout files and storage, then restart; legacy layouts remain available read-only.");
		}
	}

	private boolean isRepositoryLayoutActive() {
		return layoutProfileRuntime != null && layoutProfileRuntime.isRepositoryActive();
	}

	private void openLayoutEditor() {
		if (!isRepositoryLayoutActive() || layoutProfileRepository == null) {
			Toast.makeText(this, "The layout editor is unavailable until saved profiles are safely activated.",
					Toast.LENGTH_LONG).show();
			return;
		}
		try {
			LayoutProfile selected = layoutProfileRepository.loadSelectedProfile();
			if (selected == null) {
				Toast.makeText(this, "No saved layout is selected.", Toast.LENGTH_LONG).show();
				return;
			}
			Intent intent = new Intent(this, LayoutEditorActivity.class);
			intent.putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, selected.getId());
			returningFromLayoutEditor = true;
			openExternalScreen(intent);
		} catch (Exception ignored) {
			returningFromLayoutEditor = false;
			Toast.makeText(this, "The selected layout could not be opened.", Toast.LENGTH_LONG).show();
		}
	}

	private void openLayoutProfileManager() {
		if (!isRepositoryLayoutActive() || layoutProfileRepository == null) {
			Toast.makeText(this, "Named layouts are unavailable until saved profiles are safely activated.",
					Toast.LENGTH_LONG).show();
			return;
		}
		try {
			returningFromLayoutManager = true;
			openExternalScreen(new Intent(this, LayoutProfileManagerActivity.class));
		} catch (Exception ignored) {
			returningFromLayoutManager = false;
			Toast.makeText(this, "The layout manager could not be opened.", Toast.LENGTH_LONG).show();
		}
	}

	/** Rebind normal controls only from the repository's saved selected profile after editor return. */
	private void reloadSelectedSavedLayout() {
		if (!isRepositoryLayoutActive() || layoutProfileRepository == null) return;
		try {
			LayoutProfile selected = layoutProfileRuntime.loadSelectedProfile();
			if (selected == null) return;
			mItems = customButtonsFromProfile(selected);
			if (mCoolDragAndDropGridView != null) {
				mItemAdapter = new CustomKeyboardButtonAdapter(this, mItems);
				mCoolDragAndDropGridView.setAdapter(mItemAdapter);
				mItemAdapter.notifyDataSetChanged();
			}
		} catch (Exception ignored) {
			Log.w(TAG, "layout_profile_reload_failed");
			Toast.makeText(this, "The saved layout could not be reloaded.", Toast.LENGTH_LONG).show();
		}
	}

	private LinkedList<CustomKeyboardButton> customButtonsFromProfile(LayoutProfile profile) {
		LinkedList<CustomKeyboardButton> buttons = new LinkedList<>();
		layoutButtonIds.clear();
		for (LayoutButton button : profile.getButtons()) {
			CustomKeyboardButton customButton = new CustomKeyboardButton(
					0, button.getWidth(), button.getTitle(), button.getCommand(), button.getColor());
			buttons.add(customButton);
			layoutButtonIds.put(customButton, button.getId());
		}
		return buttons;
	}

	private void assignFreshLayoutButtonIds(List<CustomKeyboardButton> buttons) {
		layoutButtonIds.clear();
		for (CustomKeyboardButton button : buttons) {
			layoutButtonIds.put(button, UUID.randomUUID().toString());
		}
	}

	private List<LayoutButton> layoutButtonsForRepository() {
		List<LayoutButton> buttons = new ArrayList<>();
		for (CustomKeyboardButton button : mItems) {
			String id = layoutButtonIds.get(button);
			if (id == null) {
				id = UUID.randomUUID().toString();
				layoutButtonIds.put(button, id);
			}
			buttons.add(new LayoutButton(id, button.getmTitle(), button.getmCommand(),
					button.getmSpans(), button.getmColor()));
		}
		return buttons;
	}

	private void saveCurrentRepositoryLayout() {
		if (!isRepositoryLayoutActive()) return;
		try {
			layoutProfileRuntime.saveCurrentProfile(layoutButtonsForRepository());
		} catch (Exception ignored) {
			Log.w(TAG, "layout_profile_save_failed");
			Toast.makeText(this, "Keyboard layout could not be saved.", Toast.LENGTH_LONG).show();
		}
	}

	private void saveRepositoryLayoutAs(String profileId) {
		if (!isRepositoryLayoutActive()) return;
		try {
			layoutProfileRuntime.saveProfileAs(profileId, presetNameForId(profileId), layoutButtonsForRepository());
		} catch (Exception ignored) {
			Log.w(TAG, "layout_profile_save_failed");
			Toast.makeText(this, "Keyboard layout could not be saved.", Toast.LENGTH_LONG).show();
		}
	}

	private String presetNameForId(String profileId) {
		if (LegacyLayoutProfileMigrator.PRESET_ONE_PROFILE_ID.equals(profileId)) return "Preset 1";
		if (LegacyLayoutProfileMigrator.PRESET_TWO_PROFILE_ID.equals(profileId)) return "Preset 2";
		if (LegacyLayoutProfileMigrator.PRESET_THREE_PROFILE_ID.equals(profileId)) return "Preset 3";
		return "Saved layout";
	}

	public LinkedList<CustomKeyboardButton> loadKeyboardLayout(Context mCont,String stream){
		LinkedList<CustomKeyboardButton> views = new LinkedList<CustomKeyboardButton>();
		try{
			//BufferedReader reader = new BufferedReader(stream);
			String[] lines = stream.split("\n");
			String line=null;
			for(int i=0;i<lines.length;i++){
				line = lines[i];
				//sb.append(line).append("\n");
				LegacyLayoutReader.Record record = LegacyLayoutReader.parseRecord(line, "#ffffff");
				if(record.isSeparatorFormat() && (record.getSpan()<1 || record.getSpan()>10)){
					Toast.makeText(mCont, "You have included an invalid span integer, try again", Toast.LENGTH_SHORT).show();
					return null;
				}
				views.add(new CustomKeyboardButton(
						0, record.getSpan(), record.getTitle(), record.getCommand(), record.getColor()));
			}
			//fis.close();
		} catch(OutOfMemoryError ignored){
			Log.e(TAG, "layout_parse_resource_failure");
			Toast.makeText(mCont, "Keyboard layout could not be loaded.", Toast.LENGTH_LONG).show();
			return null;
		} catch(Exception ignored){
			Log.w(TAG, "layout_parse_failed");
			Toast.makeText(mCont, "Keyboard layout could not be loaded.", Toast.LENGTH_LONG).show();
			return null;
		}

		if(views.size()<1){
			views = loadDefaultKeyboardLayout(mCont);
		}

		return views;
	}
	public LinkedList<CustomKeyboardButton> loadKeyboardLayout(Context mCont,boolean def){

		if(def){
			return loadDefaultKeyboardLayout(mCont);
		}

		LinkedList<CustomKeyboardButton> views = new LinkedList<CustomKeyboardButton>();
		FileInputStream fis;
		try {
			fis = mCont.openFileInput(KEYLOAYOUTFILENAME);

			//StringBuilder sb = new StringBuilder();
			try{
				BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
				String line = null;
				while ((line = reader.readLine()) != null) {
					//sb.append(line).append("\n");
					LegacyLayoutReader.Record record = LegacyLayoutReader.parseRecord(line, "#FFFFFF");
					views.add(new CustomKeyboardButton(
							0, record.getSpan(), record.getTitle(), record.getCommand(), record.getColor()));
				}
				fis.close();
			} catch(OutOfMemoryError ignored){
				Log.e(TAG, "layout_read_resource_failure");
			} catch(Exception ignored){
				Log.w(TAG, "layout_parse_failed");
			}
			//String result = sb.toString();
			//Toast.makeText(mCont, result, Toast.LENGTH_LONG).show();

		} catch (Exception ignored) {
			Log.w(TAG, "layout_read_failed");
		}

		if(views.size()<1){
			views = loadDefaultKeyboardLayout(mCont);
		}

		return views;
	}
	public LinkedList<CustomKeyboardButton> loadDefaultKeyboardLayout(Context mCont){
		LinkedList<CustomKeyboardButton> views = new LinkedList<CustomKeyboardButton>();

		//new default design by Xalalau Xubilozo, Brazil
		views.add(new CustomKeyboardButton( 0, 2,"Esc","key Escape","#FFe6e6"));
		views.add(new CustomKeyboardButton( 0, 3,"Minimize","xdotool windowminimize $(xdotool getactivewindow)","#9FFF80"));
		views.add(new CustomKeyboardButton( 0, 3,"Maximize","xdotool windowsize $(xdotool getactivewindow) 100% 100%","#FFCC66"));
		views.add(new CustomKeyboardButton( 0, 2,"X","key alt+F4","#FF9999"));
		views.add(new CustomKeyboardButton( 0, 2,"F1","key F1","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F2","key F2","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F3","key F3","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F4","key F4","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F5","key F5","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F6","key F6","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F7","key F7","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F8","key F8","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F9","key F9","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F10","key F10","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F11","key F11","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"F12","key F12","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 3,"+Brightness","key XF86MonBrightnessUp","#e6f7ff"));
		views.add(new CustomKeyboardButton( 0, 3,"-Brightness","key XF86MonBrightnessDown","#e6f7ff"));
		views.add(new CustomKeyboardButton( 0, 2,"Super","key super","#80d4ff"));
		views.add(new CustomKeyboardButton( 0, 3,"+Volume","key XF86AudioRaiseVolume","#B3E6FF"));
		views.add(new CustomKeyboardButton( 0, 3,"-Volume","key XF86AudioLowerVolume","#B3E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Mute","key XF86AudioMute","#B3E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Alt|Tab","key alt+Tab","#80d4ff"));
		views.add(new CustomKeyboardButton( 0, 3,"[Prev]","key XF86AudioPrev","#B3E6FF"));
		views.add(new CustomKeyboardButton( 0, 3,"[Next]","key XF86AudioNext","#B3E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"[Play]","key XF86AudioPlay","#B3E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"New","key ctrl+n","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Open","key ctrl+o","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Save","key ctrl+s","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Find","key ctrl+f","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Print","key ctrl+p","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 4,"Select all","key ctrl+a","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 3,"+Zoom","key ctrl+plus","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 3,"-Zoom","key ctrl+minus","#E6B3FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Undo","key ctrl+z","#F7E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Redo","key ctrl+y","#F7E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Copy","key ctrl+c","#F7E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Crop","key ctrl+x","#F7E6FF"));
		views.add(new CustomKeyboardButton( 0, 2,"Paste","key ctrl+v","#F7E6FF"));
		views.add(new CustomKeyboardButton( 0, 3,"PgUp","key Prior","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 3,"PgDown","key Next","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 4,"Delete","key Delete","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"Home","key Home","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"Up","key Up","#FFFFB3"));
		views.add(new CustomKeyboardButton( 0, 2,"End","key End","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 4,"Backspace","key BackSpace","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 2,"Left","key Left","#FFFFB3"));
		views.add(new CustomKeyboardButton( 0, 2,"Down","key Down","#FFFFB3"));
		views.add(new CustomKeyboardButton( 0, 2,"Right","key Right","#FFFFB3"));
		views.add(new CustomKeyboardButton( 0, 4,"Enter","key Return","#FFFFFF"));
		views.add(new CustomKeyboardButton( 0, 5,"<Back","key XF86Back","#f2e5d9"));
		views.add(new CustomKeyboardButton( 0, 5,"Forward>","key XF86Forward","#f2e5d9"));
		views.add(new CustomKeyboardButton( 0, 1,"+","key ctrl+t","#e6ccb3"));
		views.add(new CustomKeyboardButton( 0, 1,"-","key ctrl+w","#e6ccb3"));
		views.add(new CustomKeyboardButton( 0, 4,"Reopen Last Tab","key ctrl+shift+t","#e6ccb3"));
		views.add(new CustomKeyboardButton( 0, 2,"<-","key ctrl+shift+Tab","#e6ccb3"));
		views.add(new CustomKeyboardButton( 0, 2,"->","key ctrl+Tab","#e6ccb3"));

		return views;
	}
	public String getKeyboardTextValue(){

		StringBuilder sb = new StringBuilder();
		for(int x=0;x<mItems.size();x++){
			sb.append(mItems.get(x).getmTitle())
					.append("<xmousesep>").append(mItems.get(x).getmCommand())
					.append("<xmousesep>").append(mItems.get(x).getmSpans())
					.append("<xmousesep>").append(mItems.get(x).getmColor());

			if(x!=mItems.size()-1){
				sb.append("\n");
			}
		}
		return sb.toString();
	}
	public void saveKeyboardLayout(){
		if (isRepositoryLayoutActive()) {
			saveCurrentRepositoryLayout();
			return;
		}
		if (legacyLayoutFallbackReadOnly) {
			Log.w(TAG, "layout_recovery_source_write_blocked");
			return;
		}

		try{
			FileOutputStream fos = openFileOutput(KEYLOAYOUTFILENAME, Context.MODE_PRIVATE);
			fos.write(getKeyboardTextValue().getBytes());
			fos.close();
			//Toast.makeText(getBaseContext(), "Saved", Toast.LENGTH_SHORT).show();
		}catch(Exception ignored){
			Log.w(TAG, "layout_save_failed");
			Toast.makeText(getBaseContext(), "Keyboard layout could not be saved.", Toast.LENGTH_LONG).show();
		}
	}
	public void xMouseNewCustomButton(final Integer id){
		//open dialog for new command

		//Preparing views
		LayoutInflater inflater = (LayoutInflater) getLayoutInflater();
		View layout = inflater.inflate(R.layout.new_button_dialog, null);
		//layout_root should be the name of the "top-level" layout node in the dialog_layout.xml file.
		final EditText nameBox = (EditText) layout.findViewById(R.id.new_command_name);
		final EditText script = (EditText) layout.findViewById(R.id.new_command_command);
		final EditText spans = (EditText) layout.findViewById(R.id.new_command_width);
		final EditText color = (EditText) layout.findViewById(R.id.new_command_hex);
		//Building dialog
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setView(layout);
		if(id != null) {
			builder.setTitle("Edit Button");

			nameBox.setText(mItems.get(id).getmTitle());
			script.setText(mItems.get(id).getmCommand());
			spans.setText(String.valueOf(mItems.get(id).getmSpans()));
			color.setText(mItems.get(id).getmColor());

		}else{
			builder.setTitle("New Button");
		}
		builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {

				String name = nameBox.getText().toString();
				String content = script.getText().toString();
				String spanStr = spans.getText().toString();
				String colorStr = color.getText().toString();

				if (!spanStr.isEmpty()) {

					if (name.length() > 0 && content.length() > 0) {
						int span = Integer.valueOf(spanStr);

						if (span > 0 && span < 11) {


							if(id!=null){

								mItems.get(id).setName(name);
								mItems.get(id).setSpans(span);
								mItems.get(id).setmCommand(content);
								mItems.get(id).setColor(colorStr);

							}else {
								mItems.add(new CustomKeyboardButton(0, span, name, content, colorStr));
							}

							mItemAdapter = new CustomKeyboardButtonAdapter(MainActivity.this, mItems);
							mCoolDragAndDropGridView.setAdapter(mItemAdapter);
							mItemAdapter.notifyDataSetChanged();
							saveKeyboardLayout();

							Toast.makeText(getBaseContext(), "Button \"" + name + "\" saved to main layout", Toast.LENGTH_LONG).show();

							dialog.dismiss();
						} else {
							Toast.makeText(getBaseContext(), "Width must be between 1 and 10", Toast.LENGTH_LONG).show();

						}
					} else {

						Toast.makeText(getBaseContext(), "A required value was empty, button not saved", Toast.LENGTH_LONG).show();

					}
				} else {
					Toast.makeText(getBaseContext(), "A required value was empty, button not saved", Toast.LENGTH_LONG).show();
				}
			}
		});
		builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		AlertDialog dialog = builder.create();
		showOwnedDialog(dialog);
		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		}


	}
	private void useKeyboardSendText(IDoTool inputTool) {
		String text = ET.getText().toString();
		if (inputTool != null && !text.isEmpty()) {
			inputTool.type(ShellTextEncoder.quoteForShell(text));
			if(setting_keyboard_autoclear){
				ET.setText("");
			}
		}
	}


	@Override public void showRecentCommand(String command) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			mainHandler.post(() -> showRecentCommand(command));
			return;
		}
		if (recentCmdTextView != null && !isDestroyed()) recentCmdTextView.setText(command);
	}

	@Override public void performCallback() {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			mainHandler.post(this::performCallback);
			return;
		}
		if (!isDestroyed()) showConnectionStat();
	}

	@Override
	public void performCallback2(Integer i) {

	}

}
