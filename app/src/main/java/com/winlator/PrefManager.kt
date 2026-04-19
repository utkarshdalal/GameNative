package com.winlator

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Kind of ugly, but works to be a universal preference manager.
 */
object PrefManager {

    private val Context.datastore by preferencesDataStore(
        name = "WinlatorPreferences",
        corruptionHandler = ReplaceFileCorruptionHandler {
            Timber.e("Preferences (somehow got) corrupted, resetting.")
            emptyPreferences()
        },
    )

    private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("PrefManager"))

    private var dataStore: DataStore<Preferences>? = null

    @JvmStatic
    fun init(context: Context) {
        if (dataStore == null) {
            // Pre-create the datastore directory so DataStore doesn't throw FileNotFoundException
            // (ENOENT) on first launch — DataStore 1.1.x bug on some Android 13 devices.
            runCatching { context.applicationContext.filesDir.resolve("datastore").mkdirs() }
            dataStore = context.datastore
        }
    }

    @JvmStatic
    fun deInit() {
        dataStore = null
    }

    @JvmStatic
    fun getString(key: String, defaultValue: String): String =
        getPref(stringPreferencesKey(key), defaultValue)

    @JvmStatic
    fun putString(key: String, value: String): CompletableFuture<Unit> {
        return setPref(stringPreferencesKey(key), value)
    }

    @JvmStatic
    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getPref(booleanPreferencesKey(key), defaultValue)

    @JvmStatic
    fun putBoolean(key: String, value: Boolean): CompletableFuture<Unit> {
        return setPref(booleanPreferencesKey(key), value)
    }

    private fun <T> getPref(key: Preferences.Key<T>, defaultValue: T): T = runBlocking {
        try {
            dataStore!!.data.first()[key] ?: defaultValue
        } catch (e: Exception) {
            // DataStore 1.1.x bug: missing file can propagate as FileNotFoundException
            // instead of returning emptyPreferences() on some Android 13 devices.
            Timber.w(e, "WinlatorPrefManager: failed to read pref '${key.name}', returning default")
            defaultValue
        }
    }

    private fun <T> setPref(key: Preferences.Key<T>, value: T): CompletableFuture<Unit> {
        return scope.future {
            dataStore!!.edit { pref -> pref[key] = value }
        }
    }
}
