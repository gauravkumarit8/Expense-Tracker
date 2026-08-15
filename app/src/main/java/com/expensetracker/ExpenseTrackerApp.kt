package com.expensetracker

import android.app.Application
import net.sqlcipher.database.SQLiteDatabase

class ExpenseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SQLiteDatabase.loadLibs(this) // load native SQLCipher libs once
    }
}
