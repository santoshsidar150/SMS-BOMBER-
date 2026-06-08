package com.example.data

import kotlinx.coroutines.flow.Flow

class SmsRepository(private val smsLogDao: SmsLogDao) {
    val allLogs: Flow<List<SmsLogEntry>> = smsLogDao.getAllSmsLogs()

    suspend fun insertLog(entry: SmsLogEntry): Long {
        return smsLogDao.insertSmsLog(entry)
    }

    suspend fun deleteLog(entry: SmsLogEntry) {
        smsLogDao.deleteSmsLog(entry)
    }

    suspend fun clearLogs() {
        smsLogDao.clearAllSmsLogs()
    }
}
