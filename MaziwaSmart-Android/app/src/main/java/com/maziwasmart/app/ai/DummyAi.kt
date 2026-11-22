
package com.maziwasmart.app.ai

import android.content.Context
import com.maziwasmart.app.data.Cow
import com.maziwasmart.app.data.Dates
import com.maziwasmart.app.data.FeedingRecord
import com.maziwasmart.app.data.MilkRecord
import java.time.LocalTime

data class Prediction(
    val cowName: String,
    val predictedLitres: Double,
    val lastActualLitres: Double?,
    val generatedAt: String,
    val averageDmiKg: Double,
    val thiRange: String,
    val geneticGroup: String,
    val actualHistory: List<Double>,
    val dmiWithinTrainingRange: Boolean
)

interface MilkYieldPredictor {
    fun predict(
        cow: Cow,
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>
    ): Prediction
}

class RidgeMilkYieldPredictor(context: Context) : MilkYieldPredictor {

    private val calculator = RidgeModelCalculator(context)

    override fun predict(
        cow: Cow,
        feedings: List<FeedingRecord>,
        milk: List<MilkRecord>
    ): Prediction {

        require(
            cow.geneticGroup in listOf(
                "Local", "HF50", "HF62.5", "HF75", "HF87.5"
            )
        ) {
            "Please set this cow's Genetic Group first."
        }

        val cowFeedings = feedings.filter {
            it.cowId == cow.id
        }

        val latestDate = cowFeedings.maxOfOrNull {
            it.date
        } ?: error("No feeding records found for this cow.")

        val latestDay = cowFeedings.filter {
            it.date == latestDate
        }

        val thiValues = latestDay
            .map { it.thiRange }
            .filter { it.isNotBlank() }
            .distinct()

        require(thiValues.size == 1) {
            "Please record one consistent THI Range for the latest feeding day."
        }

        val thiRange = thiValues.first()

        val dailyDmi = latestDay.sumOf {
            it.dmiKg
        }

        require(dailyDmi > 0.0) {
            "The latest day's DMI must be greater than zero."
        }

        val predicted = calculator.predict(
            dmi = dailyDmi,
            thiRange = thiRange,
            geneticGroup = cow.geneticGroup
        )

        val actualHistory = Dates.lastNDays(7).map { day ->
            milk.filter {
                it.cowId == cow.id && it.date == day
            }.sumOf { it.litres }
        }

        val actualRecords = milk.filter {
            it.cowId == cow.id
        }.groupBy {
            it.date
        }

        val lastActual = actualRecords
            .maxByOrNull { it.key }
            ?.value
            ?.sumOf { it.litres }

        val now = LocalTime.now()

        return Prediction(
            cowName = cow.name,
            predictedLitres = predicted,
            lastActualLitres = lastActual,
            generatedAt = "Today, %d:%02d".format(
                now.hour,
                now.minute
            ),
            averageDmiKg = dailyDmi,
            thiRange = thiRange,
            geneticGroup = cow.geneticGroup,
            actualHistory = actualHistory,
            dmiWithinTrainingRange =
                calculator.isDmiWithinTrainingRange(dailyDmi)
        )
    }
}

// Retained for the existing Insights and Recommendations screens.
// Empty datasets are intentional until real records are connected.
object DummyAi {

    val dmiVsMilk = emptyList<Pair<Double, Double>>()

    data class Insight(
        val title: String,
        val message: String
    )

    val insights = listOf(
        Insight(
            title = "Record daily DMI",
            message = "Consistent feeding records help monitor the relationship between intake and milk yield."
        ),
        Insight(
            title = "Monitor THI",
            message = "Record the daily THI Range required by the trained model."
        ),
        Insight(
            title = "Set Genetic Group",
            message = "Each cow needs a valid Genetic Group before generating a prediction."
        )
    )

    val supplementComparison = emptyList<Pair<String, Double>>()

    data class Recommendation(
        val id: Int,
        val category: String,
        val cowName: String,
        val title: String,
        val message: String,
        val reason: String
    )

    val recommendations = emptyList<Recommendation>()
}
