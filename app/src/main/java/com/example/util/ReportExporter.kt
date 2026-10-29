package com.example.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ManagementReportRow(
    val serialNumber: Int,
    val personName: String,
    val co2Saved: Double,
    val ridesAsDriver: Int,
    val ridesAsPassenger: Int
)

object ReportExporter {

    fun escapeCsv(value: String): String {
        val containsSpecial = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        return if (containsSpecial) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    fun generateCsvContent(rows: List<ManagementReportRow>): String {
        val sb = StringBuilder()
        sb.append("Sr.#,Person Name,CO2 Saved,Rides Taken as Driver,Rides Taken as Passenger\n")

        rows.forEach { row ->
            val escapedName = escapeCsv(row.personName)
            val formattedCo2 = String.format(Locale.US, "%.2f", row.co2Saved)
            sb.append("${row.serialNumber},$escapedName,$formattedCo2,${row.ridesAsDriver},${row.ridesAsPassenger}\n")
        }

        return sb.toString()
    }

    fun exportAndShareCsv(context: Context, rows: List<ManagementReportRow>): File {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fileName = "Ride_Sharing_Management_Report_$todayStr.csv"

        val reportsDir = File(context.cacheDir, "reports")
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        val file = File(reportsDir, fileName)
        FileWriter(file).use { writer ->
            writer.write(generateCsvContent(rows))
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Ride Sharing Management Report ($todayStr)")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Download / Share Management Report")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)

        return file
    }
}
