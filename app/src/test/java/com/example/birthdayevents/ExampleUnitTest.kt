package com.example.birthdayevents

import android.app.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.work.*
import android.Manifest
import org.burnoutcrew.reorderable.*

val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val gson = Gson()

data class Event(
    val name: String,
    val date: Long,
    val isBirthday: Boolean,
    val color: Long,
    val icon: String
)

val iconMap = mapOf(
    "Cake" to Icons.Default.Cake,
    "Star" to Icons.Default.Star,
    "Favorite" to Icons.Default.Favorite,
    "Event" to Icons.Default.Event
)
val iconNames = iconMap.keys.toList()

val colorOptions = listOf(
    Color(0xFFFFF59D), Color(0xFFB2FF59), Color(0xFF81D4FA),
    Color(0xFFFFAB91), Color(0xFFE1BEE7), Color(0xFFFFCDD2)
)

fun saveEvents(context: Context, events: List<Event>) {
    val json = gson.toJson(events)
    runBlocking {
        context.dataStore.edit { prefs ->
            prefs[EVENTS_KEY] = json
        }
    }
}

fun loadEvents(context: Context): List<Event> {
    return runBlocking {
        val prefs = context.dataStore.data.first()
        val json = prefs[EVENTS_KEY] ?: "[]"
        val type = object : TypeToken<List<Event>>() {}.type
        gson.fromJson(json, type)
    }
}

class EventNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val eventName = inputData.getString("event_name") ?: return Result.failure()
        val context = applicationContext
        val channelId = "event_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Events", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Event Reminder")
            .setContentText("Don't forget: $eventName is tomorrow!")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        notificationManager.notify(eventName.hashCode(), notification)
        return Result.success()
    }
}

fun scheduleEventNotification(context: Context, event: Event) {
    val oneDayBefore = event.date - TimeUnit.DAYS.toMillis(1)
    val delay = oneDayBefore - System.currentTimeMillis()
    if (delay > 0) {
        val work = OneTimeWorkRequestBuilder<EventNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("event_name" to event.name))
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}

@Composable
fun BirthdayEventTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        typography = Typography(),
        content = content
    )
}

@Composable
fun SplashScreen() {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val color = lerp(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        progress
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CalendarMonth, // Calendar icon
                    contentDescription = "Calendar",
                    modifier = Modifier.size(80.dp).scale(scale),
                    tint = color
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "ChronoEcho",
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    color = color
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Loading...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent { BirthdayEventTheme { AppWithSplashScreen() } }
    }
}

enum class SortMode(val displayName: String) {
    Closest("Closest"),
    Farthest("Farthest"),
    MostRecent("Most Recent"),
    Custom("Custom")
}

fun sortEvents(events: List<Event>, mode: SortMode): List<Event> {
    return when (mode) {
        SortMode.Closest -> events.sortedBy { it.date }
        SortMode.Farthest -> events.sortedByDescending { it.date }
        SortMode.MostRecent -> events.sortedByDescending { it.date }
        SortMode.Custom -> events
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayEventApp() {
    val context = LocalContext.current
    var events by remember { mutableStateOf(loadEvents(context)) }
    var showDialog by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<Event?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeletedEvent by remember { mutableStateOf<Event?>(null) }
    val scope = rememberCoroutineScope()
    var sortMode by remember { mutableStateOf(SortMode.Custom) }

    // Save events on change
    LaunchedEffect(events) { saveEvents(context, events) }

    // Filtered and sorted events
    val filteredEvents = sortEvents(
        events.filter { it.name.contains(searchQuery, ignoreCase = true) },
        sortMode
    )

    val reorderState = rememberReorderableLazyListState(onMove = { from, to ->
        if (sortMode == SortMode.Custom) {
            events = events.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
    })

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ChronoEcho", style = MaterialTheme.typography.headlineLarge) },
                actions = {
                    SortMenu(sortMode, onSortChange = { sortMode = it })
                }
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = {
                    editEvent = null
                    showDialog = true
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Event",
                    modifier = Modifier.size(36.dp) // Larger plus icon
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
            if (filteredEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎈", fontSize = 64.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No events yet!", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap + to add your first birthday or event.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                if (sortMode == SortMode.Custom) {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier.reorderable(reorderState)
                    ) {
                        items(filteredEvents, key = { it.name + it.date }) { event ->
                            ReorderableItem(reorderState, key = event.name + event.date) { isDragging ->
                                EventCard(
                                    event = event,
                                    onEdit = {
                                        editEvent = event
                                        showDialog = true
                                    },
                                    onDelete = {
                                        events = events - event
                                        recentlyDeletedEvent = event
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Event deleted",
                                                actionLabel = "Undo"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                events = events + recentlyDeletedEvent!!
                                                recentlyDeletedEvent = null
                                            }
                                        }
                                    },
                                    dragHandle = {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = "Drag",
                                            modifier = Modifier
                                                .detectReorder(reorderState)
                                                .size(32.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    isDragging = isDragging
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn {
                        items(filteredEvents, key = { it.name + it.date }) { event ->
                            EventCard(
                                event = event,
                                onEdit = {
                                    editEvent = event
                                    showDialog = true
                                },
                                onDelete = {
                                    events = events - event
                                    recentlyDeletedEvent = event
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Event deleted",
                                            actionLabel = "Undo"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            events = events + recentlyDeletedEvent!!
                                            recentlyDeletedEvent = null
                                        }
                                    }
                                },
                                dragHandle = null,
                                isDragging = false
                            )
                        }
                    }
                }
            }
        }
        if (showDialog) {
            AddEditEventDialog(
                initialEvent = editEvent,
                onDismiss = { showDialog = false },
                onSave = { event ->
                    if (editEvent != null) {
                        events = events.map { if (it == editEvent) event else it }
                    } else {
                        events = events + event
                        scheduleEventNotification(context, event)
                    }
                    showDialog = false
                },
                onDelete = {
                    if (editEvent != null) {
                        events = events - editEvent!!
                        showDialog = false
                    }
                }
            )
        }
    }
}

@Composable
fun SortMenu(sortMode: SortMode, onSortChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }) {
            Text("Sort: ${sortMode.displayName}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
                        onSortChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search events...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    )
}

fun getYearsAndDays(from: Long, to: Long): Pair<Int, Int> {
    val fromCal = Calendar.getInstance().apply { timeInMillis = from }
    val toCal = Calendar.getInstance().apply { timeInMillis = to }
    var years = toCal.get(Calendar.YEAR) - fromCal.get(Calendar.YEAR)
    var days = toCal.get(Calendar.DAY_OF_YEAR) - fromCal.get(Calendar.DAY_OF_YEAR)
    if (days < 0) {
        years -= 1
        fromCal.set(Calendar.YEAR, fromCal.get(Calendar.YEAR) + years)
        days = ((toCal.timeInMillis - fromCal.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
    }
    return years to days
}

@Composable
fun EventCard(
    event: Event,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: (@Composable () -> Unit)? = null,
    isDragging: Boolean = false
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val dateStr = sdf.format(Date(event.date))
    val icon = iconMap[event.icon] ?: Icons.Default.Cake
    val cardColor = Color(event.color.toInt())
    val now = System.currentTimeMillis()
    val (years, days) = if (event.isBirthday) getYearsAndDays(event.date, now) else getYearsAndDays(event.date, now)
    val sinceOrAge = if (event.isBirthday) {
        "Age: $years years, $days days"
    } else {
        "Since: $years years, $days days"
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(if (isDragging) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .animateItemPlacement(),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dragHandle != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(32.dp)
                        .then(Modifier), // dragHandle will add detectReorder
                    contentAlignment = Alignment.Center
                ) {
                    dragHandle()
                }
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Date: $dateStr",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (event.isBirthday) "🎂 Birthday" else "⭐ Event",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    sinceOrAge,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, // Use delete icon for clarity
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun AddEditEventDialog(
    initialEvent: Event?,
    onDismiss: () -> Unit,
    onSave: (Event) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(TextFieldValue(initialEvent?.name ?: "")) }
    var isBirthday by remember { mutableStateOf(initialEvent?.isBirthday ?: true) }
    var dateMillis by remember { mutableStateOf(initialEvent?.date ?: System.currentTimeMillis()) }
    var selectedColor by remember { mutableStateOf(initialEvent?.color?.let { Color(it.toInt()) } ?: colorOptions.first()) }
    var selectedIcon by remember { mutableStateOf(initialEvent?.icon ?: iconNames.first()) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (initialEvent == null) "Add Event" else "Edit Event", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = isBirthday,
                            onClick = { isBirthday = true }
                        )
                        Text("Birthday", modifier = Modifier.padding(start = 4.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = !isBirthday,
                            onClick = { isBirthday = false }
                        )
                        Text("Event", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                FilledTonalButton(onClick = {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = dateMillis
                    DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            cal.set(y, m, d)
                            dateMillis = cal.timeInMillis
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }) {
                    Text("Pick Date: ${sdf.format(Date(dateMillis))}")
                }
                Spacer(Modifier.height(12.dp))
                ColorIconPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    selectedIcon = selectedIcon,
                    onIconSelected = { selectedIcon = it }
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (name.text.isNotBlank()) {
                        onSave(
                            Event(
                                name.text,
                                dateMillis,
                                isBirthday,
                                selectedColor.toArgb().toLong(),
                                selectedIcon
                            )
                        )
                    } else {
                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initialEvent != null && onDelete != null) {
                    FilledTonalButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete", color = MaterialTheme.colorScheme.onError) }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun ColorIconPicker(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    selectedIcon: String,
    onIconSelected: (String) -> Unit
) {
    Column {
        Text("Pick a color:")
        Row {
            colorOptions.forEach { color ->
                Box(
                    Modifier
                        .size(32.dp)
                        .padding(4.dp)
                        .background(color, CircleShape)
                        .border(
                            width = if (color == selectedColor) 2.dp else 0.dp,
                            color = if (color == selectedColor) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Pick an icon:")
        Row {
            iconNames.forEach { iconName ->
                val icon = iconMap[iconName] ?: Icons.Default.Cake
                Box(
                    Modifier
                        .size(40.dp)
                        .padding(4.dp)
                        .background(
                            if (iconName == selectedIcon) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
                            CircleShape
                        )
                        .clickable { onIconSelected(iconName) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = iconName, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}