package com.matanh.transfer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import com.matanh.transfer.BuildConfig
import com.matanh.transfer.R
import com.matanh.transfer.util.TransferApp

class ReportErrorActivity : AppCompatActivity() {

    private lateinit var fullReportText: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_error)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val logView: TextView = findViewById(R.id.log_text)
        val logs = TransferApp.memoryTree.getLog()

        fullReportText = buildDeviceInfo() + "\n\n" + logs.joinToString("\n")
        logView.text = fullReportText

        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            copyToClipboard(fullReportText)
        }

        findViewById<Button>(R.id.btn_github).setOnClickListener {
            openGitHubIssue(fullReportText)
        }
    }

    private fun buildDeviceInfo(): String {
        return """
            App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            Installer Package: ${getInstallerPackage()}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
        """.trimIndent()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Error Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    fun getInstallerPackage(): String {
        val pm = this.packageManager
        val packageName = this.packageName
        return pm.getInstallerPackageName(packageName) ?: "unknown"
    }

    private fun openGitHubIssue(report: String) {
        val issueBodyTemplate = """
### Describe the bug
<!-- A short description of what the bug is. -->

### Steps to reproduce

1. 
2. 
3. 

### Expected behavior
<!-- A short description of what you expected to happen. -->

### App info
```yml
$report
```
""".trimIndent()
        val issueTitle = Uri.encode("Report:")
        val issueBody = Uri.encode(issueBodyTemplate)

        val repoUrl = "https://github.com/matan-h/Transfer"
        val url = "$repoUrl/issues/new?title=$issueTitle&body=$issueBody"

        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }
}
