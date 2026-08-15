package com.expensetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import com.expensetracker.util.KeystoreHelper

@Database(entities = [Transaction::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val DB_NAME = "expense_tracker_encrypted.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

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
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration() // acceptable pre-v1; replace with real migrations post-launch
                .build()
        }
    }
}
