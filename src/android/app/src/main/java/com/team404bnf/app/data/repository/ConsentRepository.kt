package com.team404bnf.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Keep the existing file name so consent and app preferences survive upgrades.
internal val Context.appPreferences by preferencesDataStore(
    name = "consent_settings"
)

enum class ConsentStatus {
    LOADING,
    REQUIRED,
    ACCEPTED,
    ERROR
}

class ConsentRepository(
    context: Context
) {
    companion object {
        const val TERMS_VERSION = "1.0"

        private val VERSION_KEY =
            stringPreferencesKey("accepted_terms_version")

        private val ACCEPTED_AT_KEY =
            longPreferencesKey("accepted_at_epoch_ms")
    }

    private val appContext = context.applicationContext

    val status: Flow<ConsentStatus> =
        appContext.appPreferences.data
            .map { preferences ->
                if (
                    preferences[VERSION_KEY] == TERMS_VERSION &&
                    preferences[ACCEPTED_AT_KEY] != null
                ) {
                    ConsentStatus.ACCEPTED
                } else {
                    ConsentStatus.REQUIRED
                }
            }
            .catch {
                emit(ConsentStatus.ERROR)
            }

    suspend fun accept() {
        appContext.appPreferences.edit { preferences ->
            preferences[VERSION_KEY] = TERMS_VERSION
            preferences[ACCEPTED_AT_KEY] =
                System.currentTimeMillis()
        }
    }
}
