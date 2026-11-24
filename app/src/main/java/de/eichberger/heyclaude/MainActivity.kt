package de.eichberger.heyclaude

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import de.eichberger.heyclaude.ServiceStatus.State
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var listenSwitch: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var scoreText: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdSlider: Slider
    private lateinit var directCheck: CheckBox
    private lateinit var micButton: Button
    private lateinit var notifButton: Button
    private lateinit var overlayButton: Button
    private lateinit var batteryButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private var updatingSwitch = false
    private var startAfterMicGrant = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        if (startAfterMicGrant) {
            startAfterMicGrant = false
            if (granted) startListening() else setSwitch(false)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    private val statusListener = ServiceStatus.Listener { state, message -> showStatus(state, message) }

    private val scoreUpdater = object : Runnable {
        override fun run() {
            val listening = ServiceStatus.state == State.LISTENING
            scoreText.text = if (listening) {
                getString(R.string.score_value, format(ServiceStatus.recentScore))
            } else {
                getString(R.string.score_idle)
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listenSwitch = findViewById(R.id.listen_switch)
        statusText = findViewById(R.id.status_text)
        scoreText = findViewById(R.id.score_text)
        thresholdLabel = findViewById(R.id.threshold_label)
        thresholdSlider = findViewById(R.id.threshold_slider)
        directCheck = findViewById(R.id.direct_check)
        micButton = findViewById(R.id.mic_button)
        notifButton = findViewById(R.id.notif_button)
        overlayButton = findViewById(R.id.overlay_button)
        batteryButton = findViewById(R.id.battery_button)

        setSwitch(Prefs.enabled(this))
        listenSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) startListening() else stopListening()
        }

        // Schieberegler in Prozent (5..95), damit keine Rundungsfehler bei 0,05er-Schritten entstehen.
        val percent = ((Prefs.threshold(this) * 100f / 5f).roundToInt() * 5).coerceIn(5, 95)
        thresholdSlider.value = percent.toFloat()
        showThreshold(percent / 100f)
        thresholdSlider.addOnChangeListener { _, value, fromUser ->
            val t = value / 100f
            showThreshold(t)
            if (fromUser) Prefs.setThreshold(this, t)
        }

        directCheck.isChecked = Prefs.openClaudeDirectly(this)
        directCheck.setOnCheckedChangeListener { _, checked -> Prefs.setOpenClaudeDirectly(this, checked) }

        micButton.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        notifButton.setOnClickListener { requestNotifications() }
        overlayButton.setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryButton.setOnClickListener { requestBatteryExemption() }

        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    override fun onStart() {
        super.onStart()
        ServiceStatus.addListener(statusListener)
        ui.post(scoreUpdater)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onStop() {
        ServiceStatus.removeListener(statusListener)
        ui.removeCallbacks(scoreUpdater)
        super.onStop()
    }

    /** Aus der Benachrichtigung „Tippen zum Aktivieren“ nach einem Neustart. */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            intent.removeExtra(EXTRA_AUTOSTART)
            Notifications.cancelActivatePrompt(this)
            if (Prefs.enabled(this)) startListening()
        }
    }

    private fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            startAfterMicGrant = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Prefs.setEnabled(this, true)
        setSwitch(true)
        WakeWordService.start(this)
    }

    private fun stopListening() {
        Prefs.setEnabled(this, false)
        WakeWordService.stop(this)
    }

    private fun setSwitch(on: Boolean) {
        updatingSwitch = true
        listenSwitch.isChecked = on
        updatingSwitch = false
    }

    private fun showStatus(state: State, message: String?) {
        statusText.text = when (state) {
            State.STOPPED -> getString(R.string.status_stopped)
            State.STARTING -> getString(R.string.status_starting)
            State.LISTENING -> getString(R.string.status_listening)
            State.PAUSED -> getString(R.string.status_paused)
            State.WAITING -> message ?: getString(R.string.status_waiting)
            State.ERROR -> message ?: getString(R.string.notif_error)
        }
    }

    private fun showThreshold(t: Float) {
        thresholdLabel.text = getString(R.string.threshold_label, format(t))
    }

    private fun refreshPermissions() {
        setPermissionButton(micButton, granted(Manifest.permission.RECORD_AUDIO), R.string.perm_mic)
        setPermissionButton(notifButton, notificationsAllowed(), R.string.perm_notifications)
        setPermissionButton(overlayButton, Settings.canDrawOverlays(this), R.string.perm_overlay)
        val pm = getSystemService(PowerManager::class.java)
        setPermissionButton(batteryButton, pm.isIgnoringBatteryOptimizations(packageName), R.string.perm_battery)
    }

    private fun setPermissionButton(button: Button, ok: Boolean, label: Int) {
        button.text = getString(if (ok) R.string.perm_granted else R.string.perm_missing, getString(label))
        button.isEnabled = !ok
    }

    private fun notificationsAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!open(direct)) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun format(v: Float) = String.format(Locale.GERMANY, "%.2f", v)

    companion object {
        const val EXTRA_AUTOSTART = "de.eichberger.heyclaude.AUTOSTART"
    }
}
