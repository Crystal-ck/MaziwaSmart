package com.maziwasmart.app.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Crystal's test farm: writes two cows and 14 days of records into the
 * logged-in farmer's own Firestore data, to test Reports and Advice.
 *
 *  - Baraka (HF75): COMPLETE records - morning + evening milk and
 *    feeding (with THI) every day for 14 days.
 *  - Neema: INCOMPLETE records - no genetic group, milk on only 5 days,
 *    feeding on only 2 days (one without THI), nothing in the last 4 days.
 *
 * All documents use ids starting with "test-", so loading again just
 * overwrites them and clear() removes only test data.
 *
 * Use a separate test account (e.g. crystal.test@...) - not a real farmer's account.
 */
object TestFarmSeeder {

    private const val PREFIX = "test-"
    private const val BARAKA = "test-baraka"
    private const val NEEMA = "test-neema"

    private val collections = listOf("cows", "milk", "feedings", "expenses", "sales")

    suspend fun seed(
        uid: String,
        db: FirebaseFirestore = FirebaseFirestore.getInstance()
    ) {
        val user = db.collection("users").document(uid)
        val batch = db.batch()
        val now = System.currentTimeMillis()

        fun put(collection: String, id: String, data: Any) {
            batch.set(user.collection(collection).document(id), data)
        }

        // ---------------- cows ----------------
        put(
            "cows", BARAKA,
            Cow(
                name = "Baraka",
                tagId = "COW-TEST-BARAKA",
                breed = "Friesian",
                dateOfBirth = "2021-03-14",
                lactationStatus = "Lactating",
                notes = "Test cow - complete records",
                createdAt = now,
                geneticGroup = "HF75"
            )
        )
        put(
            "cows", NEEMA,
            Cow(
                name = "Neema",
                tagId = "COW-TEST-NEEMA",
                breed = "Crossbreed",
                dateOfBirth = "2020-08-02",
                lactationStatus = "Lactating",
                notes = "Test cow - incomplete records",
                createdAt = now,
                geneticGroup = ""          // missing on purpose
            )
        )

        // ---------------- Baraka: 14 complete days ----------------
        // Small fixed variations so the charts aren't flat lines.
        val morningVar = listOf(0.0, 0.2, -0.1, 0.3, -0.2, 0.1, 0.0, -0.3, 0.2, 0.1, -0.1, 0.3, 0.0, 0.2)
        val eveningVar = listOf(0.1, -0.1, 0.2, 0.0, 0.1, -0.2, 0.2, 0.0, -0.1, 0.2, 0.1, -0.1, 0.0, 0.1)

        for (daysAgo in 13 downTo 0) {
            val i = 13 - daysAgo
            val date = Dates.daysAgo(daysAgo.toLong())
            val thi = if (i % 3 == 2) "T1" else "T0"          // one THI value per day
            val supplement = if (i % 2 == 0) "Mineral lick" else "None"

            put(
                "milk", "$BARAKA-$date-am",
                MilkRecord(cowId = BARAKA, cowName = "Baraka", date = date,
                    session = "Morning", litres = 4.2 + morningVar[i], createdAt = now)
            )
            put(
                "milk", "$BARAKA-$date-pm",
                MilkRecord(cowId = BARAKA, cowName = "Baraka", date = date,
                    session = "Evening", litres = 2.9 + eveningVar[i], createdAt = now)
            )

            val napierKg = 38.0 + (i % 4)                     // 38-41 kg fresh
            put(
                "feedings", "$BARAKA-$date-napier",
                FeedingRecord(cowId = BARAKA, cowName = "Baraka", date = date,
                    session = "Morning", feedType = "Napier",
                    freshWeightKg = napierKg, dryMatterPercent = 20.0,
                    dmiKg = Feeds.dryMatterIntake(napierKg, 20.0),
                    supplement = supplement,
                    supplementGrams = if (supplement == "None") 0.0 else 50.0,
                    timesPerDay = 2, createdAt = now, thiRange = thi)
            )
            put(
                "feedings", "$BARAKA-$date-meal",
                FeedingRecord(cowId = BARAKA, cowName = "Baraka", date = date,
                    session = "Evening", feedType = "Dairy meal",
                    freshWeightKg = 3.0, dryMatterPercent = 90.0,
                    dmiKg = Feeds.dryMatterIntake(3.0, 90.0),
                    timesPerDay = 2, createdAt = now, thiRange = thi)
            )
        }

        // ---------------- Neema: gaps on purpose ----------------
        // Milk on 5 days only (13, 11, 9, 7, 5 days ago) - nothing in the last 4 days.
        listOf(13, 11, 9, 7, 5).forEach { daysAgo ->
            val date = Dates.daysAgo(daysAgo.toLong())
            put(
                "milk", "$NEEMA-$date-am",
                MilkRecord(cowId = NEEMA, cowName = "Neema", date = date,
                    session = "Morning", litres = 2.0, createdAt = now)
            )
            if (daysAgo != 9) {   // evening missed on one day
                put(
                    "milk", "$NEEMA-$date-pm",
                    MilkRecord(cowId = NEEMA, cowName = "Neema", date = date,
                        session = "Evening", litres = 1.2, createdAt = now)
                )
            }
        }
        // Feeding on 2 days only; the latest one has no THI range.
        listOf(11 to "T1", 7 to "").forEach { (daysAgo, thi) ->
            val date = Dates.daysAgo(daysAgo.toLong())
            put(
                "feedings", "$NEEMA-$date-napier",
                FeedingRecord(cowId = NEEMA, cowName = "Neema", date = date,
                    session = "Morning", feedType = "Napier",
                    freshWeightKg = 25.0, dryMatterPercent = 20.0,
                    dmiKg = Feeds.dryMatterIntake(25.0, 20.0),
                    timesPerDay = 1, createdAt = now, thiRange = thi)
            )
        }

        // ---------------- money ----------------
        listOf(
            Triple(10, "Feed", 3000.0 to "Napier and dairy meal"),
            Triple(4, "Veterinary", 1500.0 to "Check-up for Neema"),
            Triple(2, "Supplements", 500.0 to "Mineral lick")
        ).forEach { (daysAgo, category, amountAndText) ->
            val date = Dates.daysAgo(daysAgo.toLong())
            put(
                "expenses", "$PREFIX$category-$date",
                Expense(category = category, description = amountAndText.second,
                    amount = amountAndText.first, date = date, createdAt = now)
            )
        }
        for (daysAgo in 12 downTo 0 step 2) {
            val date = Dates.daysAgo(daysAgo.toLong())
            put(
                "sales", "${PREFIX}sale-$date",
                MilkSale(litres = 12.0, pricePerLitre = 50.0, date = date, createdAt = now)
            )
        }

        batch.commit().await()
    }

    /** Deletes only the documents created by seed() (ids starting with "test-"). */
    suspend fun clear(
        uid: String,
        db: FirebaseFirestore = FirebaseFirestore.getInstance()
    ) {
        val user = db.collection("users").document(uid)
        val batch = db.batch()

        collections.forEach { name ->
            user.collection(name).get().await().documents
                .filter { it.id.startsWith(PREFIX) }
                .forEach { batch.delete(it.reference) }
        }
        // Reviewed advice for the test cows
        user.collection("reviewedAdvice").get().await().documents
            .filter { it.id.startsWith(PREFIX) }
            .forEach { batch.delete(it.reference) }

        batch.commit().await()
    }
}
