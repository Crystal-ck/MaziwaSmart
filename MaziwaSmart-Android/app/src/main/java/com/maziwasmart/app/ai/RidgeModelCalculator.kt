
package com.maziwasmart.app.ai

import android.content.Context
import org.json.JSONObject

class RidgeModelCalculator(context: Context) {

    private val model: JSONObject

    init {
        val json = context.assets
            .open("milk_yield_ridge.json")
            .bufferedReader()
            .use { it.readText() }

        model = JSONObject(json)
    }

    fun predict(
        dmi: Double,
        thiRange: String,
        geneticGroup: String
    ): Double {

        require(dmi >= 0.0) { "DMI cannot be negative" }

        require(thiRange in listOf("T0", "T1", "T2")) {
            "Invalid THI Range"
        }

        require(
            geneticGroup in listOf(
                "Local", "HF50", "HF62.5", "HF75", "HF87.5"
            )
        ) {
            "Invalid Genetic Group"
        }

        val features = model.getJSONArray("features")
        val coefficients = model.getJSONArray("coefficients")

        val scaledDmi =
            (dmi - model.getDouble("dmi_mean")) /
                    model.getDouble("dmi_scale")

        var result = model.getDouble("intercept")

        for (i in 0 until features.length()) {

            val feature = features.getString(i)

            val value = when {
                feature == "DMI (kg)" -> scaledDmi

                feature == "THI Range_$thiRange" -> 1.0

                feature.startsWith("THI Range_") -> 0.0

                feature == "Genetic Group_$geneticGroup" -> 1.0

                feature.startsWith("Genetic Group_") -> 0.0

                else -> error("Unknown model feature: $feature")
            }

            result += value * coefficients.getDouble(i)
        }

        return result
    }

    fun isDmiWithinTrainingRange(dmi: Double): Boolean {
        return dmi >= model.getDouble("dmi_min") &&
                dmi <= model.getDouble("dmi_max")
    }
}
