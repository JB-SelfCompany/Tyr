package com.jbselfcompany.tyr.chat.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.databinding.BottomSheetAddContactBinding

class AddContactBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddContactBinding? = null
    private val binding get() = _binding!!

    var onContactAdded: ((address: String, name: String) -> Unit)? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        val scanned = result.contents?.trim() ?: return@registerForActivityResult
        val address = when {
            // tyr://open?pubkey=<hex>[&peer=...][&name=...] — Tyr contact sharing deeplink
            scanned.startsWith("tyr://open", ignoreCase = true) -> {
                val uri = Uri.parse(scanned)
                val pubkey = uri.getQueryParameter("pubkey")?.trim() ?: ""
                if (pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "$pubkey@yggmail"
                } else ""
            }
            scanned.startsWith("mailto:", ignoreCase = true) ->
                scanned.removePrefix("mailto:").substringBefore("?").trim()
            scanned.contains("@") -> scanned.trim()
            else -> ""
        }
        if (address.isNotEmpty() && address.endsWith("@yggmail")) {
            binding.editAddressManual.setText(address)
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        } else {
            Toast.makeText(requireContext(), R.string.chat_invalid_address_qr, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.sectionManual.visibility = View.VISIBLE
                        binding.sectionQr.visibility = View.GONE
                    }
                    1 -> {
                        binding.sectionManual.visibility = View.GONE
                        binding.sectionQr.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.sectionManual.visibility = View.VISIBLE
        binding.sectionQr.visibility = View.GONE

        binding.buttonAdd.setOnClickListener {
            val address = binding.editAddressManual.text.toString().trim().lowercase()
            val name = binding.editName.text.toString().trim()
            if (!isValidYggmailAddress(address)) {
                binding.editAddressManual.error = getString(R.string.chat_invalid_address)
                return@setOnClickListener
            }
            onContactAdded?.invoke(address, name)
            dismiss()
        }

        binding.buttonScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.chat_scan_qr_hint))
                setBeepEnabled(false)
                setOrientationLocked(true)  // Lock to portrait, no rotation to landscape
                setBarcodeImageEnabled(false)  // Skip saving barcode image for faster startup
                setCameraId(0)  // Back camera, faster init
            }
            qrLauncher.launch(options)
        }
    }

    private fun isValidYggmailAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val parts = address.split("@")
        if (parts.size != 2) return false
        if (parts[1] != "yggmail") return false
        val local = parts[0]
        if (local.length != 64) return false
        return local.all { it in '0'..'9' || it in 'a'..'f' }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddContactBottomSheet"
    }
}
