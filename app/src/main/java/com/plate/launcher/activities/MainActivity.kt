package com.plate.launcher.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.widget.ViewSwitcher
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R
import com.plate.launcher.dialogs.DockLayoutDialog
import com.plate.launcher.fragments.PreferencesFragment
import com.plate.launcher.fragments.MultiUserFragment
import com.plate.launcher.utils.ShizukoManager
import com.plate.launcher.utils.RootManager
import com.plate.launcher.services.NotificationService
import kotlinx.coroutines.launch
import com.plate.launcher.utils.ColorUtils
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.DeviceUtils
import kotlin.reflect.KFunction0
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

class MainActivity : BaseFontScaleActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    /**
     * FIX: تنقل موحّد بأنيميشن Pixel-style بين كل صفحات الإعدادات.
     *
     * المشكلة القديمة: صفحات الإعدادات الفرعية (app:fragment= بالـ XML) كانت
     * تستخدم الـ default AndroidX fragment animation (أو بدون animation)، بينما
     * الـ Activities مثل GalleryActivity تستخدم windowAnimationStyle (عمودي).
     * النتيجة: إحساس إن الخلفيات المتحركة "معكوسة" عن باقي الصفحات.
     *
     * الحل: نطبق setCustomAnimations على كل fragment transaction بأنيميشن
     * Pixel-style موحّد (fade + scale خفيف) لكل الصفحات بدون استثناء.
     */
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: androidx.preference.Preference
    ): Boolean {
        // Patched: About/Contributors rows looked clickable but did nothing.
        // same root cause as applyOpenFragmentExtra() below — this callback
        // had no debounce of its own, so a fast double-tap on a preference
        // row (or a tap landing while the previous row's ripple/transition
        // was still settling) fired onPreferenceStartFragment() twice in
        // quick succession. That committed two overlapping fragment
        // instances into settings_container at once: the top one is what
        // renders, but the other one (attached a beat later/earlier) sits
        // over it and intercepts touches, so every row underneath — GitHub,
        // Discord, the contributor rows — visually exists but never
        // responds to taps. Reusing the same lastFragmentSwitchAt/260ms
        // debounce as applyOpenFragmentExtra() (and sharing the one
        // timestamp across both entry points) collapses a same-instant
        // double-fire into a single transaction, the same fix already
        // proven there.
        val now = System.currentTimeMillis()
        if (now - lastFragmentSwitchAt < 260L) return false
        lastFragmentSwitchAt = now

        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, pref.fragment ?: return false)
        fragment.arguments = pref.extras

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_open_enter,
                R.anim.fragment_open_exit,
                R.anim.fragment_close_enter,
                R.anim.fragment_close_exit
            )
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var permissionsDialog: AlertDialog
    private lateinit var overlayBtn:         MaterialButton
    private lateinit var storageBtn:         MaterialButton
    private lateinit var adminBtn:           MaterialButton
    private lateinit var notificationsBtn:   MaterialButton
    private lateinit var accessibilityBtn:   MaterialButton
    private lateinit var settingsOverlays:   MaterialButton
    private lateinit var recentAppsBtn:      MaterialButton
    private lateinit var secureBtn:          MaterialButton
    private lateinit var defaultLauncherBtn: MaterialButton
    private lateinit var shizukuBtn:         MaterialButton

    private var canDrawOverOtherApps       = false
    private var hasStoragePermission       = false
    private var isDeviceAdminEnabled       = false
    private var settingsOverlaysAllowed    = false

    // FIX: Receives broadcast from DockService when SYSTEM_ALERT_WINDOW is missing
    // so we can immediately show the permissions dialog to the user.
    private val overlayMissingReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!isFinishing) showPermissionsDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!com.plate.launcher.utils.OnboardingPrefs.isOnboardingComplete(this)) {
            startActivity(Intent(this, com.plate.launcher.onboarding.OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_settings)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_permissions)
            .setOnClickListener { showPermissionsDialog() }
        animateSettingsEntrance()

        ContextCompat.registerReceiver(
            this, overlayMissingReceiver,
            IntentFilter("com.plate.launcher.OVERLAY_PERMISSION_MISSING"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Support direct navigation from the Dock to any fragment
        applyOpenFragmentExtra(intent)
        if (!DeviceUtils.hasStoragePermission(this))
            DeviceUtils.requestStoragePermissions(this)
        if (!DeviceUtils.canDrawOverOtherApps(this) || !DeviceUtils.isAccessibilityServiceEnabled(this))
            showPermissionsDialog()
        if (sharedPreferences.getInt("dock_layout", -1) == -1)
            DockLayoutDialog(this)

        // Show "Add desktop shortcut" button if it has not been added yet
        if (!sharedPreferences.getBoolean("shortcut_pinned", false)) {
            pinShortcutToHomeScreen()
        }
    }

    /**
     * FIX (multi-instance stacking): MainActivity is now launchMode="singleTask"
     * (see AndroidManifest.xml) so the dock/power-menu "Settings" shortcuts
     * always reuse the same instance instead of piling up a new task every
     * tap (the old FLAG_ACTIVITY_MULTIPLE_TASK behavior). With singleTask,
     * a second launch doesn't call onCreate() again — it delivers the new
     * Intent here instead, so we must re-apply "open_fragment" ourselves or
     * a request like "open Multi-User" while Settings is already open in
     * the background would silently be ignored.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenFragmentExtra(intent)
    }

    private var lastFragmentSwitchAt = 0L

    private fun applyOpenFragmentExtra(intent: Intent?) {
        // Bug fix — settings list showed every row doubled/staggered. MainActivity
        // is launchMode="singleTask" specifically so repeat taps on any of
        // the dock's "Settings" shortcuts (App Menu gear, power menu, etc.)
        // reuse this same instance via onNewIntent() instead of stacking a
        // new task — but nothing debounced onNewIntent() itself. A fast
        // double-tap (or a tap landing while the previous open's 260ms
        // fade-in from animateSettingsEntrance was still running) fired
        // applyOpenFragmentExtra() twice in quick succession: the second
        // .replace() committed a fresh PreferenceFragmentCompat (a fresh
        // RecyclerView) into settings_container before the first one's view
        // had actually finished being torn down, so both were briefly
        // attached and rendering over each other — every row appearing
        // twice, offset by whatever each RecyclerView's own scroll/layout
        // pass happened to settle on. Debouncing at the same 260ms as the
        // entrance animation means a genuine repeat "open X fragment"
        // request (a different open_fragment extra arriving deliberately)
        // still goes through immediately after that window, while a
        // same-instant double-tap collapses into a single transaction.
        val now = System.currentTimeMillis()
        if (now - lastFragmentSwitchAt < 260L) return
        lastFragmentSwitchAt = now

        val openFrag = intent?.getStringExtra("open_fragment")
        val startFragment = when (openFrag) {
            "multi_user"  -> MultiUserFragment()
            "plugins", "performance" -> com.plate.launcher.fragments.PluginsFragment()
            else          -> PreferencesFragment()
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_open_enter,
                R.anim.fragment_open_exit,
                R.anim.fragment_close_enter,
                R.anim.fragment_close_exit
            )
            .replace(R.id.settings_container, startFragment)
            .commit()
    }

    private fun pinShortcutToHomeScreen() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) return

        val shortcutIntent = Intent(this, DesktopOverlayActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val shortcutInfo = ShortcutInfo.Builder(this, "youki_desktop_shortcut")
            .setShortLabel("PLate DEX")
            .setLongLabel("PLate Desktop Mode")
            // ic_youki removed: it's now a wide 16:9 image used only for the
            // onboarding welcome screen (fragment_onboarding_welcome.xml).
            // A pinned home-screen shortcut icon is masked into a square/
            // circle by the launcher, so it needs the square app icon
            // instead — same as ic_launcher.
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .build()

        sm.requestPinShortcut(shortcutInfo, null)
        sharedPreferences.edit().putBoolean("shortcut_pinned", true).apply()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionsDialog.isInitialized && permissionsDialog.isShowing)
            updatePermissionsStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(overlayMissingReceiver) } catch (e: Exception) {}
    }

    /**
     * Soft, calm entrance for the settings screen — the whole root column fades in and
     * rises slightly (matching the per-row stagger in PreferenceHeader.kt), instead of the
     * previous hard-cut appearance with no transition at all.
     */
    private fun animateSettingsEntrance() {
        val root = findViewById<View>(R.id.settings_root_column)
        root.alpha = 0f
        root.translationY = 40f
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    // Permissions Dialog
    private fun showPermissionsDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.manage_permissions)
        val view        = layoutInflater.inflate(R.layout.dialog_permissions, null)
        val viewSwitcher = view.findViewById<ViewSwitcher>(R.id.permissions_view_switcher)
        val requiredBtn  = view.findViewById<Button>(R.id.show_required_button)
        val optionalBtn  = view.findViewById<Button>(R.id.show_optional_button)
        overlayBtn         = view.findViewById(R.id.btn_grant_overlay)
        storageBtn         = view.findViewById(R.id.btn_grant_storage)
        adminBtn           = view.findViewById(R.id.btn_grant_admin)
        notificationsBtn   = view.findViewById(R.id.btn_grant_notifications)
        accessibilityBtn   = view.findViewById(R.id.btn_manage_service)
        settingsOverlays   = view.findViewById(R.id.btn_manage_settings_overlays)
        recentAppsBtn      = view.findViewById(R.id.btn_manage_recent_apps)
        secureBtn          = view.findViewById(R.id.btn_manage_secure)
        defaultLauncherBtn = view.findViewById(R.id.btn_set_default_launcher)
        shizukuBtn         = view.findViewById(R.id.btn_manage_shizuku)
        // The new buttons
        val manageStorageBtn    = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_manage_external_storage)
        val postNotifBtn        = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_post_notifications)
        val rootBtn             = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_root_access)
        val bluetoothBtn        = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_bluetooth)
        val writeSystemBtn      = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_write_system_settings)
        val readMediaBtn        = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_read_media)
        builder.setView(view)
        permissionsDialog = builder.create()

        overlayBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.display_over_other_apps,
                R.string.display_over_other_apps_desc,
                ::grantOverlayPermissions, canDrawOverOtherApps)
        }
        storageBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.storage, R.string.storage_desc,
                ::requestStoragePermissions, hasStoragePermission)
        }
        adminBtn.setOnClickListener {
            showDeviceAdminDialog()
        }
        accessibilityBtn.setOnClickListener  { showAccessibilityDialog() }
        settingsOverlays.setOnClickListener  {
            showPermissionInfoDialog(R.string.overlays_in_settings,
                R.string.overlays_in_settings_desc, null, true)
        }
        recentAppsBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.recent_apps, R.string.recent_apps_desc,
                ::requestRecentAppsPermission, DeviceUtils.hasRecentAppsPermission(this))
        }
        // FIX: wire up the notifications button to showNotificationsDialog (it wasn't wired up!)
        notificationsBtn.setOnClickListener { showNotificationsDialog() }
        // WRITE_SECURE_SETTINGS - auto-grant if Shizuku ready, else show dialog
        secureBtn.setOnClickListener { handleWriteSecureSettings() }
        // Shizuku - request permission
        shizukuBtn.setOnClickListener { showShizukuDialog() }
        defaultLauncherBtn.setOnClickListener { showDefaultLauncherDialog() }

        // MANAGE_EXTERNAL_STORAGE
        manageStorageBtn.setOnClickListener {
            if (DeviceUtils.hasManageExternalStorage()) {
                Toast.makeText(this, getString(R.string.all_files_access_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                DeviceUtils.requestManageExternalStorage(this)
            }
        }

        // POST_NOTIFICATIONS
        postNotifBtn.setOnClickListener {
            if (DeviceUtils.hasPostNotificationsPermission(this)) {
                Toast.makeText(this, getString(R.string.notifications_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                DeviceUtils.requestPostNotifications(this)
            }
        }

        // Root Access — display only (auto-detected)
        rootBtn.setOnClickListener {
            val available = DeviceUtils.isRootAvailable(this)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.root_access)
                .setMessage(if (available) getString(R.string.root_access_detected) else getString(R.string.root_access_not_detected))
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        // Bluetooth
        bluetoothBtn.setOnClickListener {
            if (DeviceUtils.hasBluetoothPermission(this)) {
                Toast.makeText(this, getString(R.string.bluetooth_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                DeviceUtils.requestBluetoothPermissions(this)
            }
        }

        // Write System Settings
        writeSystemBtn.setOnClickListener {
            if (DeviceUtils.hasWriteSystemSettings(this)) {
                Toast.makeText(this, getString(R.string.modify_system_settings_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                DeviceUtils.requestWriteSystemSettings(this)
            }
        }

        // Read Media
        readMediaBtn.setOnClickListener {
            if (DeviceUtils.hasReadMediaPermissions(this)) {
                Toast.makeText(this, getString(R.string.media_access_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                DeviceUtils.requestReadMediaPermissions(this)
            }
        }

        requiredBtn.setOnClickListener { viewSwitcher.showPrevious() }
        optionalBtn.setOnClickListener { viewSwitcher.showNext() }

        // FIX: findViewById() on an AlertDialog only works AFTER show() has
        // attached its content view to the window — calling it earlier
        // (as this used to, right before .show() below) silently returns
        // null for every view looked up via permissionsDialog.findViewById
        // (Manage External Storage, Post Notifications, Bluetooth, Write
        // System Settings, Read Media). Their whole ?.let {} block would
        // then just skip running, leaving each one stuck on the ic_alert/
        // colorError icon baked into the XML as a default — even when the
        // permission was actually already granted (e.g. "All Files Access
        // already granted" toast firing on tap, while the icon still shows
        // a red warning). Showing first, then updating status, ensures
        // findViewById can actually find these views on the very first
        // open, not just after a later refresh (e.g. onResume()).
        permissionsDialog.show()
        updatePermissionsStatus()
    }

    // Auto-grant WRITE_SECURE_SETTINGS — priority: Root → Shizuku
    private fun handleWriteSecureSettings() {
        val root    = RootManager.getInstance(this)
        val shizuko = ShizukoManager.getInstance(this)
        val pkg     = packageName
        when {
            DeviceUtils.hasWriteSettingsPermission(this) -> {
                Toast.makeText(this, getString(R.string.write_secure_settings_already_granted), Toast.LENGTH_SHORT).show()
                updatePermissionsStatus()
            }
            root.isAvailable -> {
                root.grantWriteSecureSettings(pkg) { result ->
                    runOnUiThread {
                        Toast.makeText(this, result.take(80), Toast.LENGTH_LONG).show()
                        updatePermissionsStatus()
                    }
                }
            }
            shizuko.hasPermission -> {
                shizuko.grantWriteSecureSettings(pkg) { result ->
                    runOnUiThread {
                        Toast.makeText(this, result.take(80), Toast.LENGTH_LONG).show()
                        updatePermissionsStatus()
                    }
                }
            }
            else -> Toast.makeText(
                this,
                "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Shizuku Dialog
    private fun showShizukuDialog() {
        val shizuko = ShizukoManager.getInstance(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shizuku)
            .setMessage(R.string.shizuku_desc)
            .setPositiveButton(
                if (shizuko.hasPermission) R.string.ok else R.string.grant
            ) { _, _ ->
                if (!shizuko.hasPermission) shizuko.requestPermission()
                else updatePermissionsStatus()
            }
            .setNeutralButton(R.string.shizuku_open) { _, _ ->
                try {
                    startActivity(
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:moe.shizuku.privileged.api")
                            }
                    )
                } catch (e: Exception) {}
            }
            .show()
    }

    // Default Launcher
    private fun showDefaultLauncherDialog() {
        val isDefault = isDefaultLauncher()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_launcher)
            .setMessage(R.string.default_launcher_desc)
            .setPositiveButton(if (isDefault) R.string.ok else R.string.grant) { _, _ ->
                if (!isDefault) openDefaultLauncherSettings()
            }
            .show()
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, 0)
        return info?.activityInfo?.packageName == packageName
    }

    private fun openDefaultLauncherSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                    !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(roleManager.createRequestRoleIntent(
                        android.app.role.RoleManager.ROLE_HOME), 99)
                    return
                }
            }
        } catch (e: Exception) {}
        AppUtils.openSystemSettings(this, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // Permission Helpers
    private fun grantOverlayPermissions()       = DeviceUtils.grantOverlayPermissions(this)
    private fun requestStoragePermissions()     = DeviceUtils.requestStoragePermissions(this)
    private fun requestDeviceAdminPermissions() = DeviceUtils.requestDeviceAdminPermissions(this)
    private fun requestRecentAppsPermission()   = AppUtils.openSystemSettings(this, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    private fun updatePermissionsStatus() {
        canDrawOverOtherApps = DeviceUtils.canDrawOverOtherApps(this)
        accessibilityBtn.isEnabled = canDrawOverOtherApps
        val accent = ColorStateList.valueOf(ColorUtils.getThemeColors(this, false)[0])
        val warn   = ColorStateList.valueOf(ColorUtils.getThemeColors(this, false)[2])

        if (canDrawOverOtherApps) {
            overlayBtn.setIconResource(R.drawable.ic_granted); overlayBtn.iconTint = accent
        }
        if (DeviceUtils.isAccessibilityServiceEnabled(this)) {
            // GitHub issue #18: this used to show ic_settings (a gear icon)
            // for the granted state — the only permission button on this
            // whole screen not using ic_granted (a checkmark) like every
            // other one above/below it. A gear icon reads as "needs
            // configuration", not "already granted", which is what a user
            // reported seeing and correctly called a mistake.
            accessibilityBtn.setIconResource(R.drawable.ic_granted); accessibilityBtn.iconTint = accent
        } else {
            accessibilityBtn.setIconResource(R.drawable.ic_alert); accessibilityBtn.iconTint = warn
        }
        if (DeviceUtils.hasRecentAppsPermission(this)) {
            recentAppsBtn.setIconResource(R.drawable.ic_granted); recentAppsBtn.iconTint = accent
        }
        // FIX: correctly check the Notification Listener permission via Settings.Secure
        val notifListenerGranted = run {
            val flat = android.provider.Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners"
            ) ?: ""
            val cn = android.content.ComponentName(this, NotificationService::class.java)
            flat.split(":").any {
                try { android.content.ComponentName.unflattenFromString(it) == cn } catch (_: Exception) { false }
            }
        }
        if (notifListenerGranted) {
            notificationsBtn.setIconResource(R.drawable.ic_granted); notificationsBtn.iconTint = accent
        } else {
            notificationsBtn.setIconResource(R.drawable.ic_alert); notificationsBtn.iconTint = warn
        }
        isDeviceAdminEnabled = DeviceUtils.isDeviceAdminEnabled(this)
        if (isDeviceAdminEnabled) {
            adminBtn.setIconResource(R.drawable.ic_granted); adminBtn.iconTint = accent
        } else {
            adminBtn.setIconResource(R.drawable.ic_alert); adminBtn.iconTint = warn
        }
        hasStoragePermission = DeviceUtils.hasStoragePermission(this)
        if (hasStoragePermission) {
            storageBtn.setIconResource(R.drawable.ic_granted); storageBtn.iconTint = accent
        }
        settingsOverlaysAllowed = DeviceUtils.getSettingsOverlaysAllowed(this)
        if (settingsOverlaysAllowed) {
            settingsOverlays.setIconResource(R.drawable.ic_granted); settingsOverlays.iconTint = accent
        }
        if (DeviceUtils.hasWriteSettingsPermission(this)) {
            secureBtn.setIconResource(R.drawable.ic_granted); secureBtn.iconTint = accent
        }
        val shizuko = ShizukoManager.getInstance(this)
        if (::shizukuBtn.isInitialized) {
            if (shizuko.hasPermission) {
                shizukuBtn.setIconResource(R.drawable.ic_granted); shizukuBtn.iconTint = accent
            } else {
                shizukuBtn.setIconResource(R.drawable.ic_alert); shizukuBtn.iconTint = warn
            }
        }
        if (::defaultLauncherBtn.isInitialized) {
            if (isDefaultLauncher()) {
                defaultLauncherBtn.setIconResource(R.drawable.ic_granted); defaultLauncherBtn.iconTint = accent
            } else {
                defaultLauncherBtn.setIconResource(R.drawable.ic_alert); defaultLauncherBtn.iconTint = warn
            }
        }

        // Update the new buttons (found directly from the dialog view)
        permissionsDialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_manage_external_storage)?.let { btn ->
            if (DeviceUtils.hasManageExternalStorage()) {
                btn.setIconResource(R.drawable.ic_granted); btn.iconTint = accent
            } else {
                btn.setIconResource(R.drawable.ic_alert); btn.iconTint = warn
            }
        }
        permissionsDialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_post_notifications)?.let { btn ->
            if (DeviceUtils.hasPostNotificationsPermission(this)) {
                btn.setIconResource(R.drawable.ic_granted); btn.iconTint = accent
            } else {
                btn.setIconResource(R.drawable.ic_alert); btn.iconTint = warn
            }
        }
        permissionsDialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_root_access)?.let { btn ->
            if (DeviceUtils.isRootAvailable(this)) {
                btn.setIconResource(R.drawable.ic_granted); btn.iconTint = accent
            } else {
                btn.setIconResource(R.drawable.ic_alert); btn.iconTint = warn
            }
        }
        permissionsDialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_bluetooth)?.let { btn ->
            if (DeviceUtils.hasBluetoothPermission(this)) {
                btn.setIconResource(R.drawable.ic_granted); btn.iconTint = accent
            } else {
                btn.setIconResource(R.drawable.ic_alert); btn.iconTint = warn
            }
        }
        permissionsDialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_write_system_settings)?.let { btn ->
            if (DeviceUtils.hasWriteSystemSettings(this)) {
                btn.setIconResource(R.drawable.ic_granted); btn.iconTint = accent
            } else {
                btn.setIconResource(R.drawable.ic_alert); btn.iconTint = warn
            }
        }
        permissionsDialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_read_media)?.let { btn ->
            if (DeviceUtils.hasReadMediaPermissions(this)) {
                btn.setIconResource(R.drawable.ic_granted); btn.iconTint = accent
            } else {
                btn.setIconResource(R.drawable.ic_alert); btn.iconTint = warn
            }
        }
    }

    private fun showPermissionInfoDialog(permission: Int, description: Int,
        grantMethod: KFunction0<Unit>?, granted: Boolean) {
        val db = MaterialAlertDialogBuilder(this)
        db.setTitle(permission); db.setMessage(description)
        if (!granted) db.setPositiveButton(R.string.grant) { _, _ -> grantMethod!!.invoke() }
        else db.setPositiveButton(R.string.ok, null)
        db.show()
    }

    private fun showAccessibilityDialog() {
        val db = MaterialAlertDialogBuilder(this)
        db.setTitle(R.string.accessibility_service)
        db.setMessage(R.string.accessibility_service_desc)
        if (DeviceUtils.hasWriteSettingsPermission(this)) {
            db.setPositiveButton(R.string.enable) { _, _ ->
                DeviceUtils.enableService(this)
                Handler(mainLooper).postDelayed({ updatePermissionsStatus() }, 500)
            }
            db.setNegativeButton(R.string.disable) { _, _ ->
                DeviceUtils.disableService(this)
                Handler(mainLooper).postDelayed({ updatePermissionsStatus() }, 500)
            }
        } else {
            db.setPositiveButton(R.string.manage) { _, _ ->
                AppUtils.openSystemSettings(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, R.string.enable_access_help, Toast.LENGTH_LONG).show()
            }
        }
        db.setNeutralButton(R.string.help) { _, _ ->
            AppUtils.openUrl(this,
                "https://github.com/Zoro-15/PLate.Dex#grant-restricted-permissions")
        }
        db.show()
    }

    private fun showDeviceAdminDialog() {
        if (isDeviceAdminEnabled) {
            Toast.makeText(this, getString(R.string.device_admin_already_enabled, getString(R.string.device_administrator)), Toast.LENGTH_SHORT).show()
            return
        }
        showPermissionInfoDialog(
            R.string.device_administrator,
            R.string.device_administrator_desc,
            ::requestDeviceAdminPermissions,
            isDeviceAdminEnabled
        )
    }

    private fun showNotificationsDialog() {
        val db = MaterialAlertDialogBuilder(this)
        db.setTitle(R.string.notification_access)
        db.setMessage(R.string.notification_access_desc)
        db.setPositiveButton(R.string.manage) { _, _ ->
            AppUtils.openSystemSettings(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, R.string.enable_access_help, Toast.LENGTH_LONG).show()
        }
        db.setNeutralButton(R.string.help) { _, _ ->
            AppUtils.openUrl(this,
                "https://github.com/Zoro-15/PLate.Dex#grant-restricted-permissions")
        }
        db.show()
    }
}