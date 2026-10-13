package app.gamenative.utils

import app.gamenative.data.GameSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceGameStatsServiceTest {
    @Test fun preservesOldFieldsAndParsesAppendedStarsInServerOrder() {
        val stats = DeviceGameStatsService.parse(
            JSONObject("""{"games":{"STEAM":{"Door Kickers 2":[44,60,0,581,32,1,0,0],"Broforce":[630,58,69,14055,16,9,7,9]}}}"""), false,
        ).getValue(GameSource.STEAM)
        val door = stats.getValue("Door Kickers 2")
        assertEquals(44, door.successfulRuns)
        assertEquals(60, door.medianFps)
        assertEquals(581, door.medianSessionSec)
        assertEquals(0, door.fiveStarReviews)
        assertEquals(32, door.ratings!!.oneStar)
        assertEquals(1, door.ratings.twoStar)
        assertEquals(33, door.ratings.ratedTotal)
        val broforce = stats.getValue("Broforce").ratings!!
        assertEquals(listOf(16, 9, 7, 9, 69), listOf(broforce.oneStar, broforce.twoStar, broforce.threeStar, broforce.fourStar, broforce.fiveStar))
    }

    @Test fun acceptsOldRowsWithoutInventingMissingCountsAndHonorsModernPayload() {
        val stats = DeviceGameStatsService.parse(
            JSONObject("""{"games":{"STEAM":{"Old":[10,30,4,500]}},"games_modern":{"STEAM":{"New":[1,60,1,100,0,0,0,0]}}}"""), false,
        ).getValue(GameSource.STEAM).getValue("Old")
        assertEquals(4, stats.fiveStarReviews)
        assertNull(stats.ratings)
        val modern = DeviceGameStatsService.parse(
            JSONObject("""{"games":{},"games_modern":{"STEAM":{"New":[1,60,1,100,0,0,0,0]}}}"""), true,
        )
        assertEquals(1, modern.getValue(GameSource.STEAM).getValue("New").ratings!!.fiveStar)
    }

    @Test fun unknownPlatformsAndMalformedEntriesDoNotCrash() {
        val parsed = DeviceGameStatsService.parse(
            JSONObject("""{"games":{"FUTURE":{"Game":[1,2]},"STEAM":{"Bad":null,"Good":[1,60,0,5,null,0,0,0]}}}"""), false,
        )
        assertEquals(1, parsed.size)
        assertNull(parsed.getValue(GameSource.STEAM).getValue("Good").ratings)
    }
}
