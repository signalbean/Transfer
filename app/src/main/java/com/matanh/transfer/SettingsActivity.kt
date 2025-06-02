package com.matanh.transfer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import android.widget.Toast


class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // Simple container layout
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_settings)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

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
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}