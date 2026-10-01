package com.example.myfinance

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.content.ContentValues
import android.provider.MediaStore
import java.security.MessageDigest
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Account(val id: Long, val name: String, val type: String, val balance: Double = 0.0, val rate: Double = 0.0, val capitalization: Boolean = false)
data class Operation(val id: Long, val amount: Double, val category: String, val comment: String, val income: Boolean, val accountId: Long, val mandatory: Boolean = false, val date: Long = System.currentTimeMillis())
data class ForecastItem(val id: Long, val name: String, val amount: Double, val income: Boolean, val date: Long, val recurrence: String = "Разово", val accountId: Long = 0L, val reminderDays: Int = 1, val reminderEnabled: Boolean = true, val mandatory: Boolean = false, val completed: Boolean = false, val archived: Boolean = false, val nextOccurrenceId: Long = 0L)

fun nextForecastDate(date: Long, recurrence: String): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = date }
    when (recurrence) {
        "Ежемесячно" -> {
            val day = cal.get(Calendar.DAY_OF_MONTH)
            cal.add(Calendar.MONTH, 1)
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, minOf(day, maxDay))
        }
        "Ежегодно" -> {
            val month = cal.get(Calendar.MONTH)
            val day = cal.get(Calendar.DAY_OF_MONTH)
            cal.add(Calendar.YEAR, 1)
            cal.set(Calendar.MONTH, month)
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, minOf(day, maxDay))
        }
        else -> return date
    }
    return cal.timeInMillis
}


data class CalendarEvent(val date: Long, val title: String, val amount: Double, val income: Boolean)

private const val PREFS_NAME = "my_finance_data"
private const val OPERATIONS_KEY = "operations"
private const val ACCOUNTS_KEY = "accounts"
private const val FORECAST_KEY = "forecast"

val IncomeColor = Color(0xFF2E7D32)
val IncomeContainer = Color(0xFFE8F5E9)
val ExpenseColor = Color(0xFFC62828)
val ExpenseContainer = Color(0xFFFFEBEE)

fun money(v: Double) = String.format(Locale.getDefault(), "%.2f", v)
fun formatDate(t: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(t))
fun parseDate(s: String): Long? = runCatching { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).parse(s)?.time }.getOrNull()


data class UserProfile(val id: Long, val name: String)
private const val USERS_KEY = "users"
private const val ACTIVE_USER_KEY = "active_user_id"
private const val APP_PIN_HASH_KEY = "app_pin_hash"
private const val APP_PIN_ENABLED_KEY = "app_pin_enabled"
private const val FIRST_USER_MIGRATED_KEY = "first_user_migrated"

fun userPrefs(c: Context, userId: Long) = c.getSharedPreferences("my_finance_user_$userId", Context.MODE_PRIVATE)

fun loadUsers(c: Context): List<UserProfile> = runCatching {
    val raw = c.getSharedPreferences(PREFS_NAME, 0).getString(USERS_KEY, null) ?: return@runCatching emptyList()
    val a = JSONArray(raw)
    buildList { for (i in 0 until a.length()) { val x=a.getJSONObject(i); add(UserProfile(x.getLong("id"), x.getString("name"))) } }
}.getOrDefault(emptyList())

fun saveUsers(c: Context, users: List<UserProfile>) {
    val a=JSONArray(); users.forEach { a.put(JSONObject().apply { put("id",it.id); put("name",it.name) }) }
    c.getSharedPreferences(PREFS_NAME,0).edit().putString(USERS_KEY,a.toString()).apply()
}

fun ensureUsers(c: Context): List<UserProfile> {
    var users=loadUsers(c)
    if (users.isEmpty()) {
        val first=UserProfile(1L,"Основной пользователь")
        users=listOf(first)
        saveUsers(c,users)
        val old=c.getSharedPreferences(PREFS_NAME,0)
        val up=userPrefs(c,first.id)
        if (!old.getString(ACCOUNTS_KEY,null).isNullOrBlank()) up.edit()
            .putString(ACCOUNTS_KEY,old.getString(ACCOUNTS_KEY,"[]"))
            .putString(OPERATIONS_KEY,old.getString(OPERATIONS_KEY,"[]"))
            .putString(FORECAST_KEY,old.getString(FORECAST_KEY,"[]"))
            .apply()
        c.getSharedPreferences(PREFS_NAME,0).edit().putLong(ACTIVE_USER_KEY,first.id).putBoolean(FIRST_USER_MIGRATED_KEY,true).apply()
    }
    if (c.getSharedPreferences(PREFS_NAME,0).getLong(ACTIVE_USER_KEY,0L)==0L)
        c.getSharedPreferences(PREFS_NAME,0).edit().putLong(ACTIVE_USER_KEY,users.first().id).apply()
    return users
}

fun activeUserId(c: Context): Long = c.getSharedPreferences(PREFS_NAME,0).getLong(ACTIVE_USER_KEY,1L)
fun setActiveUserId(c: Context,id:Long){ c.getSharedPreferences(PREFS_NAME,0).edit().putLong(ACTIVE_USER_KEY,id).apply() }

fun loadAccountsForUser(c: Context,userId:Long): List<Account> = runCatching { JSONArray(userPrefs(c,userId).getString(ACCOUNTS_KEY,"[]")).let { j -> buildList { for(i in 0 until j.length()){val x=j.getJSONObject(i);add(Account(x.getLong("id"),x.getString("name"),x.getString("type"),x.optDouble("balance"),x.optDouble("rate"),x.optBoolean("capitalization"))) } } } }.getOrDefault(emptyList())
fun loadOperationsForUser(c: Context,userId:Long,accounts:List<Account>): List<Operation> = runCatching { JSONArray(userPrefs(c,userId).getString(OPERATIONS_KEY,"[]")).let { j -> buildList { for(i in 0 until j.length()){val x=j.getJSONObject(i);add(Operation(x.getLong("id"),x.getDouble("amount"),x.getString("category"),x.optString("comment"),x.optBoolean("income"),x.optLong("accountId",accounts.firstOrNull()?.id?:0L),x.optBoolean("mandatory",false),x.optLong("date",System.currentTimeMillis()))) } } } }.getOrDefault(emptyList())
fun loadForecastForUser(c: Context,userId:Long): List<ForecastItem> = runCatching { JSONArray(userPrefs(c,userId).getString(FORECAST_KEY,"[]")).let { j -> buildList { for(i in 0 until j.length()){val x=j.getJSONObject(i);add(ForecastItem(x.getLong("id"),x.getString("name"),x.getDouble("amount"),x.optBoolean("income"),x.getLong("date"),x.optString("recurrence","Разово"),x.optLong("accountId",0L),x.optInt("reminderDays",1),x.optBoolean("reminderEnabled",true),x.optBoolean("mandatory",false),x.optBoolean("completed",false),x.optBoolean("archived",false),x.optLong("nextOccurrenceId",0L))) } } } }.getOrDefault(emptyList())
fun saveDataForUser(c:Context,userId:Long,accounts:List<Account>,operations:List<Operation>,forecast:List<ForecastItem>){
    val p=userPrefs(c,userId)
    val a=JSONArray(); accounts.forEach { a.put(JSONObject().apply{put("id",it.id);put("name",it.name);put("type",it.type);put("balance",it.balance);put("rate",it.rate);put("capitalization",it.capitalization)}) }
    val o=JSONArray(); operations.forEach { o.put(JSONObject().apply{put("id",it.id);put("amount",it.amount);put("category",it.category);put("comment",it.comment);put("income",it.income);put("accountId",it.accountId);put("mandatory",it.mandatory);put("date",it.date)}) }
    val f=JSONArray(); forecast.forEach { f.put(JSONObject().apply{put("id",it.id);put("name",it.name);put("amount",it.amount);put("income",it.income);put("date",it.date);put("recurrence",it.recurrence);put("accountId",it.accountId);put("reminderDays",it.reminderDays);put("reminderEnabled",it.reminderEnabled);put("mandatory",it.mandatory);put("completed",it.completed);put("archived",it.archived);put("nextOccurrenceId",it.nextOccurrenceId)}) }
    p.edit().putString(ACCOUNTS_KEY,a.toString()).putString(OPERATIONS_KEY,o.toString()).putString(FORECAST_KEY,f.toString()).apply()
}
fun sha256(s:String):String=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){String.format("%02x",it)}
fun pinEnabled(c:Context)=c.getSharedPreferences(PREFS_NAME,0).getBoolean(APP_PIN_ENABLED_KEY,false)
fun pinMatches(c:Context,pin:String)=sha256(pin)==c.getSharedPreferences(PREFS_NAME,0).getString(APP_PIN_HASH_KEY,"")

fun writeDownload(c:Context,name:String,mime:String,content:String){
    val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime);put(MediaStore.Downloads.IS_PENDING,1)}
    val uri=c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: return
    c.contentResolver.openOutputStream(uri)?.use{it.write(content.toByteArray(Charsets.UTF_8))}
    values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);c.contentResolver.update(uri,values,null,null)
    Toast.makeText(c,"Файл сохранён в папке Downloads",Toast.LENGTH_LONG).show()
}
fun exportUser(c:Context,user:UserProfile,accounts:List<Account>,operations:List<Operation>,forecast:List<ForecastItem>,kind:String){
    val stamp=SimpleDateFormat("yyyyMMdd_HHmm",Locale.getDefault()).format(Date())
    when(kind){
        "TXT"->{ val sb=StringBuilder("MyFinance — ${user.name}\n\nСчета:\n");accounts.forEach{sb.append("${it.name}: ${money(it.balance)}\n")};sb.append("\nОперации:\n");operations.sortedBy{it.date}.forEach{sb.append("${formatDate(it.date)}; ${if(it.income)"Доход" else "Расход"}; ${it.category}; ${money(it.amount)}; ${it.comment}\n")};sb.append("\nЗапланированные платежи:\n");forecast.forEach{sb.append("${formatDate(it.date)}; ${it.name}; ${money(it.amount)}; ${if(it.completed)"Выполнено" else "Не выполнено"}\n")};writeDownload(c,"MyFinance_${user.name}_$stamp.txt","text/plain",sb.toString())}
        "CSV"->{ val sb=StringBuilder("Дата;Тип;Категория;Сумма;Комментарий\n");operations.sortedBy{it.date}.forEach{sb.append("${formatDate(it.date)};${if(it.income)"Доход" else "Расход"};${it.category};${money(it.amount)};${it.comment.replace(";",",")}\n")};writeDownload(c,"MyFinance_${user.name}_$stamp.csv","text/csv",sb.toString())}
        "XLS"->{ val rows=operations.sortedBy{it.date}.joinToString(""){ "<tr><td>${formatDate(it.date)}</td><td>${if(it.income)"Доход" else "Расход"}</td><td>${it.category}</td><td>${money(it.amount)}</td><td>${it.comment}</td></tr>" }; val html="<html><body><table><tr><th>Дата</th><th>Тип</th><th>Категория</th><th>Сумма</th><th>Комментарий</th></tr>$rows</table></body></html>";writeDownload(c,"MyFinance_${user.name}_$stamp.xls","application/vnd.ms-excel",html)}
        "BACKUP"->{ val root=JSONObject().apply{put("version",1);put("user",user.name);put("accounts",JSONArray(userPrefs(c,user.id).getString(ACCOUNTS_KEY,"[]")));put("operations",JSONArray(userPrefs(c,user.id).getString(OPERATIONS_KEY,"[]")));put("forecast",JSONArray(userPrefs(c,user.id).getString(FORECAST_KEY,"[]")))};writeDownload(c,"MyFinance_${user.name}_$stamp.json","application/json",root.toString(2))}
    }
}

fun saveData(c: Context, accounts: List<Account>, operations: List<Operation>, forecast: List<ForecastItem>) {
    val p = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val a = JSONArray(); accounts.forEach { a.put(JSONObject().apply { put("id",it.id);put("name",it.name);put("type",it.type);put("balance",it.balance);put("rate",it.rate);put("capitalization",it.capitalization) }) }
    val o = JSONArray(); operations.forEach { o.put(JSONObject().apply { put("id",it.id);put("amount",it.amount);put("category",it.category);put("comment",it.comment);put("income",it.income);put("accountId",it.accountId);put("mandatory",it.mandatory);put("date",it.date) }) }
    val f = JSONArray(); forecast.forEach { f.put(JSONObject().apply { put("id",it.id);put("name",it.name);put("amount",it.amount);put("income",it.income);put("date",it.date);put("recurrence",it.recurrence);put("accountId",it.accountId);put("reminderDays",it.reminderDays);put("reminderEnabled",it.reminderEnabled);put("mandatory",it.mandatory);put("completed",it.completed);put("archived",it.archived);put("nextOccurrenceId",it.nextOccurrenceId) }) }
    p.edit().putString(ACCOUNTS_KEY,a.toString()).putString(OPERATIONS_KEY,o.toString()).putString(FORECAST_KEY,f.toString()).apply()
}
fun loadAccounts(c: Context): List<Account> = runCatching { JSONArray(c.getSharedPreferences(PREFS_NAME,0).getString(ACCOUNTS_KEY,"[]")).let { j -> buildList { for(i in 0 until j.length()){val x=j.getJSONObject(i);add(Account(x.getLong("id"),x.getString("name"),x.getString("type"),x.optDouble("balance"),x.optDouble("rate"),x.optBoolean("capitalization"))) } } } }.getOrDefault(emptyList())
fun loadOperations(c: Context, accounts: List<Account>): List<Operation> = runCatching { JSONArray(c.getSharedPreferences(PREFS_NAME,0).getString(OPERATIONS_KEY,"[]")).let { j -> buildList { for(i in 0 until j.length()){val x=j.getJSONObject(i);add(Operation(x.getLong("id"),x.getDouble("amount"),x.getString("category"),x.optString("comment"),x.optBoolean("income"),x.optLong("accountId",accounts.firstOrNull()?.id?:0L),x.optBoolean("mandatory",false),x.optLong("date",System.currentTimeMillis()))) } } } }.getOrDefault(emptyList())
fun loadForecast(c: Context): List<ForecastItem> = runCatching { JSONArray(c.getSharedPreferences(PREFS_NAME,0).getString(FORECAST_KEY,"[]")).let { j -> buildList { for(i in 0 until j.length()){val x=j.getJSONObject(i);add(ForecastItem(x.getLong("id"),x.getString("name"),x.getDouble("amount"),x.optBoolean("income"),x.getLong("date"),x.optString("recurrence","Разово"),x.optLong("accountId",0L),x.optInt("reminderDays",1),x.optBoolean("reminderEnabled",true),x.optBoolean("mandatory",false),x.optBoolean("completed",false),x.optBoolean("archived",false),x.optLong("nextOccurrenceId",0L))) } } } }.getOrDefault(emptyList())
fun balances(accounts: List<Account>, operations: List<Operation>) = accounts.associate { a -> a.id to (a.balance + operations.filter { it.accountId==a.id }.sumOf { if(it.income) it.amount else -it.amount }) }


private const val NOTIFICATION_CHANNEL_ID = "planned_payments"
private const val NOTIFICATION_CHANNEL_NAME = "Запланированные платежи"
private const val EXTRA_FORECAST_ID = "forecast_id"
private const val EXTRA_DUE_DATE = "due_date"
private const val EXTRA_REMINDER_DAYS = "reminder_days"

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Напоминания о запланированных платежах" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

fun nextDueDate(item: ForecastItem, now: Long = System.currentTimeMillis()): Long? {
    if (item.recurrence == "Разово") return if (item.date > now) item.date else null
    val cal = Calendar.getInstance().apply { timeInMillis = item.date }
    while (cal.timeInMillis <= now) {
        if (item.recurrence == "Ежемесячно") cal.add(Calendar.MONTH, 1)
        else cal.add(Calendar.YEAR, 1)
    }
    return cal.timeInMillis
}

fun cancelForecastNotification(context: Context, itemId: Long) {
    context.getSystemService(NotificationManager::class.java).cancel(itemId.hashCode())
}

fun cancelForecastReminder(context: Context, itemId: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
        context, itemId.hashCode(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pending)
    pending.cancel()
}

fun scheduleForecastReminder(context: Context, item: ForecastItem) {
    cancelForecastReminder(context, item.id)
    cancelForecastNotification(context, item.id)
    if (!item.reminderEnabled) return
    val due = nextDueDate(item) ?: return
    val trigger = due - item.reminderDays * 24L * 60L * 60L * 1000L
    // Если срок напоминания уже прошёл для разового платежа, не показываем его немедленно.
    // Для повторяющихся платежей разрешаем уведомление сразу, если мы уже вошли в окно напоминания.
    if (trigger <= System.currentTimeMillis() && item.recurrence == "Разово") return
    val whenToNotify = maxOf(trigger, System.currentTimeMillis() + 5000L)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderReceiver::class.java).apply {
        putExtra(EXTRA_FORECAST_ID, item.id)
        putExtra(EXTRA_DUE_DATE, due)
        putExtra(EXTRA_REMINDER_DAYS, item.reminderDays)
    }
    val pending = PendingIntent.getBroadcast(
        context, item.id.hashCode(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToNotify, pending)
}

fun scheduleAllForecastReminders(context: Context, forecast: List<ForecastItem>) {
    createNotificationChannel(context)
    forecast.forEach { if (it.reminderEnabled) scheduleForecastReminder(context, it) else cancelForecastReminder(context, it.id) }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        createNotificationChannel(context)
        val id = intent.getLongExtra(EXTRA_FORECAST_ID, 0L)
        val due = intent.getLongExtra(EXTRA_DUE_DATE, 0L)
        val forecast = loadForecast(context)
        val item = forecast.firstOrNull { it.id == id } ?: return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, id.hashCode() + 100000,
            openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val daysText = when (item.reminderDays) {
            1 -> "завтра"
            else -> "через ${item.reminderDays} дней"
        }
        val text = "${item.name}: ${money(item.amount)} — ${daysText} (${formatDate(due)})"
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Напоминание о платеже")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(id.hashCode(), notification)

        if (item.recurrence != "Разово" && item.reminderEnabled) scheduleForecastReminder(context, item)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            scheduleAllForecastReminders(context, loadForecast(context))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        createNotificationChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
        setContent { MaterialTheme { FinanceApp(this) } }
    }
}

@Composable
fun FinanceApp(c: Context) {
    val initialUsers = remember { ensureUsers(c) }
    var users by remember { mutableStateOf(initialUsers) }
    var currentUserId by remember { mutableLongStateOf(activeUserId(c)) }
    var accounts by remember(currentUserId) { mutableStateOf(loadAccountsForUser(c, currentUserId)) }
    var operations by remember(currentUserId) { mutableStateOf(loadOperationsForUser(c, currentUserId, accounts)) }
    var forecast by remember(currentUserId) { mutableStateOf(loadForecastForUser(c, currentUserId)) }
    var tab by remember { mutableStateOf(0) }
    var addingOperation by remember { mutableStateOf(false) }
    var quickMenu by remember { mutableStateOf(false) }
    var planningFromHome by remember { mutableStateOf(false) }
    var quickIncome by remember { mutableStateOf(false) }

    var userMenu by remember { mutableStateOf(false) }
    var usersDialog by remember { mutableStateOf(false) }
    var addUserDialog by remember { mutableStateOf(false) }
    var renameUser by remember { mutableStateOf<UserProfile?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(pinEnabled(c)) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    val currentUser = users.firstOrNull { it.id == currentUserId } ?: users.first()

    LaunchedEffect(currentUserId) {
        setActiveUserId(c, currentUserId)
        accounts = loadAccountsForUser(c, currentUserId)
        operations = loadOperationsForUser(c, currentUserId, accounts)
        forecast = loadForecastForUser(c, currentUserId)
        scheduleAllForecastReminders(c, forecast)
    }

    fun save(
        a: List<Account> = accounts,
        o: List<Operation> = operations,
        f: List<ForecastItem> = forecast
    ) {
        accounts = a
        operations = o
        forecast = f
        saveDataForUser(c, currentUserId, a, o, f)
    }

    if (accounts.isEmpty()) {
        accounts = listOf(Account(1, "Основной счёт", "card"))
        save(a = accounts)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentUser.name) },
                navigationIcon = {
                    IconButton(onClick = { userMenu = true }) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Пользователь")
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = {
            if (tab == 0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (quickMenu) {
                        Card(Modifier.padding(end = 10.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                TextButton(onClick = {
                                    quickMenu = false
                                    quickIncome = false
                                    addingOperation = true
                                }) { Text("➖ Расход") }
                                TextButton(onClick = {
                                    quickMenu = false
                                    quickIncome = true
                                    addingOperation = true
                                }) { Text("💰 Доход") }
                                TextButton(onClick = {
                                    quickMenu = false
                                    planningFromHome = true
                                }) { Text("📅 Запланированный доход/расход") }
                            }
                        }
                    }
                    FloatingActionButton(onClick = { quickMenu = !quickMenu }) {
                        Icon(
                            if (quickMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (quickMenu) "Закрыть" else "Добавить"
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    Icons.Default.Home,
                    Icons.Default.List,
                    Icons.Default.DateRange,
                    Icons.Default.PieChart,
                    Icons.Default.MoreHoriz
                )
                val titles = listOf("Главная", "Операции", "Календарь", "Статистика", "Ещё")
                items.forEachIndexed { i, icon ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = titles[i]) }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (tab) {
            0 -> HomeScreen(
                accounts,
                operations,
                forecast,
                Modifier.padding(paddingValues),
                { tab = 4 },
                { quickIncome = true; addingOperation = true },
                { quickIncome = false; addingOperation = true }
            )

            1 -> OperationsScreen(
                operations,
                accounts,
                { op -> save(o = operations.map { if (it.id == op.id) op else it }) },
                { id -> save(o = operations.filter { it.id != id }) },
                Modifier.padding(paddingValues)
            )

            2 -> CalendarScreen(
                operations,
                forecast,
                { item ->
                    save(f = forecast + item)
                    scheduleForecastReminder(c, item)
                },
                { updated ->
                    cancelForecastReminder(c, updated.id)
                    val previous = forecast.firstOrNull { it.id == updated.id }
                    val actualOperationId = -updated.id
                    var updatedOperations = operations

                    if (previous?.completed != updated.completed) {
                        if (updated.completed) {
                            if (!operations.any { it.id == actualOperationId }) {
                                val accountId = if (updated.accountId != 0L) {
                                    updated.accountId
                                } else {
                                    accounts.firstOrNull()?.id ?: 0L
                                }
                                updatedOperations = operations + Operation(
                                    actualOperationId,
                                    updated.amount,
                                    updated.name,
                                    "Запланированный платеж",
                                    updated.income,
                                    accountId,
                                    !updated.income && updated.mandatory,
                                    updated.date
                                )
                            }
                        } else {
                            updatedOperations = operations.filter { it.id != actualOperationId }
                        }
                    }

                    val savedForecastList: List<ForecastItem> = when {
                        updated.completed && previous?.completed != true -> {
                            val archived = updated.copy(completed = true, archived = true)
                            if (updated.recurrence != "Разово") {
                                val nextId = System.currentTimeMillis() + 1L
                                val nextItem = updated.copy(
                                    id = nextId,
                                    date = nextForecastDate(updated.date, updated.recurrence),
                                    completed = false,
                                    archived = false,
                                    nextOccurrenceId = 0L
                                )
                                scheduleForecastReminder(c, nextItem)
                                forecast.map {
                                    if (it.id == updated.id) archived.copy(nextOccurrenceId = nextId) else it
                                } + nextItem
                            } else {
                                forecast.map { if (it.id == updated.id) archived else it }
                            }
                        }

                        !updated.completed && previous?.completed == true -> {
                            forecast
                                .filter { it.id != previous.nextOccurrenceId }
                                .map {
                                    if (it.id == updated.id) {
                                        updated.copy(completed = false, archived = false, nextOccurrenceId = 0L)
                                    } else it
                                }
                        }

                        else -> forecast.map { if (it.id == updated.id) updated else it }
                    }

                    save(o = updatedOperations, f = savedForecastList)
                },
                { id ->
                    forecast.firstOrNull { it.id == id }?.let {
                        cancelForecastReminder(c, it.id)
                        cancelForecastNotification(c, it.id)
                    }
                    save(
                        o = operations.filter { it.id != -id },
                        f = forecast.filter { it.id != id }
                    )
                },
                { item, enabled ->
                    val updated = item.copy(reminderEnabled = enabled)
                    save(f = forecast.map { if (it.id == item.id) updated else it })
                    if (enabled) {
                        scheduleForecastReminder(c, updated)
                    } else {
                        cancelForecastReminder(c, item.id)
                        cancelForecastNotification(c, item.id)
                    }
                },
                Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            )

            3 -> StatisticsScreen(operations, accounts, Modifier.padding(paddingValues))

            4 -> MoreScreen(
                forecast,
                accounts,
                operations,
                { updatedForecast, updatedOperations ->
                    val removedIds = forecast.map { it.id }.toSet() - updatedForecast.map { it.id }.toSet()
                    removedIds.forEach {
                        cancelForecastReminder(c, it)
                        cancelForecastNotification(c, it)
                    }
                    save(o = updatedOperations, f = updatedForecast)
                    updatedForecast
                        .filter { it.reminderEnabled && !it.completed && !it.archived }
                        .forEach { scheduleForecastReminder(c, it) }
                },
                { id ->
                    forecast.firstOrNull { it.id == id }?.let {
                        cancelForecastReminder(c, it.id)
                        cancelForecastNotification(c, it.id)
                    }
                    save(
                        o = operations.filter { it.id != -id },
                        f = forecast.filter { it.id != id }
                    )
                },
                Modifier.padding(paddingValues)
            )
        }
    }

    DropdownMenu(
        expanded = userMenu,
        onDismissRequest = { userMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("👤 Пользователи") },
            onClick = { userMenu = false; usersDialog = true }
        )
        DropdownMenuItem(
            text = { Text("➕ Добавить пользователя") },
            onClick = { userMenu = false; addUserDialog = true }
        )
        DropdownMenuItem(
            text = { Text("💾 Сохранить / экспорт") },
            onClick = { userMenu = false; exportDialog = true }
        )
        DropdownMenuItem(
            text = { Text("🔐 Код входа") },
            onClick = { userMenu = false; pinDialog = true }
        )
    }

    if (locked) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("🔐 MyFinance") },
            text = {
                Column {
                    Text("Введите код доступа")
                    OutlinedTextField(
                        value = pinEntry,
                        onValueChange = { pinEntry = it.filter(Char::isDigit).take(12) },
                        singleLine = true,
                        label = { Text("Код") }
                    )
                    if (pinError) {
                        Text("Неверный код", color = ExpenseColor)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pinMatches(c, pinEntry)) {
                        locked = false
                        pinEntry = ""
                        pinError = false
                    } else {
                        pinError = true
                    }
                }) { Text("Войти") }
            }
        )
    }

    if (usersDialog) {
        AlertDialog(
            onDismissRequest = { usersDialog = false },
            title = { Text("Пользователи") },
            text = {
                Column {
                    users.forEach { u ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (u.id != currentUserId) {
                                        save()
                                        currentUserId = u.id
                                        setActiveUserId(c, u.id)
                                    }
                                    usersDialog = false
                                    tab = 0
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (u.id == currentUserId) "${u.name} ✓" else u.name,
                                Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                usersDialog = false
                                renameUser = u
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Переименовать")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { usersDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (addUserDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addUserDialog = false },
            title = { Text("Добавить пользователя") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val id = (users.maxOfOrNull { it.id } ?: 0L) + 1L
                        val newUser = UserProfile(id, name.trim())
                        users = users + newUser
                        saveUsers(c, users)
                        saveDataForUser(c, id, emptyList(), emptyList(), emptyList())
                        addUserDialog = false
                    }
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { addUserDialog = false }) { Text("Отмена") }
            }
        )
    }

    renameUser?.let { userToRename ->
        var name by remember(userToRename.id) { mutableStateOf(userToRename.name) }
        AlertDialog(
            onDismissRequest = { renameUser = null },
            title = { Text("Переименовать пользователя") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        users = users.map { if (it.id == userToRename.id) it.copy(name = name.trim()) else it }
                        saveUsers(c, users)
                        renameUser = null
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renameUser = null }) { Text("Отмена") }
            }
        )
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Сохранить / экспорт") },
            text = {
                Column {
                    Text("Текущий пользователь: ${currentUser.name}")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { exportUser(c, currentUser, accounts, operations, forecast, "XLS"); exportDialog = false }) {
                        Text("📊 Excel (.xls)")
                    }
                    TextButton(onClick = { exportUser(c, currentUser, accounts, operations, forecast, "CSV"); exportDialog = false }) {
                        Text("📄 CSV")
                    }
                    TextButton(onClick = { exportUser(c, currentUser, accounts, operations, forecast, "TXT"); exportDialog = false }) {
                        Text("📝 TXT")
                    }
                    TextButton(onClick = { exportUser(c, currentUser, accounts, operations, forecast, "BACKUP"); exportDialog = false }) {
                        Text("💾 Полная резервная копия")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (pinDialog) {
        var newPin by remember { mutableStateOf("") }
        var enabled by remember { mutableStateOf(pinEnabled(c)) }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("Код входа") },
            text = {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Защита включена")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter(Char::isDigit).take(12) },
                        label = { Text("Новый код (минимум 4 цифры)") },
                        singleLine = true
                    )
                    Text(
                        "Один код защищает всё приложение и всех пользователей.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = c.getSharedPreferences(PREFS_NAME, 0)
                    if (enabled) {
                        if (newPin.length >= 4) {
                            p.edit()
                                .putString(APP_PIN_HASH_KEY, sha256(newPin))
                                .putBoolean(APP_PIN_ENABLED_KEY, true)
                                .apply()
                        }
                    } else {
                        p.edit().putBoolean(APP_PIN_ENABLED_KEY, false).apply()
                    }
                    pinDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { pinDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (addingOperation) {
        AddOperationDialog(accounts, quickIncome, { addingOperation = false }) { op ->
            save(o = operations + op)
            addingOperation = false
        }
    }

    if (planningFromHome) {
        PlannedPaymentDialog(
            onDismiss = { planningFromHome = false },
            onSave = { item ->
                save(f = forecast + item)
                if (item.reminderEnabled) scheduleForecastReminder(c, item)
                planningFromHome = false
            }
        )
    }
}

@Composable fun HomeScreen(
    accounts:List<Account>,
    operations:List<Operation>,
    forecast:List<ForecastItem>,
    modifier:Modifier=Modifier,
    onPlannedPaymentsClick:()->Unit={},
    onIncomeClick:()->Unit={},
    onExpenseClick:()->Unit={}
){
    val b=balances(accounts,operations)
    val inc=operations.filter{it.income}.sumOf{it.amount}
    val exp=operations.filter{!it.income}.sumOf{it.amount}
    val now=System.currentTimeMillis()
    val monthOps=operations
    val monthInc=monthOps.filter{it.income}.sumOf{it.amount}
    val monthExp=monthOps.filter{!it.income}.sumOf{it.amount}
    val upcoming=forecast.flatMap{item->forecastDatesInMonth(item,now).map{d->item to d}}.filter{it.second>=startOfDay(now)}.sortedBy{it.second}.take(5)
    val mandatoryUpcoming=upcoming.filter{it.first.mandatory}
    val upcomingAmount=mandatoryUpcoming.filter{!it.first.income}.sumOf{it.first.amount}
    val totalBalance=b.values.sum()
    val topCategories=operations.filter{!it.income}.groupBy{it.category.substringBefore(" — ")}.mapValues{it.value.sumOf{v->v.amount}}.entries.sortedByDescending{it.value}.take(5)

    // Планируемые платежи: можно переключаться между текущим и следующим календарным месяцем.
    var plannedMonthOffset by remember { mutableStateOf(0) }
    val plannedMonth = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, plannedMonthOffset) }.timeInMillis
    val selectedMonthItems=forecast.flatMap{item->forecastDatesInMonth(item,plannedMonth).map{d->item to d}}
    val plannedExpenseMonth=selectedMonthItems.filter{!it.first.income}.sumOf{it.first.amount}
    val plannedIncomeMonth=selectedMonthItems.filter{it.first.income}.sumOf{it.first.amount}
    val plannedCount=selectedMonthItems.size
    val monthTitle=SimpleDateFormat("LLLL yyyy",Locale("ru")).format(Date(plannedMonth)).replaceFirstChar{it.uppercase()}

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())){
        Text("Мои финансы",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
            Column(Modifier.padding(18.dp)){
                Text("ОБЩИЙ БАЛАНС",style=MaterialTheme.typography.labelLarge)
                Text(money(totalBalance),style=MaterialTheme.typography.headlineLarge)
                Text("На всех счетах",style=MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))

        Card(
            modifier=Modifier.fillMaxWidth().clickable{onPlannedPaymentsClick()},
            colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)
        ){
            Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text("Планируемые платежи",style=MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text("$monthTitle",style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                        TextButton(onClick={ plannedMonthOffset=0 }) { Text("Текущий",style=MaterialTheme.typography.labelSmall) }
                        TextButton(onClick={ plannedMonthOffset=1 }) { Text("Следующий",style=MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text("Доходы",style=MaterialTheme.typography.labelSmall)
                            Text(money(plannedIncomeMonth),color=IncomeColor,style=MaterialTheme.typography.titleMedium,maxLines=1)
                        }
                        Column(Modifier.weight(1f)){
                            Text("Расходы",style=MaterialTheme.typography.labelSmall)
                            Text(money(plannedExpenseMonth),color=ExpenseColor,style=MaterialTheme.typography.titleMedium,maxLines=1)
                        }
                    }
                    if(plannedCount>0) Text("Запланировано: $plannedCount",style=MaterialTheme.typography.bodySmall) else Text("Платежей нет",style=MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight,contentDescription="Открыть прогноз")
            }
        }

        val unpaidUpcoming = forecast
            .filter { !it.completed && !it.income }
            .flatMap { item -> forecastDatesInMonth(item, now).map { d -> item to d } }
            .filter { it.second >= startOfDay(now) }
            .sortedBy { it.second }
            .take(5)

        if (unpaidUpcoming.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPlannedPaymentsClick() },
                colors = CardDefaults.cardColors(containerColor = ExpenseContainer)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Неоплаченные платежи", style = MaterialTheme.typography.titleSmall, color = ExpenseColor, modifier = Modifier.weight(1f))
                        Text("${forecast.count { !it.completed && !it.income }}", style = MaterialTheme.typography.labelLarge, color = ExpenseColor)
                    }
                    unpaidUpcoming.forEach { (item, d) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${item.name} • ${formatDate(d)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(money(item.amount), color = ExpenseColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (forecast.count { !it.completed && !it.income } > unpaidUpcoming.size) {
                        Text("Ещё ${forecast.count { !it.completed && !it.income } - unpaidUpcoming.size}…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            SummaryBlock("Доходы",monthInc,IncomeContainer,IncomeColor,Modifier.weight(1f).clickable{onIncomeClick()})
            SummaryBlock("Расходы",monthExp,ExpenseContainer,ExpenseColor,Modifier.weight(1f).clickable{onExpenseClick()})
        }
        Spacer(Modifier.height(8.dp))
        Text("Остаток месяца: ${money(monthInc-monthExp)}",style=MaterialTheme.typography.titleMedium,color=if(monthInc-monthExp>=0)IncomeColor else ExpenseColor)
        Spacer(Modifier.height(18.dp))
        if(mandatoryUpcoming.isNotEmpty()){
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=ExpenseContainer)){
                Column(Modifier.padding(14.dp)){
                    Text("Ближайшие обязательные платежи",style=MaterialTheme.typography.titleMedium,color=ExpenseColor)
                    mandatoryUpcoming.take(3).forEach{(item,d)->
                        Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.SpaceBetween){
                            Text("${item.name} • ${formatDate(d)}",Modifier.weight(1f))
                            Text(money(item.amount),color=ExpenseColor)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Всего ближайших обязательных расходов: ${money(upcomingAmount)}",style=MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(18.dp))
        Text("Расходы за месяц",style=MaterialTheme.typography.titleLarge)
        topCategories.forEach{(name,value)->
            Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){
                Text(name,Modifier.weight(1f))
                Text(money(value),color=ExpenseColor)
            }
        }
        if(topCategories.isEmpty())Text("Пока нет расходов")
        Spacer(Modifier.height(18.dp))
        Text("Счета",style=MaterialTheme.typography.titleLarge)
        accounts.forEach{a->
            Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){
                Column(Modifier.padding(14.dp)){
                    Text(a.name,style=MaterialTheme.typography.titleMedium)
                    Text(money(b[a.id]?:0.0))
                    if(a.type=="deposit"){
                        Text("Ставка: ${a.rate}%")
                        Text(if(a.capitalization)"Капитализация: да" else "Капитализация: нет")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Последние операции",style=MaterialTheme.typography.titleLarge)
        operations.sortedByDescending{it.id}.take(5).forEach{OperationBlock(it,showDate=true)}
    }
}
@Composable fun SummaryBlock(title:String,value:Double,bg:Color,fg:Color,modifier:Modifier=Modifier){Card(modifier=modifier, colors=CardDefaults.cardColors(containerColor=bg)){Column(Modifier.padding(16.dp)){Text(title,color=fg);Text(money(value),style=MaterialTheme.typography.titleLarge,color=fg)}}}
@Composable fun OperationBlock(o:Operation,onEdit:(()->Unit)?=null,onDelete:(()->Unit)?=null,showDate:Boolean=false){
    val bg=if(o.income)IncomeContainer else ExpenseContainer
    val fg=if(o.income)IncomeColor else ExpenseColor
    Card(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), colors=CardDefaults.cardColors(containerColor=bg)){
        Column(Modifier.padding(12.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Column(Modifier.weight(1f)){
                    Text(o.category,color=fg)
                    if(showDate) Text(formatDate(o.date), style=MaterialTheme.typography.bodySmall)
                    if(o.comment.isNotBlank())Text(o.comment)
                    if(!o.income&&o.mandatory)Text("Обязательный",color=fg)
                    Text("${if(o.income)"Доход" else "Расход"} • ${money(o.amount)}",color=fg)
                }
                if(onEdit!=null||onDelete!=null){
                    Row{
                        if(onEdit!=null)IconButton(onClick={onEdit()}){Icon(Icons.Default.Edit,"Изменить")}
                        if(onDelete!=null)IconButton(onClick={onDelete()}){Icon(Icons.Default.Delete,"Удалить",tint=ExpenseColor)}
                    }
                }
            }
        }
    }
}

@Composable fun OperationsScreen(operations:List<Operation>,accounts:List<Account>,onEdit:(Operation)->Unit,onDelete:(Long)->Unit,modifier:Modifier=Modifier){
    var editing by remember{mutableStateOf<Operation?>(null)}
    var deleting by remember{mutableStateOf<Operation?>(null)}
    Column(modifier.fillMaxSize()){
        Text("Операции",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(16.dp))
        LazyColumn(contentPadding=PaddingValues(horizontal=16.dp)){
            items(operations.sortedByDescending{it.id},key={it.id}){o->
                OperationBlock(o,
                    onEdit={editing=o},
                    onDelete={deleting=o}
                )
                Text("Счёт: ${accounts.firstOrNull{a->a.id==o.accountId}?.name?:"Счёт"}",modifier=Modifier.padding(bottom=6.dp))
            }
        }
    }
    editing?.let{op->
        EditOperationDialog(op,accounts,onDismiss={editing=null}){updated->onEdit(updated);editing=null}
    }
    deleting?.let{op->
        AlertDialog(
            onDismissRequest={deleting=null},
            title={Text("Удалить операцию?")},
            text={Text("${if(op.income)"Доход" else "Расход"}: ${money(op.amount)}\n${op.category}")},
            confirmButton={TextButton({onDelete(op.id);deleting=null}){Text("Удалить",color=ExpenseColor)}},
            dismissButton={TextButton({deleting=null}){Text("Отмена")}}
        )
    }
}

fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
}.timeInMillis

fun monthStart(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis=time; set(Calendar.DAY_OF_MONTH,1); set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
}.timeInMillis

fun monthEnd(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis=monthStart(time); add(Calendar.MONTH,1); add(Calendar.MILLISECOND,-1)
}.timeInMillis

fun forecastDatesInMonth(item: ForecastItem, month: Long): List<Long> {
    val start=monthStart(month); val end=monthEnd(month)
    // Не показываем повторяющийся платеж раньше его первой запланированной даты.
    if (end < startOfDay(item.date)) return emptyList()
    if(item.recurrence=="Разово") return if(item.date in start..end) listOf(item.date) else emptyList()
    val base=Calendar.getInstance().apply{timeInMillis=item.date}
    val result=mutableListOf<Long>()
    val c=Calendar.getInstance().apply{timeInMillis=start}
    if(item.recurrence=="Ежемесячно") {
        val day=base.get(Calendar.DAY_OF_MONTH)
        val max=c.getActualMaximum(Calendar.DAY_OF_MONTH)
        c.set(Calendar.DAY_OF_MONTH,minOf(day,max)); result.add(c.timeInMillis)
    } else if(item.recurrence=="Ежегодно") {
        val monthIndex=base.get(Calendar.MONTH)
        if(c.get(Calendar.MONTH)==monthIndex){
            val day=base.get(Calendar.DAY_OF_MONTH); val max=c.getActualMaximum(Calendar.DAY_OF_MONTH)
            c.set(Calendar.DAY_OF_MONTH,minOf(day,max)); result.add(c.timeInMillis)
        }
    }
    return result
}

@Composable fun CalendarScreen(operations:List<Operation>,forecast:List<ForecastItem>,onForecastAdd:(ForecastItem)->Unit,onForecastEdit:(ForecastItem)->Unit,onForecastDelete:(Long)->Unit,onReminderToggle:(ForecastItem,Boolean)->Unit,modifier:Modifier=Modifier){
    var showPlan by remember{mutableStateOf(false)}
    var editing by remember{mutableStateOf<ForecastItem?>(null)}
    var deleting by remember{mutableStateOf<ForecastItem?>(null)}
    var shownMonth by remember{mutableStateOf(monthStart(System.currentTimeMillis()))}
    var selectedDay by remember{mutableStateOf(startOfDay(System.currentTimeMillis()))}
    val monthCal=Calendar.getInstance().apply{timeInMillis=shownMonth}
    val year=monthCal.get(Calendar.YEAR); val month=monthCal.get(Calendar.MONTH)
    val daysInMonth=monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstOffset=(monthCal.get(Calendar.DAY_OF_WEEK)+5)%7
    val today=startOfDay(System.currentTimeMillis())
    val dayEvents=operations.filter{startOfDay(it.date) in shownMonth..monthEnd(shownMonth)}
    val plannedOccurrences=forecast.flatMap{x->forecastDatesInMonth(x,shownMonth).map{d->x to d}}
    val selectedOperations=dayEvents.filter{startOfDay(it.date)==selectedDay}
    val selectedForecast=plannedOccurrences.filter{startOfDay(it.second)==selectedDay}.map{it.first}

    Box(modifier.fillMaxSize()) {
        // Один общий прокручиваемый список. Благодаря этому нижняя область экрана
        // больше не может перекрывать карточки операций.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start=12.dp,end=12.dp,top=12.dp,bottom=8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text("Финансовый календарь",style=MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                            IconButton(onClick={val c=Calendar.getInstance().apply{timeInMillis=shownMonth;add(Calendar.MONTH,-1)};shownMonth=monthStart(c.timeInMillis);selectedDay=shownMonth}){Icon(Icons.Default.ChevronLeft,"Предыдущий месяц")}
                            Text(SimpleDateFormat("LLLL yyyy",Locale("ru")).format(Date(shownMonth)).replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.titleLarge)
                            IconButton(onClick={val c=Calendar.getInstance().apply{timeInMillis=shownMonth;add(Calendar.MONTH,1)};shownMonth=monthStart(c.timeInMillis);selectedDay=shownMonth}){Icon(Icons.Default.ChevronRight,"Следующий месяц")}
                        }
                        Row(Modifier.fillMaxWidth()){
                            listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach{Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)}
                        }
                        val totalCells=((firstOffset+daysInMonth+6)/7)*7
                        for(row in 0 until totalCells/7){
                            Row(Modifier.fillMaxWidth()){
                                for(col in 0..6){
                                    val cell=row*7+col
                                    if(cell<firstOffset || cell>=firstOffset+daysInMonth){
                                        Spacer(Modifier.weight(1f).height(58.dp))
                                    } else {
                                        val day=cell-firstOffset+1
                                        val d=Calendar.getInstance().apply{set(year,month,day,0,0,0);set(Calendar.MILLISECOND,0)}.timeInMillis
                                        val hasIncome=dayEvents.any{startOfDay(it.date)==d&&it.income}
                                        val hasExpense=dayEvents.any{startOfDay(it.date)==d&&!it.income}
                                        val hasMandatory=plannedOccurrences.any{startOfDay(it.second)==d&&!it.first.income&&it.first.mandatory}
                                        val hasPlanned=plannedOccurrences.any{startOfDay(it.second)==d}
                                        val selected=d==selectedDay
                                        Surface(
                                            onClick={selectedDay=d},
                                            modifier=Modifier.weight(1f).height(58.dp).padding(2.dp),
                                            shape=MaterialTheme.shapes.small,
                                            color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            border=if(d==today) BorderStroke(2.dp, ExpenseColor) else null
                                        ){
                                            Column(Modifier.fillMaxSize().padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                                Text(day.toString(),style=MaterialTheme.typography.labelLarge)
                                                Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                                                    if(hasIncome)Text("●",color=IncomeColor,style=MaterialTheme.typography.labelSmall)
                                                    if(hasExpense)Text("●",color=ExpenseColor,style=MaterialTheme.typography.labelSmall)
                                                    if(hasMandatory)Text("●",color=ExpenseColor,style=MaterialTheme.typography.labelSmall)
                                                    else if(hasPlanned)Text("●",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                Text(SimpleDateFormat("d MMMM yyyy",Locale("ru")).format(Date(selectedDay)).replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
            }
            if(selectedForecast.isEmpty() && selectedOperations.isEmpty()) {
                item { Text("На этот день ничего не запланировано",modifier=Modifier.padding(vertical=12.dp)) }
            }
            items(selectedForecast.filter{it.mandatory}, key={"f_m_"+it.id}) { x ->
                PlannedPaymentCard(x,{editing=x},{deleting=x},{enabled->onReminderToggle(x,enabled)},{done->onForecastEdit(x.copy(completed=done))})
            }
            items(selectedForecast.filter{!it.mandatory}, key={"f_n_"+it.id}) { x ->
                PlannedPaymentCard(x,{editing=x},{deleting=x},{enabled->onReminderToggle(x,enabled)},{done->onForecastEdit(x.copy(completed=done))})
            }
            items(selectedOperations, key={"o_"+it.id}) { o ->
                CalendarEventBlock(CalendarEvent(o.date,o.category,o.amount,o.income))
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("Легенда",style=MaterialTheme.typography.titleMedium)
                Text("● доход",color=IncomeColor); Text("● расход",color=ExpenseColor); Text("● запланированный платеж",color=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(96.dp))
            }
        }

        FloatingActionButton(
            onClick = { showPlan=true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end=16.dp,bottom=72.dp)
        ) { Icon(Icons.Default.Add,contentDescription="Запланировать") }
    }

    if(showPlan) PlannedPaymentDialog(onDismiss={showPlan=false},onSave={onForecastAdd(it);showPlan=false})
    editing?.let{x->PlannedPaymentDialog(existing=x,onDismiss={editing=null},onSave={onForecastEdit(it);editing=null})}
    deleting?.let{x->AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить запланированный платеж?")},text={Text("${x.name}: ${money(x.amount)}\n${formatDate(x.date)}")},confirmButton={TextButton({onForecastDelete(x.id);deleting=null}){Text("Удалить",color=ExpenseColor)}},dismissButton={TextButton({deleting=null}){Text("Отмена")}})}
}

@Composable fun PlannedPaymentDialog(existing:ForecastItem?=null,onDismiss:()->Unit,onSave:(ForecastItem)->Unit){
    var name by remember{mutableStateOf(existing?.name ?: "")}
    var amount by remember{mutableStateOf(existing?.amount?.let{money(it)} ?: "")}
    var dateText by remember{mutableStateOf(existing?.date?.let{formatDate(it)} ?: SimpleDateFormat("dd.MM.yyyy",Locale.getDefault()).format(Date()))}
    var recurrence by remember{mutableStateOf(existing?.recurrence ?: "Разово")}
    var reminderDays by remember{mutableStateOf(existing?.reminderDays ?: 1)}
    var reminderEnabled by remember{mutableStateOf(existing?.reminderEnabled ?: true)}
    var mandatory by remember{mutableStateOf(existing?.mandatory ?: false)}
    var isIncome by remember{mutableStateOf(existing?.income ?: false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(isIncome) "Запланированный доход" else "Запланированный расход")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text("Тип операции",style=MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=!isIncome,onClick={isIncome=false},label={Text("➖ Расход")})
            FilterChip(selected=isIncome,onClick={isIncome=true},label={Text("💰 Доход")})
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(name,{name=it},label={Text(if(isIncome) "Источник дохода / название" else "Название платежа")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(amount,{amount=it},label={Text("Сумма")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(dateText,{dateText=it},label={Text("Дата ДД.ММ.ГГГГ")},modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp));Text("Повторение")
        Row{listOf("Разово","Ежемесячно","Ежегодно").forEach{r->FilterChip(recurrence==r,{recurrence=r},label={Text(r)});Spacer(Modifier.width(6.dp))}}
        Spacer(Modifier.height(10.dp));Text("Напомнить за")
        Row{listOf(1,3,7,14).forEach{d->FilterChip(reminderDays==d,{reminderDays=d},label={Text("$d д." )});Spacer(Modifier.width(6.dp))}}
        if(!isIncome) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Обязательный платеж");Switch(checked=mandatory,onCheckedChange={mandatory=it})}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Напоминание включено");Switch(checked=reminderEnabled,onCheckedChange={reminderEnabled=it})}
        Spacer(Modifier.height(8.dp))
        Text(if(isIncome) "Этот доход попадет в «Будущие доходы» в прогнозе на 30 дней." else "Этот расход попадет в «Будущие расходы» в прогнозе на 30 дней.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({
        val v=amount.replace(',','.').toDoubleOrNull(); val d=try{SimpleDateFormat("dd.MM.yyyy",Locale.getDefault()).parse(dateText)?.time}catch(_:Exception){null}
        if(!name.isBlank()&&v!=null&&v>0&&d!=null) onSave(ForecastItem(existing?.id?:System.currentTimeMillis(),name,v,isIncome,d,recurrence,existing?.accountId?:0L,reminderDays,reminderEnabled,if(isIncome) false else mandatory,existing?.completed ?: false,existing?.archived ?: false,existing?.nextOccurrenceId ?: 0L))
    }){Text("Сохранить")}},dismissButton={TextButton(onDismiss){Text("Отмена")}})
}

@Composable fun PlannedPaymentCard(item:ForecastItem,onEdit:()->Unit,onDelete:()->Unit,onReminderToggle:(Boolean)->Unit,onCompletedToggle:(Boolean)->Unit={}){
    val today = startOfDay(System.currentTimeMillis())
    val itemDay = startOfDay(item.date)
    val overdue = !item.completed && item.recurrence == "Разово" && itemDay < today
    val bg = if(item.completed) IncomeContainer else if(overdue) Color(0xFFFFF3E0) else ExpenseContainer
    val statusColor = if(item.completed) IncomeColor else if(overdue) Color(0xFFE65100) else ExpenseColor
    Card(Modifier.fillMaxWidth().padding(vertical=5.dp), colors=CardDefaults.cardColors(containerColor=bg)){
        Column(Modifier.padding(12.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(item.name,style=MaterialTheme.typography.titleMedium)
                    Text("${money(item.amount)} • ${formatDate(item.date)}")
                }
                Row{
                    IconButton(onClick=onEdit){Icon(Icons.Default.Edit,contentDescription="Изменить")}
                    IconButton(onClick=onDelete){Icon(Icons.Default.Delete,contentDescription="Удалить",tint=ExpenseColor)}
                }
            }

            Spacer(Modifier.height(6.dp))

            // Яркий статус, чтобы после переключения было сразу понятно,
            // что платеж действительно отмечен выполненным.
            Surface(
                modifier=Modifier.fillMaxWidth(),
                color=if(item.completed) IncomeColor else if(overdue) Color(0xFFE65100) else ExpenseColor,
                shape=MaterialTheme.shapes.medium
            ){
                Row(
                    Modifier.padding(horizontal=12.dp,vertical=9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    Icon(
                        if(item.completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription=null,
                        tint=Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)){
                        Text(
                            if(item.completed) "ВЫПОЛНЕНО" else if(overdue) "ПРОСРОЧЕНО" else "НЕ ВЫПОЛНЕНО",
                            color=Color.White,
                            style=MaterialTheme.typography.titleSmall
                        )
                        if(item.completed && item.archived){
                            Text("Сохранено в архиве",color=Color.White,style=MaterialTheme.typography.bodySmall)
                        } else if(!item.completed && item.recurrence != "Разово"){
                            Text("Нажмите переключатель после оплаты",color=Color.White,style=MaterialTheme.typography.bodySmall)
                        } else if(!item.completed){
                            Text("Нажмите переключатель после оплаты",color=Color.White,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if(item.completed && item.recurrence != "Разово"){
                val nextDate = nextForecastDate(item.date,item.recurrence)
                Spacer(Modifier.height(6.dp))
                Text("Следующий платёж: ${formatDate(nextDate)}",color=IncomeColor,style=MaterialTheme.typography.bodyMedium)
            } else if(!item.completed && item.recurrence != "Разово"){
                Spacer(Modifier.height(4.dp))
                Text("Дата следующего платежа: ${formatDate(item.date)}",style=MaterialTheme.typography.bodyMedium)
            }

            if(item.mandatory) Text("Обязательный платеж",color=ExpenseColor)
            Text(if(item.reminderEnabled) "Напоминание: за ${item.reminderDays} д." else "Напоминание выключено")

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically
            ){
                Text(
                    if(item.completed) "Отменить выполнение"
                    else "Отметить как выполненное",
                    style=MaterialTheme.typography.labelLarge
                )
                Switch(
                    checked=item.completed,
                    onCheckedChange={value -> onCompletedToggle(value)}
                )
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Text("Напоминание")
                Switch(checked=item.reminderEnabled,onCheckedChange=onReminderToggle)
            }
        }
    }
}

@Composable fun CalendarEventBlock(e:CalendarEvent){val bg=if(e.income)IncomeContainer else ExpenseContainer;val fg=if(e.income)IncomeColor else ExpenseColor;Card(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), colors=CardDefaults.cardColors(containerColor=bg)){Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(formatDate(e.date));Text(e.title,color=fg)};Text("${if(e.income)"+" else "-"}${money(e.amount)}",color=fg)}}}

@Composable
fun StatisticsScreen(operations: List<Operation>, accounts: List<Account>, modifier: Modifier = Modifier) {
    var period by remember { mutableStateOf("Текущий месяц") }
    var cs by remember { mutableStateOf("") }
    var ce by remember { mutableStateOf("") }
    var chartType by remember { mutableStateOf("Круговая") }

    val (start, end) = when (period) {
        "Предыдущий месяц" -> {
            val a = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val b = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                timeInMillis -= 1
            }
            a.timeInMillis to b.timeInMillis
        }
        "Год" -> {
            val a = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            a.timeInMillis to System.currentTimeMillis()
        }
        "Произвольные даты" -> {
            val a = parseDate(cs)
            val b = parseDate(ce)
            if (a != null && b != null) a to b + 86399999 else 0L to 0L
        }
        else -> {
            val a = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            a.timeInMillis to System.currentTimeMillis()
        }
    }

    // Операции фильтруются именно по дате операции, а не по ID.
    val po = operations.filter { it.date in start..end }
    val inc = po.filter { it.income }
    val ex = po.filter { !it.income }
    val man = ex.filter { it.mandatory }
    val non = ex.filter { !it.mandatory }
    val incomeTotal = inc.sumOf { it.amount }
    val mandatoryTotal = man.sumOf { it.amount }
    val nonMandatoryTotal = non.sumOf { it.amount }
    val expenseTotal = ex.sumOf { it.amount }
    val days = (((end - start) / 86400000) + 1).coerceAtLeast(1)
    val daily = expenseTotal / days
    val weekly = daily * 7
    val monthly = daily * 30.4375

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Статистика", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(period == "Текущий месяц", { period = "Текущий месяц" }, label = { Text("Текущий месяц") }, modifier = Modifier.weight(1f))
            FilterChip(period == "Предыдущий месяц", { period = "Предыдущий месяц" }, label = { Text("Предыдущий месяц") }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(period == "Год", { period = "Год" }, label = { Text("Год") }, modifier = Modifier.weight(1f))
            FilterChip(period == "Произвольные даты", { period = "Произвольные даты" }, label = { Text("Произвольные даты") }, modifier = Modifier.weight(1f))
        }

        if (period == "Произвольные даты") {
            OutlinedTextField(cs, { cs = it }, Modifier.fillMaxWidth(), label = { Text("Начало ДД.ММ.ГГГГ") })
            OutlinedTextField(ce, { ce = it }, Modifier.fillMaxWidth(), label = { Text("Конец ДД.ММ.ГГГГ") })
        }

        Spacer(Modifier.height(16.dp))
        Text("Доходы и расходы", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(chartType == "Круговая", { chartType = "Круговая" }, label = { Text("Круговая") }, modifier = Modifier.weight(1f))
            FilterChip(chartType == "Гистограмма", { chartType = "Гистограмма" }, label = { Text("Гистограмма") }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        FinanceChart(chartType, incomeTotal, expenseTotal)

        Spacer(Modifier.height(12.dp))
        // Компактная сводка: доходы отдельно, расходы объединены в одном поле.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactStatCard("Доходы", incomeTotal, IncomeContainer, IncomeColor, Modifier.weight(1f))
            Card(
                modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = ExpenseContainer)
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("Расходы", color = ExpenseColor, style = MaterialTheme.typography.labelMedium)
                    Text(money(expenseTotal), color = ExpenseColor, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            money(mandatoryTotal),
                            color = ExpenseColor,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        Text(
                            money(nonMandatoryTotal),
                            color = Color(0xFFF57C00),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        AverageExpenseCard(period, daily, monthly, expenseTotal)

        Text("Остатки по счетам", style = MaterialTheme.typography.titleLarge)
        val bs = balances(accounts, operations)
        accounts.forEach { a -> Text("${a.name}: ${money(bs[a.id] ?: 0.0)}") }
        Text("Остаток за период: ${money(incomeTotal - expenseTotal)}")
    }
}

@Composable
fun FinanceChart(type: String, income: Double, expense: Double) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (income <= 0.0 && expense <= 0.0) {
                Text("За выбранный период операций нет", style = MaterialTheme.typography.bodyMedium)
            } else if (type == "Круговая") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(190.dp)) {
                        val total = income + expense
                        val incomeAngle = if (total > 0) 360f * (income / total).toFloat() else 0f
                        drawArc(IncomeColor, -90f, incomeAngle, true)
                        drawArc(ExpenseColor, -90f + incomeAngle, 360f - incomeAngle, true)
                        drawCircle(Color.White, radius = size.minDimension * 0.25f)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Доходы", color = IncomeColor)
                        Text(money(income))
                        Spacer(Modifier.height(10.dp))
                        Text("Расходы", color = ExpenseColor)
                        Text(money(expense))
                    }
                }
            } else {
                Canvas(Modifier.fillMaxWidth().height(210.dp)) {
                    val maxValue = maxOf(income, expense, 1.0)
                    val chartHeight = size.height * 0.78f
                    val barWidth = size.width * 0.22f
                    val incomeHeight = chartHeight * (income / maxValue).toFloat()
                    val expenseHeight = chartHeight * (expense / maxValue).toFloat()
                    val baseY = size.height - 28.dp.toPx()
                    val incomeX = size.width * 0.28f
                    val expenseX = size.width * 0.62f
                    drawRect(IncomeColor, androidx.compose.ui.geometry.Offset(incomeX, baseY - incomeHeight), androidx.compose.ui.geometry.Size(barWidth, incomeHeight))
                    drawRect(ExpenseColor, androidx.compose.ui.geometry.Offset(expenseX, baseY - expenseHeight), androidx.compose.ui.geometry.Size(barWidth, expenseHeight))
                    drawLine(Color.Gray, androidx.compose.ui.geometry.Offset(size.width * 0.12f, baseY), androidx.compose.ui.geometry.Offset(size.width * 0.88f, baseY), strokeWidth = 2.dp.toPx())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Доходы", color = IncomeColor)
                        Text(money(income))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Расходы", color = ExpenseColor)
                        Text(money(expense))
                    }
                }
            }
        }
    }
}

@Composable
fun CompactStatCard(title:String, value:Double, bg:Color, fg:Color, modifier:Modifier = Modifier) {
    Card(
        modifier = modifier.padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(title, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(money(value), color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
fun StatCard(title:String,value:Double,bg:Color,fg:Color){
    Card(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), colors=CardDefaults.cardColors(containerColor=bg)){
        Column(Modifier.padding(16.dp)){Text(title,color=fg);Text(money(value),style=MaterialTheme.typography.titleLarge,color=fg)}
    }
}
@Composable fun AverageExpenseCard(period:String,daily:Double,monthly:Double,total:Double){
    val title="Средние расходы"
    val firstLabel=when(period){
        "Год" -> "В месяц"
        else -> "В день"
    }
    val firstValue=when(period){
        "Год" -> monthly
        else -> daily
    }
    val secondLabel=when(period){
        "Год" -> "За год"
        else -> "За период"
    }
    Card(Modifier.fillMaxWidth().padding(vertical=6.dp)){
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Text(title,style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(18.dp)){
                Column(horizontalAlignment=Alignment.End){
                    Text(firstLabel,style=MaterialTheme.typography.labelSmall)
                    Text(money(firstValue),color=ExpenseColor)
                }
                Column(horizontalAlignment=Alignment.End){
                    Text(secondLabel,style=MaterialTheme.typography.labelSmall)
                    Text(money(total),color=ExpenseColor)
                }
            }
        }
    }
}
@Composable fun ExpenseComparisonRow(label:String,day:Double,week:Double,month:Double){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text("День ${money(day)} / Нед ${money(week)} / Мес ${money(month)}",color=ExpenseColor)}}

@Composable fun MoreScreen(forecast:List<ForecastItem>,accounts:List<Account>,operations:List<Operation>,onPlannedToggle:(List<ForecastItem>,List<Operation>)->Unit,onDelete:(Long)->Unit,modifier:Modifier=Modifier){
    var editing by remember{mutableStateOf<ForecastItem?>(null)}
    var deleting by remember{mutableStateOf<ForecastItem?>(null)}
    var statusFilter by remember{mutableStateOf("Все")}
    val visibleForecast=forecast.filter{when(statusFilter){"Не выполнено"->!it.completed && !it.archived;"Архив"->it.archived;else->!it.archived}}.sortedBy{it.date}
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){
        Text("Ещё",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(8.dp));ForecastSummary(forecast,accounts)
        Text("Запланированные платежи",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("Все","Не выполнено","Архив").forEach{f->FilterChip(selected=statusFilter==f,onClick={statusFilter=f},label={Text(f)})}
        }
        visibleForecast.forEach{x->PlannedPaymentCard(x,{editing=x},{deleting=x},{enabled->
            val updatedForecast = forecast.map{if(it.id==x.id)it.copy(reminderEnabled=enabled) else it}
            onPlannedToggle(updatedForecast, operations)
        },{done->
            val actualOperationId = -x.id
            val updatedOperations = if(done){
                if(operations.any{it.id==actualOperationId}) operations
                else {
                    val accountId = if(x.accountId != 0L) x.accountId else accounts.firstOrNull()?.id ?: 0L
                    operations + Operation(actualOperationId,x.amount,x.name,"Запланированный платеж",x.income,accountId,!x.income&&x.mandatory,x.date)
                }
            } else operations.filter{it.id!=actualOperationId}
            val updatedForecast = if(done) {
                val archived = x.copy(completed = true, archived = true)
                if (x.recurrence != "Разово") {
                    val nextId = System.currentTimeMillis() + 1L
                    val nextItem = x.copy(id = nextId, date = nextForecastDate(x.date, x.recurrence), completed = false, archived = false, nextOccurrenceId = 0L)
                    forecast.map{if(it.id==x.id) archived.copy(nextOccurrenceId = nextId) else it} + nextItem
                } else forecast.map{if(it.id==x.id) archived else it}
            } else {
                val linkedNextId = x.nextOccurrenceId
                forecast.filter{it.id != linkedNextId}.map{if(it.id==x.id)x.copy(completed=false, archived=false, nextOccurrenceId=0L) else it}
            }
            onPlannedToggle(updatedForecast, updatedOperations)
        })}
        if(visibleForecast.isEmpty()) Text("Нет платежей в выбранном списке",modifier=Modifier.padding(top=12.dp))
    }
    editing?.let{x->PlannedPaymentDialog(existing=x,onDismiss={editing=null},onSave={updated->onPlannedToggle(forecast.map{if(it.id==x.id)updated else it},operations);editing=null})}
    deleting?.let{x->AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить запланированный платеж?")},text={Text(x.name)},confirmButton={TextButton({onDelete(x.id);deleting=null}){Text("Удалить",color=ExpenseColor)}},dismissButton={TextButton({deleting=null}){Text("Отмена")}})}
}
@Composable fun ForecastSummary(forecast:List<ForecastItem>,accounts:List<Account>){
    val t=startOfDay(System.currentTimeMillis())
    val end=t+30L*86400000
    // Учитываем все даты будущих платежей в ближайшие 30 дней,
    // включая ежемесячные и ежегодные повторения. Выполненные платежи
    // в будущий прогноз больше не входят.
    val occurrences=forecast.flatMap { item ->
        val result=mutableListOf<Pair<ForecastItem,Long>>()
        var month=monthStart(t)
        val lastMonth=monthStart(end)
        while(month<=lastMonth){
            forecastDatesInMonth(item,month).forEach { d ->
                if(d>=t && d<=end && !item.completed) result.add(item to d)
            }
            month=Calendar.getInstance().apply{timeInMillis=month;add(Calendar.MONTH,1)}.timeInMillis
        }
        result
    }
    val incomeItems=occurrences.filter{it.first.income}
    val expenseItems=occurrences.filter{!it.first.income}
    val incomeTotal=incomeItems.sumOf{it.first.amount}
    val expenseTotal=expenseItems.sumOf{it.first.amount}
    var detailsType by remember{mutableStateOf<Boolean?>(null)}

    Text("Прогноз на 30 дней",style=MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Card(
            Modifier.weight(1f).clickable{detailsType=true},
            colors=CardDefaults.cardColors(containerColor=IncomeContainer)
        ){
            Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)){
                Text("Будущие доходы",style=MaterialTheme.typography.labelMedium)
                Text(money(incomeTotal),color=IncomeColor,style=MaterialTheme.typography.titleMedium)
                Text("Нажмите для расчёта",style=MaterialTheme.typography.bodySmall)
            }
        }
        Card(
            Modifier.weight(1f).clickable{detailsType=false},
            colors=CardDefaults.cardColors(containerColor=ExpenseContainer)
        ){
            Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)){
                Text("Будущие расходы",style=MaterialTheme.typography.labelMedium)
                Text(money(expenseTotal),color=ExpenseColor,style=MaterialTheme.typography.titleMedium)
                Text("Нажмите для расчёта",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Прогнозируемый баланс: ${money(balances(accounts,emptyList()).values.sum()+incomeTotal-expenseTotal)}",style=MaterialTheme.typography.bodyMedium)

    detailsType?.let { isIncome ->
        val items=if(isIncome) incomeItems else expenseItems
        val total=if(isIncome) incomeTotal else expenseTotal
        AlertDialog(
            onDismissRequest={detailsType=null},
            title={Text(if(isIncome) "Расчёт будущих доходов" else "Расчёт будущих расходов")},
            text={
                Column(Modifier.verticalScroll(rememberScrollState())){
                    if(items.isEmpty()){
                        Text("Нет запланированных ${if(isIncome) "доходов" else "расходов"} в ближайшие 30 дней.")
                    }else{
                        items.sortedBy{it.second}.forEach { pair ->
                            val item=pair.first
                            val date=SimpleDateFormat("dd.MM.yyyy",Locale.getDefault()).format(Date(pair.second))
                            Text("$date — ${item.name}: ${money(item.amount)}",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=3.dp))
                        }
                        HorizontalDivider(Modifier.padding(vertical=6.dp))
                        Text("Итого: ${money(total)}",style=MaterialTheme.typography.titleMedium)
                        Text(
                            items.joinToString(" + "){money(it.first.amount)}+" = ${money(total)}",
                            style=MaterialTheme.typography.bodySmall,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                }
            },
            confirmButton={TextButton(onClick={detailsType=null}){Text("Закрыть")}}
        )
    }
}

val mandatoryCategories = linkedMapOf(
    "🏠 Дом и жильё" to listOf("Аренда","Коммунальные услуги","Электричество","Вода","Отопление","Интернет","Ремонт","Бытовая химия","Мебель и бытовая техника"),
    "🚗 Транспорт" to listOf("Бензин","Общественный транспорт","Такси","Парковка","Ремонт и обслуживание","Страховка","Автомойка"),
    "🐕 Животные" to listOf("Корм","Ветеринар","Лекарства","Уход","Игрушки и аксессуары")
)
val optionalCategories = linkedMapOf(
    "🏠 Дом и жильё" to listOf("Аренда","Коммунальные услуги","Электричество","Вода","Отопление","Интернет","Ремонт","Бытовая химия","Мебель и бытовая техника"),
    "🚗 Транспорт" to listOf("Бензин","Общественный транспорт","Такси","Парковка","Ремонт и обслуживание","Страховка","Автомойка"),
    "🐕 Животные" to listOf("Корм","Ветеринар","Лекарства","Уход","Игрушки и аксессуары"),
    "🛒 Продукты" to listOf("Супермаркет","Рынок","Доставка"),
    "🍽️ Кафе и рестораны" to listOf("Кафе","Ресторан","Доставка еды"),
    "🛍️ Покупки" to listOf("Одежда","Обувь","Электроника","Косметика","Прочее"),
    "💊 Здоровье" to listOf("Лекарства","Врач","Стоматология","Анализы"),
    "🎬 Развлечения" to listOf("Кино","Игры","Хобби","Мероприятия"),
    "📱 Связь и интернет" to listOf("Мобильная связь","Подписки","Онлайн-сервисы"),
    "✈️ Путешествия" to listOf("Билеты","Отель","Экскурсии","Прочее"),
    "🎓 Образование" to listOf("Курсы","Книги","Обучение"),
    "👨‍👩‍👧 Семья и дети" to listOf("Детские товары","Одежда","Обучение","Прочее"),
    "🎁 Подарки" to listOf("Подарки","Праздники"),
    "💳 Финансовые расходы" to listOf("Банковские комиссии","Обслуживание карт","Прочее"),
    "📦 Прочее" to listOf("Прочее")
)

@Composable fun CategorySelector(
    title:String,
    categories:Map<String,List<String>>,
    selectedCategory:String,
    selectedSubcategory:String,
    onCategorySelected:(String)->Unit,
    onSubcategorySelected:(String)->Unit
){
    var categoryMenu by remember{mutableStateOf(false)}
    var subcategoryMenu by remember{mutableStateOf(false)}
    val subs=categories[selectedCategory].orEmpty()
    Column{
        Box(Modifier.fillMaxWidth()){
            OutlinedButton({categoryMenu=true},Modifier.fillMaxWidth()){
                Text(if(selectedCategory.isBlank()) title else selectedCategory)
            }
            DropdownMenu(categoryMenu,{categoryMenu=false}){
                categories.keys.forEach{cat->
                    DropdownMenuItem(text={Text(cat)},onClick={onCategorySelected(cat);onSubcategorySelected("");categoryMenu=false})
                }
            }
        }
        if(selectedCategory.isNotBlank()){
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()){
                OutlinedButton({subcategoryMenu=true},Modifier.fillMaxWidth()){
                    Text(if(selectedSubcategory.isBlank()) "Подкатегория" else selectedSubcategory)
                }
                DropdownMenu(subcategoryMenu,{subcategoryMenu=false}){
                    subs.forEach{sub->
                        DropdownMenuItem(text={Text(sub)},onClick={onSubcategorySelected(sub);subcategoryMenu=false})
                    }
                }
            }
        }
    }
}

@Composable fun AddOperationDialog(accounts:List<Account>,initialIncome:Boolean=false,onDismiss:()->Unit,onSave:(Operation)->Unit){
    OperationEditorDialog(null,accounts,onDismiss,onSave,initialIncome)
}

@Composable fun EditOperationDialog(operation:Operation,accounts:List<Account>,onDismiss:()->Unit,onSave:(Operation)->Unit){
    OperationEditorDialog(operation,accounts,onDismiss,onSave,operation.income)
}

@Composable fun OperationEditorDialog(existing:Operation?,accounts:List<Account>,onDismiss:()->Unit,onSave:(Operation)->Unit,initialIncome:Boolean=false){
    val initialParts=existing?.category?.split(" — ",limit=2).orEmpty()
    var amount by remember{mutableStateOf(existing?.amount?.let{money(it)}?:"")}
    var category by remember{mutableStateOf(initialParts.firstOrNull().orEmpty())}
    var subcategory by remember{mutableStateOf(initialParts.getOrNull(1).orEmpty())}
    var comment by remember{mutableStateOf(existing?.comment?:"")}
    var income by remember{mutableStateOf(existing?.income?:initialIncome)}
    var mandatory by remember{mutableStateOf(existing?.mandatory?:false)}
    var accountId by remember{mutableStateOf(existing?.accountId?:accounts.firstOrNull()?.id?:0L)}
    var dateText by remember{mutableStateOf(existing?.date?.let{formatDate(it)}?:formatDate(System.currentTimeMillis()))}
    val categories=if(mandatory) mandatoryCategories else optionalCategories
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(if(existing==null)"Новая операция" else "Изменить операцию")},
        text={Column(Modifier.verticalScroll(rememberScrollState())){
            OutlinedTextField(amount,{amount=it},label={Text("Сумма")})
            Row{FilterChip(income,{income=true},label={Text("Доход")});Spacer(Modifier.width(8.dp));FilterChip(!income,{income=false},label={Text("Расход")})}
            if(!income){
                Row{FilterChip(mandatory,{mandatory=true;category="";subcategory=""},label={Text("Обязательный")});Spacer(Modifier.width(8.dp));FilterChip(!mandatory,{mandatory=false;category="";subcategory=""},label={Text("Необязательный")})}
                CategorySelector("Категория",categories,category,subcategory,{category=it;subcategory=""},{subcategory=it})
            }else{
                CategorySelector("Категория дохода",linkedMapOf("Зарплата" to emptyList(),"Пенсия" to emptyList(),"Проценты по вкладу" to emptyList(),"Подарок" to emptyList(),"Прочий доход" to emptyList()),category,subcategory,{category=it;subcategory=""},{subcategory=it})
            }
            OutlinedTextField(comment,{comment=it},label={Text("Комментарий")})
            AccountSelector("Счёт",accounts,accountId){accountId=it}
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(dateText,{dateText=it},label={Text("Дата операции")},placeholder={Text("дд.мм.гггг")},singleLine=true)
            Text("По умолчанию — сегодня. Можно указать прошедшую или будущую дату.",style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton({
            val v=amount.replace(',', '.').toDoubleOrNull()
            val finalCategory=if(subcategory.isBlank())category else "$category — $subcategory"
            val operationDate=parseDate(dateText)
            if(v!=null&&v>0&&finalCategory.isNotBlank()&&accountId!=0L&&operationDate!=null){
                onSave(Operation(existing?.id?:System.currentTimeMillis(),v,finalCategory,comment,income,accountId,!income&&mandatory,operationDate))
            }
        }){Text(if(existing==null)"Сохранить" else "Сохранить изменения")}},
        dismissButton={TextButton(onDismiss){Text("Отмена")}}
    )
}

@Composable fun AccountSelector(title:String,accounts:List<Account>,selectedId:Long,onSelected:(Long)->Unit){var ex by remember{mutableStateOf(false)};Box{OutlinedButton({ex=true},Modifier.fillMaxWidth()){Text("$title: ${accounts.firstOrNull{it.id==selectedId}?.name?:"Не выбран"}")};DropdownMenu(ex,{ex=false}){accounts.forEach{a->DropdownMenuItem({Text(a.name)},{onSelected(a.id);ex=false})}}}}
