package com.example.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CachedReport(
    val rows: List<ManagementReportRow>,
    val lastUpdatedTimestamp: Long
)

object ReportCacheManager {

    private const val PREFS_NAME = "travelbuddy_report_cache"
    private const val KEY_ROWS_JSON = "cached_management_report_rows_json"
    private const val KEY_LAST_UPDATED = "cached_management_report_last_updated"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Persists the management report rows and timestamp to device local storage.
     * Takes 0 Firestore operations.
     */
    fun saveReportCache(context: Context, rows: List<ManagementReportRow>, timestamp: Long) {
        val jsonArray = JSONArray()
        rows.forEach { row ->
            val obj = JSONObject().apply {
                put("serialNumber", row.serialNumber)
                put("personName", row.personName)
                put("co2Saved", row.co2Saved)
                put("ridesAsDriver", row.ridesAsDriver)
                put("ridesAsPassenger", row.ridesAsPassenger)
            }
            jsonArray.put(obj)
        }

        getPrefs(context).edit()
            .putString(KEY_ROWS_JSON, jsonArray.toString())
            .putLong(KEY_LAST_UPDATED, timestamp)
            .apply()
    }

    /**
     * Retrieves the cached report from local storage.
     * Guaranteed 0 Firestore reads.
     */
    fun getCachedReport(context: Context): CachedReport {
        val prefs = getPrefs(context)
        val timestamp = prefs.getLong(KEY_LAST_UPDATED, 0L)
        val jsonStr = prefs.getString(KEY_ROWS_JSON, null)

        if (jsonStr.isNullOrBlank()) {
            return CachedReport(emptyList(), timestamp)
        }

        return try {
            val jsonArray = JSONArray(jsonStr)
            val rows = ArrayList<ManagementReportRow>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                rows.add(
                    ManagementReportRow(
                        serialNumber = obj.optInt("serialNumber", i + 1),
                        personName = obj.optString("personName", "User"),
                        co2Saved = obj.optDouble("co2Saved", 0.0),
                        ridesAsDriver = obj.optInt("ridesAsDriver", 0),
                        ridesAsPassenger = obj.optInt("ridesAsPassenger", 0)
                    )
                )
            }
            CachedReport(rows, timestamp)
        } catch (_: Exception) {
            CachedReport(emptyList(), timestamp)
        }
    }

    /**
     * User-friendly formatting of the cache timestamp.
     */
    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "Never updated"
        val sdf = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun clearCache(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
