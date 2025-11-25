package com.jbselfcompany.tyr.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.databinding.FragmentOnboardingPasswordBinding
import com.jbselfcompany.tyr.utils.BackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Callback interface for restore completion
 */
interface OnRestoreCompletedListener {
    fun onRestoreCompleted()
}

/**
 * Password setup fragment for onboarding
 */
class OnboardingPasswordFragment : Fragment() {

    private var _binding: FragmentOnboardingPasswordBinding? = null
    private val binding get() = _binding!!

    private var restoreListener: OnRestoreCompletedListener? = null

    private val selectBackupFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                showPasswordDialog(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPasswordBinding.inflate(inflater, container, false)
        setupRestoreButton()
        return binding.root
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnRestoreCompletedListener) {
            restoreListener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        restoreListener = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRestoreButton() {
        binding.buttonRestoreBackup.setOnClickListener {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "*/*"
            ))
        }
        selectBackupFileLauncher.launch(intent)
    }

    private fun showPasswordDialog(backupUri: Uri) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(
            R.layout.dialog_backup_password,
            null
        )

        val passwordLayout = dialogView.findViewById<TextInputLayout>(R.id.password_layout)
        val passwordEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_password)

        // Hide confirm password field for restore
        dialogView.findViewById<TextInputLayout>(R.id.confirm_password_layout)?.visibility = View.GONE

        AlertDialog.Builder(context)
            .setTitle(R.string.restore_backup)
            .setMessage(R.string.backup_password_hint)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                val password = passwordEdit.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(
                        context,
                        R.string.error_backup_password_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    performRestore(backupUri, password)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performRestore(backupUri: Uri, password: String) {
        val context = requireContext()
        val progressDialog = AlertDialog.Builder(context)
            .setTitle(R.string.restore_backup)
            .setMessage(R.string.restoring_backup)
            .setCancelable(false)
            .create()

        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = context.contentResolver.openInputStream(backupUri)
                val success = if (inputStream != null) {
                    BackupManager.restoreBackup(context, inputStream, password)
                } else {
                    false
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()

                    if (success) {
                        Toast.makeText(
                            context,
                            R.string.backup_restored,
                            Toast.LENGTH_LONG
                        ).show()

                        // Notify parent activity that restore is complete
                        restoreListener?.onRestoreCompleted()
                    } else {
                        Toast.makeText(
                            context,
                            R.string.error_invalid_backup_password,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore backup", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        context,
                        R.string.restore_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Get password entered by user
     */
    fun getPassword(): String {
        return binding.editPassword.text.toString()
    }

    /**
     * Get confirm password entered by user
     */
    fun getConfirmPassword(): String {
        return binding.editConfirmPassword.text.toString()
    }

    companion object {
        private const val TAG = "OnboardingPasswordFragment"
    }
}
