package com.vibcontrol

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class StorageManager(context: Context) {

    private val prefs = context.getSharedPreferences("vibcontrol", Context.MODE_PRIVATE)
    private val gson  = Gson()

    // ── Saved patterns ───────────────────────────────────────
    fun loadPatterns(): MutableList<SavedPattern> {
        val json = prefs.getString("saved_patterns", null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<SavedPattern>>() {}.type)
        } catch (e: Exception) { mutableListOf() }
    }

    fun savePatterns(list: List<SavedPattern>) {
        prefs.edit().putString("saved_patterns", gson.toJson(list)).apply()
    }

    // ── Schedules ────────────────────────────────────────────
    fun loadSchedules(): MutableList<Schedule> {
        val json = prefs.getString("schedules", null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<Schedule>>() {}.type)
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveSchedules(list: List<Schedule>) {
        prefs.edit().putString("schedules", gson.toJson(list)).apply()
    }

    // ── Motor config ─────────────────────────────────────────
    fun loadMotorPins(): List<Int> {
        val json = prefs.getString("motor_pins", null) ?: return listOf(2)
        return try {
            gson.fromJson(json, object : TypeToken<List<Int>>() {}.type)
        } catch (e: Exception) { listOf(2) }
    }

    fun saveMotorPins(pins: List<Int>) {
        prefs.edit().putString("motor_pins", gson.toJson(pins)).apply()
    }
}
