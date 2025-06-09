package com.matanh.transfer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.animation.AnimationUtils
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.core.net.toUri

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Setup Toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        // Set Version Name dynamically
        val versionTextView: TextView = findViewById(R.id.tvVersion)
        val versionName = BuildConfig.VERSION_NAME
        versionTextView.text = getString(R.string.version_name, versionName)

        // Fade-in Animation for Card
        val cardContent = findViewById<androidx.cardview.widget.CardView>(R.id.cardContent)
        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        cardContent.startAnimation(fadeIn)

        // GitHub Button Click
        val btnGithub: MaterialButton = findViewById(R.id.btnGithub)
        btnGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/matan-h/transfer".toUri())
            startActivity(intent)
        }

        // Buy Me a Coffee Button Click
        val btnCoffee: MaterialButton = findViewById(R.id.btnCoffee)
        btnCoffee.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://coff.ee/matanh".toUri())
            startActivity(intent)
        }
    }

}