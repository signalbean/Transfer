package com.matanh.transfer

import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.core.net.toUri

class AboutActivity : AppCompatActivity() {
    private var clickCount = 0
    private var inverted = false
    private val clickTimeout = 3000L // ms
    private val handler = Handler(Looper.getMainLooper())
    private val resetClickRunnable = Runnable { clickCount = 0 }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Setup Toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        // Set Version Name dynamically
        val versionTextView: TextView = findViewById(R.id.tvVersion)
        val versionName = BuildConfig.VERSION_NAME
        versionTextView.text = getString(R.string.version_name, 0,versionName)

        fun updateText(){
            versionTextView.text = getString(R.string.version_name, clickCount,versionName)
        }

        versionTextView.setOnClickListener {
            clickCount++
            handler.removeCallbacks(resetClickRunnable)
            if (clickCount == 9) {
                toggleInvertColors()
                clickCount = 0
            } else {
                handler.postDelayed(resetClickRunnable, clickTimeout)
            }
            updateText()
        }
        versionTextView.setOnLongClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Version", versionName)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "$versionName copied!", Toast.LENGTH_SHORT).show()
            clickCount = 0
            updateText()
            true
        }

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
    private fun toggleInvertColors() {
        Toast.makeText(this, "flash! ",Toast.LENGTH_SHORT).show()
        // Grab the very top view of the window
        val rootView: View = window.decorView.rootView

        if (!inverted) {
            // Build an invert‐color matrix
            val invertMatrix = ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f,   0f
            ))
            val filter = ColorMatrixColorFilter(invertMatrix)
            // Create a paint with that filter
            val paint = Paint().apply { colorFilter = filter }
            // Put the root view into a hardware layer using our paint
            rootView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            // Remove the hardware layer (back to normal)
            rootView.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        inverted = !inverted
    }
}