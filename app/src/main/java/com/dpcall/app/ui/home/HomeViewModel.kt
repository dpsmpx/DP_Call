package com.dpcall.app.ui.home

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpcall.app.data.ContactsRepository
import com.dpcall.app.data.IdentityRepository
import com.dpcall.app.domain.Contact
import com.dpcall.app.domain.Identity
import com.dpcall.app.signaling.SignalCodec
import com.dpcall.app.signaling.SignalType
import com.dpcall.app.util.CodeValidator
import com.dpcall.app.webrtc.CallController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val identity: Identity? = null,
    val contacts: List<Contact> = emptyList(),
    val isLoading: Boolean = true,
    val copied: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val addCodeInput: String = "",
    val addNameInput: String = "",
    val addError: String? = null,
    val isAdding: Boolean = false,
    val activeCall: CallController? = null,
    val showIncomingDialog: Boolean = false,
    val incomingOfferInput: String = ""
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val identityRepo = IdentityRepository(application)
    private val contactsRepo = ContactsRepository(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                identityRepo.identityFlow,
                contactsRepo.contactsFlow
            ) { identity, contacts ->
                identity to contacts
            }.collect { (identity, contacts) ->
                val finalIdentity = identity ?: identityRepo.getOrCreate()
                _uiState.update {
                    it.copy(
                        identity = finalIdentity,
                        contacts = contacts.sortedBy { c -> c.name.lowercase() },
                        isLoading = false
                    )
                }
            }
        }
    }

    fun copyCodeToClipboard() {
        val identity = _uiState.value.identity ?: return
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DP Call code", identity.value))

        _uiState.update { it.copy(copied = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(copied = false) }
        }
    }

    fun openAddDialog() {
        _uiState.update {
            it.copy(showAddDialog = true, addCodeInput = "", addNameInput = "", addError = null)
        }
    }

    fun closeAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, addError = null, isAdding = false) }
    }

    fun onAddCodeChange(value: String) {
        _uiState.update { it.copy(addCodeInput = value, addError = null) }
    }

    fun onAddNameChange(value: String) {
        _uiState.update { it.copy(addNameInput = value, addError = null) }
    }

    fun pasteCodeFromClipboard() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(getApplication())
            ?.toString()
            ?.trim()
            ?: return
        _uiState.update { it.copy(addCodeInput = text, addError = null) }
    }

    fun confirmAddContact() {
        val state = _uiState.value
        val code = CodeValidator.normalize(state.addCodeInput)
        val name = state.addNameInput.trim()

        when {
            !CodeValidator.isValid(code) -> {
                _uiState.update { it.copy(addError = "Неверный формат кода") }
                return
            }
            name.isBlank() -> {
                _uiState.update { it.copy(addError = "Введите имя") }
                return
            }
            code == state.identity?.value -> {
                _uiState.update { it.copy(addError = "Нельзя добавить свой собственный код") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true, addError = null) }
            val result = contactsRepo.add(Contact(code = code, name = name))
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showAddDialog = false,
                            isAdding = false,
                            addCodeInput = "",
                            addNameInput = ""
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isAdding = false, addError = e.message ?: "Ошибка добавления")
                    }
                }
            )
        }
    }

    fun removeContact(code: String) {
        viewModelScope.launch { contactsRepo.remove(code) }
    }

    fun startCall(contact: Contact) {
        val identity = _uiState.value.identity ?: return
        if (_uiState.value.activeCall != null) return

        val controller = CallController(
            context = getApplication(),
            myIdentity = identity.value,
            useTurn = true
        )
        _uiState.update { it.copy(activeCall = controller) }
        controller.startOutgoing(contact.name, contact.code)
    }

    fun openIncomingDialog() {
        _uiState.update { it.copy(showIncomingDialog = true, incomingOfferInput = "") }
    }

    fun closeIncomingDialog() {
        _uiState.update { it.copy(showIncomingDialog = false) }
    }

    fun onIncomingOfferChange(value: String) {
        _uiState.update { it.copy(incomingOfferInput = value) }
    }

    fun pasteIncomingOffer() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(getApplication())
            ?.toString()
            ?.trim()
            ?: return
        _uiState.update { it.copy(incomingOfferInput = text) }
    }

    fun acceptIncomingOffer() {
        val offer = _uiState.value.incomingOfferInput.trim()
        if (offer.isBlank()) return

        val decoded = SignalCodec.decode(offer).getOrNull()
        if (decoded == null || decoded.type != SignalType.OFFER) {
            return
        }

        val identity = _uiState.value.identity ?: return
        val remoteId = decoded.payload.identity
        val contactName = _uiState.value.contacts
            .find { it.code.equals(remoteId, ignoreCase = true) }
            ?.name
            ?: remoteId
            ?: "Собеседник"

        val controller = CallController(
            context = getApplication(),
            myIdentity = identity.value,
            useTurn = true
        )
        _uiState.update {
            it.copy(
                activeCall = controller,
                showIncomingDialog = false,
                incomingOfferInput = ""
            )
        }
        controller.acceptIncoming(offer, contactName)
    }

    fun closeCall() {
        _uiState.value.activeCall?.reset()
        _uiState.update { it.copy(activeCall = null) }
    }
}
