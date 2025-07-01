package com.silentninja.chronoecho

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.silentninja.chronoecho.ui.theme.BirthdayEventTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit

val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val SORT_MODE_KEY = stringPreferencesKey("sort_mode")
val gson = Gson()

data class Event(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val date: Long,
    val isBirthday: Boolean,
    val color: Int,
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
    Color(0xFFB2EBF2), // Light Cyan
    Color(0xFFD7CCC8), // Soft Taupe
    Color(0xFFC8E6C9), // Muted Green
    Color(0xFFCFD8DC), // Blue Grey
    Color(0xFFFFD180), // Pale Orange
    Color(0xFFD1C4E9)  // Soft Lavender
)

fun formatEventTime(event: Event): Pair<String, String> {
    val now = LocalDate.now()
    val eventDate = Instant.ofEpochMilli(event.date).atZone(ZoneId.systemDefault()).toLocalDate()

    val daysUntil = getDaysUntilNextOccurrence(event.date, System.currentTimeMillis())

    // THE FIX IS HERE: Wrap the literal text 'on' in single quotes
    val secondaryFmtModern = DateTimeFormatter.ofPattern("'on' MMMM d", Locale.getDefault())

    if (event.isBirthday) {
        val age = ChronoUnit.YEARS.between(eventDate, now).toInt()
        val nextAge = age + 1

        val nextBirthday = eventDate.withYear(now.year)
        val finalNextBirthday = if (nextBirthday.isBefore(now)) nextBirthday.plusYears(1) else nextBirthday

        return when (daysUntil) {
            0 -> "Turns $nextAge today!" to eventDate.format(secondaryFmtModern)
            1 -> "Turns $nextAge tomorrow!" to eventDate.format(secondaryFmtModern)
            else -> "Turns $nextAge in $daysUntil days" to finalNextBirthday.format(secondaryFmtModern)
        }
    } else {
        return if (event.date > System.currentTimeMillis()) { // Future event
            when (daysUntil) {
                0 -> "Today" to eventDate.format(secondaryFmtModern)
                1 -> "Tomorrow" to eventDate.format(secondaryFmtModern)
                else -> "In $daysUntil days" to eventDate.format(secondaryFmtModern)
            }
        } else { // Past event
            val years = ChronoUnit.YEARS.between(eventDate, now)
            val months = ChronoUnit.MONTHS.between(eventDate.plusYears(years), now)
            val days = ChronoUnit.DAYS.between(eventDate.plusYears(years).plusMonths(months), now)

            val primaryText = when {
                years > 0 -> "$years ${if (years == 1L) "year" else "years"}, $months ${if (months == 1L) "month" else "months"} ago"
                months > 0 -> "$months ${if (months == 1L) "month" else "months"}, $days ${if (days == 1L) "day" else "days"} ago"
                days > 0 -> "$days ${if (days == 1L) "day" else "days"} ago"
                else -> "Earlier today"
            }
            // Using the old SimpleDateFormat here for the full date is fine and not causing the crash
            primaryText to SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(event.date))
        }
    }
}

suspend fun saveEvents(context: Context, events: List<Event>) {
    val json = gson.toJson(events)
    context.dataStore.edit { prefs ->
        prefs[EVENTS_KEY] = json
    }
}

suspend fun loadEvents(context: Context): List<Event> {
    return try {
        val prefs = context.dataStore.data.first()
        val json = prefs[EVENTS_KEY] ?: "[]"
        val type = object : TypeToken<List<Event>>() {}.type
        gson.fromJson(json, type)
    } catch (e: Exception) {
        Log.e("loadEvents", "Error loading events: ${e.message}")
        emptyList()
    }
}

suspend fun saveSortMode(context: Context, mode: SortMode) {
    context.dataStore.edit { prefs ->
        prefs[SORT_MODE_KEY] = mode.name
    }
}

suspend fun loadSortMode(context: Context): SortMode {
    return try {
        val prefs = context.dataStore.data.first()
        val sortModeName = prefs[SORT_MODE_KEY] ?: SortMode.Custom.name
        SortMode.valueOf(sortModeName)
    } catch (e: Exception) {
        Log.e("loadSortMode", "Error loading sort mode, defaulting to Custom. ${e.message}")
        SortMode.Custom
    }
}

class EventNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val eventName = inputData.getString("event_name") ?: return Result.failure()
        val eventIdHash = inputData.getString("event_id")?.hashCode() ?: eventName.hashCode()
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
        notificationManager.notify(eventIdHash, notification)
        return Result.success()
    }
}

fun scheduleEventNotification(context: Context, event: Event) {
    WorkManager.getInstance(context).cancelUniqueWork(event.id)
    val oneDayBefore = event.date - TimeUnit.DAYS.toMillis(1)
    val delay = oneDayBefore - System.currentTimeMillis()
    if (delay > 0) {
        val work = OneTimeWorkRequestBuilder<EventNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("event_name" to event.name, "event_id" to event.id))
            .addTag("event_notification_${event.id}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            event.id,
            ExistingWorkPolicy.REPLACE,
            work
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            BirthdayEventTheme {
                AppWithSplashScreen()
            }
        }
    }
}

@Composable
fun AppWithSplashScreen() {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1800)
        showSplash = false
    }
    if (showSplash) {
        SplashScreen()
    } else {
        BirthdayEventApp()
    }
}

@Composable
fun SplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "splashTransition")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "splashProgress"
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
                    Icons.Default.CalendarMonth,
                    contentDescription = "Calendar",
                    modifier = Modifier.size(80.dp),
                    tint = color
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "ChronoEcho",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
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


enum class SortMode { Closest, Farthest, Most_Recent, Custom }

fun sortEvents(events: List<Event>, mode: SortMode): List<Event> {
    val now = System.currentTimeMillis()
    return when (mode) {
        SortMode.Closest -> events.sortedBy { getDaysUntilNextOccurrence(it.date, now) }
        SortMode.Farthest -> events.sortedByDescending { getDaysUntilNextOccurrence(it.date, now) }
        SortMode.Most_Recent -> events.sortedByDescending { it.date }
        SortMode.Custom -> events
    }
}

@Composable
fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "🎈",
            fontSize = 64.sp,
            modifier = Modifier.graphicsLayer { rotationZ = 5f }
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No events yet!",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap + to add your first birthday or event.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BirthdayEventApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Start with a null state to represent "loading"
    var events by remember { mutableStateOf<List<Event>?>(null) }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<Event?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeletedEvent by remember { mutableStateOf<Pair<Event, Int>?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.Custom) }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var eventToDelete by remember { mutableStateOf<Event?>(null) }

    // Load events and sort mode on startup. When done, `events` will no longer be null.
    LaunchedEffect(Unit) {
        events = loadEvents(context)
        sortMode = loadSortMode(context)
    }

    // Save sort mode whenever it changes
    LaunchedEffect(sortMode) {
        saveSortMode(context, sortMode)
    }

    // This local variable is only calculated when events is not null
    val currentEvents = events

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            if (sortMode == SortMode.Custom && currentEvents != null) {
                val reorderedEvents = currentEvents.toMutableList().apply { add(to.index, removeAt(from.index)) }
                events = reorderedEvents
                scope.launch { saveEvents(context, reorderedEvents) }
            }
        }
    )

    val filteredEvents = remember(currentEvents, sortMode) {
        // Only sort if the list is not null
        currentEvents?.let { sortEvents(it, sortMode) } ?: emptyList()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ChronoEcho", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    SortMenuButton(
                        currentMode = sortMode,
                        onModeSelected = { sortMode = it }
                    )
                }
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = {
                    eventToEdit = null
                    showAddEditDialog = true
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Event",
                    modifier = Modifier.size(36.dp)
                )
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(
                            onClick = { data.performAction() }
                        ) {
                            Text(
                                "UNDO",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showAddEditDialog) {
                AddEditEventDialog(
                    initialEvent = eventToEdit,
                    onDismiss = { showAddEditDialog = false },
                    onSave = { eventToSave ->
                        if (currentEvents != null) {
                            val newEvents = if (currentEvents.any { it.id == eventToSave.id }) {
                                currentEvents.map { if (it.id == eventToSave.id) eventToSave else it }
                            } else {
                                currentEvents + eventToSave
                            }
                            events = newEvents
                            scope.launch { saveEvents(context, newEvents) }
                            scheduleEventNotification(context, eventToSave)
                        }
                        showAddEditDialog = false
                    },
                    onDelete = {
                        showAddEditDialog = false
                        showDeleteConfirmation = true
                        eventToDelete = eventToEdit
                    }
                )
            }

            if (showDeleteConfirmation) {
                DeletionConfirmationDialog(
                    eventName = eventToDelete?.name ?: "this event",
                    onConfirm = {
                        if (currentEvents != null && eventToDelete != null) {
                            val event = eventToDelete!!
                            val eventIndex = currentEvents.indexOf(event)
                            recentlyDeletedEvent = Pair(event, eventIndex)

                            val newEvents = currentEvents - event
                            events = newEvents
                            scope.launch { saveEvents(context, newEvents) }
                            WorkManager.getInstance(context).cancelUniqueWork(event.id)

                            scope.launch {
                                val result = withTimeoutOrNull(5000L) {
                                    snackbarHostState.showSnackbar(
                                        message = "Event deleted", actionLabel = "Undo", duration = SnackbarDuration.Indefinite
                                    )
                                }
                                if (result == SnackbarResult.ActionPerformed) {
                                    recentlyDeletedEvent?.let { (deletedEvent, index) ->
                                        // This check is important inside a coroutine
                                        val eventsNow = events
                                        if (eventsNow != null) {
                                            val restoredEvents = eventsNow.toMutableList().apply { add(index, deletedEvent) }
                                            events = restoredEvents
                                            scope.launch { saveEvents(context, restoredEvents) }
                                            scheduleEventNotification(context, deletedEvent)
                                            recentlyDeletedEvent = null
                                        }
                                    }
                                }
                            }
                        }
                        showDeleteConfirmation = false
                        eventToDelete = null
                    },
                    onDismiss = {
                        showDeleteConfirmation = false
                        eventToDelete = null
                    }
                )
            }

            // Handle all three states: loading, empty, and has-data
            when {
                // State 1: Loading
                currentEvents == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                // State 2: Loaded but empty
                currentEvents.isEmpty() -> {
                    EmptyState()
                }
                // State 3: Loaded and has data
                else -> {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (sortMode == SortMode.Custom) Modifier.reorderable(reorderState)
                                else Modifier
                            ),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(filteredEvents, key = { it.id }) { event ->
                            ReorderableItem(reorderState, key = event.id) { isDragging ->
                                SwipeableEventCard(
                                    event = event,
                                    isDragging = isDragging,
                                    onEdit = {
                                        eventToEdit = event
                                        showAddEditDialog = true
                                    },
                                    onDeleteRequest = {
                                        eventToDelete = event
                                        showDeleteConfirmation = true
                                    },
                                    dragHandle = {
                                        if (sortMode == SortMode.Custom) {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Drag to reorder",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier.detectReorder(reorderState)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SortMenuButton(currentMode: SortMode, onModeSelected: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort")
            Spacer(Modifier.width(4.dp))
            Text(currentMode.name.replace("_", " "))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SortMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.replace("_", " ")) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            when (mode) {
                                SortMode.Closest -> Icons.Default.ArrowUpward
                                SortMode.Farthest -> Icons.Default.ArrowDownward
                                SortMode.Most_Recent -> Icons.Default.Update
                                SortMode.Custom -> Icons.Default.DragHandle
                            },
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun EventCard(
    event: Event,
    modifier: Modifier = Modifier,
    onCardClick: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
    isDragging: Boolean = false
) {
    val cardColor = Color(event.color)
    val elevation by animateDpAsState(if (isDragging) 12.dp else 4.dp, label = "elevation_anim")
    val scale by animateFloatAsState(if (isDragging) 1.05f else 1f, label = "scale_anim")
    val icon = iconMap[event.icon] ?: Icons.Default.Cake

    val (primaryText, secondaryText) = formatEventTime(event)

    val cardModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(if (isDragging) Modifier.zIndex(1f) else Modifier.zIndex(0f))
        .let { if (onCardClick != null) it.clickable { onCardClick() } else it }

    ElevatedCard(
        modifier = cardModifier,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        event.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (event.isBirthday) "Birthday" else "Event",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                dragHandle?.let {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { it() }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditEventDialog(
    initialEvent: Event?,
    onDismiss: () -> Unit,
    onSave: (Event) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initialEvent?.name ?: "") }
    var isBirthday by remember { mutableStateOf(initialEvent?.isBirthday ?: true) }
    var dateMillis by remember { mutableStateOf(initialEvent?.date ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(initialEvent?.color?.let { Color(it) } ?: colorOptions.first()) }
    var selectedIcon by remember { mutableStateOf(initialEvent?.icon ?: iconNames.first()) }
    val context = LocalContext.current

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEvent == null) "Add New Event" else "Edit Event") },
        text = {
            LazyColumn {
                item {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Event Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Type:", style = MaterialTheme.typography.bodyLarge)
                            Row {
                                FilterChip(selected = isBirthday, onClick = { isBirthday = true }, label = { Text("Birthday") })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected = !isBirthday, onClick = { isBirthday = false }, label = { Text("Event") })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(dateMillis)))
                        }
                        Spacer(Modifier.height(16.dp))
                        ColorIconPicker(
                            selectedColor = selectedColor,
                            onColorSelected = { selectedColor = it },
                            selectedIcon = selectedIcon,
                            onIconSelected = { selectedIcon = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(Event(
                            id = initialEvent?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            date = dateMillis,
                            isBirthday = isBirthday,
                            color = selectedColor.toArgb(),
                            icon = selectedIcon
                        ))
                    } else {
                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initialEvent != null && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { dateMillis = it }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }
}

@Composable
fun ColorIconPicker(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    selectedIcon: String,
    onIconSelected: (String) -> Unit
) {
    Column {
        Text("Color", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            colorOptions.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color, CircleShape)
                        .border(
                            width = if (color == selectedColor) 2.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Icon", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            iconNames.forEach { iconName ->
                IconButton(
                    onClick = { onIconSelected(iconName) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (iconName == selectedIcon) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        iconMap[iconName] ?: Icons.Default.Event,
                        contentDescription = iconName,
                        tint = if (iconName == selectedIcon) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun getDaysUntilNextOccurrence(eventDate: Long, now: Long): Int {
    val eventCal = Calendar.getInstance().apply { timeInMillis = eventDate }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    nowCal.set(Calendar.HOUR_OF_DAY, 0)
    nowCal.set(Calendar.MINUTE, 0)
    nowCal.set(Calendar.SECOND, 0)
    nowCal.set(Calendar.MILLISECOND, 0)

    eventCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR))
    if (eventCal.timeInMillis < nowCal.timeInMillis) {
        eventCal.add(Calendar.YEAR, 1)
    }

    val diffMillis = eventCal.timeInMillis - nowCal.timeInMillis
    return TimeUnit.MILLISECONDS.toDays(diffMillis).toInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEventCard(
    event: Event,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
    isDragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onEdit()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteRequest()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * .25f }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> Color.Transparent
                }, label = "swipeBackgroundColor"
            )
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterStart
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
                else -> null
            }
            val iconColor = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> Color.Transparent
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(color, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = "Swipe action",
                        tint = iconColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    ) {
        EventCard(
            event = event,
            dragHandle = dragHandle,
            isDragging = isDragging
        )
    }
}

@Composable
fun DeletionConfirmationDialog(
    eventName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text = { Text("Are you sure you want to permanently delete \"$eventName\"?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}