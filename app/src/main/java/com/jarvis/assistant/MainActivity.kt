package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val ACTION_ACTIVATE = "com.jarvis.assistant.ACTIVATE"
        private const val REQ_PERMS = 42
    }

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var toggle: Button
    private lateinit var permInfo: TextView
    private lateinit var keyField: EditText
    private lateinit var titleField: EditText

    private val wantedPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orb = findViewById(R.id.orb)
        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        toggle = findViewById(R.id.toggle)
        permInfo = findViewById(R.id.perm_info)
        keyField = findViewById(R.id.api_key)
        titleField = findViewById(R.id.title_field)

        val prefs = Brain.prefs(this)
        keyField.setText(prefs.getString(Brain.KEY_API, ""))
        titleField.setText(prefs.getString(Brain.KEY_TITLE, ""))

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.edit()
                .putString(Brain.KEY_API, keyField.text.toString().trim())
                .putString(Brain.KEY_TITLE, titleField.text.toString().trim())
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        toggle.setOnClickListener {
            if (JarvisBus.state == JarvisBus.State.OFF) {
                startJarvis()
            } else {
                startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
            }
        }

        findViewById<Button>(R.id.grant).setOnClickListener { requestMissingPermissions() }

        findViewById<Button>(R.id.overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.battery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        if (!hasMic()) requestMissingPermissions() else startJarvis()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ACTIVATE && hasMic()) {
            // Starts the service if needed and tells it to say "Yes, sir?" and listen.
            startForegroundService(
                Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_ACTIVATE)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        JarvisBus.listener = { state, text -> render(state, text) }
        render(JarvisBus.state, JarvisBus.text)
        refreshPermissionInfo()
    }

    override fun onPause() {
        JarvisBus.listener = null
        super.onPause()
    }

    private fun render(state: JarvisBus.State, text: String) {
        orb.state = state
        status.text = when (state) {
            JarvisBus.State.OFF -> "J.A.R.V.I.S. OFFLINE"
            JarvisBus.State.IDLE -> "STANDING BY"
            JarvisBus.State.LISTENING -> "LISTENING"
            JarvisBus.State.THINKING -> "THINKING"
            JarvisBus.State.SPEAKING -> "SPEAKING"
        }
        transcript.text = text
        toggle.text = if (state == JarvisBus.State.OFF) "Start Jarvis" else "Stop Jarvis"
    }

    // ------------------------------------------------------------ permissions

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val missing = wantedPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        refreshPermissionInfo()
        if (code == REQ_PERMS && hasMic()) {
            startJarvis()
            handleIntent(intent)
        }
    }

    private fun startJarvis() {
        if (!hasMic()) return
        startForegroundService(Intent(this, JarvisService::class.java))
    }

    private fun refreshPermissionInfo() {
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(PowerManager::class.java)
        permInfo.text = buildString {
            appendLine("${mark(has(Manifest.permission.RECORD_AUDIO))}  Microphone")
            appendLine("${mark(has(Manifest.permission.READ_CONTACTS))}  Contacts")
            appendLine("${mark(has(Manifest.permission.CALL_PHONE))}  Phone calls")
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))}  Open apps from the background")
            append("${mark(pm.isIgnoringBatteryOptimizations(packageName))}  Battery optimisation off")
        }
    }
}
