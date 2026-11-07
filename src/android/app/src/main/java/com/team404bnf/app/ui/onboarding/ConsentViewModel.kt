package com.team404bnf.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.team404bnf.app.data.repository.ConsentRepository
import com.team404bnf.app.data.repository.ConsentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConsentViewModel(
    private val repository: ConsentRepository
) : ViewModel() {

    val status = repository.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConsentStatus.LOADING
    )

    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun accept() {
        if (
            _saving.value ||
            status.value != ConsentStatus.REQUIRED
        ) return

        _saving.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                repository.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value =
                    "Не удалось сохранить согласие"
            } finally {
                _saving.value = false
            }
        }
    }

    class Factory(
        private val repository: ConsentRepository
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {
            if (modelClass.isAssignableFrom(
                    ConsentViewModel::class.java
                )
            ) {
                return ConsentViewModel(repository) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel"
            )
        }
    }
}