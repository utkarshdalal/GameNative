package app.gamenative.service

import app.gamenative.PluviaApp
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.xenvironment.XEnvironment
import timber.log.Timber

/**
 * Manages the lifecycle of game session components to prevent memory leaks.
 * These components were previously held in PluviaApp's companion object,
 * which caused leaks because they often hold references to Activity contexts.
 */
object GameSessionManager {

    /**
     * Clears all game session related references.
     * This should be called when a game session ends or the XServer screen is destroyed.
     */
    fun clearSession() {
        Timber.d("Clearing game session components")
        
        // Stop environment components before nulling the reference
        PluviaApp.xEnvironment?.stopEnvironmentComponents()
        PluviaApp.xEnvironment = null
        
        // Null out view references which hold Activity contexts
        PluviaApp.xServerView = null
        PluviaApp.inputControlsView = null
        PluviaApp.touchpadView = null
        
        // Manager might also hold references to context/views
        PluviaApp.inputControlsManager = null
        PluviaApp.onDestinationChangedListener = null
    }

    /**
     * Safely updates the environment reference.
     */
    fun setEnvironment(environment: XEnvironment?) {
        PluviaApp.xEnvironment = environment
    }

    /**
     * Safely updates the XServer view reference.
     */
    fun setXServerView(view: XServerView?) {
        PluviaApp.xServerView = view
    }
}
