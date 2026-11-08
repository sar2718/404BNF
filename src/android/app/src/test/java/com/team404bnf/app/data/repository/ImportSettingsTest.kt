package com.team404bnf.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImportSettingsTest {
    @Test fun `default is unlimited and unset or invalid preferences do not impose a size cap`() {
        assertTrue(ImportFileLimit.DEFAULT.isUnlimited)
        assertEquals(ImportFileLimit.UNLIMITED, ImportFileLimit.fromStored(null))
        assertEquals(ImportFileLimit.UNLIMITED, ImportFileLimit.fromStored(-1L))
        assertEquals(ImportFileLimit.UNLIMITED, ImportFileLimit.fromStored(Long.MAX_VALUE))
        assertEquals(ImportFileLimit.UNLIMITED, ImportFileLimit.fromStored(1L))
    }

    @Test fun `manual limits and unlimited have stable stored values and readable labels`() {
        val custom = requireNotNull(ImportFileLimit.fromMegabytes(" 750 "))
        assertEquals(750L * 1024 * 1024, custom.bytes)
        assertEquals("750 МБ", custom.label)
        assertEquals(750L, custom.megabytes)
        assertEquals(custom, ImportFileLimit.fromStored(custom.bytes))
        assertEquals(ImportFileLimit.GB_1, ImportFileLimit.fromMegabytes("1024"))
        assertEquals("2 ГБ", ImportFileLimit.fromMegabytes("2048")?.label)
        assertEquals(ImportFileLimit.UNLIMITED, ImportFileLimit.fromStored(0L))
        assertTrue(ImportFileLimit.UNLIMITED.isUnlimited)
        assertNull(ImportFileLimit.UNLIMITED.megabytes)
        assertEquals("Без ограничений", ImportFileLimit.UNLIMITED.label)
    }

    @Test fun `manual input rejects zero invalid values and overflowing byte counts`() {
        for (input in listOf("", " ", "0", "-1", "+1", "1.5", "1,5", "abc", "1e3", "1 000", Long.MAX_VALUE.toString())) {
            assertNull(input, ImportFileLimit.fromMegabytes(input))
        }
        val largest = Long.MAX_VALUE / (1024L * 1024)
        assertEquals(largest * 1024 * 1024, ImportFileLimit.fromMegabytes(largest.toString())?.bytes)
        assertNull(ImportFileLimit.fromMegabytes((largest + 1).toString()))
    }

    @Test fun `selected limit persists across store recreation and preserves existing consent`() = runBlocking {
        val custom = requireNotNull(ImportFileLimit.fromMegabytes("750"))
        for (selected in listOf(ImportFileLimit.MB_250, custom, ImportFileLimit.UNLIMITED)) {
            val directory = Files.createTempDirectory("404bnf-import-settings-").toFile()
            val file = File(directory, "consent_settings.preferences_pb")
            val version = stringPreferencesKey("accepted_terms_version")
            val acceptedAt = longPreferencesKey("accepted_at_epoch_ms")
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val firstStore = PreferenceDataStoreFactory.create(scope = scope1, produceFile = { file })
                firstStore.edit { it[version] = "1.0"; it[acceptedAt] = 42L }
                val first = ImportSettings(firstStore)
                assertEquals(ImportFileLimit.UNLIMITED, first.currentLimit())
                for (option in ImportFileLimit.presets + listOf(custom, ImportFileLimit.UNLIMITED)) {
                    first.setLimit(option)
                    assertEquals(option, first.currentLimit())
                }
                first.setLimit(selected)
                scope1.cancel()
                scope1.coroutineContext[Job]!!.join()
                val reopenedStore = PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file })
                assertEquals(selected, ImportSettings(reopenedStore).currentLimit())
                val preferences = reopenedStore.data.first()
                assertEquals("1.0", preferences[version])
                assertEquals(42L, preferences[acceptedAt])
            } finally {
                scope1.cancel(); scope2.cancel()
                scope1.coroutineContext[Job]!!.join()
                scope2.coroutineContext[Job]!!.join()
                directory.deleteRecursively()
            }
        }
    }
}
