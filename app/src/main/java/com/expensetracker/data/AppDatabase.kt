package com.autoexpensetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.autoexpensetracker.util.KeystoreHelper

@Database(entities = [Transaction::class, Reminder::class, Budget::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun reminderDao(): ReminderDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        // Renamed from "expense_tracker_encrypted.db" on the
        // sqlcipher-android migration (2026-09-04, REQUIREMENTS.md ยง10.6).
        // The two libraries' underlying encrypted file formats are NOT
        // guaranteed compatible — real-world reports of "file is not a
        // database" errors exist for in-place upgrades between them. Rather
        // than write and test one-time cross-library migration/export code
        // for a database format switch (not a schema change Room's own
        // Migration system can express at all), this deliberately opens a
        // fresh file under a new name. Safe to do now specifically because
        // this app has not yet reached Production — no real user has data
        // in the old-format file that this would need to preserve. This
        // must NOT be treated as a template for a future rename after
        // real users exist.
        private const val DB_NAME = "expense_tracker_encrypted_v2.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: added `note` and `tags` columns to `transactions`, and
         * added the `reminders` table (categories feature). Preserves all
         * existing rows instead of wiping them.
         *
         * IMPORTANT: every future schema change MUST add its own explicit
         * Migration here. There is no `fallbackToDestructiveMigration()`
         * fallback anymore — a missing migration will now throw a crash
         * (IllegalStateException) during development instead of silently
         * deleting user data, which is the safer failure mode. See
         * REQUIREMENTS.md Decision Log 2026-08-16 for why this changed.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN tags TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        amount REAL,
                        dueDayOfMonth INTEGER NOT NULL,
                        notes TEXT,
                        lastNotifiedYearMonth TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 -> v3: added the `budgets` table (per-category monthly limits). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budgets (
                        category TEXT PRIMARY KEY NOT NULL,
                        monthlyLimit REAL NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v3 -> v4: added `balanceAfter` column to `transactions` (balance tracking feature). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN balanceAfter REAL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase {
            // Passphrase is generated once and stored only inside Android Keystore
            // (hardware-backed on most devices). It never touches disk in plaintext
            // and is never logged. See REQUIREMENTS.md Security ยง1.
            val passphrase: ByteArray = KeystoreHelper.getOrCreateDbPassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}