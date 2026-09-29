package com.example.myfinance

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class Account(
    val id: Long, val name: String, val type: String,
    val balance: Double = 0.0, val rate: Double = 0.0,
    val capitalization: Boolean = false
)

data class Operation(
    val id: Long, val amount: Double, val category: String,
    val comment: String, val income: Boolean, val accountId: Long
)

data class ForecastItem(
    val id: Long,
    val name: String,
    val amount: Double,
    val income: Boolean,
    val date: Long,
    val recurrence: String = "Разово",
    val accountId: Long = 0L
)

private const val PREFS_NAME = "my_finance_data"
private const val OPERATIONS_KEY = "operations"
private const val ACCOUNTS_KEY = "accounts"
private const val FORECAST_KEY = "forecast"

private fun saveData(
    context: Context,
    accounts: List<Account>,
    operations: List<Operation>,
    forecast: List<ForecastItem> = emptyList()
) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val a = JSONArray()
    accounts.forEach { x -> a.put(JSONObject().apply {
        put("id", x.id); put("name", x.name); put("type", x.type)
        put("balance", x.balance); put("rate", x.rate); put("capitalization", x.capitalization)
    }) }
    val o = JSONArray()
    operations.forEach { x -> o.put(JSONObject().apply {
        put("id", x.id); put("amount", x.amount); put("category", x.category)
        put("comment", x.comment); put("income", x.income); put("accountId", x.accountId)
    }) }
    val f = JSONArray()
    forecast.forEach { x -> f.put(JSONObject().apply {
        put("id", x.id); put("name", x.name); put("amount", x.amount)
        put("income", x.income); put("date", x.date); put("recurrence", x.recurrence)
        put("accountId", x.accountId)
    }) }
    prefs.edit().putString(ACCOUNTS_KEY, a.toString())
        .putString(OPERATIONS_KEY, o.toString())
        .putString(FORECAST_KEY, f.toString()).apply()
}

private fun loadAccounts(context: Context): List<Account> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(ACCOUNTS_KEY, null) ?: return listOf(
        Account(1, "Карта", "card"), Account(2, "Наличные", "cash"), Account(3, "Сбережения", "savings")
    )
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i -> val x = a.getJSONObject(i)
            Account(x.getLong("id"), x.getString("name"), x.getString("type"),
                x.optDouble("balance", 0.0), x.optDouble("rate", 0.0), x.optBoolean("capitalization", false))
        }
    } catch (_: Exception) { listOf(Account(1, "Карта", "card")) }
}

private fun loadOperations(context: Context, accounts: List<Account>): List<Operation> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(OPERATIONS_KEY, null) ?: return emptyList()
    val defaultId = accounts.firstOrNull()?.id ?: 1L
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i -> val x = a.getJSONObject(i)
            Operation(x.getLong("id"), x.getDouble("amount"), x.getString("category"),
                x.getString("comment"), x.getBoolean("income"), x.optLong("accountId", defaultId))
        }
    } catch (_: Exception) { emptyList() }
}

private fun loadForecast(context: Context): List<ForecastItem> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(FORECAST_KEY, null) ?: return emptyList()
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i ->
            val x = a.getJSONObject(i)
            ForecastItem(
                x.getLong("id"), x.getString("name"), x.getDouble("amount"),
                x.getBoolean("income"), x.getLong("date"),
                x.optString("recurrence", "Разово"), x.optLong("accountId", 0L)
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun balances(accounts: List<Account>, operations: List<Operation>) =
    accounts.map { a -> a.copy(balance = operations.filter { it.accountId == a.id }
        .sumOf { if (it.income) it.amount else -it.amount }) }

private fun money(v: Double) = String.format(Locale.getDefault(), "%.2f ₽", v)
private val IncomeColor = Color(0xFF2E7D32)
private val IncomeContainer = Color(0xFFE8F5E9)
private val ExpenseColor = Color(0xFFC62828)
private val ExpenseContainer = Color(0xFFFFEBEE)

private fun amountColor(income: Boolean) = if (income) IncomeColor else ExpenseColor
private fun amountContainer(income: Boolean) = if (income) IncomeContainer else ExpenseContainer


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FinanceApp(this) } }
    }
}

@Composable
fun FinanceApp(context: Context) {
    var accounts by remember { mutableStateOf(loadAccounts(context)) }
    var operations by remember { mutableStateOf(loadOperations(context, accounts)) }
    var forecast by remember { mutableStateOf(loadForecast(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var showDeposit by remember { mutableStateOf(false) }

    val actualAccounts = balances(accounts, operations)
    val total = actualAccounts.sumOf { it.balance }

    fun persist() = saveData(context, accounts, operations, forecast)

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(when (tab) {
                    0 -> "Мои финансы"
                    1 -> "Операции"
                    2 -> "Календарь"
                    3 -> "Статистика"
                    else -> "Ещё"
                })
            })
        },
        bottomBar = {
            NavigationBar {
                listOf("Главная", "Операции", "Календарь", "Статистика", "Ещё").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(
                            when(i) {
                                0 -> Icons.Default.Home
                                1 -> Icons.Default.List
                                2 -> Icons.Default.DateRange
                                3 -> Icons.Default.PieChart
                                else -> Icons.Default.Settings
                            }, label
                        ) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0 || tab == 1) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, "Добавить")
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(actualAccounts, operations, total,
                onTransfer = { showTransfer = true }, onDeposit = { showDeposit = true },
                modifier = Modifier.padding(padding))
            1 -> OperationsScreen(actualAccounts, operations, Modifier.padding(padding))
            2 -> CalendarScreen(Modifier.padding(padding))
            3 -> StatisticsScreen(actualAccounts, operations, Modifier.padding(padding))
            else -> MoreScreen(forecast, actualAccounts, { forecast = it; persist() }, Modifier.padding(padding))
        }
    }

    if (showAdd) AddOperationDialog(actualAccounts, { showAdd = false }) { amount, income, category, comment, accountId ->
        operations = operations + Operation(System.currentTimeMillis(), amount, category, comment, income, accountId)
        persist(); showAdd = false
    }

    if (showTransfer) TransferDialog(actualAccounts, { showTransfer = false }) { from, to, amount ->
        val now = System.currentTimeMillis()
        operations = operations +
                Operation(now, amount, "Перевод", "На: ${actualAccounts.first { it.id == to }.name}", false, from) +
                Operation(now + 1, amount, "Перевод", "С: ${actualAccounts.first { it.id == from }.name}", true, to)
        persist(); showTransfer = false
    }

    if (showDeposit) NewDepositDialog({ showDeposit = false }) { name, amount, rate, cap ->
        val id = (accounts.maxOfOrNull { it.id } ?: 0) + 1
        accounts = accounts + Account(id, name, "deposit", 0.0, rate, cap)
        operations = operations + Operation(System.currentTimeMillis(), amount, "Взнос на вклад", "Открытие вклада", true, id)
        persist(); showDeposit = false
    }
}

@Composable
fun HomeScreen(accounts: List<Account>, operations: List<Operation>, total: Double,
               onTransfer: () -> Unit, onDeposit: () -> Unit, modifier: Modifier = Modifier) {
    val income = operations.filter { it.income }.sumOf { it.amount }
    val expense = operations.filter { !it.income }.sumOf { it.amount }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Общий баланс", style = MaterialTheme.typography.labelLarge)
                    Text(money(total), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = IncomeContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Доходы", color = IncomeColor, style = MaterialTheme.typography.titleMedium)
                    Text("+${money(income)}", color = IncomeColor, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = ExpenseContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Расходы", color = ExpenseColor, style = MaterialTheme.typography.titleMedium)
                    Text("-${money(expense)}", color = ExpenseColor, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        item { Text("Мои счета", style = MaterialTheme.typography.titleLarge) }
        items(accounts) { a ->
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = {
                        if (a.type == "deposit") Text("Вклад • ${a.rate}% годовых") else Text(a.typeName())
                    },
                    trailingContent = { Text(money(a.balance)) }
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTransfer) { Text("Перевод") }
                Button(onClick = onDeposit) { Text("Новый вклад") }
            }
        }
        item { Text("Последние операции", style = MaterialTheme.typography.titleLarge) }
        items(operations.sortedByDescending { it.id }.take(5)) { op ->
            ListItem(
                headlineContent = { Text(op.category) },
                supportingContent = { Text(op.comment) },
                trailingContent = { Text((if(op.income) "+" else "-") + money(op.amount)) }
            )
        }
    }
}

private fun Account.typeName() = when(type) {
    "card" -> "Банковская карта"
    "cash" -> "Наличные"
    "savings" -> "Сбережения"
    else -> "Счёт"
}

@Composable
fun OperationsScreen(accounts: List<Account>, operations: List<Operation>, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(operations.sortedByDescending { it.id }) { op ->
            val account = accounts.firstOrNull { it.id == op.accountId }?.name ?: "Счёт"
            ListItem(
                headlineContent = { Text(op.category) },
                supportingContent = { Text("$account • ${op.comment}") },
                trailingContent = { Text((if(op.income) "+" else "-") + money(op.amount)) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun CalendarScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Календарь финансов", style = MaterialTheme.typography.headlineSmall)
        Text("Здесь будут отображаться обязательные платежи, доходы, расходы и начисления по вкладам.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Обязательные платежи", style = MaterialTheme.typography.titleMedium)
                Text("Функция напоминаний подключается следующим этапом.")
            }
        }
    }
}

@Composable
fun StatisticsScreen(
    operations: List<Operation>,
    accounts: List<Account>,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }

    fun startOfMonth(time: Long): Long =
        java.util.Calendar.getInstance().apply {
            timeInMillis = time
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun endOfMonth(time: Long): Long =
        java.util.Calendar.getInstance().apply {
            timeInMillis = time
            set(java.util.Calendar.DAY_OF_MONTH, getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis

    val currentMonthStart = startOfMonth(now)
    val currentMonthEnd = endOfMonth(now)
    val previousMonthAnchor = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        add(java.util.Calendar.MONTH, -1)
    }.timeInMillis
    val previousMonthStart = startOfMonth(previousMonthAnchor)
    val previousMonthEnd = endOfMonth(previousMonthAnchor)
    val yearStart = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
        set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yearEnd = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.MONTH, java.util.Calendar.DECEMBER)
        set(java.util.Calendar.DAY_OF_MONTH, 31)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59)
        set(java.util.Calendar.MILLISECOND, 999)
    }.timeInMillis

    var selectedPeriod by remember { mutableStateOf("Текущий месяц") }
    var customStart by remember { mutableStateOf(currentMonthStart) }
    var customEnd by remember { mutableStateOf(currentMonthEnd) }

    val selectedStart: Long
    val selectedEnd: Long

    when (selectedPeriod) {
        "Предыдущий месяц" -> {
            selectedStart = previousMonthStart
            selectedEnd = previousMonthEnd
        }
        "Год" -> {
            selectedStart = yearStart
            selectedEnd = yearEnd
        }
        "Произвольные даты" -> {
            selectedStart = minOf(customStart, customEnd)
            selectedEnd = maxOf(customStart, customEnd)
        }
        else -> {
            selectedStart = currentMonthStart
            selectedEnd = currentMonthEnd
        }
    }

    val periodOperations = operations.filter { it.id in selectedStart..selectedEnd }
    val expenses = periodOperations.filter { it.type == "expense" }
    val mandatory = expenses.filter { it.mandatory }
    val nonMandatory = expenses.filter { !it.mandatory }

    val total = expenses.sumOf { it.amount }
    val mandatoryTotal = mandatory.sumOf { it.amount }
    val nonMandatoryTotal = nonMandatory.sumOf { it.amount }

    val millisPerDay = 24.0 * 60.0 * 60.0 * 1000.0
    val days = maxOf(1.0, ((selectedEnd - selectedStart + 1) / millisPerDay))
    val averageDay = total / days
    val averageWeek = averageDay * 7.0
    val averageMonth = averageDay * 30.4375

    val mandatoryDay = mandatoryTotal / days
    val nonMandatoryDay = nonMandatoryTotal / days

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Статистика", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(12.dp))
        Text("Период", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Текущий месяц", "Предыдущий месяц", "Год").forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { selectedPeriod = period },
                    label = { Text(period.replace("Предыдущий месяц", "Прошлый").replace("Текущий месяц", "Этот")) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        FilterChip(
            selected = selectedPeriod == "Произвольные даты",
            onClick = { selectedPeriod = "Произвольные даты" },
            label = { Text("Произвольные даты") }
        )

        if (selectedPeriod == "Произвольные даты") {
            Spacer(Modifier.height(10.dp))
            DateRangePicker(
                startMillis = customStart,
                endMillis = customEnd,
                onStartChange = { customStart = it },
                onEndChange = { customEnd = it }
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Расходы за выбранный период: ${"%.2f".format(total)}",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(14.dp))
        Text("Средние расходы", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        AverageExpenseCard("День", averageDay, "средний расход за календарный день")
        AverageExpenseCard("Неделя", averageWeek, "пересчёт на 7 дней")
        AverageExpenseCard("Месяц", averageMonth, "пересчёт на средний месяц")

        Spacer(Modifier.height(16.dp))
        Text("Обязательные / необязательные", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        ExpenseComparisonRow(
            "Обязательные",
            mandatoryDay,
            mandatoryDay * 7.0,
            mandatoryDay * 30.4375
        )
        ExpenseComparisonRow(
            "Необязательные",
            nonMandatoryDay,
            nonMandatoryDay * 7.0,
            nonMandatoryDay * 30.4375
        )
        ExpenseComparisonRow(
            "Всего",
            averageDay,
            averageWeek,
            averageMonth
        )

        Spacer(Modifier.height(14.dp))
        Text("Обязательные: ${"%.2f".format(mandatoryTotal)}")
        Text("Необязательные: ${"%.2f".format(nonMandatoryTotal)}")

        Spacer(Modifier.height(16.dp))
        Text(
            "Период учитывает все операции между выбранными датами. " +
                "Средний месяц рассчитан как 30,4375 дня.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(16.dp))
        Button(onClick = {}) {
            Text("Сохранить отчёт в TXT")
        }
    }
}

@Composable
fun DateRangePicker(
    startMillis: Long,
    endMillis: Long,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit
) {
    var startText by remember { mutableStateOf(formatDate(startMillis)) }
    var endText by remember { mutableStateOf(formatDate(endMillis)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Введите даты в формате ДД.ММ.ГГГГ")
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = startText,
                onValueChange = {
                    startText = it
                    parseDate(it)?.let(onStartChange)
                },
                label = { Text("С") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = endText,
                onValueChange = {
                    endText = it
                    parseDate(it)?.let(onEndChange)
                },
                label = { Text("По") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

fun formatDate(time: Long): String {
    return java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(time))
}

fun parseDate(value: String): Long? {
    return try {
        val format = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        format.isLenient = false
        format.parse(value)?.time
    } catch (_: Exception) {
        null
    }
}

@Composable
fun AverageExpenseCard(title: String, value: Double, subtitle: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("${"%.2f".format(value)}", style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ExpenseComparisonRow(
    label: String,
    day: Double,
    week: Double,
    month: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("День ${"%.2f".format(day)}")
                Text("Нед. ${"%.2f".format(week)}")
                Text("Мес. ${"%.2f".format(month)}")
            }
        }
    }
}


@Composable
fun AverageExpenseCard(title: String, value: Double, subtitle: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${"%.2f".format(value)}",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ExpenseComparisonRow(
    label: String,
    day: Double,
    week: Double,
    month: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("День ${"%.2f".format(day)}")
                Text("Нед. ${"%.2f".format(week)}")
                Text("Мес. ${"%.2f".format(month)}")
            }
        }
    }
}


@Composable
private fun StatCard(title: String, value: Double) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title); Text(money(value))
        }
    }
}

@Composable
fun MoreScreen(
    forecast: List<ForecastItem>,
    accounts: List<Account>,
    onForecastChange: (List<ForecastItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdd by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ещё", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Планирование", style = MaterialTheme.typography.titleLarge)
                Text("Прогнозируемые доходы и расходы")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAdd = true }) { Text("Добавить") }
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Скрыть" else "Показать")
                    }
                }
            }
        }

        if (expanded) {
            ForecastSummary(forecast, accounts)
            forecast.sortedBy { it.date }.forEach { item ->
                val fg = amountColor(item.income)
                val bg = amountContainer(item.income)
                Card(
                    colors = CardDefaults.cardColors(containerColor = bg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text("${if (item.income) "+" else "-"} ${item.name}", color = fg)
                        },
                        supportingContent = {
                            Text("${"%.2f".format(item.amount)} • ${item.recurrence} • ${formatDate(item.date)}")
                        },
                        trailingContent = {
                            Text(if (item.income) "Доход" else "Расход", color = fg)
                        }
                    )
                }
            }
        }

        ListItem(headlineContent = { Text("Счета и вклады") }, supportingContent = { Text("Управление счетами") })
        ListItem(headlineContent = { Text("Категории") }, supportingContent = { Text("Обязательные и необязательные расходы") })
        ListItem(headlineContent = { Text("Обязательные платежи") }, supportingContent = { Text("Платежи и напоминания") })
        ListItem(headlineContent = { Text("Настройки") }, supportingContent = { Text("Внешний вид и другие параметры") })
    }

    if (showAdd) {
        AddForecastDialog(accounts, { showAdd = false }) {
            onForecastChange(forecast + it)
            showAdd = false
        }
    }
}

@Composable
fun ForecastSummary(forecast: List<ForecastItem>, accounts: List<Account>) {
    val now = System.currentTimeMillis()
    val horizon = now + 90L * 24L * 60L * 60L * 1000L
    val upcoming = forecast.filter { it.date in now..horizon }
    val income = upcoming.filter { it.income }.sumOf { it.amount }
    val expense = upcoming.filter { !it.income }.sumOf { it.amount }
    val balance = accounts.sumOf { it.balance }
    val projected = balance + income - expense

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Прогноз на ближайшие 90 дней", style = MaterialTheme.typography.titleMedium)
        Card(
            colors = CardDefaults.cardColors(containerColor = IncomeContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Доходы  +${"%.2f".format(income)}",
                color = IncomeColor,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(14.dp)
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = ExpenseContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Расходы  -${"%.2f".format(expense)}",
                color = ExpenseColor,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(14.dp)
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Text(
                "Прогнозируемый остаток: ${"%.2f".format(projected)}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}

@Composable
fun AddForecastDialog(
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onSave: (ForecastItem) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(true) }
    var dateText by remember { mutableStateOf(formatDate(System.currentTimeMillis())) }
    var recurrence by remember { mutableStateOf("Разово") }
    val parsedDate = parseDate(dateText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Планируемая операция") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = income,
                        onClick = { income = true },
                        label = { Text("Доход", color = IncomeColor) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = IncomeContainer
                        )
                    )
                    FilterChip(
                        selected = !income,
                        onClick = { income = false },
                        label = { Text("Расход", color = ExpenseColor) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ExpenseContainer
                        )
                    )
                }
                OutlinedTextField(
                    dateText, { dateText = it },
                    label = { Text("Дата ДД.ММ.ГГГГ") }, singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Разово", "Ежемесячно", "Ежегодно").forEach { r ->
                        FilterChip(recurrence == r, { recurrence = r }, label = { Text(r) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = amount.replace(",", ".").toDoubleOrNull()
                if (name.isNotBlank() && value != null && value > 0 && parsedDate != null) {
                    onSave(ForecastItem(
                        System.currentTimeMillis(), name.trim(), value, income,
                        parsedDate, recurrence, accounts.firstOrNull()?.id ?: 0L
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}


@Composable
private fun AddOperationDialog(accounts: List<Account>, onDismiss: () -> Unit,
                               onSave: (Double, Boolean, String, String, Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("Продукты") }
    var comment by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 1) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новая операция") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { amount = it }, label = { Text("Сумма") })
            Row {
                FilterChip(!income, { income = false }, label = { Text("Расход") })
                Spacer(Modifier.width(8.dp))
                FilterChip(income, { income = true }, label = { Text("Доход") })
            }
            AccountSelector(accounts, accountId) { accountId = it }
            OutlinedTextField(category, { category = it }, label = { Text("Категория") })
            OutlinedTextField(comment, { comment = it }, label = { Text("Комментарий") })
        }
    }, confirmButton = { TextButton(onClick = {
        amount.toDoubleOrNull()?.takeIf { it > 0 }?.let { onSave(it, income, category, comment, accountId) }
    }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun AccountSelector(accounts: List<Account>, selectedId: Long, onSelected: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Счёт: ${accounts.firstOrNull { it.id == selectedId }?.name ?: "Выберите"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            accounts.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { onSelected(a.id); expanded = false }) }
        }
    }
}

@Composable
private fun TransferDialog(accounts: List<Account>, onDismiss: () -> Unit, onSave: (Long, Long, Double) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 1) }
    var to by remember { mutableStateOf(accounts.getOrNull(1)?.id ?: from) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Перевод между счетами") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountSelector(accounts, from) { from = it }
            AccountSelector(accounts, to) { to = it }
            OutlinedTextField(amount, { amount = it }, label = { Text("Сумма") })
        }
    }, confirmButton = { TextButton(onClick = {
        amount.toDoubleOrNull()?.takeIf { it > 0 && from != to }?.let { onSave(from, to, it) }
    }) { Text("Перевести") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun NewDepositDialog(onDismiss: () -> Unit, onSave: (String, Double, Double, Boolean) -> Unit) {
    var name by remember { mutableStateOf("Вклад") }
    var amount by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var cap by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новый вклад") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") })
            OutlinedTextField(amount, { amount = it }, label = { Text("Сумма") })
            OutlinedTextField(rate, { rate = it }, label = { Text("Процент годовых") })
            Row { Checkbox(cap, { cap = it }); Text("Капитализация") }
        }
    }, confirmButton = { TextButton(onClick = {
        val a = amount.toDoubleOrNull(); val r = rate.toDoubleOrNull()
        if (a != null && a > 0 && r != null && r >= 0) onSave(name.ifBlank { "Вклад" }, a, r, cap)
    }) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
