package com.example.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Standardized Date and Time utility enforcing the corporate time zone (Asia/Karachi).
 * Guarantees that query bounds, date strings, and quota windows match regardless of client device settings.
 */
object TimeUtils {
    val KARACHI_ZONE: ZoneId = ZoneId.of("Asia/Karachi")

    fun todayKarachi(): LocalDate = LocalDate.now(KARACHI_ZONE)

    fun todayKarachiString(): String = todayKarachi().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun getQuotaResetExplanation(): String =
        "Firebase Firestore free daily quota resets at midnight Pacific Time, which corresponds to 13:00 PKT (winter) / 12:00 PKT (summer)."
}
