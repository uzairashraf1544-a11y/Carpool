package com.example.util

import com.example.data.model.CommuteSlot
import com.example.data.model.MasterData
import com.example.data.model.PickupSide
import java.time.LocalTime

object CommuteSlotResolver {
    const val POST_SLOT_GRACE_MINUTES = 45

    fun resolveSlotForPickup(pickupArea: String, localTime: LocalTime = LocalTime.now()): CommuteSlot {
        val side = MasterData.getSideForPickup(pickupArea)
        return resolveSlotForSide(side, localTime)
    }

    fun resolveSlotForSide(side: PickupSide, localTime: LocalTime = LocalTime.now()): CommuteSlot {
        val currentMinutes = localTime.hour * 60 + localTime.minute

        return if (side == PickupSide.TOWNSHIP) {
            if (currentMinutes < 630 || currentMinutes >= 1080) {
                CommuteSlot.MORNING_TO_PLANT
            } else {
                CommuteSlot.LUNCH_RETURN_TO_PLANT
            }
        } else {
            if (currentMinutes < 900 || currentMinutes >= 1320) {
                CommuteSlot.LUNCH_TO_TOWNSHIP
            } else {
                CommuteSlot.EVENING_TO_TOWNSHIP
            }
        }
    }
}
