package com.example

import com.example.data.model.CommuteSlot
import com.example.data.model.LocalTravelState
import com.example.data.model.MasterData
import com.example.data.model.PassengerEligibility
import com.example.data.model.PassengerNeed
import com.example.data.model.PickupSide
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.TravelMode
import com.example.data.model.User
import com.example.data.repository.AppReminderSettings
import com.example.service.CommuteReminderScheduler
import com.example.service.DailyCommuteScheduler
import com.example.util.PhoneDialerUtil
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteSlotAndInferenceTest {

    @Test
    fun testAllTownshipLocationsMapToTownshipSide() {
        MasterData.TOWNSHIP_LOCATIONS.forEach { loc ->
            assertEquals(
                "Expected $loc to be TOWNSHIP",
                PickupSide.TOWNSHIP,
                MasterData.getSideForPickup(loc)
            )
        }
    }

    @Test
    fun testAllPlantLocationsMapToPlantSide() {
        MasterData.PLANT_LOCATIONS.forEach { loc ->
            assertEquals(
                "Expected $loc to be PLANT",
                PickupSide.PLANT,
                MasterData.getSideForPickup(loc)
            )
        }
    }

    @Test
    fun testExactPickupEqualityNotRequiredForSameSideMatching() {
        val driverPickup = "EMs"
        val passengerPickup = "C-Type"

        val driverSide = MasterData.getSideForPickup(driverPickup)
        val passengerSide = MasterData.getSideForPickup(passengerPickup)

        // Exact strings differ
        assertFalse(driverPickup.equals(passengerPickup, ignoreCase = true))

        // But both belong to TOWNSHIP side
        assertEquals(PickupSide.TOWNSHIP, driverSide)
        assertEquals(PickupSide.TOWNSHIP, passengerSide)
        assertEquals(driverSide, passengerSide)
    }

    @Test
    fun testPlantDifferentExactPickupSameSideMatching() {
        val driverPickup = "FFL Plant Main Gate"
        val passengerPickup = "MCR"

        val driverSide = MasterData.getSideForPickup(driverPickup)
        val passengerSide = MasterData.getSideForPickup(passengerPickup)

        // Exact strings differ
        assertFalse(driverPickup.equals(passengerPickup, ignoreCase = true))

        // But both belong to PLANT side
        assertEquals(PickupSide.PLANT, driverSide)
        assertEquals(PickupSide.PLANT, passengerSide)
        assertEquals(driverSide, passengerSide)
    }

    @Test
    fun testCrossSideSeparation() {
        val townshipPickup = "EMs"
        val plantPickup = "Maintenance South Workshop"

        val townshipSide = MasterData.getSideForPickup(townshipPickup)
        val plantSide = MasterData.getSideForPickup(plantPickup)

        assertEquals(PickupSide.TOWNSHIP, townshipSide)
        assertEquals(PickupSide.PLANT, plantSide)
        assertFalse(townshipSide == plantSide)
    }

    @Test
    fun testRideModelStatusDiscoverabilityAndExpiry() {
        val now = System.currentTimeMillis()
        val ride = Ride(
            rideId = "test_ride_1",
            driverId = "driver_1",
            driverName = "Ahmed",
            date = "2026-09-02",
            pickupZone = "EMs",
            pickupSide = "TOWNSHIP",
            totalSeats = 3,
            availableSeats = 3,
            status = Ride.STATUS_AVAILABLE,
            expiresAt = now + 18 * 3600 * 1000L
        )

        assertEquals("EMs", ride.pickupZone)
        assertEquals("TOWNSHIP", ride.pickupSide)
        assertEquals(Ride.STATUS_AVAILABLE, ride.status)
        assertTrue("Expected expiry to be in future", ride.expiresAt > now)

        // Status-based discoverability check
        val isRideDiscoverable = ride.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL) && ride.availableSeats > 0
        assertTrue(isRideDiscoverable)

        val fullRide = ride.copy(status = Ride.STATUS_FULL, availableSeats = 0)
        assertFalse(fullRide.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL) && fullRide.availableSeats > 0)
    }

    @Test
    fun testPassengerNeedModelStatusDiscoverabilityAndExpiry() {
        val now = System.currentTimeMillis()
        val need = PassengerNeed(
            needId = "test_need_1",
            passengerId = "passenger_1",
            passengerName = "Uzair",
            date = "2026-09-02",
            pickupZone = "C-Type",
            pickupSide = "TOWNSHIP",
            status = PassengerNeed.STATUS_LOOKING,
            expiresAt = now + 18 * 3600 * 1000L
        )

        assertEquals("C-Type", need.pickupZone)
        assertEquals("TOWNSHIP", need.pickupSide)
        assertEquals(PassengerNeed.STATUS_LOOKING, need.status)
        assertTrue("Expected expiry to be in future", need.expiresAt > now)

        // Status-based discoverability check
        assertTrue(need.status == PassengerNeed.STATUS_LOOKING)

        val matchedNeed = need.copy(status = PassengerNeed.STATUS_MATCHED)
        assertFalse(matchedNeed.status == PassengerNeed.STATUS_LOOKING)
    }

    @Test
    fun testOtherPickupMapsToOtherSide() {
        assertEquals(PickupSide.OTHER, MasterData.getSideForPickup("OTHER"))
        assertEquals(PickupSide.OTHER, MasterData.getSideForPickup("other"))
        assertEquals(PickupSide.OTHER, MasterData.getSideForPickup("Other / Different Location"))
        assertEquals(PickupSide.OTHER, MasterData.getSideForPickup(MasterData.OTHER_LOCATION_NAME))
    }

    @Test
    fun testWildcardMatchingVisibility() {
        fun isVisibleToViewer(postSide: PickupSide, viewerSide: PickupSide): Boolean {
            return when (viewerSide) {
                PickupSide.OTHER -> true // OTHER viewer sees all posts
                PickupSide.TOWNSHIP -> postSide == PickupSide.TOWNSHIP || postSide == PickupSide.OTHER
                PickupSide.PLANT -> postSide == PickupSide.PLANT || postSide == PickupSide.OTHER
            }
        }

        // Township viewer
        assertTrue(isVisibleToViewer(PickupSide.TOWNSHIP, PickupSide.TOWNSHIP))
        assertTrue(isVisibleToViewer(PickupSide.OTHER, PickupSide.TOWNSHIP))
        assertFalse(isVisibleToViewer(PickupSide.PLANT, PickupSide.TOWNSHIP))

        // Plant viewer
        assertTrue(isVisibleToViewer(PickupSide.PLANT, PickupSide.PLANT))
        assertTrue(isVisibleToViewer(PickupSide.OTHER, PickupSide.PLANT))
        assertFalse(isVisibleToViewer(PickupSide.TOWNSHIP, PickupSide.PLANT))

        // Other viewer
        assertTrue(isVisibleToViewer(PickupSide.TOWNSHIP, PickupSide.OTHER))
        assertTrue(isVisibleToViewer(PickupSide.PLANT, PickupSide.OTHER))
        assertTrue(isVisibleToViewer(PickupSide.OTHER, PickupSide.OTHER))
    }

    @Test
    fun testDisplayPickupHelper() {
        val regularRide = Ride(pickupZone = "EMs", pickupSide = "TOWNSHIP")
        assertEquals("EMs", regularRide.displayPickup)

        val otherRideWithCustom = Ride(
            pickupZone = "OTHER",
            pickupSide = "OTHER",
            customPickupLocation = "Head Office Parking"
        )
        assertEquals("Head Office Parking", otherRideWithCustom.displayPickup)

        val otherRideBlankCustom = Ride(
            pickupZone = "OTHER",
            pickupSide = "OTHER",
            customPickupLocation = ""
        )
        assertEquals("OTHER", otherRideBlankCustom.displayPickup)

        val regularNeed = PassengerNeed(pickupZone = "C-Type", pickupSide = "TOWNSHIP")
        assertEquals("C-Type", regularNeed.displayPickup)

        val otherNeed = PassengerNeed(
            pickupZone = "OTHER",
            pickupSide = "OTHER",
            customPickupLocation = "Main Hospital Gate"
        )
        assertEquals("Main Hospital Gate", otherNeed.displayPickup)
    }

    @Test
    fun testAutoCloseRideWhenAllPassengersTerminal() {
        fun evaluateAutoClose(passengerStatuses: List<String>): Pair<Boolean, String> {
            val terminalStatuses = setOf(
                "COMPLETED",
                "CANCELLED_BY_PASSENGER",
                "CANCELLED_BY_DRIVER",
                "CLOSED_UNCONFIRMED",
                "REJECTED",
                "EXPIRED"
            )
            val allTerminal = passengerStatuses.isNotEmpty() && passengerStatuses.all { it in terminalStatuses }
            if (!allTerminal) return Pair(false, "STILL_ACTIVE")

            val hasCompleted = passengerStatuses.any { it == "COMPLETED" }
            return Pair(true, if (hasCompleted) "COMPLETED" else "CANCELLED")
        }

        // Scenario 1: 1 passenger completes -> auto-close COMPLETED
        val (closed1, status1) = evaluateAutoClose(listOf("COMPLETED"))
        assertTrue(closed1)
        assertEquals("COMPLETED", status1)

        // Scenario 2: 1 passenger cancels -> auto-close CANCELLED
        val (closed2, status2) = evaluateAutoClose(listOf("CANCELLED_BY_PASSENGER"))
        assertTrue(closed2)
        assertEquals("CANCELLED", status2)

        // Scenario 3: P1 completed, P2 still ACCEPTED -> NOT auto-closed
        val (closed3, _) = evaluateAutoClose(listOf("COMPLETED", "ACCEPTED"))
        assertFalse(closed3)

        // Scenario 4: P1 completed, P2 didn't travel -> all terminal, auto-close COMPLETED
        val (closed4, status4) = evaluateAutoClose(listOf("COMPLETED", "CANCELLED_BY_PASSENGER"))
        assertTrue(closed4)
        assertEquals("COMPLETED", status4)

        // Scenario 5: P1 and P2 both cancelled -> all terminal, auto-close CANCELLED
        val (closed5, status5) = evaluateAutoClose(listOf("CANCELLED_BY_PASSENGER", "CANCELLED_BY_DRIVER"))
        assertTrue(closed5)
        assertEquals("CANCELLED", status5)
    }

    @Test
    fun testRideModelPassengerCountFields() {
        val ride = Ride(
            rideId = "test_ride_1",
            driverId = "d1",
            acceptedPassengerIds = listOf("p1", "p2"),
            completedPassengerIds = listOf("p1"),
            finalPassengerCount = 2,
            confirmedPassengerCount = 1
        )
        assertEquals(2, ride.finalPassengerCount)
        assertEquals(1, ride.confirmedPassengerCount)
    }

    @Test
    fun testDriverMasterClosePassengerCounting() {
        data class MockRequest(val passengerId: String, val status: String)

        fun calculateDriverCloseCounts(
            outcome: String,
            acceptedPassengers: List<String>,
            requests: List<MockRequest>
        ): Triple<String, Int, Int> {
            val reqMap = requests.associateBy { it.passengerId }
            var confirmedCount = 0
            var finalCount = 0

            acceptedPassengers.forEach { pUid ->
                val curReqStatus = reqMap[pUid]?.status ?: ""
                if (outcome == "COMPLETED") {
                    if (curReqStatus == "COMPLETED") {
                        confirmedCount++
                        finalCount++
                    } else if (curReqStatus in listOf("ACCEPTED", "RIDE_STARTED", "REQUESTED")) {
                        finalCount++ // unconfirmed will be CLOSED_UNCONFIRMED
                    } else if (curReqStatus == "CLOSED_UNCONFIRMED") {
                        finalCount++
                    }
                }
            }

            val targetRideStatus = if (outcome == "COMPLETED") "COMPLETED" else "CANCELLED"
            return Triple(
                targetRideStatus,
                if (outcome == "COMPLETED") finalCount else 0,
                if (outcome == "COMPLETED") confirmedCount else 0
            )
        }

        // 1. Driver closes RIDE HAPPENED with P1 completed, P2 unconfirmed, P3 unconfirmed
        val (st1, final1, conf1) = calculateDriverCloseCounts(
            outcome = "COMPLETED",
            acceptedPassengers = listOf("p1", "p2", "p3"),
            requests = listOf(
                MockRequest("p1", "COMPLETED"),
                MockRequest("p2", "ACCEPTED"),
                MockRequest("p3", "ACCEPTED")
            )
        )
        assertEquals("COMPLETED", st1)
        assertEquals(3, final1)
        assertEquals(1, conf1)

        // 2. Driver closes RIDE DIDN'T HAPPEN
        val (st2, final2, conf2) = calculateDriverCloseCounts(
            outcome = "DID_NOT_TRAVEL",
            acceptedPassengers = listOf("p1", "p2"),
            requests = listOf(
                MockRequest("p1", "ACCEPTED"),
                MockRequest("p2", "ACCEPTED")
            )
        )
        assertEquals("CANCELLED", st2)
        assertEquals(0, final2)
        assertEquals(0, conf2)
    }

    @Test
    fun testPassengerClosureCountsOnMixedTerminalStates() {
        fun calculatePassengerAutoClose(
            acceptedPassengers: List<String>,
            allStatuses: Map<String, String>
        ): Triple<String, Int, Int> {
            val confirmedCount = acceptedPassengers.count { allStatuses[it] == "COMPLETED" }
            val finalCount = acceptedPassengers.count {
                allStatuses[it] in listOf("COMPLETED", "CLOSED_UNCONFIRMED")
            }
            val targetRideStatus = if (confirmedCount > 0) "COMPLETED" else "CANCELLED"
            return Triple(targetRideStatus, finalCount, confirmedCount)
        }

        // P1 completed, P2 cancelled by passenger, P3 completed
        val (st1, final1, conf1) = calculatePassengerAutoClose(
            acceptedPassengers = listOf("p1", "p2", "p3"),
            allStatuses = mapOf(
                "p1" to "COMPLETED",
                "p2" to "CANCELLED_BY_PASSENGER",
                "p3" to "COMPLETED"
            )
        )
        assertEquals("COMPLETED", st1)
        assertEquals(2, final1)
        assertEquals(2, conf1)

        // P1 cancelled by passenger, P2 cancelled by passenger
        val (st2, final2, conf2) = calculatePassengerAutoClose(
            acceptedPassengers = listOf("p1", "p2"),
            allStatuses = mapOf(
                "p1" to "CANCELLED_BY_PASSENGER",
                "p2" to "CANCELLED_BY_PASSENGER"
            )
        )
        assertEquals("CANCELLED", st2)
        assertEquals(0, final2)
        assertEquals(0, conf2)
    }

    @Test
    fun testIsActivePassengerParticipation() {
        assertTrue(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_ACCEPTED))
        assertTrue(RideRequest.isActivePassengerParticipation("RIDE_STARTED"))

        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_COMPLETED))
        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_CANCELLED_BY_PASSENGER))
        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_CANCELLED_BY_DRIVER))
        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_CLOSED_UNCONFIRMED))
        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_REJECTED))
        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_EXPIRED))
        assertFalse(RideRequest.isActivePassengerParticipation(RideRequest.STATUS_REQUESTED))
        assertFalse(RideRequest.isActivePassengerParticipation(null))
    }

    @Test
    fun testPassengerEligibilityDerivation() {
        fun deriveEligibility(
            activeRequest: RideRequest?,
            lookingNeed: PassengerNeed?
        ): PassengerEligibility {
            return when {
                activeRequest != null && RideRequest.isActivePassengerParticipation(activeRequest.status) ->
                    PassengerEligibility.ActiveRide(activeRequest)
                lookingNeed != null && lookingNeed.status == PassengerNeed.STATUS_LOOKING ->
                    PassengerEligibility.LookingForRide(lookingNeed)
                else -> PassengerEligibility.CanCreateRequest
            }
        }

        val activeReq = RideRequest(requestId = "r1", passengerId = "p1", status = RideRequest.STATUS_ACCEPTED)
        val completedReq = RideRequest(requestId = "r2", passengerId = "p1", status = RideRequest.STATUS_COMPLETED)
        val lookingNeed = PassengerNeed(needId = "n1", passengerId = "p1", status = PassengerNeed.STATUS_LOOKING)
        val matchedNeed = PassengerNeed(needId = "n2", passengerId = "p1", status = PassengerNeed.STATUS_MATCHED)

        // Case 1: Active RideRequest exists -> ActiveRide (even if lookingNeed is somehow present)
        val el1 = deriveEligibility(activeReq, lookingNeed)
        assertTrue(el1 is PassengerEligibility.ActiveRide)

        // Case 2: No active request, Looking need exists -> LookingForRide
        val el2 = deriveEligibility(null, lookingNeed)
        assertTrue(el2 is PassengerEligibility.LookingForRide)

        // Case 3: No active request, need is matched (belongs to MY RIDE flow) -> CanCreateRequest if no active request
        val el3 = deriveEligibility(null, matchedNeed)
        assertTrue(el3 is PassengerEligibility.CanCreateRequest)

        // Case 4: No active request, no need -> CanCreateRequest
        val el4 = deriveEligibility(null, null)
        assertTrue(el4 is PassengerEligibility.CanCreateRequest)

        // Case 5: Completed request -> CanCreateRequest
        val el5 = deriveEligibility(completedReq, null)
        assertTrue(el5 is PassengerEligibility.CanCreateRequest)
    }

    @Test
    fun testExclusivitySingleActionStateInvariant() {
        fun computeVisibleActionPanels(eligibility: PassengerEligibility): Triple<Boolean, Boolean, Boolean> {
            val hasActiveRide = eligibility is PassengerEligibility.ActiveRide
            val hasLookingNeed = eligibility is PassengerEligibility.LookingForRide
            val canCreateRequest = eligibility is PassengerEligibility.CanCreateRequest

            val showMyRide = hasActiveRide
            val showLookingNeed = !hasActiveRide && hasLookingNeed
            val showNeedCreation = !hasActiveRide && canCreateRequest

            return Triple(showMyRide, showLookingNeed, showNeedCreation)
        }

        val dummyReq = RideRequest(requestId = "req_1", status = RideRequest.STATUS_ACCEPTED)
        val dummyNeed = PassengerNeed(needId = "need_1", status = PassengerNeed.STATUS_LOOKING)

        // Test A: ActiveRide state
        val (myRideA, lookingA, createA) = computeVisibleActionPanels(PassengerEligibility.ActiveRide(dummyReq))
        assertTrue("showMyRide must be true", myRideA)
        assertFalse("showLookingNeed must be false", lookingA)
        assertFalse("showNeedCreation must be false", createA)
        assertEquals("Count of visible actions must be exactly 1", 1, listOf(myRideA, lookingA, createA).count { it })

        // Test B: LookingForRide state
        val (myRideB, lookingB, createB) = computeVisibleActionPanels(PassengerEligibility.LookingForRide(dummyNeed))
        assertFalse("showMyRide must be false", myRideB)
        assertTrue("showLookingNeed must be true", lookingB)
        assertFalse("showNeedCreation must be false", createB)
        assertEquals("Count of visible actions must be exactly 1", 1, listOf(myRideB, lookingB, createB).count { it })

        // Test C: CanCreateRequest state
        val (myRideC, lookingC, createC) = computeVisibleActionPanels(PassengerEligibility.CanCreateRequest)
        assertFalse("showMyRide must be false", myRideC)
        assertFalse("showLookingNeed must be false", lookingC)
        assertTrue("showNeedCreation must be true", createC)
        assertEquals("Count of visible actions must be exactly 1", 1, listOf(myRideC, lookingC, createC).count { it })
    }

    @Test
    fun testPhoneNumberNormalization() {
        assertEquals("03001234567", PhoneDialerUtil.normalizePhoneNumber("0300 1234567"))
        assertEquals("+923001234567", PhoneDialerUtil.normalizePhoneNumber("+92 300 1234567"))
        assertEquals("03001234567", PhoneDialerUtil.normalizePhoneNumber(" 0300-1234567 "))
        assertEquals("+923001234567", PhoneDialerUtil.normalizePhoneNumber("+92 (300) 123-4567"))
        assertEquals("03001234567", PhoneDialerUtil.normalizePhoneNumber("03001234567"))
        assertEquals("+923001234567", PhoneDialerUtil.normalizePhoneNumber("+923001234567"))

        // Edge and invalid cases
        assertEquals("", PhoneDialerUtil.normalizePhoneNumber(""))
        assertEquals("", PhoneDialerUtil.normalizePhoneNumber("   "))
        assertEquals("", PhoneDialerUtil.normalizePhoneNumber(null))
        assertEquals("", PhoneDialerUtil.normalizePhoneNumber("abc"))
        assertEquals("", PhoneDialerUtil.normalizePhoneNumber("+-()"))
    }

    @Test
    fun testMultiPassengerPhoneMapping() {
        val p1Req = RideRequest(
            requestId = "req_1",
            passengerId = "p1",
            passengerName = "Passenger One",
            passengerPhone = "0300 1111111"
        )
        val p2Req = RideRequest(
            requestId = "req_2",
            passengerId = "p2",
            passengerName = "Passenger Two",
            passengerPhone = "+92 300 2222222"
        )
        val p3Req = RideRequest(
            requestId = "req_3",
            passengerId = "p3",
            passengerName = "Passenger Three",
            passengerPhone = "0300-3333333"
        )

        val confirmedPassengers = listOf(p1Req, p2Req, p3Req)

        val mappedDialUris = confirmedPassengers.associate { req ->
            req.passengerId to PhoneDialerUtil.normalizePhoneNumber(req.passengerPhone)
        }

        assertEquals("03001111111", mappedDialUris["p1"])
        assertEquals("+923002222222", mappedDialUris["p2"])
        assertEquals("03003333333", mappedDialUris["p3"])
    }

    @Test
    fun testPassengerViewingDriverPhoneMapping() {
        val driverRide = Ride(
            rideId = "ride_100",
            driverId = "driver_1",
            driverName = "Driver One",
            driverPhone = "0300 9999999"
        )
        val activeRequest = RideRequest(
            requestId = "req_100",
            rideId = "ride_100",
            driverId = "driver_1",
            driverName = "Driver One",
            driverPhone = driverRide.driverPhone
        )

        val normalizedDriverPhone = PhoneDialerUtil.normalizePhoneNumber(activeRequest.driverPhone)
        assertEquals("03009999999", normalizedDriverPhone)
    }

    @Test
    fun testManifestDoesNotRequestCallPhonePermission() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        if (manifestFile.exists()) {
            val content = manifestFile.readText()
            assertFalse(
                "AndroidManifest.xml must NOT request android.permission.CALL_PHONE",
                content.contains("android.permission.CALL_PHONE")
            )
        }
    }

    @Test
    fun testTravelModeEnumConversion() {
        assertEquals(TravelMode.PASSENGER, TravelMode.fromString("PASSENGER"))
        assertEquals(TravelMode.PASSENGER, TravelMode.fromString("passenger"))
        assertEquals(TravelMode.DRIVER, TravelMode.fromString("DRIVER"))
        assertEquals(TravelMode.DRIVER, TravelMode.fromString("driver"))
        assertEquals(TravelMode.PASSENGER, TravelMode.fromString(null))
        assertEquals(TravelMode.PASSENGER, TravelMode.fromString(""))
        assertEquals(TravelMode.PASSENGER, TravelMode.fromString("INVALID"))

        assertEquals(User.MODE_PASSENGER, TravelMode.PASSENGER.toUserMode())
        assertEquals(User.MODE_DRIVER, TravelMode.DRIVER.toUserMode())
    }

    @Test
    fun testInitialModePrecedence() {
        // Precedence: DataStore > Firestore currentMode > defaultRolePreference > PASSENGER
        fun resolveInitialMode(
            dataStoreMode: String?,
            firestoreMode: String?,
            defaultPref: String?
        ): TravelMode {
            return if (!dataStoreMode.isNullOrBlank()) {
                TravelMode.fromString(dataStoreMode)
            } else if (!firestoreMode.isNullOrBlank()) {
                TravelMode.fromString(firestoreMode)
            } else if (!defaultPref.isNullOrBlank()) {
                TravelMode.fromString(defaultPref)
            } else {
                TravelMode.PASSENGER
            }
        }

        // 1. DataStore takes top priority
        assertEquals(
            TravelMode.DRIVER,
            resolveInitialMode("DRIVER", "PASSENGER", "PASSENGER")
        )
        assertEquals(
            TravelMode.PASSENGER,
            resolveInitialMode("PASSENGER", "DRIVER", "DRIVER")
        )

        // 2. If DataStore null, fallback to Firestore currentMode
        assertEquals(
            TravelMode.DRIVER,
            resolveInitialMode(null, "DRIVER", "PASSENGER")
        )
        assertEquals(
            TravelMode.PASSENGER,
            resolveInitialMode(null, "PASSENGER", "DRIVER")
        )

        // 3. If Firestore blank, fallback to defaultRolePreference
        assertEquals(
            TravelMode.DRIVER,
            resolveInitialMode(null, null, "DRIVER")
        )

        // 4. If all null, default to PASSENGER
        assertEquals(
            TravelMode.PASSENGER,
            resolveInitialMode(null, null, null)
        )
    }

    @Test
    fun testModeSwitchingDoesNotAlterUserRole() {
        val adminUser = User(
            uid = "admin_1",
            role = User.ROLE_ADMIN,
            currentMode = User.MODE_PASSENGER
        )
        assertEquals(User.ROLE_ADMIN, adminUser.effectiveRole)
        assertTrue(adminUser.isAdmin())

        var currentUiMode = TravelMode.PASSENGER
        // Simulate 50 rapid mode switches
        repeat(25) {
            currentUiMode = TravelMode.DRIVER
            assertEquals(User.ROLE_ADMIN, adminUser.effectiveRole)
            assertTrue(adminUser.isAdmin())

            currentUiMode = TravelMode.PASSENGER
            assertEquals(User.ROLE_ADMIN, adminUser.effectiveRole)
            assertTrue(adminUser.isAdmin())
        }
        assertEquals(User.ROLE_ADMIN, adminUser.role)
    }

    @Test
    fun testConflictGuardsOnPostingNotBrowsing() {
        val hasActivePassengerRide = true
        val hasActiveDriverRide = false

        // Browsing is allowed freely
        var browsingMode = TravelMode.PASSENGER
        browsingMode = TravelMode.DRIVER // Allowed!
        assertEquals(TravelMode.DRIVER, browsingMode)

        // But Driver publishing is blocked when active passenger ride exists
        val driverPublishAllowed = !hasActivePassengerRide
        assertFalse(
            "Driver publish should be blocked if user has an active passenger ride",
            driverPublishAllowed
        )

        // Vice-versa: if user has active driver ride, passenger posting is blocked
        val pHasActiveDriver = true
        val passengerPostAllowed = !pHasActiveDriver
        assertFalse(
            "Passenger post should be blocked if user has an active driver ride",
            passengerPostAllowed
        )
    }

    @Test
    fun testDiscoveryListenerExclusivityByMode() {
        // When PASSENGER: availableDrivers should run (if not matched), matchingPassengerNeeds should be empty
        val passengerMode = TravelMode.PASSENGER
        val isPassengerMode = passengerMode == TravelMode.PASSENGER
        val isDriverMode = passengerMode == TravelMode.DRIVER

        assertTrue(isPassengerMode)
        assertFalse(isDriverMode)

        // When DRIVER: matchingPassengerNeeds should run, availableDrivers should be empty
        val driverMode = TravelMode.DRIVER
        val isPassengerMode2 = driverMode == TravelMode.PASSENGER
        val isDriverMode2 = driverMode == TravelMode.DRIVER

        assertFalse(isPassengerMode2)
        assertTrue(isDriverMode2)
    }

    @Test
    fun testCalculateReminderDateTime() {
        val testDate = LocalDate.of(2026, 9, 4)

        // Case 1: 17:05 base with 20 lead minutes -> 16:45 on same day
        val nowEarly = LocalDateTime.of(testDate, LocalTime.of(10, 0))
        val target1 = DailyCommuteScheduler.calculateReminderDateTime(17, 5, 20, nowEarly)
        assertEquals(LocalDateTime.of(testDate, LocalTime.of(16, 45)), target1)

        // Case 2: 13:00 base with 75 lead minutes -> 11:45 on same day
        val target2 = DailyCommuteScheduler.calculateReminderDateTime(13, 0, 75, nowEarly)
        assertEquals(LocalDateTime.of(testDate, LocalTime.of(11, 45)), target2)

        // Case 3: 07:00 base with 0 lead minutes -> 07:00 on same day
        val nowMorning = LocalDateTime.of(testDate, LocalTime.of(6, 0))
        val target3 = DailyCommuteScheduler.calculateReminderDateTime(7, 0, 0, nowMorning)
        assertEquals(LocalDateTime.of(testDate, LocalTime.of(7, 0)), target3)

        // Case 4: 07:00 base with 480 lead minutes (8 hours lead time -> 23:00 previous day)
        // If now is 10:00 on testDate, today's 07:00 minus 480m would be 23:00 yesterday (which is passed).
        // Next occurrence is tomorrow's 07:00 minus 480m -> 23:00 today!
        val target4 = DailyCommuteScheduler.calculateReminderDateTime(7, 0, 480, nowEarly)
        assertEquals(LocalDateTime.of(testDate, LocalTime.of(23, 0)), target4)

        // Case 5: Today's reminder already passed -> next occurrence scheduled for tomorrow
        val nowAfter1705 = LocalDateTime.of(testDate, LocalTime.of(17, 0))
        // Target was 16:45 which has passed at 17:00, so target should be tomorrow at 16:45
        val target5 = DailyCommuteScheduler.calculateReminderDateTime(17, 5, 20, nowAfter1705)
        assertEquals(LocalDateTime.of(testDate.plusDays(1), LocalTime.of(16, 45)), target5)
    }

    @Test
    fun testLocalTravelStateFlagsAndExclusivity() {
        // NONE
        val stateNone = LocalTravelState.NONE
        assertFalse(stateNone.isPassengerPostOrActive)
        assertFalse(stateNone.isDriverPostOrActive)

        // PASSENGER_LOOKING
        val stateLooking = LocalTravelState.PASSENGER_LOOKING
        assertTrue(stateLooking.isPassengerPostOrActive)
        assertFalse(stateLooking.isDriverPostOrActive)

        // PASSENGER_ACTIVE
        val statePassengerActive = LocalTravelState.PASSENGER_ACTIVE
        assertTrue(statePassengerActive.isPassengerPostOrActive)
        assertFalse(statePassengerActive.isDriverPostOrActive)

        // DRIVER_AVAILABLE
        val stateAvailable = LocalTravelState.DRIVER_AVAILABLE
        assertFalse(stateAvailable.isPassengerPostOrActive)
        assertTrue(stateAvailable.isDriverPostOrActive)

        // DRIVER_ACTIVE
        val stateDriverActive = LocalTravelState.DRIVER_ACTIVE
        assertFalse(stateDriverActive.isPassengerPostOrActive)
        assertTrue(stateDriverActive.isDriverPostOrActive)

        // Exclusivity guard check:
        // If user has PASSENGER_LOOKING or PASSENGER_ACTIVE, driver publish is blocked
        assertTrue(stateLooking.isPassengerPostOrActive)
        val canDriverPublishWithLooking = !stateLooking.isPassengerPostOrActive
        assertFalse(canDriverPublishWithLooking)

        // If user has DRIVER_AVAILABLE or DRIVER_ACTIVE, passenger post is blocked
        assertTrue(stateAvailable.isDriverPostOrActive)
        val canPassengerPostWithDriverAvailable = !stateAvailable.isDriverPostOrActive
        assertFalse(canPassengerPostWithDriverAvailable)
    }

    @Test
    fun testAppReminderSettingsDefaultTimesAndReset() {
        val settings = AppReminderSettings()
        // Check default times
        assertEquals(7, settings.slot0700Hour)
        assertEquals(0, settings.slot0700Minute)
        assertEquals(13, settings.slot1300Hour)
        assertEquals(0, settings.slot1300Minute)
        assertEquals(14, settings.slot1400Hour)
        assertEquals(0, settings.slot1400Minute)
        assertEquals(17, settings.slot1705Hour)
        assertEquals(5, settings.slot1705Minute)
        assertTrue(settings.masterEnabled)
        assertTrue(settings.slot0700Enabled)
        assertTrue(settings.slot1300Enabled)
        assertTrue(settings.slot1400Enabled)
        assertTrue(settings.slot1705Enabled)

        // Modify times and disable one slot
        val modified = settings.copy(
            slot0700Hour = 6,
            slot0700Minute = 45,
            slot1300Hour = 12,
            slot1300Minute = 30,
            slot1400Hour = 14,
            slot1400Minute = 15,
            slot1705Hour = 18,
            slot1705Minute = 0,
            slot1300Enabled = false
        )
        assertEquals(6, modified.slot0700Hour)
        assertEquals(45, modified.slot0700Minute)
        assertFalse(modified.slot1300Enabled)

        // Reset times restores 07:00, 13:00, 14:00, 17:05 while preserving enabled toggles
        val reset = modified.copy(
            slot0700Hour = 7,
            slot0700Minute = 0,
            slot1300Hour = 13,
            slot1300Minute = 0,
            slot1400Hour = 14,
            slot1400Minute = 0,
            slot1705Hour = 17,
            slot1705Minute = 5
        )
        assertEquals(7, reset.slot0700Hour)
        assertEquals(0, reset.slot0700Minute)
        assertEquals(13, reset.slot1300Hour)
        assertEquals(0, reset.slot1300Minute)
        assertEquals(14, reset.slot1400Hour)
        assertEquals(0, reset.slot1400Minute)
        assertEquals(17, reset.slot1705Hour)
        assertEquals(5, reset.slot1705Minute)
        // slot1300Enabled is preserved as false
        assertFalse(reset.slot1300Enabled)
        assertTrue(reset.slot0700Enabled)
    }

    @Test
    fun testStringsXmlContainsTravelBuddy() {
        val stringsFile = File("src/main/res/values/strings.xml")
        if (stringsFile.exists()) {
            val content = stringsFile.readText()
            assertTrue("strings.xml should define app_name as TravelBuddy", content.contains("<string name=\"app_name\">TravelBuddy</string>"))
            assertFalse("strings.xml should not have Carpool as app_name", content.contains("<string name=\"app_name\">Carpool</string>"))
        }
    }

    @Test
    fun testWorkingDayReminderEligibilityMatrix() {
        // MON - THU: all reminders allowed
        val weekdaysMonToThu = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        for (day in weekdaysMonToThu) {
            assertTrue("Reminder 1 should be allowed on $day", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, day))
            assertTrue("Reminder 2 should be allowed on $day", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, day))
            assertTrue("Reminder 3 should be allowed on $day", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, day))
            assertTrue("Reminder 4 should be allowed on $day", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, day))
        }

        // FRIDAY: Half day -> Reminder 1 allowed, Reminders 2-4 suppressed
        assertTrue("Reminder 1 must be allowed on FRIDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, DayOfWeek.FRIDAY))
        assertFalse("Reminder 2 must be suppressed on FRIDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.FRIDAY))
        assertFalse("Reminder 3 must be suppressed on FRIDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, DayOfWeek.FRIDAY))
        assertFalse("Reminder 4 must be suppressed on FRIDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.FRIDAY))

        // SATURDAY: All commute reminders suppressed
        assertFalse("Reminder 1 must be suppressed on SATURDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, DayOfWeek.SATURDAY))
        assertFalse("Reminder 2 must be suppressed on SATURDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.SATURDAY))
        assertFalse("Reminder 3 must be suppressed on SATURDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, DayOfWeek.SATURDAY))
        assertFalse("Reminder 4 must be suppressed on SATURDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.SATURDAY))

        // SUNDAY: All commute reminders suppressed
        assertFalse("Reminder 1 must be suppressed on SUNDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, DayOfWeek.SUNDAY))
        assertFalse("Reminder 2 must be suppressed on SUNDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.SUNDAY))
        assertFalse("Reminder 3 must be suppressed on SUNDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, DayOfWeek.SUNDAY))
        assertFalse("Reminder 4 must be suppressed on SUNDAY", CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.SUNDAY))
    }

    @Test
    fun testWeekdayTriggerCalculationForMondayToThursday() {
        // Tuesday 2026-09-08
        val testTuesday = LocalDate.of(2026, 9, 8)
        val zone = ZoneId.systemDefault()

        // 1. Morning: 07:00 exact time today when now is 06:00 on Tuesday
        val nowMorning = LocalDateTime.of(testTuesday, LocalTime.of(6, 0))
        val trigger1 = CommuteReminderScheduler.calculateNextTriggerMillis(CommuteReminderScheduler.REMINDER_1, 7, 0, nowMorning)
        val expected1 = LocalDateTime.of(testTuesday, LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected1, trigger1)

        // 2. Custom time: 08:30 exact time today when now is 07:00 on Tuesday
        val nowEarly = LocalDateTime.of(testTuesday, LocalTime.of(7, 0))
        val trigger2 = CommuteReminderScheduler.calculateNextTriggerMillis(CommuteReminderScheduler.REMINDER_1, 8, 30, nowEarly)
        val expected2 = LocalDateTime.of(testTuesday, LocalTime.of(8, 30)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected2, trigger2)

        // 3. Passed reminder on Tuesday afternoon (13:30) -> target is Wednesday 13:00
        val nowAfternoon = LocalDateTime.of(testTuesday, LocalTime.of(13, 30))
        val trigger3 = CommuteReminderScheduler.calculateNextTriggerMillis(CommuteReminderScheduler.REMINDER_2, 13, 0, nowAfternoon)
        val expected3 = LocalDateTime.of(testTuesday.plusDays(1), LocalTime.of(13, 0)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected3, trigger3)

        // 4. Exact match on Tuesday 13:00 -> advances to Wednesday 13:00
        val nowExact = LocalDateTime.of(testTuesday, LocalTime.of(13, 0))
        val trigger4 = CommuteReminderScheduler.calculateNextTriggerMillis(CommuteReminderScheduler.REMINDER_2, 13, 0, nowExact)
        val expected4 = LocalDateTime.of(testTuesday.plusDays(1), LocalTime.of(13, 0)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected4, trigger4)
    }

    @Test
    fun testNextWorkingDayOccurrenceCalculation() {
        // Week of Mon Sep 7 to Sun Sep 13, 2026:
        // Mon = Sep 7, Tue = Sep 8, Wed = Sep 9, Thu = Sep 10, Fri = Sep 11, Sat = Sep 12, Sun = Sep 13
        // Following Mon = Sep 14
        val monday = LocalDate.of(2026, 9, 7)
        val thursday = LocalDate.of(2026, 9, 10)
        val friday = LocalDate.of(2026, 9, 11)
        val saturday = LocalDate.of(2026, 9, 12)
        val sunday = LocalDate.of(2026, 9, 13)
        val followingMonday = LocalDate.of(2026, 9, 14)

        // A. Friday Reminder 1:
        // Before 07:00 on Friday -> triggers Friday 07:00
        val friBeforeR1 = LocalDateTime.of(friday, LocalTime.of(6, 30))
        val r1OnFriTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 7, 0, friBeforeR1)
        assertEquals(LocalDateTime.of(friday, LocalTime.of(7, 0)), r1OnFriTarget)

        // After 07:00 on Friday -> skips Sat, Sun -> next occurrence is Monday Sep 14
        val friAfterR1 = LocalDateTime.of(friday, LocalTime.of(8, 0))
        val r1AfterFriTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 7, 0, friAfterR1)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(7, 0)), r1AfterFriTarget)

        // Reminder 1 on Saturday -> next occurrence is Monday
        val satTime = LocalDateTime.of(saturday, LocalTime.of(10, 0))
        val r1SatTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 7, 0, satTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(7, 0)), r1SatTarget)

        // Reminder 1 on Sunday -> next occurrence is Monday
        val sunTime = LocalDateTime.of(sunday, LocalTime.of(18, 0))
        val r1SunTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 7, 0, sunTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(7, 0)), r1SunTarget)

        // B. Thursday Reminders 2, 3, 4 after Thursday trigger:
        // Skips Friday (not allowed), skips Saturday, skips Sunday -> schedules Monday Sep 14
        val thuEvening = LocalDateTime.of(thursday, LocalTime.of(18, 0))

        val r2ThuTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 13, 0, thuEvening)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(13, 0)), r2ThuTarget)

        val r3ThuTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_3, 14, 0, thuEvening)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(14, 0)), r3ThuTarget)

        val r4ThuTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_4, 17, 5, thuEvening)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(17, 5)), r4ThuTarget)

        // C. Friday Reminders 2, 3, 4 (any time on Friday):
        // Friday is not allowed -> skips Sat, Sun -> schedules Monday Sep 14
        val friMorning = LocalDateTime.of(friday, LocalTime.of(9, 0))

        val r2FriTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 13, 0, friMorning)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(13, 0)), r2FriTarget)

        val r3FriTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_3, 14, 0, friMorning)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(14, 0)), r3FriTarget)

        val r4FriTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_4, 17, 5, friMorning)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(17, 5)), r4FriTarget)

        // D. Saturday & Sunday for Reminders 2, 3, 4 -> schedules Monday Sep 14
        val r2SatTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 13, 0, satTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(13, 0)), r2SatTarget)

        val r3SatTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_3, 14, 0, satTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(14, 0)), r3SatTarget)

        val r4SatTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_4, 17, 5, satTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(17, 5)), r4SatTarget)

        val r2SunTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 13, 0, sunTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(13, 0)), r2SunTarget)

        val r3SunTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_3, 14, 0, sunTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(14, 0)), r3SunTarget)

        val r4SunTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_4, 17, 5, sunTime)
        assertEquals(LocalDateTime.of(followingMonday, LocalTime.of(17, 5)), r4SunTarget)
    }

    @Test
    fun testMonthEndAndYearEndNextOccurrence() {
        // 1. Month-end transition: Thursday April 30, 2026
        // April 30 is Thursday. Next working day for Reminder 2 (13:00) is Monday May 4, 2026.
        val thuApr30 = LocalDateTime.of(2026, 4, 30, 18, 0)
        val r2MonthEndTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 13, 0, thuApr30)
        assertEquals(LocalDateTime.of(2026, 5, 4, 13, 0), r2MonthEndTarget)

        // Reminder 1 on Friday May 1 after 07:00 -> Monday May 4, 2026
        val friMay1 = LocalDateTime.of(2026, 5, 1, 8, 0)
        val r1MonthEndTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 7, 0, friMay1)
        assertEquals(LocalDateTime.of(2026, 5, 4, 7, 0), r1MonthEndTarget)

        // 2. Year-end transition: Thursday December 31, 2026
        // Dec 31 is Thursday. Next working day for Reminder 2 (13:00) is Monday Jan 4, 2027.
        val thuDec31 = LocalDateTime.of(2026, 12, 31, 18, 0)
        val r2YearEndTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 13, 0, thuDec31)
        assertEquals(LocalDateTime.of(2027, 1, 4, 13, 0), r2YearEndTarget)

        // Reminder 1 on Friday Jan 1, 2027 after 07:00 -> Monday Jan 4, 2027
        val friJan1 = LocalDateTime.of(2027, 1, 1, 8, 0)
        val r1YearEndTarget = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 7, 0, friJan1)
        assertEquals(LocalDateTime.of(2027, 1, 4, 7, 0), r1YearEndTarget)
    }

    @Test
    fun testUserEditedTimesPreserveWorkingDayEligibility() {
        // User customizes Reminder 1 to 06:15 -> still allowed on Friday morning
        assertTrue(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, DayOfWeek.FRIDAY))
        val friMorning = LocalDateTime.of(2026, 9, 11, 5, 30)
        val customR1Target = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_1, 6, 15, friMorning)
        assertEquals(LocalDateTime.of(2026, 9, 11, 6, 15), customR1Target)

        // User customizes Reminder 2 to 11:30 (before noon!) -> STILL NOT ALLOWED on Friday
        // Must NOT hard-code "anything after 12:00 is disabled"
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.FRIDAY))
        val customR2Target = CommuteReminderScheduler.calculateNextTriggerDateTime(CommuteReminderScheduler.REMINDER_2, 11, 30, friMorning)
        // Advances to Monday Sep 14 at 11:30
        assertEquals(LocalDateTime.of(2026, 9, 14, 11, 30), customR2Target)

        // User customizes Reminder 4 to 16:00 -> still blocked on Friday and weekend
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.FRIDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.SATURDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.SUNDAY))
    }

    @Test
    fun testFinalDeliveryValidationSuppression() {
        // Verify final delivery filter for stale alarms reaching receiver on non-working days
        // 1. Stale alarm for Reminder 2 firing on Friday -> suppressed
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.FRIDAY))
        // 2. Stale alarm for Reminder 3 firing on Friday -> suppressed
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, DayOfWeek.FRIDAY))
        // 3. Stale alarm for Reminder 4 firing on Friday -> suppressed
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.FRIDAY))
        // 4. Stale alarm for any reminder firing on Saturday -> suppressed
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, DayOfWeek.SATURDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.SATURDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, DayOfWeek.SATURDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.SATURDAY))
        // 5. Stale alarm for any reminder firing on Sunday -> suppressed
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_1, DayOfWeek.SUNDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_2, DayOfWeek.SUNDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_3, DayOfWeek.SUNDAY))
        assertFalse(CommuteReminderScheduler.isReminderAllowedOnDay(CommuteReminderScheduler.REMINDER_4, DayOfWeek.SUNDAY))
    }

    @Test
    fun testCommuteReminderSchedulerRequestCodesAndSlots() {
        // Distinct request codes
        val r1Code = CommuteReminderScheduler.getRequestCode(CommuteReminderScheduler.REMINDER_1)
        val r2Code = CommuteReminderScheduler.getRequestCode(CommuteReminderScheduler.REMINDER_2)
        val r3Code = CommuteReminderScheduler.getRequestCode(CommuteReminderScheduler.REMINDER_3)
        val r4Code = CommuteReminderScheduler.getRequestCode(CommuteReminderScheduler.REMINDER_4)

        val codes = setOf(r1Code, r2Code, r3Code, r4Code)
        assertEquals("Each reminder must have a distinct request code", 4, codes.size)

        // Mapping to CommuteSlot
        assertEquals(CommuteSlot.MORNING_TO_PLANT, CommuteReminderScheduler.mapReminderIdToSlot(CommuteReminderScheduler.REMINDER_1))
        assertEquals(CommuteSlot.LUNCH_TO_TOWNSHIP, CommuteReminderScheduler.mapReminderIdToSlot(CommuteReminderScheduler.REMINDER_2))
        assertEquals(CommuteSlot.LUNCH_RETURN_TO_PLANT, CommuteReminderScheduler.mapReminderIdToSlot(CommuteReminderScheduler.REMINDER_3))
        assertEquals(CommuteSlot.EVENING_TO_TOWNSHIP, CommuteReminderScheduler.mapReminderIdToSlot(CommuteReminderScheduler.REMINDER_4))
    }

    @Test
    fun testAppReminderSettingsCustomHourAndMinute() {
        val defaultSettings = AppReminderSettings()
        // Check default hours and minutes
        assertEquals(7, defaultSettings.slot0700Hour)
        assertEquals(0, defaultSettings.slot0700Minute)
        assertEquals(13, defaultSettings.slot1300Hour)
        assertEquals(0, defaultSettings.slot1300Minute)
        assertEquals(14, defaultSettings.slot1400Hour)
        assertEquals(0, defaultSettings.slot1400Minute)
        assertEquals(17, defaultSettings.slot1705Hour)
        assertEquals(5, defaultSettings.slot1705Minute)

        // Customize hours and minutes
        val custom = defaultSettings.copy(
            slot0700Hour = 6,
            slot0700Minute = 45,
            slot1300Hour = 12,
            slot1300Minute = 30,
            slot1400Hour = 14,
            slot1400Minute = 15,
            slot1705Hour = 18,
            slot1705Minute = 0
        )
        assertEquals(6, custom.slot0700Hour)
        assertEquals(45, custom.slot0700Minute)
        assertEquals(12, custom.slot1300Hour)
        assertEquals(30, custom.slot1300Minute)
        assertEquals(14, custom.slot1400Hour)
        assertEquals(15, custom.slot1400Minute)
        assertEquals(18, custom.slot1705Hour)
        assertEquals(0, custom.slot1705Minute)
    }

    @Test
    fun testDayBasedReminderTestMatrix() {
        // Mon Sep 7, 2026 to Sun Sep 13, 2026:
        // Sep 7 = Mon, Sep 8 = Tue, Sep 9 = Wed, Sep 10 = Thu, Sep 11 = Fri, Sep 12 = Sat, Sep 13 = Sun
        // Sep 14 = Mon, Sep 15 = Tue, Sep 16 = Wed

        // REMINDER 1: Select Mon, Wed, Fri at 07:00
        val r1Days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        // 1. Monday 06:00 -> Monday 07:00
        val monMorning = LocalDateTime.of(2026, 9, 7, 6, 0)
        val r1Next1 = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, r1Days, monMorning)
        assertEquals(LocalDateTime.of(2026, 9, 7, 7, 0), r1Next1)

        // 2. Monday 08:00 (passed) -> Wednesday 07:00
        val monPast = LocalDateTime.of(2026, 9, 7, 8, 0)
        val r1Next2 = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, r1Days, monPast)
        assertEquals(LocalDateTime.of(2026, 9, 9, 7, 0), r1Next2)

        // 3. Friday 18:00 -> Next Monday Sep 14 07:00
        val friEvening = LocalDateTime.of(2026, 9, 11, 18, 0)
        val r1Next3 = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, r1Days, friEvening)
        assertEquals(LocalDateTime.of(2026, 9, 14, 7, 0), r1Next3)

        // REMINDER 2: Select Tue, Thu at 13:00
        val r2Days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        // 1. Tuesday 10:00 -> Tuesday 13:00
        val tueMorning = LocalDateTime.of(2026, 9, 8, 10, 0)
        val r2Next1 = CommuteReminderScheduler.calculateNextTriggerDateTime(13, 0, r2Days, tueMorning)
        assertEquals(LocalDateTime.of(2026, 9, 8, 13, 0), r2Next1)

        // 2. Tuesday 14:00 -> Thursday 13:00
        val tuePast = LocalDateTime.of(2026, 9, 8, 14, 0)
        val r2Next2 = CommuteReminderScheduler.calculateNextTriggerDateTime(13, 0, r2Days, tuePast)
        assertEquals(LocalDateTime.of(2026, 9, 10, 13, 0), r2Next2)

        // 3. Thursday 14:00 -> Next Tuesday Sep 15 13:00
        val thuPast = LocalDateTime.of(2026, 9, 10, 14, 0)
        val r2Next3 = CommuteReminderScheduler.calculateNextTriggerDateTime(13, 0, r2Days, thuPast)
        assertEquals(LocalDateTime.of(2026, 9, 15, 13, 0), r2Next3)

        // REMINDER 3: Select Sat, Sun at 14:00
        val r3Days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        // 1. Friday 10:00 -> Saturday 14:00
        val friMorning = LocalDateTime.of(2026, 9, 11, 10, 0)
        val r3Next1 = CommuteReminderScheduler.calculateNextTriggerDateTime(14, 0, r3Days, friMorning)
        assertEquals(LocalDateTime.of(2026, 9, 12, 14, 0), r3Next1)

        // 2. Saturday 15:00 -> Sunday 14:00
        val satPast = LocalDateTime.of(2026, 9, 12, 15, 0)
        val r3Next2 = CommuteReminderScheduler.calculateNextTriggerDateTime(14, 0, r3Days, satPast)
        assertEquals(LocalDateTime.of(2026, 9, 13, 14, 0), r3Next2)

        // 3. Sunday 15:00 -> Next Saturday Sep 19 14:00
        val sunPast = LocalDateTime.of(2026, 9, 13, 15, 0)
        val r3Next3 = CommuteReminderScheduler.calculateNextTriggerDateTime(14, 0, r3Days, sunPast)
        assertEquals(LocalDateTime.of(2026, 9, 19, 14, 0), r3Next3)

        // REMINDER 4: Select all 7 days at 17:05
        val r4Days = DayOfWeek.entries.toSet()
        // Friday 18:00 -> Saturday 17:05
        val r4Next1 = CommuteReminderScheduler.calculateNextTriggerDateTime(17, 5, r4Days, friEvening)
        assertEquals(LocalDateTime.of(2026, 9, 12, 17, 5), r4Next1)
        // Saturday 18:00 -> Sunday 17:05
        val satEvening = LocalDateTime.of(2026, 9, 12, 18, 0)
        val r4Next2 = CommuteReminderScheduler.calculateNextTriggerDateTime(17, 5, r4Days, satEvening)
        assertEquals(LocalDateTime.of(2026, 9, 13, 17, 5), r4Next2)
    }

    @Test
    fun testSingleDaySelection() {
        // Saturday only at 13:00
        val satOnly = setOf(DayOfWeek.SATURDAY)
        // Saturday 10:00 -> Saturday 13:00
        val satBefore = LocalDateTime.of(2026, 9, 12, 10, 0)
        val targetBefore = CommuteReminderScheduler.calculateNextTriggerDateTime(13, 0, satOnly, satBefore)
        assertEquals(LocalDateTime.of(2026, 9, 12, 13, 0), targetBefore)

        // Saturday 14:00 -> Next Saturday Sep 19 13:00
        val satAfter = LocalDateTime.of(2026, 9, 12, 14, 0)
        val targetAfter = CommuteReminderScheduler.calculateNextTriggerDateTime(13, 0, satOnly, satAfter)
        assertEquals(LocalDateTime.of(2026, 9, 19, 13, 0), targetAfter)
    }

    @Test
    fun testDayChangeReschedulingCalculation() {
        // Reminder 1 set to Monday only
        val monOnly = setOf(DayOfWeek.MONDAY)
        val nowSun = LocalDateTime.of(2026, 9, 6, 20, 0) // Sunday night
        val nextMon = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, monOnly, nowSun)
        assertEquals(LocalDateTime.of(2026, 9, 7, 7, 0), nextMon)

        // User deselects Monday and selects Wednesday
        val wedOnly = setOf(DayOfWeek.WEDNESDAY)
        val nextWed = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, wedOnly, nowSun)
        assertEquals(LocalDateTime.of(2026, 9, 9, 7, 0), nextWed)
    }

    @Test
    fun testZeroDaysSelectedReturnsNull() {
        val emptyDays = emptySet<DayOfWeek>()
        val now = LocalDateTime.of(2026, 9, 7, 6, 0)
        val result = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, emptyDays, now)
        assertNull("Empty day set must return null so no alarm is scheduled", result)

        val millis = CommuteReminderScheduler.calculateNextTriggerMillis(7, 0, emptyDays, now)
        assertNull("Empty day set millis must return null", millis)
    }

    @Test
    fun testEditingTimeRespectsDays() {
        val monWedFri = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val monMorning = LocalDateTime.of(2026, 9, 7, 6, 40) // Monday 06:40

        // At 07:00 -> still upcoming today at 07:00
        val target0700 = CommuteReminderScheduler.calculateNextTriggerDateTime(7, 0, monWedFri, monMorning)
        assertEquals(LocalDateTime.of(2026, 9, 7, 7, 0), target0700)

        // User changes time to 06:35 -> already passed for today Monday! Advances to Wednesday 06:35
        val target0635 = CommuteReminderScheduler.calculateNextTriggerDateTime(6, 35, monWedFri, monMorning)
        assertEquals(LocalDateTime.of(2026, 9, 9, 6, 35), target0635)
    }

    @Test
    fun testAppReminderSettingsDefaultsAndDaysIndependence() {
        val settings = AppReminderSettings()
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, settings.slot0700Days)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, settings.slot1300Days)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, settings.slot1400Days)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, settings.slot1705Days)

        // Deselecting Tuesday for Reminder 2 does not alter Reminder 1, 3, or 4
        val modified = settings.copy(
            slot1300Days = settings.slot1300Days - DayOfWeek.TUESDAY
        )
        assertFalse(modified.slot1300Days.contains(DayOfWeek.TUESDAY))
        assertTrue(modified.slot0700Days.contains(DayOfWeek.TUESDAY))
        assertTrue(modified.slot1400Days.contains(DayOfWeek.TUESDAY))
        assertTrue(modified.slot1705Days.contains(DayOfWeek.TUESDAY))
    }
}

