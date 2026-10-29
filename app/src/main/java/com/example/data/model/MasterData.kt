package com.example.data.model

enum class PickupSide(val displayName: String) {
    TOWNSHIP("Township"),
    PLANT("Plant"),
    OTHER("Other");

    companion object {
        fun fromString(value: String?): PickupSide {
            return when (value?.trim()?.uppercase()) {
                "PLANT" -> PLANT
                "OTHER" -> OTHER
                else -> TOWNSHIP
            }
        }
    }
}

// LocationSide alias for full source compatibility
typealias LocationSide = PickupSide

data class PickupArea(
    val id: String,
    val displayName: String,
    val side: PickupSide
)

data class CommuteWindowInfo(
    val name: String,
    val timeSlot: String,
    val direction: String,
    val origin: String = "Township",
    val destination: String = "Plant"
)

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

    val TOWNSHIP_PICKUP_AREAS: List<PickupArea> = TOWNSHIP_LOCATIONS.map {
        PickupArea(
            id = it.replace(Regex("[^A-Za-z0-9]"), "_").uppercase(),
            displayName = it,
            side = PickupSide.TOWNSHIP
        )
    }

    val PLANT_PICKUP_AREAS: List<PickupArea> = PLANT_LOCATIONS.map {
        PickupArea(
            id = it.replace(Regex("[^A-Za-z0-9]"), "_").uppercase(),
            displayName = it,
            side = PickupSide.PLANT
        )
    }

    const val OTHER_LOCATION_NAME = "Other / Different Location"
    const val OTHER_ZONE = "OTHER"

    val OTHER_PICKUP_AREAS: List<PickupArea> = listOf(
        PickupArea(
            id = "OTHER",
            displayName = OTHER_LOCATION_NAME,
            side = PickupSide.OTHER
        )
    )

    val ALL_PICKUP_AREAS: List<PickupArea> = TOWNSHIP_PICKUP_AREAS + PLANT_PICKUP_AREAS + OTHER_PICKUP_AREAS

    fun getSideForPickup(pickup: String): PickupSide {
        val trimmed = pickup.trim()
        if (trimmed.equals(OTHER_LOCATION_NAME, ignoreCase = true) || trimmed.equals(OTHER_ZONE, ignoreCase = true)) {
            return PickupSide.OTHER
        }
        if (PLANT_LOCATIONS.any { it.equals(trimmed, ignoreCase = true) }) {
            return PickupSide.PLANT
        }
        return PickupSide.TOWNSHIP
    }

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

    // Legacy helpers for historical records and screen compatibility
    val COMMUTE_WINDOWS = listOf(
        CommuteWindowInfo("Morning Inbound", "07:00", "Township → Plant", "Township", "Plant"),
        CommuteWindowInfo("Lunch Outbound", "13:00", "Plant → Township", "Plant", "Township"),
        CommuteWindowInfo("Lunch Return", "14:00", "Township → Plant", "Township", "Plant"),
        CommuteWindowInfo("Evening Return", "17:05", "Plant → Township", "Plant", "Township")
    )

    fun pickupLocationsFor(direction: String): List<String> =
        if (direction == "Plant → Township") PLANT_LOCATIONS else TOWNSHIP_LOCATIONS

    fun destinationLocationsFor(direction: String): List<String> =
        if (direction == "Plant → Township") TOWNSHIP_LOCATIONS else PLANT_LOCATIONS

    fun windowsForDirection(direction: String): List<CommuteWindowInfo> =
        COMMUTE_WINDOWS.filter { it.direction == direction }

    fun windowByValue(value: String): CommuteWindowInfo? =
        COMMUTE_WINDOWS.firstOrNull { it.name.equals(value, ignoreCase = true) || it.timeSlot.equals(value, ignoreCase = true) }

    fun displayTimeFor(windowName: String): String =
        windowByValue(windowName)?.timeSlot ?: windowName

    fun reminderSlotById(slotId: String): CommuteSlot? = CommuteSlot.fromId(slotId)
}
