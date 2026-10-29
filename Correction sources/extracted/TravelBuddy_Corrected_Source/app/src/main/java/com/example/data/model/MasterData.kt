package com.example.data.model

object MasterData {
    val TOWNSHIP_LOCATIONS = listOf(
        "D-Type — Blocks A–K/L",
        "C-Type",
        "MOQs",
        "EMs",
        "BOQs",
        "Guest Houses",
        "Single Staff"
    )

    val PLANT_LOCATIONS = listOf(
        "FFL Plant Main Gate",
        "HSE Building",
        "Warehouse",
        "L&DC Building",
        "Main Workshop",
        "Laboratory",
        "CCR-1",
        "MCR",
        "Maintenance South Workshop",
        "Maintenance North Workshop"
    )

    val COMMUTE_WINDOWS = listOf(
        CommuteWindowInfo("Morning Inbound", "07:15 AM – 08:00 AM", "Township → Plant"),
        CommuteWindowInfo("Lunch Outbound", "12:45 PM – 01:15 PM", "Plant → Township"),
        CommuteWindowInfo("Lunch Return", "02:15 PM – 02:30 PM", "Township → Plant"),
        CommuteWindowInfo("Evening Return", "05:00 PM – 05:30 PM", "Plant → Township")
    )

    val DEPARTMENTS = listOf(
        "Maintenance",
        "HSE",
        "Production",
        "Administration",
        "Laboratory",
        "Logistics & Warehouse",
        "Engineering",
        "IT & Telecom"
    )

    fun windowsForDirection(direction: String): List<CommuteWindowInfo> =
        COMMUTE_WINDOWS.filter { it.direction == direction }

    fun windowByValue(value: String): CommuteWindowInfo? =
        COMMUTE_WINDOWS.firstOrNull { it.name == value || it.timeSlot == value }

    fun displayTimeFor(value: String): String = windowByValue(value)?.timeSlot ?: value

    fun pickupLocationsFor(direction: String): List<String> =
        if (direction == "Plant → Township") PLANT_LOCATIONS else TOWNSHIP_LOCATIONS

    fun destinationLocationsFor(direction: String): List<String> =
        if (direction == "Plant → Township") TOWNSHIP_LOCATIONS else PLANT_LOCATIONS
}

data class CommuteWindowInfo(
    val name: String,
    val timeSlot: String,
    val direction: String
)
