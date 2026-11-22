package de.switchbot.lock

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.CheckBox
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var lockBtn: Button
    private lateinit var unlockBtn: Button
    private lateinit var settingsBox: LinearLayout
    private lateinit var tokenField: EditText
    private lateinit var secretField: EditText
    private lateinit var bioCheck: CheckBox

    private val prefs by lazy { getSharedPreferences("switchbot", MODE_PRIVATE) }
    private var lockId: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(6), dp(12), dp(6), 0) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        title = TextView(this).apply { text = "🔒 Türschloss"; textSize = 28f; setTextColor(Color.WHITE) }
        status = TextView(this).apply { text = "…"; textSize = 22f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, 0) }
        message = TextView(this).apply { textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, 0) }

        lockBtn = button("Abschließen", Color.parseColor("#2E7D32")) { send("lock") }
        unlockBtn = button("Aufschließen", Color.parseColor("#C62828")) {
            authenticate("Zum Aufschließen bestätigen") { send("unlock") }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(lockBtn); addView(unlockBtn) }

        val refresh = Button(this).apply { text = "Aktualisieren"; setOnClickListener { refresh() } }
        val settingsToggle = Button(this).apply {
            text = "Einstellungen"
            setOnClickListener {
                settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        tokenField = EditText(this).apply { hint = "Token"; setText(prefs.getString("token", "")); setSingleLine() }
        secretField = EditText(this).apply {
            hint = "Secret"; setText(prefs.getString("secret", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Speichern"
            setOnClickListener {
                prefs.edit()
                    .putString("token", tokenField.text.toString().trim())
                    .putString("secret", secretField.text.toString().trim())
                    .apply()
                lockId = null
                settingsBox.visibility = View.GONE
                refresh()
            }
        }
        bioCheck = CheckBox(this).apply {
            text = "Fingerabdruck zum Aufschließen verlangen"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("bio", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("bio", on).apply() }
        }
        settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tokenField); addView(secretField); addView(bioCheck); addView(save)
            visibility = if (prefs.getString("token", "").isNullOrEmpty()) View.VISIBLE else View.GONE
        }
        listOf(tokenField, secretField).forEach { it.setTextColor(Color.WHITE); it.setHintTextColor(Color.GRAY) }

        listOf(title, status, row, message, refresh, settingsToggle, settingsBox).forEach { root.addView(it) }
        setContentView(root)
        refresh()
    }

    private fun client(): SwitchBot? {
        val t = prefs.getString("token", "") ?: ""
        val s = prefs.getString("secret", "") ?: ""
        if (t.isEmpty() || s.isEmpty()) {
            message.text = "Bitte Token und Secret unter „Einstellungen“ eintragen."
            return null
        }
        return SwitchBot(t, s)
    }

    private fun refresh() {
        val api = client() ?: return
        message.text = "Lade…"
        thread {
            try {
                val (id, name) = lockId?.let { it to title.text.toString() } ?: api.findLock()
                lockId = id
                val locked = api.isLocked(id)
                runOnUiThread {
                    title.text = name
                    status.text = if (locked) "🔒 Verschlossen" else "🔓 Offen"
                    message.text = ""
                }
            } catch (e: Exception) {
                runOnUiThread { message.text = "Fehler: ${e.message}" }
            }
        }
    }

    private fun send(cmd: String) {
        val api = client() ?: return
        val id = lockId ?: run { refresh(); return }
        message.text = "Sende…"
        thread {
            try {
                api.command(id, cmd)
                Thread.sleep(3000) // Schloss braucht einen Moment
                refresh()
            } catch (e: Exception) {
                runOnUiThread { message.text = "Fehler: ${e.message}" }
            }
        }
    }

    /** Fingerabdruck (oder Bildschirmsperre) abfragen; ohne Biometrie-Pflicht nur eine Rückfrage. */
    private fun authenticate(reason: String, onOk: () -> Unit) {
        if (!prefs.getBoolean("bio", true)) {
            AlertDialog.Builder(this)
                .setMessage("Wirklich aufschließen?")
                .setPositiveButton("Ja") { _, _ -> onOk() }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        val builder = BiometricPrompt.Builder(this).setTitle("Türschloss").setSubtitle(reason)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setNegativeButton("Abbrechen", mainExecutor) { _, _ -> }
        }
        builder.build().authenticate(
            CancellationSignal(), mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onOk()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    message.text = "Nicht bestätigt: $errString"
                }
            }
        )
    }
}
