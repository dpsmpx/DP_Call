package com.dpsmpx.dpcall.ui.home

import com.dpsmpx.dpcall.R
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpsmpx.dpcall.data.CallLogEntry
import com.dpsmpx.dpcall.data.CallLogRepository
import com.dpsmpx.dpcall.data.ContactsRepository
import com.dpsmpx.dpcall.data.DuplicateContactException
import com.dpsmpx.dpcall.data.IdentityRepository
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.domain.Identity
import com.dpsmpx.dpcall.signaling.SignalCodec
import com.dpsmpx.dpcall.util.CodeValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val showIncomingDialog: Boolean = false,
    val incomingOfferInput: String = "",
    val incomingError: String? = null,
    /** 0 — контакты, 1 — журнал. */
    val tab: Int = 0,
    /** Код, полученный сканированием или из «Поделиться»: карточка «Позвонить / Сохранить». */
    val foundCode: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val identityRepo = IdentityRepository(application)
    private val contactsRepo = ContactsRepository(application)

    private val callLogRepo = CallLogRepository(application)

    /** Журнал звонков, новые сверху. */
    val callLog: StateFlow<List<CallLogEntry>> =
        callLogRepo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearCallLog() {
        viewModelScope.launch { callLogRepo.clear() }
    }

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(tab = tab) }
    }

    /** Пропущенные из уведомления: открываем сразу журнал. */
    fun showCallLog() = selectTab(1)

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

    /**
     * @param code код, который уже известен (из «Поделиться», журнала, QR). Если его нет,
     *   берём код из буфера обмена: чаще всего его только что скопировали в мессенджере.
     */
    fun openAddDialog(code: String? = null) {
        val prefill = code?.let(CodeValidator::extract) ?: clipboardText()?.let(CodeValidator::extract)?.takeIf(::isNewCode)
        _uiState.update {
            it.copy(showAddDialog = true, addCodeInput = prefill.orEmpty(), addNameInput = "", addError = null)
        }
    }

    /**
     * Код отсканирован или прислан через «Поделиться». Знакомый контакт или новый код
     * показываются карточкой с выбором; если пользователь уже заполняет «Новый контакт»
     * ([fillAddDialog]) и код новый — он просто подставляется в форму.
     */
    fun onCodeFound(raw: String, fillAddDialog: Boolean = false) {
        val code = CodeValidator.extract(raw)
        if (code == null) {
            if (fillAddDialog) onAddCodeChange(CodeValidator.normalize(raw))
            return
        }
        if (fillAddDialog && isNewCode(code)) {
            onAddCodeChange(code)
            return
        }
        _uiState.update { it.copy(foundCode = code, showAddDialog = false) }
    }

    fun dismissFoundCode() {
        _uiState.update { it.copy(foundCode = null) }
    }

    /** «Сохранить в контакты» из карточки найденного кода. */
    fun saveFoundCode() {
        val code = _uiState.value.foundCode ?: return
        dismissFoundCode()
        openAddDialog(code)
    }

    private fun isNewCode(code: String): Boolean {
        val state = _uiState.value
        return code != state.identity?.value && state.contacts.none { it.code.equals(code, ignoreCase = true) }
    }

    private fun clipboardText(): String? {
        return try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Описание читается без системного сообщения «вставлено из буфера»; сам текст — только если он есть.
            val hasText = clipboard.primaryClipDescription?.let {
                it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            } ?: false
            if (!hasText) return null
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString()
                ?.takeIf { it.length <= MAX_CLIP_CHARS }?.trim()
        } catch (e: Exception) {
            null
        }
    }

    fun closeAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, addError = null, isAdding = false) }
    }

    /** Вставленное сообщение целиком («Мой код DP Call: …») сводится к самому коду. */
    fun onAddCodeChange(value: String) {
        val code = if (value.length > CODE_LENGTH) CodeValidator.extract(value) ?: value else value
        _uiState.update { it.copy(addCodeInput = code, addError = null) }
    }

    fun onAddNameChange(value: String) {
        _uiState.update { it.copy(addNameInput = value, addError = null) }
    }

    fun pasteCodeFromClipboard() {
        clipboardText()?.let(::onAddCodeChange)
    }

    fun confirmAddContact() {
        val state = _uiState.value
        val code = CodeValidator.extract(state.addCodeInput) ?: CodeValidator.normalize(state.addCodeInput)
        val name = state.addNameInput.trim()

        when {
            CodeValidator.isLegacy(code) -> {
                _uiState.update {
                    it.copy(addError = getApplication<Application>().getString(R.string.error_legacy_code))
                }
                return
            }
            !CodeValidator.isValid(code) -> {
                _uiState.update { it.copy(addError = getApplication<Application>().getString(R.string.error_code_length)) }
                return
            }
            name.isBlank() -> {
                _uiState.update { it.copy(addError = getApplication<Application>().getString(R.string.error_enter_name)) }
                return
            }
            code == state.identity?.value -> {
                _uiState.update { it.copy(addError = getApplication<Application>().getString(R.string.error_own_code)) }
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
                        val app = getApplication<Application>()
                        it.copy(
                            isAdding = false,
                            addError = app.getString(
                                if (e is DuplicateContactException) R.string.error_duplicate_contact
                                else R.string.error_add_contact
                            )
                        )
                    }
                }
            )
        }
    }

    fun removeContact(code: String) {
        viewModelScope.launch { contactsRepo.remove(code) }
    }

    fun openIncomingDialog() {
        _uiState.update { it.copy(showIncomingDialog = true, incomingOfferInput = "", incomingError = null) }
    }

    fun closeIncomingDialog() {
        _uiState.update { it.copy(showIncomingDialog = false) }
    }

    fun onIncomingOfferChange(value: String) {
        _uiState.update { it.copy(incomingOfferInput = value, incomingError = null) }
    }

    fun pasteIncomingOffer() {
        val text = clipboardText() ?: return
        _uiState.update { it.copy(incomingOfferInput = text, incomingError = null) }
    }

    fun setIncomingError(message: String?) {
        _uiState.update { it.copy(incomingError = message) }
    }

    fun renameContact(code: String, newName: String) {
        val name = newName.trim()
        if (name.isBlank()) return
        viewModelScope.launch { contactsRepo.updateName(code, name) }
    }

    private companion object {
        const val CODE_LENGTH = 52
        /** Всё, что длиннее самого большого приглашения, в поля не вставляем. */
        const val MAX_CLIP_CHARS = SignalCodec.MAX_SIGNAL_CHARS * 2
    }
}
