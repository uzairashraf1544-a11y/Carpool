package com.example.data.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CommuteSelection(
    val date: LocalDate = LocalDate.now(),
    val direction: String = "Township → Plant",
    // Store the stable window name; display its time slot through MasterData.
    val commuteWindow: String = MasterData.COMMUTE_WINDOWS.first().name,
    val pickupZone: String = "EMs",
    val destinationZone: String = "FFL Plant Main Gate"
) {
    val formattedDate: String
        get() = runCatching {
            date.format(DateTimeFormatter.ofPattern("EEEE, dd MMM", Locale.getDefault()))
        }.getOrElse { date.toString() }

    val displayTime: String
        get() = MasterData.displayTimeFor(commuteWindow)

    val isoDate: String
        get() = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}
