package com.example.monitoragricola.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.monitoragricola.R

class SettingsActivity : AppCompatActivity() {


    private lateinit var rgFonte: RadioGroup
    private lateinit var btnSalvar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        rgFonte = findViewById(R.id.rgFonte)
        btnSalvar = findViewById(R.id.btnSalvar)

        val prefs = getSharedPreferences("configs", MODE_PRIVATE)


        when (prefs.getString("fonteCoordenada", "gps")) {
            "gps" -> rgFonte.check(R.id.rbGps)
            "simulador" -> rgFonte.check(R.id.rbSimulador)
            "rtk" -> rgFonte.check(R.id.rbRtk)
        }


        btnSalvar.setOnClickListener {
            val editor = prefs.edit()

            val fonte = when (rgFonte.checkedRadioButtonId) {
                R.id.rbGps -> "gps"
                R.id.rbSimulador -> "simulador"
                R.id.rbRtk -> "rtk"
                else -> "gps"
            }
            editor.putString("fonteCoordenada", fonte)

            editor.apply()
            finish()
        }
    }
}