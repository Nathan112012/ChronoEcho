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
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb

val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val SORT_MODE_KEY = stringPreferencesKey("sort_mode")
val gson = Gson()

// Add color palette for events
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
    val isBirthday: Boolean,
    val icon: String,
    val color: Long // Now required, not nullable
)

val iconMap = mapOf(
    "Cake" to Icons.Default.Cake,
    "Star" to Icons.Default.Star,
    "Favorite" to Icons.Default.Favorite,
    "Event" to Icons.Default.Event
)
val iconNames = iconMap.keys.toList()

fun formatEventDetailText(event: Event): String {
    val now = LocalDate.now()
    val eventDate = Instant.ofEpochMilli(event.date).atZone(ZoneId.systemDefault()).toLocalDate()

    val period = Period.between(eventDate, now)
    val years = period.years
    val months = period.months
    val days = period.days

    return if (event.date <= System.currentTimeMillis()) { // Birthday or past event
        buildString {
            if (years > 0) append("$years ${if (years == 1) "year" else "years"}, ")
            if (months > 0) append("$months ${if (months == 1) "month" else "months"}, ")
            append("$days ${if (days == 1) "day" else "days"}")
            if (!event.isBirthday) append(" ago")
        }
    } else { // Future event
        val daysUntil = getDaysUntilNextOccurrence(event.date, System.currentTimeMillis())
        when (daysUntil) {
            0 -> "Today!"
            1 -> "Tomorrow"
            else -> "In $daysUntil days"
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
                    Icons.Default.Cake, // Using a standard icon
                    contentDescription = "App Icon",
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

    var events by remember { mutableStateOf<List<Event>?>(null) }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<Event?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeletedEvent by remember { mutableStateOf<Pair<Event, Int>?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.Custom) }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var eventToDelete by remember { mutableStateOf<Event?>(null) }

    LaunchedEffect(Unit) {
        events = loadEvents(context)
        sortMode = loadSortMode(context)
    }

    LaunchedEffect(sortMode) {
        saveSortMode(context, sortMode)
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            if (sortMode == SortMode.Custom && events != null) {
                val reorderedEvents = events!!.toMutableList().apply { add(to.index, removeAt(from.index)) }
                events = reorderedEvents
                scope.launch { saveEvents(context, reorderedEvents) }
            }
        }
    )

    val filteredEvents = remember(events, sortMode) {
        events?.let { sortEvents(it, sortMode) } ?: emptyList()
    }

    val eventsList = events
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
                        if (eventsList != null) {
                            val newEvents = if (eventsList.any { it.id == eventToSave.id }) {
                                eventsList.map { if (it.id == eventToSave.id) eventToSave else it }
                            } else {
                                eventsList + eventToSave
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
                        if (eventsList != null && eventToDelete != null) {
                            val event = eventToDelete!!
                            val eventIndex = eventsList.indexOf(event)
                            recentlyDeletedEvent = Pair(event, eventIndex)

                            val newEvents = eventsList - event
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

            when {
                eventsList == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                eventsList.isEmpty() -> {
                    EmptyState()
                }
                else -> {
                    val displayedEvents = if (sortMode == SortMode.Custom) eventsList else filteredEvents
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (sortMode == SortMode.Custom) Modifier.reorderable(reorderState)
                                else Modifier
                            ),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                    ) {
                        items(displayedEvents, key = { it.id }) { event ->
                            ReorderableItem(reorderState, key = event.id) { isDragging ->
                                SwipeableEventCard(
                                    event = event,
                                    isDragging = isDragging,
                                    onEdit = { eventToEdit = event; showAddEditDialog = true },
                                    onDeleteRequest = { eventToDelete = event; showDeleteConfirmation = true },
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
                val isSelected = mode == currentMode
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.name.replace("_", " "),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
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
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier
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
    val elevation by animateDpAsState(if (isDragging) 16.dp else 4.dp, label = "elevation_anim")
    val scale by animateFloatAsState(if (isDragging) 1.07f else 1f, label = "scale_anim")
    val borderColor by animateColorAsState(
        if (isDragging) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "border_anim"
    )

    val detailText = formatEventDetailText(event)
    val fullDate = Instant.ofEpochMilli(event.date).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))

    val cardModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(if (isDragging) Modifier.zIndex(2f) else Modifier.zIndex(0f))
        .let { if (onCardClick != null) it.clickable { onCardClick() } else it }
        .border(2.dp, borderColor, shape = RoundedCornerShape(50)) // pill shape and highlight

    ElevatedCard(
        modifier = cardModifier,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        shape = RoundedCornerShape(50), // pill shape
        colors = CardDefaults.cardColors(containerColor = Color(event.color))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Icon
            Icon(
                imageVector = iconMap[event.icon] ?: Icons.Default.Event,
                contentDescription = event.name,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            // Right: Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // yyyy mm dd NOT in a pill
                Text(
                    text = fullDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // Only the detail text is in a pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Drag handle on the far right if present
            dragHandle?.let {
                Box(contentAlignment = Alignment.Center) { it() }
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
    var selectedIcon by remember { mutableStateOf(initialEvent?.icon ?: iconNames.first()) }
    var selectedColor by remember { mutableStateOf(initialEvent?.color ?: eventColorOptions.first().toArgb().toLong()) }
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
                        IconPicker(
                            selectedIcon = selectedIcon,
                            onIconSelected = { selectedIcon = it }
                        )
                        Spacer(Modifier.height(16.dp))
                        // Color palette picker
                        Text("Color", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            eventColorOptions.forEach { colorOption ->
                                val isSelected = selectedColor == colorOption.toArgb().toLong()
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorOption.toArgb()))
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColor = colorOption.toArgb().toLong() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                    }
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
                    if (name.isNotBlank()) {
                        onSave(
                            Event(
                                id = initialEvent?.id ?: UUID.randomUUID().toString(),
                                name = name,
                                date = dateMillis,
                                isBirthday = isBirthday,
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
fun IconPicker(
    selectedIcon: String,
    onIconSelected: (String) -> Unit
) {
    Column {
        Text("Icon", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            iconNames.forEach { iconName ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (iconName == selectedIcon) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .clickable { onIconSelected(iconName) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconMap[iconName]!!,
                        contentDescription = iconName,
                        modifier = Modifier.size(36.dp),
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(color)
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