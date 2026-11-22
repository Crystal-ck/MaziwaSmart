package com.maziwasmart.app.ui.reports

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.maziwasmart.app.ai.AccuracyReport
import com.maziwasmart.app.ai.CowCompleteness
import com.maziwasmart.app.ai.FarmAnalysis
import com.maziwasmart.app.ai.RidgeModelCalculator
import com.maziwasmart.app.data.Cow
import com.maziwasmart.app.data.Dates
import com.maziwasmart.app.data.Expense
import com.maziwasmart.app.data.FeedingRecord
import com.maziwasmart.app.data.MilkRecord
import com.maziwasmart.app.data.MilkSale
import com.maziwasmart.app.data.clean
import com.maziwasmart.app.data.ksh
import com.maziwasmart.app.data.oneDecimal
import com.maziwasmart.app.ui.FarmViewModel
import com.maziwasmart.app.ui.components.AppButton
import com.maziwasmart.app.ui.components.AppCard
import com.maziwasmart.app.ui.components.BarChart
import com.maziwasmart.app.ui.components.ChipRow
import com.maziwasmart.app.ui.components.EmptyText
import com.maziwasmart.app.ui.components.HeaderIconButton
import com.maziwasmart.app.ui.components.LineChart
import com.maziwasmart.app.ui.components.Pill
import com.maziwasmart.app.ui.components.ProgressBar
import com.maziwasmart.app.ui.components.ScreenWithHeader
import com.maziwasmart.app.ui.components.SectionTitle
import com.maziwasmart.app.ui.components.Segmented
import com.maziwasmart.app.ui.components.StatCard
import com.maziwasmart.app.ui.components.toast
import com.maziwasmart.app.ui.navigation.Routes
import com.maziwasmart.app.ui.theme.Brown
import com.maziwasmart.app.ui.theme.Green
import com.maziwasmart.app.ui.theme.GreenDeep
import com.maziwasmart.app.ui.theme.GreenSoft
import com.maziwasmart.app.ui.theme.Muted
import com.maziwasmart.app.ui.theme.Orange
import com.maziwasmart.app.ui.theme.Yellow

@Composable
fun ReportsScreen(
    vm: FarmViewModel,
    onNavigate: (String) -> Unit
) {

    val cows by vm.cows.collectAsState()
    val milk by vm.milk.collectAsState()
    val feedings by vm.feedings.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val sales by vm.sales.collectAsState()

    val context = LocalContext.current

    val calculator = remember(context) {
        runCatching { RidgeModelCalculator(context) }.getOrNull()
    }

    var period by rememberSaveable {
        mutableStateOf("Month")
    }

    val days = if (period == "Week") 7 else 30

    val dayList = remember(period) { Dates.lastNDays(days) }
    val range = dayList.toSet()

    val periodMilk = milk.filter {
        it.date in range
    }

    val periodFeedings = feedings.filter {
        it.date in range
    }

    val totalMilk = periodMilk.sumOf {
        it.litres
    }

    val cowDays = periodMilk
        .map { it.cowId to it.date }
        .distinct()
        .size

    val avgPerCowDay = if (cowDays == 0)
        0.0
    else
        totalMilk / cowDays

    // Average litres per day for each cow (only cows with milk in this period)

    val byCow = cows.mapNotNull { cow ->

        val records = periodMilk.filter {
            it.cowId == cow.id
        }

        val recordedDays = records
            .map { it.date }
            .distinct()
            .size

        if (recordedDays == 0) null
        else cow.name to records.sumOf { it.litres } / recordedDays

    }.sortedByDescending {
        it.second
    }.take(6)

    // Feeding statistics

    val dmiDays = periodFeedings
        .map { it.cowId to it.date }
        .distinct()
        .size

    val avgDmi = if (dmiDays == 0)
        0.0
    else
        periodFeedings.sumOf { it.dmiKg } / dmiDays

    // Financial statistics

    val periodExpenses = expenses.filter { it.date in range }
    val periodSales = sales.filter { it.date in range }

    val spent = periodExpenses.sumOf { it.amount }
    val earned = periodSales.sumOf { it.total }

    // Record completeness + prediction accuracy (from FarmAnalysis)

    val completeness = remember(cows, feedings, milk, period) {
        FarmAnalysis.completeness(cows, feedings, milk, dayList)
    }

    val accuracy = remember(cows, feedings, milk, period, calculator) {
        calculator?.let {
            FarmAnalysis.accuracy(cows, feedings, milk, dayList, it)
        }
    }

    fun share() = shareCsv(
        context,
        period,
        buildCsv(
            dayList, cows, periodMilk, periodFeedings,
            periodExpenses, periodSales, completeness, accuracy
        )
    )

    ScreenWithHeader(
        title = "Reports",
        subtitle = if (period == "Week")
            "Last 7 days"
        else
            "Last 30 days",

        actions = {
            HeaderIconButton(
                Icons.Filled.Download,
                "Download"
            ) {
                share()
            }
        }
    ) {

        Segmented(
            listOf("Week", "Month"),
            period
        ) {
            period = it
        }

        // Milk statistics

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            StatCard(
                "${totalMilk.clean()} L",
                "Total milk",
                Green,
                Color.White,
                Modifier.weight(1f)
            )

            StatCard(
                "${avgPerCowDay.oneDecimal()} L",
                "Avg / cow / day",
                Yellow,
                Brown,
                Modifier.weight(1f)
            )
        }

        // Yield by cow

        AppCard {

            SectionTitle("Yield by cow (avg L/day)")

            if (byCow.isEmpty()) {

                EmptyText("No milk records in this period")

            } else {

                BarChart(
                    byCow.map { it.second },
                    byCow.map { it.first },
                    colors = listOf(
                        GreenDeep,
                        Yellow,
                        Orange,
                        Brown,
                        Green,
                        GreenSoft
                    )
                )
            }
        }

        // Record completeness

        CompletenessCard(completeness)

        // Prediction accuracy

        AccuracyCard(accuracy)

        // Dry matter intake

        AppCard(
            background = GreenSoft
        ) {

            Text(
                "Average dry matter intake",
                fontSize = 11.sp,
                color = Muted
            )

            Text(
                "${avgDmi.oneDecimal()} kg / cow / day",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenDeep
            )

            Text(
                "${periodFeedings.size} feeding records",
                fontSize = 11.sp,
                color = Muted
            )
        }

        // Financial summary

        AppCard {

            SectionTitle(
                "Money",
                "Details"
            ) {
                onNavigate(Routes.FINANCE)
            }

            MoneyRow("Milk sales", earned.ksh(), GreenDeep)
            MoneyRow("Expenses", spent.ksh(), Brown)
            MoneyRow("Estimated profit", (earned - spent).ksh(), Brown, bold = true)
        }

        // Export

        AppButton(
            "Share report (CSV)",
            { share() },
            Modifier.fillMaxWidth(),
            background = Brown,
            textColor = Yellow,
            small = true
        )
    }
}

@Composable
private fun MoneyRow(label: String, value: String, valueColor: Color, bold: Boolean = false) {
    Row {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = Brown,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor
        )
    }
}

/* ---------------- Record completeness ---------------- */

@Composable
private fun CompletenessCard(items: List<CowCompleteness>) {

    AppCard {

        SectionTitle("Record completeness")

        if (items.isEmpty()) {
            EmptyText("No cows yet")
            return@AppCard
        }

        val incomplete = items.count { !it.isComplete }

        Text(
            if (incomplete == 0) "All cows have complete records"
            else "$incomplete of ${items.size} cows have incomplete records",
            fontSize = 11.sp,
            color = Muted
        )

        items.forEach { c ->

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(Modifier.weight(1f)) {

                    Text(
                        c.cow.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brown
                    )

                    Text(
                        "Milk ${c.milkDays}/${c.totalDays} days · " +
                            "Feeding ${c.feedingDays}/${c.totalDays} days" +
                            if (!c.hasGeneticGroup) " · no genetic group" else "",
                        fontSize = 11.sp,
                        color = Muted
                    )
                }

                if (c.isComplete) {
                    Pill("Complete", Green, Color.White)
                } else {
                    Pill("Incomplete", Orange, Color.White)
                }
            }

            ProgressBar(
                c.percent / 100f,
                if (c.isComplete) Green else Orange
            )
        }
    }
}

/* ---------------- Prediction accuracy ---------------- */

@Composable
private fun AccuracyCard(report: AccuracyReport?) {

    AppCard {

        SectionTitle("Prediction accuracy")

        if (report == null) {
            EmptyText("The prediction model could not be loaded.")
            return@AppCard
        }

        if (report.points.isEmpty()) {
            EmptyText(
                "Not enough data yet. Accuracy needs days with milk records and " +
                    "feeding records (with a THI range) for cows that have a genetic group."
            )
        } else {

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                StatCard(
                    "${report.maeLitres?.oneDecimal()} L",
                    "Avg error / day",
                    GreenSoft,
                    GreenDeep,
                    Modifier.weight(1f)
                )

                StatCard(
                    report.mapePercent?.let { "${(100 - it).coerceAtLeast(0.0).toInt()}%" } ?: "-",
                    "Accuracy",
                    Yellow,
                    Brown,
                    Modifier.weight(1f)
                )
            }

            // Actual vs predicted chart for one cow at a time
            val cowNames = report.points.map { it.cowName }.distinct()

            var selected by rememberSaveable { mutableStateOf("") }
            val cowName = selected.takeIf { it in cowNames } ?: cowNames.first()

            if (cowNames.size > 1) {
                ChipRow(cowNames, cowName) { selected = it }
            }

            val cowPoints = report.points.filter { it.cowName == cowName }

            if (cowPoints.size >= 2) {

                LineChart(
                    values = cowPoints.map { it.actual },
                    labels = listOf(
                        Dates.pretty(cowPoints.first().date),
                        Dates.pretty(cowPoints.last().date)
                    ),
                    secondValues = cowPoints.map { it.predicted }
                )

                Text(
                    "Solid line: actual litres · Dashed line: predicted",
                    fontSize = 10.sp,
                    color = Muted
                )
            }
        }

        // One line per cow, including cows without enough data
        report.perCow.forEach { c ->

            Row(Modifier.fillMaxWidth()) {

                Text(
                    c.cowName,
                    fontSize = 12.sp,
                    color = Brown,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    if (c.maeLitres != null)
                        "±${c.maeLitres.oneDecimal()} L over ${c.days} days"
                    else
                        c.problem ?: "Not enough data",
                    fontSize = 11.sp,
                    color = if (c.maeLitres != null) GreenDeep else Orange
                )
            }
        }
    }
}

/* ---------------- CSV export ---------------- */

private fun csv(vararg cells: Any?): String =
    cells.joinToString(",") { cell ->
        val text = cell?.toString() ?: ""
        if (text.contains(',') || text.contains('"') || text.contains('\n'))
            "\"" + text.replace("\"", "\"\"") + "\""
        else text
    }

private fun buildCsv(
    days: List<String>,
    cows: List<Cow>,
    milk: List<MilkRecord>,
    feedings: List<FeedingRecord>,
    expenses: List<Expense>,
    sales: List<MilkSale>,
    completeness: List<CowCompleteness>,
    accuracy: AccuracyReport?
): String = buildString {

    val cowName = cows.associate { it.id to it.name }

    appendLine("MaziwaSmart report ${days.first()} to ${days.last()}")
    appendLine()

    appendLine("MILK")
    appendLine(csv("Date", "Cow", "Session", "Litres"))
    milk.sortedBy { it.date }.forEach {
        appendLine(csv(it.date, cowName[it.cowId] ?: it.cowName, it.session, it.litres))
    }
    appendLine()

    appendLine("FEEDING")
    appendLine(csv("Date", "Cow", "Session", "Feed", "Fresh kg", "DM %", "DMI kg", "Supplement", "THI"))
    feedings.sortedBy { it.date }.forEach {
        appendLine(
            csv(
                it.date, cowName[it.cowId] ?: it.cowName, it.session, it.feedType,
                it.freshWeightKg, it.dryMatterPercent, it.dmiKg.oneDecimal(),
                it.supplement, it.thiRange
            )
        )
    }
    appendLine()

    appendLine("RECORD COMPLETENESS")
    appendLine(csv("Cow", "Milk days", "Feeding days", "Days in period", "Genetic group set", "Status"))
    completeness.forEach {
        appendLine(
            csv(
                it.cow.name, it.milkDays, it.feedingDays, it.totalDays,
                if (it.hasGeneticGroup) "Yes" else "No",
                if (it.isComplete) "Complete" else "Incomplete"
            )
        )
    }
    appendLine()

    if (accuracy != null) {
        appendLine("PREDICTION ACCURACY")
        appendLine(csv("Date", "Cow", "Predicted L", "Actual L", "Error L"))
        accuracy.points.forEach {
            appendLine(
                csv(
                    it.date, it.cowName, it.predicted.oneDecimal(),
                    it.actual.oneDecimal(), it.error.oneDecimal()
                )
            )
        }
        accuracy.maeLitres?.let { appendLine(csv("Average error (L)", it.oneDecimal())) }
        appendLine()
    }

    appendLine("EXPENSES")
    appendLine(csv("Date", "Category", "Description", "Amount KSh"))
    expenses.sortedBy { it.date }.forEach {
        appendLine(csv(it.date, it.category, it.description, it.amount))
    }
    appendLine()

    appendLine("MILK SALES")
    appendLine(csv("Date", "Litres", "Price per litre", "Total KSh"))
    sales.sortedBy { it.date }.forEach {
        appendLine(csv(it.date, it.litres, it.pricePerLitre, it.total))
    }
}

/** Opens the Android share sheet (WhatsApp, Gmail, Drive, …) with the CSV text. */
private fun shareCsv(context: Context, period: String, csvText: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "MaziwaSmart report (${period.lowercase()})")
        putExtra(Intent.EXTRA_TEXT, csvText)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share report"))
    } catch (e: Exception) {
        context.toast("No app available to share the report")
    }
}
