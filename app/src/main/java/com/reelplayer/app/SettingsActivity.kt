package com.reelplayer.app

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val languages = findViewById<EditText>(R.id.languages)
        val osKey = findViewById<EditText>(R.id.osKey)
        val osUser = findViewById<EditText>(R.id.osUser)
        val osPass = findViewById<EditText>(R.id.osPass)
        val subdlKey = findViewById<EditText>(R.id.subdlKey)
        val hw = findViewById<MaterialSwitch>(R.id.hwDecode)
        val encoding = findViewById<EditText>(R.id.encoding)
        val subSize = findViewById<EditText>(R.id.subSize)

        languages.setText(prefs.languages)
        osKey.setText(prefs.osApiKey)
        osUser.setText(prefs.osUser)
        osPass.setText(prefs.osPass)
        subdlKey.setText(prefs.subdlApiKey)
        hw.isChecked = prefs.hwDecoding
        encoding.setText(prefs.subEncoding)
        subSize.setText(prefs.subSizePercent.toString())

        findViewById<android.view.View>(R.id.saveButton).setOnClickListener {
            prefs.languages = languages.text.toString()
            // Only touch login fields when they changed, so the cached login token survives.
            if (osKey.text.toString().trim() != prefs.osApiKey) prefs.osApiKey = osKey.text.toString()
            if (osUser.text.toString().trim() != prefs.osUser) prefs.osUser = osUser.text.toString()
            if (osPass.text.toString() != prefs.osPass) prefs.osPass = osPass.text.toString()
            prefs.subdlApiKey = subdlKey.text.toString()
            prefs.hwDecoding = hw.isChecked
            prefs.subEncoding = encoding.text.toString()
            subSize.text.toString().toIntOrNull()?.let { prefs.subSizePercent = it }
            Toast.makeText(this, "Saved. Playback changes apply to the next video you open.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
