package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

class ErrandBrainStore {
    private val TAG = "ErrandBrainStore"

    private val lock = Any()
    private val tasks = mutableListOf<TaskEntry>()
    private val reminders = mutableListOf<ReminderEntry>()
    private var loaded = false

    fun addTask(task: TaskEntry, maxHistory: Int) {
        synchronized(lock) {
            tasks.add(task)
            val overflow = tasks.size - maxHistory.coerceAtLeast(50)
            if (overflow > 0) {
                repeat(overflow) { tasks.removeFirstOrNull() }
            }
        }
    }

    fun getTasks(maxCount: Int): List<TaskEntry> {
        synchronized(lock) {
            if (tasks.isEmpty()) return emptyList()
            val takeCount = maxCount.coerceIn(1, tasks.size)
            return tasks.takeLast(takeCount).toList()
        }
    }

    fun updateTask(taskId: String, isCompleted: Boolean) {
        synchronized(lock) {
            val index = tasks.indexOfFirst { it.id == taskId }
            if (index >= 0) {
                tasks[index] = tasks[index].copy(isCompleted = isCompleted)
            }
        }
    }

    fun deleteTask(taskId: String) {
        synchronized(lock) {
            tasks.removeAll { it.id == taskId }
        }
    }

    fun addReminder(reminder: ReminderEntry, maxHistory: Int) {
        synchronized(lock) {
            reminders.add(reminder)
            val overflow = reminders.size - maxHistory.coerceAtLeast(50)
            if (overflow > 0) {
                repeat(overflow) { reminders.removeFirstOrNull() }
            }
        }
    }

    fun getReminders(maxCount: Int): List<ReminderEntry> {
        synchronized(lock) {
            if (reminders.isEmpty()) return emptyList()
            val takeCount = maxCount.coerceIn(1, reminders.size)
            return reminders.takeLast(takeCount).toList()
        }
    }

    fun markReminderTriggered(reminderId: String) {
        synchronized(lock) {
            val index = reminders.indexOfFirst { it.id == reminderId }
            if (index >= 0) {
                reminders[index] = reminders[index].copy(isTriggered = true)
            }
        }
    }

    fun deleteReminder(reminderId: String) {
        synchronized(lock) {
            reminders.removeAll { it.id == reminderId }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            tasks.clear()
            reminders.clear()
            persistInternal(context, 200)
        }
    }

    private fun persistInternal(context: Context, maxHistory: Int) {
        try {
            // Persist tasks
            val tasksArr = JSONArray()
            val taskItems = tasks.takeLast(maxHistory.coerceAtLeast(50))
            for (task in taskItems) {
                tasksArr.put(
                    JSONObject()
                        .put("id", task.id)
                        .put("timestampMs", task.timestampMs)
                        .put("title", task.title)
                        .put("description", task.description)
                        .put("isCompleted", task.isCompleted)
                        .put("priority", task.priority.name)
                        .put("dueDate", task.dueDate ?: JSONObject.NULL)
                        .put("category", task.category)
                )
            }
            PluginPrefs.ErrandBrainStore.tasksJson[context] = tasksArr.toString()

            // Persist reminders
            val remindersArr = JSONArray()
            val reminderItems = reminders.takeLast(maxHistory.coerceAtLeast(50))
            for (reminder in reminderItems) {
                remindersArr.put(
                    JSONObject()
                        .put("id", reminder.id)
                        .put("timestampMs", reminder.timestampMs)
                        .put("title", reminder.title)
                        .put("description", reminder.description)
                        .put("reminderTime", reminder.reminderTime)
                        .put("isTriggered", reminder.isTriggered)
                )
            }
            PluginPrefs.ErrandBrainStore.remindersJson[context] = remindersArr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist data", e)
        }
    }

    fun persist(context: Context, maxHistory: Int) {
        persistInternal(context, maxHistory)
    }

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                // Load tasks
                val tasksRaw = PluginPrefs.ErrandBrainStore.tasksJson[context]
                val tasksArr = JSONArray(tasksRaw)
                for (i in 0 until tasksArr.length()) {
                    val obj = tasksArr.optJSONObject(i) ?: continue
                    val task = TaskEntry(
                        id = obj.optString("id", ""),
                        timestampMs = obj.optLong("timestampMs", 0L),
                        title = obj.optString("title", ""),
                        description = obj.optString("description", ""),
                        isCompleted = obj.optBoolean("isCompleted", false),
                        priority = try {
                            TaskPriority.valueOf(obj.optString("priority", "MEDIUM"))
                        } catch (e: Exception) {
                            TaskPriority.MEDIUM
                        },
                        dueDate = obj.optLong("dueDate").takeIf { it > 0 },
                        category = obj.optString("category", "personal"),
                    )
                    if (task.id.isNotBlank()) {
                        tasks.add(task)
                    }
                }

                // Load reminders
                val remindersRaw = PluginPrefs.ErrandBrainStore.remindersJson[context]
                val remindersArr = JSONArray(remindersRaw)
                for (i in 0 until remindersArr.length()) {
                    val obj = remindersArr.optJSONObject(i) ?: continue
                    val reminder = ReminderEntry(
                        id = obj.optString("id", ""),
                        timestampMs = obj.optLong("timestampMs", 0L),
                        title = obj.optString("title", ""),
                        description = obj.optString("description", ""),
                        reminderTime = obj.optLong("reminderTime", 0L),
                        isTriggered = obj.optBoolean("isTriggered", false),
                    )
                    if (reminder.id.isNotBlank()) {
                        reminders.add(reminder)
                    }
                }

                Log.i(TAG, "Loaded ${tasks.size} tasks and ${reminders.size} reminders")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load data", e)
            }
        }
    }
}
