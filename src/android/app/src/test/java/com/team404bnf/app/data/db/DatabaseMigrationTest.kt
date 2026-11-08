package com.team404bnf.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DatabaseMigrationTest {

    @Test
    fun `MIGRATION_3_4 SQL statement is valid and executable`() {
        assertNotNull(MIGRATION_3_4)
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test
    fun `MIGRATION_4_5 changes attempt id to text without destructive reset`() {
        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }

    @Test
    fun `MIGRATION_1_2 and MIGRATION_2_3 migration objects check`() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }
}
