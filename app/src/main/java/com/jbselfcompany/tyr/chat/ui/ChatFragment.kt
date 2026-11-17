package com.jbselfcompany.tyr.chat.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.chat.data.ChatContact
import com.jbselfcompany.tyr.chat.data.ChatMessage
import com.jbselfcompany.tyr.chat.data.ChatRepository
import com.jbselfcompany.tyr.chat.network.ImapFetcher
import com.jbselfcompany.tyr.chat.network.SmtpSender
import com.jbselfcompany.tyr.databinding.FragmentChatBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.MainActivity

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val chatRepository by lazy { ChatRepository(requireContext()) }
    private val adapter = ChatContactAdapter(
        onContactClick = { contact -> openConversation(contact) },
        onContactLongClick = { contact -> showContactOptions(contact) }
    )

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchNewMessages()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // Guard against concurrent IMAP fetches (timer overlapping with a slow 30s+ download)
    private val isFetchInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerContacts.adapter = adapter

        binding.fabAddContact.setOnClickListener {
            showAddContactSheet()
        }

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_chat, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                    when (menuItem.itemId) {
                        R.id.action_my_profile -> {
                            showMyProfileDialog()
                            true
                        }
                        else -> false
                    }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )

        refreshContactList()
    }

    override fun onResume() {
        super.onResume()
        refreshContactList()
        // Refresh badge when returning from a conversation
        (activity as? MainActivity)?.refreshChatBadge()
        if (YggmailService.isRunning) {
            pollHandler.post(pollRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun showAddContactSheet() {
        val sheet = AddContactBottomSheet()
        sheet.onContactAdded = { address, name ->
            val myAddress = configRepository.getMailAddress() ?: ""
            when {
                address.equals(myAddress, ignoreCase = true) ->
                    Snackbar.make(
                        binding.root,
                        R.string.chat_cannot_add_self,
                        Snackbar.LENGTH_SHORT
                    ).show()
                chatRepository.contactExists(address) ->
                    Snackbar.make(
                        binding.root,
                        R.string.chat_contact_already_exists,
                        Snackbar.LENGTH_SHORT
                    ).show()
                else -> {
                    chatRepository.addContact(ChatContact(address = address, name = name))
                    refreshContactList()
                    Snackbar.make(
                        binding.root,
                        R.string.chat_contact_added,
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
        sheet.show(childFragmentManager, AddContactBottomSheet.TAG)
    }

    internal fun refreshContactList() {
        val myAddress = configRepository.getMailAddress() ?: ""
        val contacts = chatRepository.getAllContacts()

        if (contacts.isEmpty()) {
            binding.recyclerContacts.visibility = View.GONE
            binding.textEmptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerContacts.visibility = View.VISIBLE
            binding.textEmptyState.visibility = View.GONE

            val items = contacts.map { contact ->
                ChatContactAdapter.ContactItem(
                    contact = contact,
                    lastMessage = chatRepository.getLastMessage(myAddress, contact.address),
                    unreadCount = chatRepository.getUnreadCount(myAddress, contact.address)
                )
            }.sortedByDescending { it.lastMessage?.timestamp ?: it.contact.addedAt }

            adapter.submitList(items)
        }
    }

    private fun openConversation(contact: ChatContact) {
        val myAddress = configRepository.getMailAddress() ?: return
        // For pending contacts, don't mark as read until user accepts
        if (!contact.isPending) {
            chatRepository.markConversationRead(myAddress, contact.address)
        }
        val intent = android.content.Intent(
            requireContext(),
            ConversationActivity::class.java
        ).apply {
            putExtra(ConversationActivity.EXTRA_CONTACT_ADDRESS, contact.address)
            putExtra(ConversationActivity.EXTRA_CONTACT_NAME, contact.name)
        }
        startActivity(intent)
    }

    private fun showContactOptions(contact: ChatContact) {
        val displayName = contact.name.ifBlank { contact.address }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(displayName)
            .setItems(
                arrayOf(
                    getString(R.string.chat_rename_contact),
                    getString(R.string.chat_delete_contact)
                )
            ) { _, which ->
                when (which) {
                    0 -> showRenameDialog(contact)
                    1 -> showDeleteDialog(contact)
                }
            }
            .show()
    }

    private fun showRenameDialog(contact: ChatContact) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(contact.name)
            hint = getString(R.string.chat_contact_name_hint)
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_rename_contact)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                chatRepository.updateContactName(contact.address, input.text.toString())
                refreshContactList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(contact: ChatContact) {
        val displayName = contact.name.ifBlank { contact.address }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_delete_contact)
            .setMessage(getString(R.string.chat_delete_contact_confirmation, displayName))
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                val address = contact.address
                // Optimistically remove from UI immediately.
                chatRepository.deleteContact(address)
                refreshContactList()
                // Root fix: purge messages from the local IMAP server so they can never
                // be re-fetched — even after a backup restore that resets the sinceUid
                // watermark. This runs on a background thread (local socket, ~fast).
                val myAddress = configRepository.getMailAddress()
                val password = configRepository.getPassword()
                if (myAddress != null && password != null) {
                    Thread {
                        ImapFetcher().deleteMessagesBySender(myAddress, password, address)
                    }.also { it.name = "ImapPurge-$address" }.start()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fetchNewMessages() {
        if (!YggmailService.isRunning) return
        if (!isFetchInProgress.compareAndSet(false, true)) {
            return
        }
        val myAddress =
            configRepository.getMailAddress() ?: run {
                isFetchInProgress.set(false)
                return
            }
        val password =
            configRepository.getPassword() ?: run {
                isFetchInProgress.set(false)
                return
            }
        val acceptFromNonContacts = configRepository.getAcceptMessagesFromNonContacts()
        // Capture context before leaving the main thread — avoids IllegalStateException
        // if the fragment is detached while the background thread is still running.
        val appContext = context?.applicationContext ?: run {
            isFetchInProgress.set(false)
            return
        }
        val filesDir = appContext.filesDir

        Thread {
            try {
                val sinceUid = chatRepository.getMaxImapUid()
                val attachmentsDir = java.io.File(
                    appContext.getExternalFilesDir(null) ?: filesDir,
                    "attachments"
                ).also { it.mkdirs() }
                val result = ImapFetcher(
                    cacheDir = appContext.cacheDir
                ).fetchNewMessages(myAddress, password, sinceUid, attachmentsDir)
                if (result is ImapFetcher.Result.Success) {
                    // Delivery receipts → single checkmark (only if still SENDING)
                    for (receipt in result.data.deliveryReceiptTimestamps) {
                        chatRepository.updateSentMessageStatusNearTimestamp(
                            myAddress,
                            receipt.senderAddr,
                            receipt.originalTimestamp,
                            ChatMessage.STATUS_SENT,
                            ChatMessage.STATUS_SENDING
                        )
                    }
                    var added = 0
                    val newlyReceived = mutableListOf<ChatMessage>()
                    val nicknameMap = result.data.nicknameUpdates.associate {
                        it.senderAddr to
                            it.nickname
                    }

                    for (msg in result.data.messages) {
                        // Skip messages sent by this account — YggmailService also skips these;
                        // inserting them here would create duplicates in the messages table.
                        if (msg.isSent) continue
                        if (chatRepository.imapUidExists(msg.imapUid)) continue
                        if (chatRepository.isContactDeclined(msg.fromAddr)) continue

                        val senderInContacts = chatRepository.contactExists(msg.fromAddr)
                        when {
                            senderInContacts -> {
                                chatRepository.insertMessage(msg)
                                newlyReceived.add(msg)
                                added++
                            }
                            acceptFromNonContacts -> {
                                // Store as pending contact — user decides in ConversationActivity
                                // Use sender's nickname if available
                                chatRepository.addPendingContact(
                                    ChatContact(
                                        address = msg.fromAddr,
                                        name =
                                        nicknameMap[msg.fromAddr] ?: ""
                                    )
                                )
                                // Insert as unread (isRead=false) so badge counts it
                                chatRepository.insertMessage(msg.copy(isRead = false))
                                newlyReceived.add(msg)
                                added++
                            }
                            // else: setting off — drop silently
                        }
                    }

                    // Apply nickname updates to known contacts with blank names
                    for ((senderAddr, nickname) in nicknameMap) {
                        val contact = chatRepository.getContact(senderAddr)
                        if (contact != null && contact.name.isBlank()) {
                            chatRepository.updateContactName(senderAddr, nickname)
                        }
                    }

                    val somethingChanged = added > 0 ||
                        result.data.readReceiptUids.isNotEmpty() ||
                        result.data.readReceiptTimestamps.isNotEmpty() ||
                        result.data.deliveryReceiptTimestamps.isNotEmpty()

                    if (somethingChanged) {
                        // Broadcast so ConversationActivity (if open) reloads immediately —
                        // previously this was missing, causing the open conversation to stay
                        // stale until the YggmailService 2-minute poll fired.
                        appContext.sendBroadcast(
                            android.content.Intent(YggmailService.ACTION_NEW_CHAT_MESSAGES)
                        )
                        activity?.runOnUiThread {
                            refreshContactList()
                            (activity as? MainActivity)?.refreshChatBadge()
                        }
                    }

                    // Send delivery receipts for messages we inserted first (before YggmailService poll)
                    for (msg in newlyReceived) {
                        SmtpSender().send(
                            fromAddress = myAddress,
                            password = password,
                            toAddress = msg.fromAddr,
                            body = "",
                            deliveryReceiptTimestamp = msg.timestamp
                        )
                    }
                }
            } finally {
                isFetchInProgress.set(false)
            }
        }.start()
    }

    private fun showMyProfileDialog() {
        val dialogView = requireActivity().layoutInflater.inflate(R.layout.dialog_my_profile, null)
        val nicknameEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_my_nickname)
        nicknameEdit.setText(configRepository.getNickname())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_my_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val nickname = nicknameEdit.text.toString().trim()
                configRepository.setNickname(nickname)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
