# ChronoEcho

A beautiful and intuitive birthday and event tracking app built with Jetpack Compose.

## Features

- **Event Management**: Add, edit, and delete birthdays and events
- **Smart Sorting**: Multiple sorting options (Closest, Farthest, Most Recent, Custom)
- **Search**: Find events quickly with the search functionality
- **Customization**: Choose from different colors and icons for your events
- **Notifications**: Get reminded about upcoming events
- **Swipe Gestures**: 
  - **Swipe Left** (End to Start): Edit the event (shows blue preview)
  - **Swipe Right** (Start to End): Delete the event (shows red preview)
- **Drag & Drop**: Reorder events in Custom sort mode
- **Undo**: Restore accidentally deleted events with the undo snackbar

## Swipe Gestures

The app features intuitive swipe gestures for quick actions:

- **🔄 Swipe Left**: Edit the event
  - Shows a blue background with an edit icon
  - Opens the edit dialog for the event

- **🗑️ Swipe Right**: Delete the event  
  - Shows a red background with a delete icon
  - Removes the event with an undo option

The swipe preview indicators provide visual feedback as you swipe, making it clear what action will be performed.

## Getting Started

1. Clone the repository
2. Open the project in Android Studio
3. Build and run the app on your device or emulator

## Requirements

- Android API level 21 or higher
- Android Studio Arctic Fox or later
- Kotlin 1.5 or later

## Dependencies

- Jetpack Compose
- Material 3 Design
- DataStore for data persistence
- WorkManager for notifications
- Reorderable library for drag & drop functionality