package com.example.data.model

enum class CommuteSlot(
    val id: String,
    val hour: Int,
    val minute: Int,
    val originSide: PickupSide,
    val destinationSide: PickupSide,
    val direction: String,
    val displayTime: String,
    val notificationTitle: String,
    val notificationBody: String
) {
    MORNING_TO_PLANT(
        id = "MORNING_TO_PLANT",
        hour = 7,
        minute = 0,
        originSide = PickupSide.TOWNSHIP,
        destinationSide = PickupSide.PLANT,
        direction = "Township → Plant",
        displayTime = "7:00 AM",
        notificationTitle = "How are you travelling this morning?",
        notificationBody = "Heading out? Let colleagues know if you're driving or need a ride."
    ),
    LUNCH_TO_TOWNSHIP(
        id = "LUNCH_TO_TOWNSHIP",
        hour = 13,
        minute = 0,
        originSide = PickupSide.PLANT,
        destinationSide = PickupSide.TOWNSHIP,
        direction = "Plant → Township",
        displayTime = "1:00 PM",
        notificationTitle = "Heading to Township?",
        notificationBody = "Driving or looking for a ride? Check in with TravelBuddy."
    ),
    LUNCH_RETURN_TO_PLANT(
        id = "LUNCH_RETURN_TO_PLANT",
        hour = 14,
        minute = 0,
        originSide = PickupSide.TOWNSHIP,
        destinationSide = PickupSide.PLANT,
        direction = "Township → Plant",
        displayTime = "2:00 PM",
        notificationTitle = "Heading back to Plant?",
        notificationBody = "Let colleagues know whether you're driving or need a ride."
    ),
    EVENING_TO_TOWNSHIP(
        id = "EVENING_TO_TOWNSHIP",
        hour = 17,
        minute = 5,
        originSide = PickupSide.PLANT,
        destinationSide = PickupSide.TOWNSHIP,
        direction = "Plant → Township",
        displayTime = "5:05 PM",
        notificationTitle = "Heading home?",
        notificationBody = "Driving or looking for a ride? Check in with TravelBuddy."
    );

    companion object {
        fun fromId(id: String?): CommuteSlot? {
            if (id.isNullOrBlank()) return null
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: when (id) {
                    "0700", "Morning Inbound" -> MORNING_TO_PLANT
                    "1300", "Lunch Outbound" -> LUNCH_TO_TOWNSHIP
                    "1400", "Lunch Return" -> LUNCH_RETURN_TO_PLANT
                    "1705", "Evening Return" -> EVENING_TO_TOWNSHIP
                    else -> null
                }
        }
    }
}
