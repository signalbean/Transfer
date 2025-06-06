package com.matanh.transfer

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {


    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            Utils.persistUriPermission(this, uri)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit { putString(Constants.EXTRA_FOLDER_URI, uri.toString()) }
            Toast.makeText(this, "Shared folder selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_settings)
    }

    fun launchFolderSelection() {
        selectFolderLauncher.launch(null)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Password Preference
            val passwordPreference = findPreference<EditTextPreference>(getString(R.string.pref_key_server_password))
            updatePasswordSummary(passwordPreference)
            passwordPreference?.setOnPreferenceChangeListener { preference, newValue ->
                val newPassword = newValue as String?
                if (newPassword.isNullOrEmpty()) {
                    preference.summary = getString(R.string.pref_summary_password_protect_off)
                    Toast.makeText(requireContext(), getString(R.string.password_cleared), Toast.LENGTH_SHORT).show()
                } else {
                    preference.summary = getString(R.string.pref_summary_password_protect_on)
                    Toast.makeText(requireContext(), getString(R.string.password_set), Toast.LENGTH_SHORT).show()
                }
                true
            }

            // Folder Selection Preference
            val folderPreference = findPreference<Preference>("pref_key_shared_folder")
            folderPreference?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.launchFolderSelection()
                true
            }
            updateFolderSummary()
        }

        private fun updatePasswordSummary(passwordPreference: EditTextPreference?) {
            val password = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(getString(R.string.pref_key_server_password), null)
            if (password.isNullOrEmpty()) {
                passwordPreference?.summary = getString(R.string.pref_summary_password_protect_off)
            } else {
                passwordPreference?.summary = getString(R.string.pref_summary_password_protect_on)
            }
        }

        private fun updateFolderSummary() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val uriString = prefs.getString(Constants.EXTRA_FOLDER_URI, null)
            val folderPreference = findPreference<Preference>("pref_key_shared_folder")
            if (uriString != null) {
                val uri = uriString.toUri()
                val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
                folderPreference?.summary = docFile?.name ?: uri.path
            } else {
                folderPreference?.summary = "No folder selected"
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}