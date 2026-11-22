
package com.maziwasmart.app.data

import com.google.firebase.firestore.Exclude

data class UserProfile(
    var name: String = "",
    var phone: String = "",
    var email: String = "",
    var farmName: String = "",
    var location: String = "",
    var herdSize: String = ""
)

data class Cow(
    @get:Exclude var id: String = "",
    var name: String = "",
    var tagId: String = "",
    var breed: String = "",
    var dateOfBirth: String = "",
    var lactationStatus: String = "Lactating",
    var notes: String = "",
    var createdAt: Long = 0L,
    var geneticGroup: String = ""
)

data class FeedingRecord(
    @get:Exclude var id: String = "",
    var cowId: String = "",
    var cowName: String = "",
    var date: String = "",
    var session: String = "",
    var feedType: String = "",
    var freshWeightKg: Double = 0.0,
    var dryMatterPercent: Double = 0.0,
    var dmiKg: Double = 0.0,
    var supplement: String = "None",
    var supplementGrams: Double = 0.0,
    var timesPerDay: Int = 1,
    var createdAt: Long = 0L,
    var thiRange: String = ""
)

data class MilkRecord(
    @get:Exclude var id: String = "",
    var cowId: String = "",
    var cowName: String = "",
    var date: String = "",
    var session: String = "",
    var litres: Double = 0.0,
    var createdAt: Long = 0L
)

data class Expense(
    @get:Exclude var id: String = "",
    var category: String = "",
    var description: String = "",
    var amount: Double = 0.0,
    var date: String = "",
    var createdAt: Long = 0L
)

data class MilkSale(
    @get:Exclude var id: String = "",
    var litres: Double = 0.0,
    var pricePerLitre: Double = 0.0,
    var date: String = "",
    var createdAt: Long = 0L
) {
    @get:Exclude
    val total: Double get() = litres * pricePerLitre
}
