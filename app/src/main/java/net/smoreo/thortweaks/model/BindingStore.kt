package net.smoreo.thortweaks.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object BindingStore {

    private const val PREFS_NAME = "thor_hotkeys"
    private const val KEY_BINDINGS = "bindings"
    private val gson = Gson()

    private var cached: MutableList<HotkeyBinding>? = null

    fun getBindings(context: Context): List<HotkeyBinding> {
        if (cached != null) return cached!!
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BINDINGS, null) ?: return emptyList()
        val type = object : TypeToken<List<HotkeyBinding>>() {}.type
        cached = gson.fromJson<List<HotkeyBinding>>(json, type).toMutableList()
        return cached!!
    }

    fun saveBindings(context: Context, bindings: List<HotkeyBinding>) {
        cached = bindings.toMutableList()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BINDINGS, gson.toJson(bindings)).apply()
    }

    fun addBinding(context: Context, binding: HotkeyBinding) {
        val list = getBindings(context).toMutableList()
        list.add(binding)
        saveBindings(context, list)
    }

    fun updateBinding(context: Context, binding: HotkeyBinding) {
        val list = getBindings(context).toMutableList()
        val idx = list.indexOfFirst { it.id == binding.id }
        if (idx >= 0) {
            list[idx] = binding
        } else {
            list.add(binding)
        }
        saveBindings(context, list)
    }

    fun removeBinding(context: Context, id: String) {
        val list = getBindings(context).toMutableList()
        list.removeAll { it.id == id }
        saveBindings(context, list)
    }

    fun invalidateCache() {
        cached = null
    }
}
