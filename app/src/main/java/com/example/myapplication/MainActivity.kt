package com.example.birthdayevents

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.random.Random

val Context.dataStore by preferencesDataStore(name = "events")
val EVENTS_KEY = stringPreferencesKey("events_json")
val gson = Gson()

data class Event(
    var name: String,
    var date: Long, // store as millis
    var isBirthday: Boolean,
    var color: Int = ColorList.random().toArgb() // random color for each event
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

val ColorList = listOf(
    Color(0xFFFFF59D), // yellow
    Color(0xFFB2FF59), // green
    Color(0xFF81D4FA), // blue
    Color(0xFFFFAB91), // orange
    Color(0xFFE1BEE7), // purple
    Color(0xFFFFCDD2), // pink
    Color(0xFFD7CCC8), // brown
    Color(0xFFB0BEC5)  // gray
)

fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)

fun Int.toComposeColor(): Color = Color(
    android.graphics.Color.red(this),
    android.graphics.Color.green(this),
    android.graphics.Color.blue(this),
    android.graphics.Color.alpha(this)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BirthdayEventApp()
        }
    }
}

@Composable
fun BirthdayEventApp() {
    val context = LocalContext.current
    var events by remember { mutableStateOf(loadEvents(context)) }
    var showDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }
    var showConfetti by remember { mutableStateOf(false) }

    // Save events whenever they change
    LaunchedEffect(events) {
        saveEvents(context, events)
    }

    // Show confetti for 2 seconds when a new event is added
    LaunchedEffect(showConfetti) {
        if (showConfetti) {
            delay(2000)
            showConfetti = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 70.dp)
        ) {
            itemsIndexed(events) { idx, event ->
                EventCard(
                    event = event,
                    onEdit = {
                        editIndex = idx
                        showDialog = true
                    },
                    onMoveUp = {
                        if (idx > 0) {
                            val mutable = events.toMutableList()
                            val temp = mutable[idx - 1]
                            mutable[idx - 1] = mutable[idx]
                            mutable[idx] = temp
                            events = mutable
                        }
                    },
                    onMoveDown = {
                        if (idx < events.lastIndex) {
                            val mutable = events.toMutableList()
                            val temp = mutable[idx + 1]
                            mutable[idx + 1] = mutable[idx]
                            mutable[idx] = temp
                            events = mutable
                        }
                    },
                    isFirst = idx == 0,
                    isLast = idx == events.lastIndex
                )
            }
        }

        // Confetti animation
        AnimatedVisibility(
            visible = showConfetti,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ConfettiRow()
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
        ) {
            FloatingActionButton(onClick = {
                editIndex = -1
                showDialog = true
            }) {
                Text("+")
            }
        }

        if (showDialog) {
            val editingEvent = if (editIndex >= 0) events[editIndex] else null
            AddEditEventDialog(
                initialEvent = editingEvent,
                onDismiss = { showDialog = false },
                onSave = { event ->
                    events = if (editIndex >= 0) {
                        events.toMutableList().apply { set(editIndex, event) }
                    } else {
                        events + event.copy(color = ColorList.random().toArgb())
                    }
                    showDialog = false
                    if (editIndex == -1) showConfetti = true
                },
                onDelete = {
                    if (editIndex >= 0) {
                        events = events.toMutableList().apply { removeAt(editIndex) }
                    }
                    showDialog = false
                }
            )
        }
    }
}

@Composable
fun getLiveDetailedAge(eventTime: Long): String {
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    // Update every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    val diff = abs(currentTime - eventTime)
    val days = diff / (1000L * 60 * 60 * 24)
    val hours = (diff / (1000L * 60 * 60)) % 24
    val minutes = (diff / (1000L * 60)) % 60
    val seconds = (diff / 1000) % 60

    return "$days days $hours hours $minutes minutes $seconds seconds"
}

fun getLevel(days: Long): Int = when {
    days < 7 -> 1
    days < 30 -> 2
    else -> 3
}

@Composable
fun EventCard(
    event: Event,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val dateStr = sdf.format(Date(event.date))
    val ageStr = getLiveDetailedAge(event.date)
    val days = abs((System.currentTimeMillis() - event.date) / (1000L * 60 * 60 * 24))
    val level = getLevel(days)
    val icon = if (event.isBirthday) Icons.Default.Cake else Icons.Default.Star
    val iconDesc = if (event.isBirthday) "Birthday" else "Event"
    val cardColor = event.color.toComposeColor()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(cardColor),
        elevation = 8.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = iconDesc, tint = Color.Unspecified, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onEdit() }
            ) {
                Text(
                    text = event.name,
                    style = MaterialTheme.typography.h6.copy(fontWeight = FontWeight.Bold)
                )
                Text(text = "Date: $dateStr")
                if (event.isBirthday) {
                    Text(text = "Age: $ageStr old")
                } else {
                    Text(text = "Since: $ageStr")
                }
                Text(
                    text = "Level $level",
                    color = when (level) {
                        1 -> Color(0xFF43A047)
                        2 -> Color(0xFF1976D2)
                        else -> Color(0xFFFBC02D)
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                }
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
                Button(
                    onClick = {
                        val emojis = listOf("🎉", "🎊", "🥳", "👏", "✨", "🎈", "🍰", "🌟")
                        Toast.makeText(
                            context,
                            "Celebrate! ${emojis.random()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text("Celebrate!")
                }
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
                                initialEvent?.color ?: ColorList.random().toArgb()
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
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                    ) { Text("Delete", color = MaterialTheme.colors.onError) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun ConfettiRow() {
    val emojis = listOf("🎉", "🎊", "🥳", "🎈", "✨", "🍰", "🌟", "🎂")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(12) {
            Text(
                text = emojis.random(),
                fontSize = MaterialTheme.typography.h4.fontSize
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}