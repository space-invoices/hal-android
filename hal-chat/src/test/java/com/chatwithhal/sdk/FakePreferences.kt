package com.chatwithhal.sdk

import android.content.SharedPreferences

/** In-memory SharedPreferences: strings only, which is all VisitorStore uses. */
internal class FakePreferences : SharedPreferences {
    private val values = mutableMapOf<String, String?>()

    override fun getString(key: String, defValue: String?) = values[key] ?: defValue
    override fun contains(key: String) = key in values
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun commit() = true
        override fun apply() {}
    }
}
