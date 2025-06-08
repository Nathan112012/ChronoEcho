package com.silentninja.chronoecho


import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.work.*
import org.burnoutcrew.reorderable.*
import android.util.Log // For debugging purposes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.BorderStroke
// Removed unused imports: import androidx.compose.foundation.gestures.Orientation, import androidx.compose.foundation.gestures.draggable


val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val gson = Gson()

data class Event(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val date: Long,
    val isBirthday: Boolean,
    val color: Int, // Changed from Long to Int
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
    Color(0xFFF5F5F5), // Soft white
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
        val eventIdHash = inputData.getString("event_id")?.hashCode() ?: eventName.hashCode() // Use event ID for robustness
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
        notificationManager.notify(eventIdHash, notification) // Using derived hash for notification ID
        return Result.success()
    }
}

fun scheduleEventNotification(context: Context, event: Event) {
    // Cancel any existing work for this event to avoid duplicate notifications if edited
    WorkManager.getInstance(context).cancelUniqueWork(event.id)

    val oneDayBefore = event.date - TimeUnit.DAYS.toMillis(1)
    val delay = oneDayBefore - System.currentTimeMillis()
    if (delay > 0) {
        val work = OneTimeWorkRequestBuilder<EventNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("event_name" to event.name, "event_id" to event.id)) // Pass event ID
            .addTag("event_notification_${event.id}") // Add a tag for easier cancellation
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            event.id, // Unique work name
            ExistingWorkPolicy.REPLACE, // Replace if exists
            work
        )
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
                    Icons.Default.CalendarMonth,
                    contentDescription = "Calendar",
                    modifier = Modifier.size(80.dp),
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

enum class SortMode { Closest, Farthest, Most_Recent, Custom }

fun sortEvents(events: List<Event>, mode: SortMode): List<Event> {
    return when (mode) {
        SortMode.Closest -> events.sortedBy { it.date }
        SortMode.Farthest -> events.sortedByDescending { it.date }
        SortMode.Most_Recent -> events.sortedByDescending { it.date }
        SortMode.Custom -> events
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayEventApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf(emptyList<Event>()) }

    LaunchedEffect(Unit) {
        events = loadEvents(context)
    }

    var showDialog by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<Event?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeletedEvent by remember { mutableStateOf<Pair<Event, Int>?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.Custom) }

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
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "ChronoEcho",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    SortMenu(sortMode, onSortChange = { sortMode = it })
                }
            }
        },
        floatingActionButton = {
            var isPressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.9f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "fabScale"
            )

            LargeFloatingActionButton(
                onClick = {
                    editEvent = null
                    showDialog = true
                },
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                            }
                        )
                    },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Event",
                    modifier = Modifier.size(36.dp)
                )
            }
        },
        snackbarHost = { 
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(16.dp)
            ) { data ->
                Snackbar(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    action = {
                        TextButton(
                            onClick = { data.performAction() }
                        ) {
                            Text("Undo")
                        }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
            
            if (filteredEvents.isEmpty()) {
                EmptyStateMessage()
            } else {
                if (sortMode == SortMode.Custom) {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier.reorderable(reorderState)
                    ) {
                        items(filteredEvents, key = { it.id }) { event ->
                            ReorderableItem(reorderState, key = event.id) { isDragging ->
                                EventCard(
                                    modifier = Modifier.animateItemPlacement(spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessVeryLow
                                    )),
                                    event = event,
                                    onEdit = {
                                        editEvent = event
                                        showDialog = true
                                    },
                                    onDelete = {
                                        val index = events.indexOf(event)
                                        events = events - event
                                        recentlyDeletedEvent = event to index
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Event deleted",
                                                actionLabel = "Undo"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                recentlyDeletedEvent?.let { (ev, idx) ->
                                                    events = events.toMutableList().apply { add(idx, ev) }
                                                    recentlyDeletedEvent = null
                                                }
                                            }
                                        }
                                    },
                                    dragHandle = {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .detectReorderAfterLongPress(reorderState)
                                                .padding(end = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Drag",
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    isDragging = isDragging
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn {
                        items(filteredEvents, key = { it.id }) { event ->
                            EventCard(
                                event = event,
                                onEdit = {
                                    editEvent = event
                                    showDialog = true
                                },
                                onDelete = {
                                    val index = events.indexOf(event)
                                    events = events - event
                                    recentlyDeletedEvent = event to index
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Event deleted",
                                            actionLabel = "Undo"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            recentlyDeletedEvent?.let { (ev, idx) ->
                                                events = events.toMutableList().apply { add(idx, ev) }
                                                recentlyDeletedEvent = null
                                            }
                                        }
                                    }
                                }
                            )
                        }
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
                    events = events.map { if (it.id == editEvent!!.id) event else it }
                    scheduleEventNotification(context, event)
                } else {
                    events = events + event
                    scheduleEventNotification(context, event)
                }
                showDialog = false
            },
            onDelete = {
                if (editEvent != null) {
                    val index = events.indexOf(editEvent!!)
                    events = events - editEvent!!
                    recentlyDeletedEvent = editEvent!! to index
                    WorkManager.getInstance(context).cancelUniqueWork(editEvent!!.id)
                    showDialog = false
                }
            }
        )
    }
}

@Composable
fun SortMenu(sortMode: SortMode, onSortChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "sortButtonScale"
    )

    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isHovered = true
                            tryAwaitRelease()
                            isHovered = false
                        }
                    )
                },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Sort: ${sortMode.name.replace("_", " ")}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(12.dp)
                )
        ) {
            SortMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.name.replace("_", " "),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    onClick = {
                        onSortChange(mode)
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
                            tint = if (mode == sortMode) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = if (mode == sortMode) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = if (mode == sortMode) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
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
                tint = if (isFocused) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}

@Composable
fun EmptyStateMessage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "🎈",
            fontSize = 64.sp,
            modifier = Modifier
                .graphicsLayer {
                    rotationZ = 5f
                }
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No events yet!",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap + to add your first birthday or event.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
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
    val cardColor = Color(event.color)
    val now = System.currentTimeMillis()

    // Enhanced animations with smoother transitions
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "cardElevation"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isDragging) 0.8f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "cardAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "cardScale"
    )

    // Hover state with smoother animation
    var isHovered by remember { mutableStateOf(false) }
    val hoverScale by animateFloatAsState(
        targetValue = if (isHovered) 1.01f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessUltraLow
        ),
        label = "hoverScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .graphicsLayer {
                scaleX = scale * hoverScale
                scaleY = scale * hoverScale
                this.alpha = alpha
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onEdit() },
                    onPress = {
                        isHovered = true
                        tryAwaitRelease()
                        isHovered = false
                    }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dragHandle != null) {
                dragHandle()
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        rotationZ = if (isHovered) 5f else 0f
                    }
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Date: $dateStr",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (event.isBirthday) "🎂 Birthday" else "⭐ Event",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(2.dp))

                val infoText = if (event.isBirthday) {
                    val daysUntilNext = getDaysUntilNextOccurrence(event.date, now)
                    when {
                        daysUntilNext == 0 -> "Today!"
                        daysUntilNext == 1 -> "Tomorrow!"
                        daysUntilNext > 1 -> "In $daysUntilNext days"
                        else -> {
                            val (years, days) = getYearsAndDaysSince(event.date, now)
                            "Age: $years years, $days days"
                        }
                    }
                } else {
                    val diffDays = TimeUnit.MILLISECONDS.toDays(event.date - now)
                    when {
                        diffDays == 0L -> "Today!"
                        diffDays == 1L -> "Tomorrow!"
                        diffDays > 1L -> "In ${diffDays} days"
                        else -> {
                            val (years, days) = getYearsAndDaysSince(event.date, now)
                            "Since: $years years, $days days"
                        }
                    }
                }
                Text(
                    infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = if (isHovered) -5f else 0f
                    }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
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
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialEvent?.name ?: "") }
    var isBirthday by remember { mutableStateOf(initialEvent?.isBirthday ?: true) }
    var dateMillis by remember { mutableStateOf(initialEvent?.date ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(initialEvent?.color?.let { Color(it) } ?: colorOptions.first()) }
    var selectedIcon by remember { mutableStateOf(initialEvent?.icon ?: iconNames.first()) }
    val context = LocalContext.current

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMillis
    )

    // Animation states
    var isDialogVisible by remember { mutableStateOf(false) }
    val dialogScale by animateFloatAsState(
        targetValue = if (isDialogVisible) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dialogScale"
    )
    val dialogAlpha by animateFloatAsState(
        targetValue = if (isDialogVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "dialogAlpha"
    )

    LaunchedEffect(Unit) {
        isDialogVisible = true
    }

    AlertDialog(
        onDismissRequest = {
            isDialogVisible = false
            onDismiss()
        },
        modifier = Modifier
            .graphicsLayer {
                scaleX = dialogScale
                scaleY = dialogScale
                alpha = dialogAlpha
            },
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                if (initialEvent == null) "Add Event" else "Edit Event",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                Modifier
                    .padding(vertical = 8.dp)
                    .animateContentSize()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text("Type:", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { isBirthday = true }
                            .padding(4.dp)
                    ) {
                        RadioButton(
                            selected = isBirthday,
                            onClick = { isBirthday = true },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Text("Birthday")
                    }
                    Spacer(Modifier.width(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { isBirthday = false }
                            .padding(4.dp)
                    ) {
                        RadioButton(
                            selected = !isBirthday,
                            onClick = { isBirthday = false },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Text("Event")
                    }
                }
                Spacer(Modifier.height(12.dp))
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                Button(
                    onClick = { showDatePicker = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
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
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            Event(
                                id = initialEvent?.id ?: UUID.randomUUID().toString(),
                                name = name,
                                date = dateMillis,
                                isBirthday = isBirthday,
                                color = selectedColor.toArgb(),
                                icon = selectedIcon
                            )
                        )
                    } else {
                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (initialEvent != null && onDelete != null) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Delete")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = {
                        isDialogVisible = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("Cancel")
                }
            }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            dateMillis = it
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
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
        Text(
            "Pick a color:",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .animateContentSize()
        ) {
            colorOptions.forEach { color ->
                var isHovered by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    targetValue = if (isHovered) 1.1f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "colorScale"
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .padding(4.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .background(color, CircleShape)
                        .border(
                            width = if (color == selectedColor) 2.dp else 0.dp,
                            color = if (color == selectedColor) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onColorSelected(color) },
                                onPress = {
                                    isHovered = true
                                    tryAwaitRelease()
                                    isHovered = false
                                }
                            )
                        }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Pick an icon:",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .animateContentSize()
        ) {
            iconNames.forEach { iconName ->
                var isHovered by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    targetValue = if (isHovered) 1.1f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "iconScale"
                )
                val rotation by animateFloatAsState(
                    targetValue = if (isHovered) 10f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "iconRotation"
                )
                val icon = iconMap[iconName] ?: Icons.Default.Cake
                Box(
                    Modifier
                        .size(40.dp)
                        .padding(4.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            rotationZ = rotation
                        }
                        .background(
                            if (iconName == selectedIcon) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onIconSelected(iconName) },
                                onPress = {
                                    isHovered = true
                                    tryAwaitRelease()
                                    isHovered = false
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = iconName,
                        tint = if (iconName == selectedIcon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun getYearsAndDaysSince(pastDate: Long, now: Long): Pair<Int, Int> {
    val fromCal = Calendar.getInstance().apply { timeInMillis = pastDate }
    val toCal = Calendar.getInstance().apply { timeInMillis = now }

    var years = toCal.get(Calendar.YEAR) - fromCal.get(Calendar.YEAR)
    var days = toCal.get(Calendar.DAY_OF_YEAR) - fromCal.get(Calendar.DAY_OF_YEAR)

    if (days < 0) {
        years -= 1
        // Create a calendar for the current year, but at the original event's month/day
        val tempFromCal = Calendar.getInstance().apply {
            timeInMillis = fromCal.timeInMillis
            set(Calendar.YEAR, toCal.get(Calendar.YEAR))
        }

        // If the event's date in the *current* year is still in the future,
        // then the "years since" calculation needs to consider the previous year
        if (tempFromCal.timeInMillis > toCal.timeInMillis) {
            tempFromCal.add(Calendar.YEAR, -1) // Go back to previous year for calculation
        }
        days = TimeUnit.MILLISECONDS.toDays(toCal.timeInMillis - tempFromCal.timeInMillis).toInt()
    }
    return years to days
}

fun getDaysUntilNextOccurrence(eventDate: Long, now: Long): Int {
    val eventCal = Calendar.getInstance().apply { timeInMillis = eventDate }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }

    // Set event to current year's date for upcoming calculation
    eventCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR))

    // If the event for this year has passed, set it to next year
    // Check if the current time has gone past the event date in the current year
    if (eventCal.timeInMillis < nowCal.timeInMillis) {
        // Exception: if it's the exact same day (e.g., event was earlier today), it's 0 days
        val isSameDay = eventCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) &&
                eventCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        if (!isSameDay) {
            eventCal.add(Calendar.YEAR, 1)
        }
    }

    val diffMillis = eventCal.timeInMillis - nowCal.timeInMillis

    return when {
        diffMillis <= 0L -> 0 // Today or already passed for the current year.
        else -> TimeUnit.MILLISECONDS.toDays(diffMillis).toInt() +
                // Add 1 if there's a remainder, meaning it's less than a full day until
                if (diffMillis % (1000 * 60 * 60 * 24) > 0) 1 else 0
    }
}