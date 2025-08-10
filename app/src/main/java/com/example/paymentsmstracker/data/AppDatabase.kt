package com.example.paymentsmstracker.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Transaction::class],
    version = 2,
//    exportSchema = false,
//    autoMigrations = [
//        AutoMigration (from = 1, to = 2)
//    ]
) // Increment version on schema changes
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    companion object {
        // Singleton prevents multiple instances of database opening at the
        // same time.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // if the INSTANCE is not null, then return it,
            // if it is, then create the database
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transaction_database" // Name of your database file
                )
                    // Wipes and rebuilds instead of migrating if no Migration object.
                    // Migration is not covered in this basic example.
                     .fallbackToDestructiveMigration() // Use with caution, for development
                    .build()
                INSTANCE = instance
                // return instance
                instance
            }
        }
    }
}