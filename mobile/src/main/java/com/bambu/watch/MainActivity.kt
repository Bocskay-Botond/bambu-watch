package com.bambu.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val prefs get() = getSharedPreferences("bambu", Context.MODE_PRIVATE)
    private lateinit var statusText: TextView
    private lateinit var codeSection: LinearLayout
    private lateinit var codeField: EditText
    private var pendingEmail = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 80, 64, 64)
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
        }

        val title = TextView(this).apply {
            text = "Bambu Watch"
            textSize = 26f
            setTextColor(android.graphics.Color.parseColor("#00BFFF"))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "3D nyomtatás státusz Samsung Watch-on"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 40)
        }

        val emailField = editText(prefs.getString("email", "") ?: "", false, "Email")
        val passField  = editText("", true, "Jelszó")

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding(0, 16, 0, 8)
        }

        val btnLogin = button("BEJELENTKEZÉS", "#00BFFF")
        btnLogin.setOnClickListener {
            val email = emailField.text.toString().trim()
            val pass  = passField.text.toString()
            if (email.isEmpty() || pass.isEmpty()) {
                status("Add meg az email-t és jelszót!", "#FF4444"); return@setOnClickListener
            }
            pendingEmail = email
            btnLogin.isEnabled = false
            status("Bejelentkezés...", "#888888")

            Thread {
                val token = BambuApi.login(email, pass)
                runOnUiThread {
                    when {
                        token != null -> onLoginSuccess(token, email)
                        BambuApi.lastError.contains("verifyCode") -> {
                            // 2FA szükséges — kód küldése emailre
                            Thread {
                                val sent = BambuApi.sendVerifyCode(email)
                                runOnUiThread {
                                    if (sent) {
                                        status("Ellenőrző kód elküldve: $email", "#00BFFF")
                                        codeSection.visibility = View.VISIBLE
                                    } else {
                                        status("Kód küldés sikertelen.", "#FF4444")
                                    }
                                    btnLogin.isEnabled = true
                                }
                            }.start()
                        }
                        else -> {
                            status("Hiba: ${BambuApi.lastError}", "#FF4444")
                            btnLogin.isEnabled = true
                        }
                    }
                }
            }.start()
        }

        // 2FA kód szekció
        codeSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        codeField = editText("", false, "6 jegyű kód (emailből)")
        val btnCode = button("KÓD ELLENŐRZÉSE", "#00AA66")
        btnCode.setOnClickListener {
            val code = codeField.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            btnCode.isEnabled = false
            status("Ellenőrzés...", "#888888")
            Thread {
                val token = BambuApi.loginWithCode(pendingEmail, code)
                runOnUiThread {
                    if (token != null) {
                        onLoginSuccess(token, pendingEmail)
                        codeSection.visibility = View.GONE
                    } else {
                        status("Hibás kód. Próbáld újra.", "#FF4444")
                        btnCode.isEnabled = true
                    }
                }
            }.start()
        }
        codeSection.addView(label("Ellenőrző kód"))
        codeSection.addView(codeField)
        codeSection.addView(btnCode)

        val btnStop = button("SZINKRONIZÁLÁS LEÁLLÍTÁSA", "#222222")
        btnStop.setOnClickListener {
            stopService(Intent(this, BambuSyncService::class.java))
            status("Leállítva.", "#888888")
        }

        listOf(title, subtitle,
            label("Email"), emailField,
            label("Jelszó"), passField,
            statusText, btnLogin, codeSection, btnStop
        ).forEach { root.addView(it) }
        setContentView(root)

        // Ha már be van jelentkezve, indítsa a service-t
        if (prefs.getString("token", null) != null) {
            val debug = prefs.getString("last_debug", "várakozás...")
            status("Szinkronizálás fut.\n$debug", "#00FF88")
            ContextCompat.startForegroundService(this, Intent(this, BambuSyncService::class.java))
        }
    }

    private fun onLoginSuccess(token: String, email: String) {
        prefs.edit().putString("email", email).putString("token", token).apply()
        status("Sikeres bejelentkezés! Szinkronizálás indul...", "#00FF88")
        ContextCompat.startForegroundService(this, Intent(this, BambuSyncService::class.java))
    }

    private fun status(msg: String, color: String) {
        statusText.setTextColor(android.graphics.Color.parseColor(color))
        statusText.text = msg
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f
        setTextColor(android.graphics.Color.parseColor("#666666"))
        setPadding(0, 16, 0, 4)
    }

    private fun editText(value: String, password: Boolean, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(android.graphics.Color.WHITE)
        setHintTextColor(android.graphics.Color.parseColor("#444444"))
        setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        setPadding(24, 20, 24, 20)
    }

    private fun button(text: String, color: String) = Button(this).apply {
        this.text = text; textSize = 12f
        setBackgroundColor(android.graphics.Color.parseColor(color))
        setTextColor(android.graphics.Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 0) }
    }
}
