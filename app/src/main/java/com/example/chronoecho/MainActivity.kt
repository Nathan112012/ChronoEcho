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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit // ADDED: Import for date calculations
import java.util.*
import java.util.concurrent.TimeUnit

// THEME DEFINITIONS START HERE

private val PolishedDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FCAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858), // A muted blue, used for the 'edit' swipe action.
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD8BDE0),
    onTertiary = Color(0xFF3B2947),
    tertiaryContainer = Color(0xFF533F5F),
    onTertiaryContainer = Color(0xFFF4D9FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF26264D), // A softer dark gray background.
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1414), // Surfaces match the background.
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E), // Used for dialogs and other components.
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C9199)
)

@Composable
fun ChronoEchoTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PolishedDarkColorScheme,
        typography = Typography(),
        content = content
    )
}

// THEME DEFINITIONS END HERE

val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val SORT_MODE_KEY = stringPreferencesKey("sort_mode")
val gson = Gson()

val eventColorOptions = listOf(
    Color(0xFFFFF59D), // Yellow
    Color(0xFFB2FF59), // Green
    Color(0xFF81D4FA), // Blue
    Color(0xFFFFAB91), // Orange
    Color(0xFFE1BEE7), // Purple
    Color(0xFFFFCDD2)  // Pink
)

data class Event(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val date: Long,
    val icon: String,
    val color: Long
)

val iconMap = mapOf(
    "Cake" to Icons.Default.Cake,
    "Event" to Icons.Default.Event,
    "Star" to Icons.Default.Star,
    "Favorite" to Icons.Default.Favorite,
    "School" to Icons.Default.School,
    "Work" to Icons.Default.Work,
    "Sports" to Icons.Default.SportsSoccer,
    "Music" to Icons.Default.MusicNote,
    "Travel" to Icons.Default.Flight,
    "Party" to Icons.Default.Celebration,
    "Heart" to Icons.Default.FavoriteBorder,
    "Book" to Icons.Default.MenuBook,
    "Camera" to Icons.Default.CameraAlt,
    "Shopping" to Icons.Default.ShoppingCart,
    "Gift" to Icons.Default.CardGiftcard,
    "Pet" to Icons.Default.Pets,
    "Movie" to Icons.Default.Movie,
    "Food" to Icons.Default.Restaurant,
    "Car" to Icons.Default.DirectionsCar,
    "Home" to Icons.Default.Home,
    "Meeting" to Icons.Default.People,
    "Calendar" to Icons.Default.CalendarToday
)
val iconNames = iconMap.keys.toList()

// ADDED: This is the function you were missing.
// It calculates the number of days from today until the next anniversary of a given date.
fun getDaysUntilNextOccurrence(eventDateMillis: Long): Long {
    val today = LocalDate.now()
    val originalEventDate = Instant.ofEpochMilli(eventDateMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    // Determine the date of the next occurrence. Start by assuming it's this year.
    var nextOccurrence = LocalDate.of(today.year, originalEventDate.month, originalEventDate.dayOfMonth)

    // If that date is in the past, the next occurrence is next year.
    if (nextOccurrence.isBefore(today)) {
        nextOccurrence = nextOccurrence.plusYears(1)
    }

    // Calculate the number of days between today and the next occurrence.
    return ChronoUnit.DAYS.between(today, nextOccurrence)
}


fun formatEventDetailText(event: Event): Pair<String, String?> {
    val now = LocalDate.now()
    val eventDate = Instant.ofEpochMilli(event.date).atZone(ZoneId.systemDefault()).toLocalDate()
    val period = Period.between(eventDate, now)
    val years = period.years
    val months = period.months
    val days = period.days

    val ageString = buildString {
        if (years > 0) append("$years ${if (years == 1) "year" else "years"}, ")
        if (months > 0) append("$months ${if (months == 1) "month" else "months"}, ")
        append("$days ${if (days == 1) "day" else "days"}")
    }

    // FIXED: Corrected the function call here.
    val daysUntil = getDaysUntilNextOccurrence(event.date)
    val closeMsg = when (daysUntil) {
        0L -> "Today!"
        1L -> "Tomorrow"
        in 2L..7L -> "In $daysUntil days"
        else -> null
    }
    return Pair(ageString, closeMsg)
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

// MODIFIED: The worker is now more powerful and funnier.
class EventNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val eventName = inputData.getString("event_name") ?: return Result.failure()
        val eventId = inputData.getString("event_id") ?: return Result.failure()
        val daysOut = inputData.getInt("days_out", -1)

        val prefix = "Nathan the Best here to say....\n"
        val funnyMessage = when (daysOut) {
            7 -> "I've peered into the future, and it looks like ${eventName}'s big day is exactly one week away! Start the party planning... or the panic."
            6 -> "My supercomputer (it's a calculator with a fancy sticker) predicts a 100% chance of cake in 6 days for ${eventName}. Prepare your fork."
            5 -> "Psst! Just 5 days until you have to act surprised and delighted for ${eventName}'s event. You can thank me for the heads-up later."
            4 -> "ALERT! We're at DEFCON 4 for ${eventName}'s celebration. That means 'Definitely Find a Cool Gift Soon'. Only 4 days left!"
            3 -> "If you listen closely, you can hear the faint sound of party poppers. That's because ${eventName}'s event is in just 3 days!"
            2 -> "It's the final countdown! (do-do-do-dooo) Less than 48 hours until we celebrate ${eventName}! I hope you've practiced your 'Happy Birthday' singing."
            1 -> "THIS IS NOT A DRILL! The day we've been training for is TOMORROW. Get ready to celebrate ${eventName} in style!"
            else -> "Looks like ${eventName}'s event is coming up soon! Better get ready!"
        }

        val contentText = prefix + funnyMessage
        // Create a unique ID for each notification to prevent them from overwriting each other
        val notificationId = (eventId + daysOut).hashCode()

        val context = applicationContext
        val channelId = "event_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Events", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Upcoming Event: $eventName")
            .setContentText(contentText)
            // Use BigTextStyle to make sure the full funny message is visible
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure you have a valid drawable
            .build()

        notificationManager.notify(notificationId, notification)
        return Result.success()
    }
}

// MODIFIED: This function now schedules a whole week of notifications.
fun scheduleEventNotification(context: Context, event: Event) {
    val workManager = WorkManager.getInstance(context)
    // First, cancel all previously scheduled notifications for this event using its ID as a tag.
    // This is important if the user edits the event date.
    workManager.cancelAllWorkByTag(event.id)

    val nowCal = Calendar.getInstance()
    val eventCal = Calendar.getInstance().apply { timeInMillis = event.date }
    eventCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR))

    // If the event anniversary for this year has already passed, schedule for next year.
    if (eventCal.before(nowCal)) {
        eventCal.add(Calendar.YEAR, 1)
    }

    // Loop to schedule a notification for each day of the week leading up to the event.
    for (daysOut in 1..7) {
        val notificationTime = eventCal.timeInMillis - TimeUnit.DAYS.toMillis(daysOut.toLong())
        val delay = notificationTime - System.currentTimeMillis()

        if (delay > 0) {
            val inputData = workDataOf(
                "event_name" to event.name,
                "event_id" to event.id,
                "days_out" to daysOut // Pass the days_out value to the worker
            )

            val workRequest = OneTimeWorkRequestBuilder<EventNotificationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .addTag(event.id) // Tag the work with the event ID for easy cancellation.
                .build()

            // Enqueue each notification with a unique name to avoid conflicts.
            workManager.enqueueUniqueWork(
                "${event.id}_${daysOut}_days_out",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            ChronoEchoTheme {
                BirthdayEventApp()
            }
        }
    }
}


enum class SortMode { Closest, Farthest, Chronological, Custom }

fun sortEvents(events: List<Event>, mode: SortMode): List<Event> {
    // The `now` variable is no longer needed here, but we can leave it.
    val now = System.currentTimeMillis()
    return when (mode) {
        // FIXED: Corrected the function calls in the sorting logic.
        SortMode.Closest -> events.sortedBy { getDaysUntilNextOccurrence(it.date) }
        SortMode.Farthest -> events.sortedByDescending { getDaysUntilNextOccurrence(it.date) }
        SortMode.Chronological -> events.sortedBy { it.date }
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
    val haptics = LocalHapticFeedback.current

    var events by remember { mutableStateOf<List<Event>?>(null) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<Event?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.Custom) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var eventToDelete by remember { mutableStateOf<Event?>(null) }

    LaunchedEffect(Unit) {
        events = loadEvents(context)
        sortMode = loadSortMode(context)
    }

    LaunchedEffect(sortMode) {
        scope.launch {
            saveSortMode(context, sortMode)
        }
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (sortMode == SortMode.Custom && events != null) {
                val reorderedEvents = events!!.toMutableList().apply { add(to.index, removeAt(from.index)) }
                events = reorderedEvents
                scope.launch { saveEvents(context, reorderedEvents) }
            }
        }
    )

    val displayedEvents = remember(events, sortMode) {
        events?.let { sortEvents(it, sortMode) } ?: emptyList()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ChronoEcho", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
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
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    eventToEdit = null
                    showAddEditDialog = true
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Event",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
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
                        val currentEvents = events ?: emptyList()
                        val newEvents = if (currentEvents.any { it.id == eventToSave.id }) {
                            currentEvents.map { if (it.id == eventToSave.id) eventToSave else it }
                        } else {
                            currentEvents + eventToSave
                        }
                        events = newEvents
                        scope.launch { saveEvents(context, newEvents) }
                        scheduleEventNotification(context, eventToSave)
                        showAddEditDialog = false
                    }
                )
            }

            if (showDeleteConfirmation) {
                DeletionConfirmationDialog(
                    eventName = eventToDelete?.name ?: "this event",
                    onConfirm = {
                        val event = eventToDelete
                        if (event != null && events != null) {
                            val newEvents = events!! - event
                            events = newEvents
                            scope.launch { saveEvents(context, newEvents) }
                            // MODIFIED: Cancel all work tagged with the event's ID.
                            WorkManager.getInstance(context).cancelAllWorkByTag(event.id)
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

            when {
                events == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                displayedEvents.isEmpty() -> {
                    EmptyState()
                }
                else -> {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (sortMode == SortMode.Custom) Modifier.reorderable(reorderState)
                                else Modifier
                            ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedEvents, key = { it.id }) { event ->
                            ReorderableItem(reorderState, key = event.id) { isDragging ->
                                val reorderModifier = if (sortMode == SortMode.Custom) {
                                    Modifier.detectReorderAfterLongPress(reorderState)
                                } else {
                                    Modifier
                                }
                                SwipeableEventCard(
                                    modifier = reorderModifier,
                                    event = event,
                                    isDragging = isDragging,
                                    onEdit = {
                                        eventToEdit = event
                                        showAddEditDialog = true
                                    },
                                    onDeleteRequest = {
                                        eventToDelete = event
                                        showDeleteConfirmation = true
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
    val haptics = LocalHapticFeedback.current
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SortMode.values().forEach { mode ->
                val isSelected = mode == currentMode
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.name.replace("_", " "),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onModeSelected(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            when (mode) {
                                SortMode.Closest -> Icons.Default.ArrowUpward
                                SortMode.Farthest -> Icons.Default.ArrowDownward
                                SortMode.Chronological -> Icons.Default.FormatListNumbered
                                SortMode.Custom -> Icons.Default.DragHandle
                            },
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
    isDragging: Boolean = false
) {
    val transition = updateTransition(targetState = isDragging, label = "dragTransition")
    val scale by transition.animateFloat(
        transitionSpec = { spring(stiffness = Spring.StiffnessMedium) },
        label = "scale_anim"
    ) { dragging -> if (dragging) 1.02f else 1f }

    val (ageText, _) = formatEventDetailText(event)
    val fullDate = Instant.ofEpochMilli(event.date).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))

    val cardModifier = modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .zIndex(if (isDragging) 1f else 0f)

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(percent = 50), // This creates the pill shape.
        colors = CardDefaults.cardColors(containerColor = Color(event.color)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconMap[event.icon] ?: Icons.Default.Event,
                contentDescription = event.name,
                modifier = Modifier.size(56.dp),
                // The icon on the card should be dark to contrast with the light pastel color.
                tint = Color.Black.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Text inside the card must be dark for readability on pastel backgrounds.
                Text(
                    text = event.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black.copy(alpha = 0.87f)
                )
                Text(
                    text = fullDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black.copy(alpha = 0.7f)
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
    onSave: (Event) -> Unit
) {
    var name by remember { mutableStateOf(initialEvent?.name ?: "") }
    var dateMillis by remember { mutableStateOf(initialEvent?.date ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedIcon by remember { mutableStateOf(initialEvent?.icon ?: iconNames.first()) }
    var selectedColor by remember { mutableStateOf(initialEvent?.color ?: eventColorOptions.first().toArgb().toLong()) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEvent == null) "Add New Event" else "Edit Event") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Event Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(dateMillis)))
                    }
                }
                item {
                    Text("Icon", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    val columns = 5
                    val rows = (iconNames.size + columns - 1) / columns
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in 0 until rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (col in 0 until columns) {
                                    val idx = row * columns + col
                                    if (idx < iconNames.size) {
                                        val iconName = iconNames[idx]
                                        val isSelected = selectedIcon == iconName
                                        IconButton(
                                            onClick = { selectedIcon = iconName },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                                )
                                        ) {
                                            Icon(
                                                imageVector = iconMap[iconName]!!,
                                                contentDescription = iconName,
                                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.size(48.dp)) // Placeholder for empty spots in the grid
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Color", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        eventColorOptions.forEach { colorOption ->
                            val isSelected = selectedColor == colorOption.toArgb().toLong()
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(colorOption)
                                    .border(
                                        width = 3.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = colorOption.toArgb().toLong() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (name.isNotBlank()) {
                        onSave(
                            Event(
                                id = initialEvent?.id ?: UUID.randomUUID().toString(),
                                name = name,
                                date = dateMillis,
                                icon = selectedIcon,
                                color = selectedColor
                            )
                        )
                    } else {
                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEventCard(
    event: Event,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEdit()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
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
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> Color.Transparent
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(percent = 50)) // MODIFIED: Match the card's pill shape.
                    .background(color)
                    .padding(horizontal = 24.dp),
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
            modifier = modifier,
            event = event,
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
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text = { Text("Are you sure you want to permanently delete \"$eventName\"?") },
        confirmButton = {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}