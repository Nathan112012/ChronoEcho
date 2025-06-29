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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.silentninja.chronoecho.ui.theme.Typography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
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
    Color(0xFFFDFDFD), // Off-white
    Color(0xFFE8F5E9), // Soft mint
    Color(0xFFE3F2FD), // Soft blue
    Color(0xFFFFF3E0), // Soft orange
    Color(0xFFF3E5F5), // Soft purple
    Color(0xFFFCE4EC)  // Soft pink
)

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
    var events by remember { mutableStateOf(emptyList<Event>()) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<Event?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeletedEvent by remember { mutableStateOf<Pair<Event, Int>?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.Custom) }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var eventToDelete by remember { mutableStateOf<Event?>(null) }


    LaunchedEffect(Unit) {
        events = loadEvents(context)
    }

    LaunchedEffect(events) { saveEvents(context, events) }

    val filteredEvents = remember(events, searchQuery, sortMode) {
        sortEvents(
            events.filter { it.name.contains(searchQuery, ignoreCase = true) },
            sortMode
        )
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            if (sortMode == SortMode.Custom) {
                events = events.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
        }
    )

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
            CustomSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it }
            )

            if (showAddEditDialog) {
                AddEditEventDialog(
                    initialEvent = eventToEdit,
                    onDismiss = { showAddEditDialog = false },
                    onSave = { eventToSave ->
                        val existing = events.find { it.id == eventToSave.id }
                        if (existing != null) {
                            events = events.map { if (it.id == eventToSave.id) eventToSave else it }
                        } else {
                            events = events + eventToSave
                        }
                        scheduleEventNotification(context, eventToSave)
                        showAddEditDialog = false
                    },
                    onDelete = {
                        showAddEditDialog = false
                        showDeleteConfirmation = true
                        eventToDelete = eventToEdit
                    }
                )
            }

            if(showDeleteConfirmation) {
                DeletionConfirmationDialog(
                    eventName = eventToDelete?.name ?: "this event",
                    onConfirm = {
                        eventToDelete?.let { event ->
                            val eventIndex = events.indexOf(event)
                            events = events - event
                            WorkManager.getInstance(context).cancelUniqueWork(event.id)
                            recentlyDeletedEvent = Pair(event, eventIndex)

                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Event deleted",
                                    actionLabel = "Undo"
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    recentlyDeletedEvent?.let { (deletedEvent, index) ->
                                        events = events.toMutableList().apply { add(index, deletedEvent) }
                                        scheduleEventNotification(context, deletedEvent)
                                        recentlyDeletedEvent = null
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


            if (filteredEvents.isEmpty() && searchQuery.isBlank()) {
                EmptyState()
            } else {
                if (sortMode == SortMode.Custom) {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .reorderable(reorderState),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(filteredEvents, key = { it.id }) { event ->
                            ReorderableItem(reorderState, key = event.id) { isDragging ->
                                EventCard(
                                    event = event,
                                    onCardClick = {
                                        eventToEdit = event
                                        showAddEditDialog = true
                                    },
                                    dragHandle = {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = "Drag to reorder",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .pointerInput(Unit) {
                                                    detectTapGestures { /* consume taps */ }
                                                }
                                        )
                                    },
                                    isDragging = isDragging
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(filteredEvents, key = { it.id }) { event ->
                            SwipeableEventCard(
                                event = event,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSearchField(query: String, onQueryChange: (String) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "searchBarScale"
    )

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search events...") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(16.dp),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        singleLine = true
    )
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
    val elevation = if (isDragging) 8.dp else 2.dp
    val icon = iconMap[event.icon] ?: Icons.Default.Cake
    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    val dateStr = sdf.format(Date(event.date))
    val now = System.currentTimeMillis()
    val daysUntil = getDaysUntilNextOccurrence(event.date, now)
    val isToday = daysUntil == 0
    val (years, _) = getYearsAndDaysSince(event.date, now)
    val ageText = if (event.isBirthday && years >= 0) " (turning ${years + 1})" else ""
    val infoText = when {
        isToday -> "Today!$ageText"
        daysUntil == 1 -> "Tomorrow!$ageText"
        else -> "In $daysUntil days$ageText"
    }

    // FIX: The clickable modifier is now applied conditionally only when onCardClick is not null.
    // This allows the card to be non-clickable in the SwipeableEventCard, resolving gesture conflicts.
    val cardModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .then(if (isDragging) Modifier.zIndex(1f) else Modifier.zIndex(0f))
        .let { if (onCardClick != null) it.clickable { onCardClick() } else it }

    ElevatedCard(
        modifier = cardModifier,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dragHandle?.let {
                Box(modifier = Modifier.padding(end = 12.dp)) { it() }
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 16.dp)) {
                Text(
                    event.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                // FIX: Added Event/Birthday Label
                Text(
                    text = if(event.isBirthday) "Birthday" else "Event",
                    style = MaterialTheme.typography.labelMedium,
                    color = if(event.isBirthday) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    infoText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
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

fun getYearsAndDaysSince(eventDate: Long, now: Long): Pair<Int, Int> {
    val eventCal = Calendar.getInstance().apply { timeInMillis = eventDate }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }

    var years = nowCal.get(Calendar.YEAR) - eventCal.get(Calendar.YEAR)
    if (nowCal.get(Calendar.DAY_OF_YEAR) < eventCal.get(Calendar.DAY_OF_YEAR)) {
        years--
    }
    return Pair(years, 0)
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
    onDeleteRequest: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            // This lambda is called when the swipe gesture is released.
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> { // Edit
                    onEdit()
                    // Don't dismiss, just snap back.
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> { // Delete
                    onDeleteRequest()
                    // Don't dismiss here. Let the dialog handle the data change.
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
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(color, shape = RoundedCornerShape(24.dp))
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
        EventCard(event = event)
    }
}

/**
 * A dialog to confirm deletion of an event.
 */
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