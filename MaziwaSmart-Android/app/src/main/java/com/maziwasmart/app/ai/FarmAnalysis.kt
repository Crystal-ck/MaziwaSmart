package com.maziwasmart.app.ai

import com.maziwasmart.app.data.Cow
import com.maziwasmart.app.data.Dates
import com.maziwasmart.app.data.FeedingRecord
import com.maziwasmart.app.data.MilkRecord
import com.maziwasmart.app.data.ModelFeatures
import com.maziwasmart.app.data.oneDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.abs

/**
 * Turns the farmer's Firestore records (cows, feedings, milk) into:
 *  - advice / recommendations (Advice tab)
 *  - record completeness and prediction accuracy (Reports tab)
 *  - DMI vs milk and supplement comparisons (Feeding Insights)
 *
 * Everything here is plain Kotlin with no Android or Firebase code,
 * so the screens just pass in the lists they already collect from FarmViewModel.
 */

/** Where the "Open records" button on an advice card should go. */
enum class AdviceTarget { FEEDING, MILK, COW }

data class Advice(
    /** Stable id, used as the Firestore document id in users/{uid}/reviewedAdvice */
    val id: String,
    val category: String,        // "Data quality", "Production" or "Feeding"
    val cowId: String,
    val cowName: String,
    val title: String,
    val message: String,
    val reason: String,
    val target: AdviceTarget
)

data class CowCompleteness(
    val cow: Cow,
    val totalDays: Int,
    val milkDays: Int,
    val feedingDays: Int,
    /** Days that have milk AND feeding with one THI range, i.e. usable by the model */
    val modelReadyDays: Int,
    val hasGeneticGroup: Boolean
) {
    val percent: Int
        get() = if (totalDays == 0) 0
        else ((milkDays + feedingDays) * 100) / (totalDays * 2)

    val isComplete: Boolean
        get() = hasGeneticGroup && percent >= 80
}

data class AccuracyPoint(
    val cowId: String,
    val cowName: String,
    val date: String,
    val predicted: Double,
    val actual: Double
) {
    val error: Double get() = abs(predicted - actual)
}

data class CowAccuracy(
    val cowName: String,
    val days: Int,
    val maeLitres: Double?,
    /** Why there is no accuracy for this cow (null when there is) */
    val problem: String?
)

data class AccuracyReport(
    val points: List<AccuracyPoint>,
    val perCow: List<CowAccuracy>
) {
    val maeLitres: Double? =
        if (points.isEmpty()) null else points.sumOf { it.error } / points.size

    /** Mean absolute percentage error, only over days with actual milk > 0 */
    val mapePercent: Double? = points.filter { it.actual > 0 }.let { usable ->
        if (usable.isEmpty()) null
        else usable.sumOf { it.error / it.actual } / usable.size * 100
    }
}

object FarmAnalysis {

    // Model training range (same numbers as assets/milk_yield_ridge.json).
    private const val DMI_MIN = 4.48
    private const val DMI_MAX = 14.82

    // ---------------------------------------------------------------- helpers

    /** Total litres per date for one cow (Morning + Evening added together). */
    fun dailyMilk(milk: List<MilkRecord>, cowId: String): Map<String, Double> =
        milk.filter { it.cowId == cowId }
            .groupBy { it.date }
            .mapValues { (_, list) -> list.sumOf { it.litres } }

    /** Feeding records per date for one cow. */
    fun dailyFeedings(
        feedings: List<FeedingRecord>,
        cowId: String
    ): Map<String, List<FeedingRecord>> =
        feedings.filter { it.cowId == cowId }.groupBy { it.date }

    /** The single THI range for a day, or null when it is missing or mixed. */
    private fun thiFor(day: List<FeedingRecord>): String? =
        day.map { it.thiRange }
            .filter { it.isNotBlank() }
            .distinct()
            .singleOrNull()
            ?.takeIf { it in ModelFeatures.thiRanges }

    private fun hasValidGroup(cow: Cow) = cow.geneticGroup in ModelFeatures.geneticGroups

    private fun daysBetween(from: String, to: String): Long =
        runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to))
        }.getOrDefault(0L)

    private fun weekKey(): String {
        val now = LocalDate.now()
        return "${now.get(IsoFields.WEEK_BASED_YEAR)}w${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
    }

    // ---------------------------------------------------------------- completeness

    fun completeness(
        cows: List<Cow>,
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>,
        days: List<String>
    ): List<CowCompleteness> {
        return cows.map { cow ->
            // Only count days since the cow's first record, so a cow added
            // 10 days ago isn't marked incomplete in the 30-day view.
            val allMilk = dailyMilk(milk, cow.id)
            val allFeed = dailyFeedings(feedings, cow.id)
            val firstDate = (allMilk.keys + allFeed.keys).minOrNull()
            val cowDays = if (firstDate == null) days else days.filter { it >= firstDate }
            val range = cowDays.toSet()

            val milkByDay = allMilk.filterKeys { it in range }
            val feedByDay = allFeed.filterKeys { it in range }
            val modelReady = feedByDay.count { (date, list) ->
                date in milkByDay && thiFor(list) != null && list.sumOf { it.dmiKg } > 0
            }
            CowCompleteness(
                cow = cow,
                totalDays = cowDays.size,
                milkDays = milkByDay.size,
                feedingDays = feedByDay.size,
                modelReadyDays = modelReady,
                hasGeneticGroup = hasValidGroup(cow)
            )
        }.sortedBy { it.percent }
    }

    // ---------------------------------------------------------------- accuracy

    /**
     * For every cow-day that has a valid genetic group, feeding records with one THI range,
     * DMI > 0 and milk records, compare the model's prediction with the litres actually recorded.
     */
    fun accuracy(
        cows: List<Cow>,
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>,
        days: List<String>,
        calculator: RidgeModelCalculator
    ): AccuracyReport {
        val range = days.toSet()
        val points = mutableListOf<AccuracyPoint>()
        val perCow = mutableListOf<CowAccuracy>()

        cows.forEach { cow ->
            if (!hasValidGroup(cow)) {
                perCow += CowAccuracy(cow.name, 0, null, "No genetic group set")
                return@forEach
            }
            val milkByDay = dailyMilk(milk, cow.id)
            val cowPoints = dailyFeedings(feedings, cow.id)
                .filterKeys { it in range && it in milkByDay }
                .mapNotNull { (date, list) ->
                    val thi = thiFor(list) ?: return@mapNotNull null
                    val dmi = list.sumOf { it.dmiKg }
                    if (dmi <= 0.0) return@mapNotNull null
                    val predicted = runCatching {
                        calculator.predict(dmi, thi, cow.geneticGroup)
                    }.getOrNull() ?: return@mapNotNull null
                    AccuracyPoint(cow.id, cow.name, date, predicted, milkByDay.getValue(date))
                }
                .sortedBy { it.date }

            points += cowPoints
            perCow += if (cowPoints.isEmpty()) {
                CowAccuracy(
                    cow.name, 0, null,
                    "Needs days with both milk and feeding (with THI) records"
                )
            } else {
                CowAccuracy(
                    cow.name,
                    cowPoints.size,
                    cowPoints.sumOf { it.error } / cowPoints.size,
                    null
                )
            }
        }
        return AccuracyReport(points.sortedBy { it.date }, perCow)
    }

    // ---------------------------------------------------------------- insights

    /** (daily DMI kg, daily litres) for each cow-day that has both. */
    fun dmiVsMilk(
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>,
        days: List<String>
    ): List<Pair<Double, Double>> {
        val range = days.toSet()
        return feedings.filter { it.date in range }
            .groupBy { it.cowId to it.date }
            .mapNotNull { (key, list) ->
                val litres = milk.filter { it.cowId == key.first && it.date == key.second }
                    .sumOf { it.litres }
                val dmi = list.sumOf { it.dmiKg }
                if (litres > 0 && dmi > 0) dmi to litres else null
            }
    }

    /** Average daily litres on days with a supplement vs days without. Empty if no data. */
    fun supplementComparison(
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>,
        days: List<String>
    ): List<Pair<String, Double>> {
        val range = days.toSet()
        val withSup = mutableListOf<Double>()
        val without = mutableListOf<Double>()

        feedings.filter { it.date in range }
            .groupBy { it.cowId to it.date }
            .forEach { (key, list) ->
                val litres = milk.filter { it.cowId == key.first && it.date == key.second }
                    .sumOf { it.litres }
                if (litres <= 0) return@forEach
                if (list.any { it.supplement.isNotBlank() && it.supplement != "None" }) {
                    withSup += litres
                } else {
                    without += litres
                }
            }

        val result = mutableListOf<Pair<String, Double>>()
        if (withSup.isNotEmpty()) result += "With supplement" to withSup.average()
        if (without.isNotEmpty()) result += "No supplement" to without.average()
        return result
    }

    // ---------------------------------------------------------------- recommendations

    fun recommendations(
        cows: List<Cow>,
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>,
        calculator: RidgeModelCalculator?
    ): List<Advice> {
        val today = Dates.today()
        val week = Dates.lastNDays(7)
        val weekSet = week.toSet()
        val prevWeekSet = (13 downTo 7).map { Dates.daysAgo(it.toLong()) }.toSet()
        val wk = weekKey()
        val out = mutableListOf<Advice>()

        // Herd average litres per recorded day this week (for comparing cows).
        val herdDaily = cows.mapNotNull { cow ->
            dailyMilk(milk, cow.id).filterKeys { it in weekSet }.values
                .takeIf { it.isNotEmpty() }?.average()?.let { cow.id to it }
        }.toMap()
        val herdAvg = if (herdDaily.size >= 2) herdDaily.values.average() else null

        cows.forEach { cow ->
            val lactating = cow.lactationStatus == "Lactating"
            val milkByDay = dailyMilk(milk, cow.id)
            val feedByDay = dailyFeedings(feedings, cow.id)
            val milkWeek = milkByDay.filterKeys { it in weekSet }
            val feedWeek = feedByDay.filterKeys { it in weekSet }

            fun add(
                key: String, category: String, title: String,
                message: String, reason: String, target: AdviceTarget
            ) {
                out += Advice(
                    id = "${cow.id}_$key",
                    category = category,
                    cowId = cow.id,
                    cowName = cow.name,
                    title = title,
                    message = message,
                    reason = reason,
                    target = target
                )
            }

            // 1. Genetic group (needed by the model)
            if (!hasValidGroup(cow)) {
                add(
                    "genetic-group", "Data quality",
                    "Set ${cow.name}'s genetic group",
                    "Open ${cow.name}'s profile and choose her genetic group (Local, HF50 … HF87.5).",
                    "The milk yield model cannot make a prediction without the genetic group.",
                    AdviceTarget.COW
                )
            }

            // 2. Nothing recorded recently
            val lastRecord = (milkByDay.keys + feedByDay.keys).maxOrNull()
            if (lastRecord != null) {
                val gap = daysBetween(lastRecord, today)
                if (gap >= 3) {
                    add(
                        "stale-$lastRecord", "Data quality",
                        "No records for $gap days",
                        "Nothing has been recorded for ${cow.name} since ${Dates.pretty(lastRecord)}. " +
                            "Add today's milk and feeding records.",
                        "Gaps make the reports and predictions less reliable.",
                        AdviceTarget.MILK
                    )
                }
            }

            // 3. Milk records this week
            if (lactating) {
                if (milkWeek.isEmpty()) {
                    add(
                        "no-milk-$wk", "Data quality",
                        "No milk recorded this week",
                        "Record ${cow.name}'s morning and evening milk every day.",
                        "There are no milk records for ${cow.name} in the last 7 days.",
                        AdviceTarget.MILK
                    )
                } else if (milkWeek.size < 5) {
                    add(
                        "milk-gaps-$wk", "Data quality",
                        "Milk missing on ${7 - milkWeek.size} days",
                        "Fill in the missing milk records for ${cow.name}.",
                        "Milk was recorded on only ${milkWeek.size} of the last 7 days.",
                        AdviceTarget.MILK
                    )
                }
            }

            // 4. Feeding records this week
            if (feedWeek.isEmpty()) {
                add(
                    "no-feed-$wk", "Data quality",
                    "No feeding recorded this week",
                    "Record what ${cow.name} eats each day (fresh weight and feed type).",
                    "Dry matter intake (DMI) is the main input of the milk yield model.",
                    AdviceTarget.FEEDING
                )
            } else {
                val milkNoFeed = milkWeek.keys.count { it !in feedWeek }
                if (milkNoFeed > 0) {
                    add(
                        "milk-no-feed-$wk", "Data quality",
                        "Feeding missing on $milkNoFeed milk days",
                        "Add feeding records for the days you recorded milk.",
                        "Milk and feeding on the same day are needed to compare intake with yield.",
                        AdviceTarget.FEEDING
                    )
                }
            }

            // 5. THI range on the latest feeding day
            val latestFeedDate = feedByDay.keys.maxOrNull()
            if (latestFeedDate != null && thiFor(feedByDay.getValue(latestFeedDate)) == null) {
                add(
                    "thi-$latestFeedDate", "Data quality",
                    "THI range missing or mixed",
                    "Set one THI range (T0, T1 or T2) for ${cow.name}'s feeding on " +
                        "${Dates.pretty(latestFeedDate)}.",
                    "The model needs exactly one THI range for the latest feeding day.",
                    AdviceTarget.FEEDING
                )
            }

            // 6. DMI outside the model's training range
            if (feedWeek.isNotEmpty()) {
                val avgDmi = feedWeek.values.map { day -> day.sumOf { it.dmiKg } }.average()
                val inRange = calculator?.isDmiWithinTrainingRange(avgDmi)
                    ?: (avgDmi in DMI_MIN..DMI_MAX)
                if (!inRange) {
                    val low = avgDmi < DMI_MIN
                    add(
                        "dmi-range-$wk", "Feeding",
                        if (low) "Dry matter intake looks low" else "Dry matter intake looks high",
                        if (low) "Check ${cow.name}'s feed amounts, or that every feeding was recorded."
                        else "Check the fresh weights and dry matter % entered for ${cow.name}.",
                        "Average DMI this week is ${avgDmi.oneDecimal()} kg/day; " +
                            "the model was trained on $DMI_MIN–$DMI_MAX kg/day.",
                        AdviceTarget.FEEDING
                    )
                }
            }

            if (!lactating) return@forEach

            // 7. Milk dropping compared with last week
            val prevWeek = milkByDay.filterKeys { it in prevWeekSet }
            if (milkWeek.size >= 3 && prevWeek.size >= 3) {
                val now = milkWeek.values.average()
                val before = prevWeek.values.average()
                if (before > 0 && now < before * 0.85) {
                    val drop = ((before - now) / before * 100).toInt()
                    add(
                        "drop-$wk", "Production",
                        "Milk down $drop% this week",
                        "Check ${cow.name}'s feed, water and health.",
                        "Average ${now.oneDecimal()} L/day this week vs ${before.oneDecimal()} L/day last week.",
                        AdviceTarget.MILK
                    )
                }
            }

            // 8. Well below the herd average
            val mine = herdDaily[cow.id]
            if (herdAvg != null && mine != null && mine < herdAvg * 0.7) {
                add(
                    "below-herd-$wk", "Production",
                    "Below the herd average",
                    "${cow.name} gives less milk than the other cows. Review her feeding and health.",
                    "${mine.oneDecimal()} L/day vs herd average ${herdAvg.oneDecimal()} L/day this week.",
                    AdviceTarget.FEEDING
                )
            }

            // 9. Actual milk well below what the model expects
            if (calculator != null && hasValidGroup(cow)) {
                val lastUsable = feedByDay.keys
                    .filter { it in milkByDay }
                    .sortedDescending()
                    .firstOrNull { date ->
                        val day = feedByDay.getValue(date)
                        thiFor(day) != null && day.sumOf { it.dmiKg } > 0
                    }
                if (lastUsable != null && daysBetween(lastUsable, today) <= 7) {
                    val day = feedByDay.getValue(lastUsable)
                    val predicted = runCatching {
                        calculator.predict(day.sumOf { it.dmiKg }, thiFor(day)!!, cow.geneticGroup)
                    }.getOrNull()
                    val actual = milkByDay.getValue(lastUsable)
                    if (predicted != null && predicted - actual > 1.5 && actual < predicted * 0.8) {
                        add(
                            "below-model-$lastUsable", "Production",
                            "Less milk than expected",
                            "${cow.name} gave ${actual.oneDecimal()} L on ${Dates.pretty(lastUsable)}; " +
                                "the model expected about ${predicted.oneDecimal()} L.",
                            "Based on that day's DMI, THI range and genetic group. " +
                                "Check for illness, missed milking or recording errors.",
                            AdviceTarget.MILK
                        )
                    }
                }
            }
        }

        val order = listOf("Data quality", "Production", "Feeding")
        return out.sortedWith(compareBy({ order.indexOf(it.category) }, { it.cowName }))
    }
}
