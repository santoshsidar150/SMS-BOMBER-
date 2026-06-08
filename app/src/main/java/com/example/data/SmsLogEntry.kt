package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_logs")
data class SmsLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val phoneNumber: String,
    val message: String,
    val timestamp: Long,
    val status: String // "SUCCESS", "FAILED", "PENDING"
)
