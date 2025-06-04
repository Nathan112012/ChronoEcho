package com.example.birthdayevents

import androidx.compose.foundation.isSystemInDarkTheme
import android.app.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// DataStore
val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val gson = Gson()

// Event Data
data class Event(
    val name: String,
    val date: Long,
    val isBirthday: Boolean,
    val color: Long,
    val icon: String
)

// Icon mapping
val iconMap = mapOf(
    "Cake" to Icons.Default.Cake,
    "Star" to Icons.Default.Star,
    "Favorite" to Icons.Default.Favorite,
    "Event" to Icons.Default.Event
)
val iconNames = iconMap.keys.toList()

// Color options
val colorOptions = listOf(
    Color(0xFFFFF59D), Color(0xFFB2FF59), Color(0xFF81D4FA),
    Color(0xFFFFAB91), Color(0xFFE1BEE7), Color(0xFFFFCDD2)
)

// Save/load events
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

// Notification Worker
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

// Main Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirthdayEventTheme { BirthdayEventApp() } }
    }
}

// Material3 Theme
@Composable
fun BirthdayEventTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        typography = Typography(),
        content = content
    )
}

// Main App
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

    // Save events on change
    LaunchedEffect(events) { saveEvents(context, events) }

    // Filtered events
    val filteredEvents = events.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ChronoEcho by Nathan the Best") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editEvent = null
                showDialog = true
            }) {
                Icon(Icons.Default.Cake, contentDescription = "Add Event")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
            if (filteredEvents.isEmpty()) {
                // Empty state
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Cake, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                        Text("No events yet!", style = MaterialTheme.typography.titleLarge)
                        Text("Tap + to add your first birthday or event.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn {
                    items(filteredEvents) { event ->
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
                            }
                        )
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

// Search Bar
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

// Event Card
@Composable
fun EventCard(
    event: Event,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val dateStr = sdf.format(Date(event.date))
    val icon = iconMap[event.icon] ?: Icons.Default.Cake
    val cardColor = Color(event.color)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.name, style = MaterialTheme.typography.titleMedium)
                Text("Date: $dateStr", style = MaterialTheme.typography.bodyMedium)
                Text(if (event.isBirthday) "Birthday" else "Event", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Favorite, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// Add/Edit Event Dialog
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
    var selectedColor by remember { mutableStateOf(initialEvent?.color?.let { Color(it) } ?: colorOptions.first()) }
    var selectedIcon by remember { mutableStateOf(initialEvent?.icon ?: iconNames.first()) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEvent == null) "Add Event" else "Edit Event") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Type: ")
                    Spacer(Modifier.width(8.dp))
                    Row {
                        RadioButton(
                            selected = isBirthday,
                            onClick = { isBirthday = true }
                        )
                        Text("Birthday")
                        Spacer(Modifier.width(8.dp))
                        RadioButton(
                            selected = !isBirthday,
                            onClick = { isBirthday = false }
                        )
                        Text("Event")
                    }
                }
                Spacer(Modifier.height(8.dp))
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                Button(onClick = {
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
                Spacer(Modifier.height(8.dp))
                ColorIconPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    selectedIcon = selectedIcon,
                    onIconSelected = { selectedIcon = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.text.isNotBlank()) {
                        onSave(
                            Event(
                                name.text,
                                dateMillis,
                                isBirthday,
                                selectedColor.value.toLong(),
                                selectedIcon
                            )
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initialEvent != null && onDelete != null) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete", color = MaterialTheme.colorScheme.onError) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// Color & Icon Picker
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