package com.team404bnf.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@ConsistentCopyVisibility
data class ImportFileLimit private constructor(val bytes: Long) {
    init {
        require(bytes >= 0L && bytes % MB == 0L) { "Некорректный размер импорта" }
    }

    // Zero is persisted as an explicit unlimited choice, never as a finite zero-byte cap.
    val isUnlimited: Boolean get() = bytes == 0L
    val megabytes: Long? get() = if (isUnlimited) null else bytes / MB
    val label: String get() = when {
        isUnlimited -> "Без ограничений"
        bytes % GB == 0L -> "${bytes / GB} ГБ"
        else -> "${bytes / MB} МБ"
    }

    companion object {
        private const val MB = 1024L * 1024
        private const val GB = 1024L * MB
        val MB_100 = ImportFileLimit(100L * MB)
        val MB_250 = ImportFileLimit(250L * MB)
        val MB_500 = ImportFileLimit(500L * MB)
        val GB_1 = ImportFileLimit(GB)
        val UNLIMITED = ImportFileLimit(0L)
        val presets = listOf(MB_100, MB_250, MB_500, GB_1)
        val DEFAULT = UNLIMITED

        fun fromStored(bytes: Long?): ImportFileLimit = when {
            bytes == 0L -> UNLIMITED
            bytes != null && bytes > 0L && bytes % MB == 0L -> ImportFileLimit(bytes)
            else -> DEFAULT
        }

        /** Whole megabytes, checked before multiplication so pasted large numbers cannot overflow. */
        fun fromMegabytes(input: String): ImportFileLimit? {
            val text = input.trim()
            if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
            val value = text.toLongOrNull() ?: return null
            if (value <= 0L || value > Long.MAX_VALUE / MB) return null
            return ImportFileLimit(value * MB)
        }
    }
}

/** One preference in the existing DataStore; changing it never mutates saved recordings. */
class ImportSettings internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.appPreferences)
    private val key = longPreferencesKey("max_import_bytes")

    val limit = store.data.map { ImportFileLimit.fromStored(it[key]) }

    suspend fun currentLimit(): ImportFileLimit = limit.first()

    suspend fun setLimit(limit: ImportFileLimit) {
        store.edit { it[key] = limit.bytes }
    }
}
