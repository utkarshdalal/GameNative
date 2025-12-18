package app.gamenative.theme.runtime

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import timber.log.Timber

/**
 * Manages spatial focus navigation for themed UI elements.
 * 
 * This manager tracks focusable elements and their screen positions,
 * allowing navigation based on actual visual placement rather than
 * composition tree order.
 */
class SpatialFocusManager {
    
    /**
     * Explicit navigation overrides for an element.
     * When set, these take priority over spatial navigation.
     */
    data class NavigationLinks(
        val up: String? = null,
        val down: String? = null,
        val left: String? = null,
        val right: String? = null,
    )
    
    data class FocusableElement(
        val id: String,
        val bounds: Rect,
        val focusRequester: FocusRequester,
        val navigationLinks: NavigationLinks = NavigationLinks()
    )
    
    enum class Direction {
        UP, DOWN, LEFT, RIGHT
    }
    
    private val elements = mutableStateMapOf<String, FocusableElement>()
    private var currentFocusedId: String? = null
    
    /**
     * Register a focusable element with its screen bounds.
     * Should be called from onGloballyPositioned modifier.
     * 
     * @param id Unique identifier for the element
     * @param bounds Screen bounds of the element
     * @param focusRequester FocusRequester to request focus on this element
     * @param navigationLinks Optional explicit navigation overrides
     */
    fun register(
        id: String,
        bounds: Rect,
        focusRequester: FocusRequester,
        navigationLinks: NavigationLinks = NavigationLinks()
    ) {
        elements[id] = FocusableElement(id, bounds, focusRequester, navigationLinks)
        Timber.tag(TAG).d("Registered '$id' at bounds: left=${bounds.left.toInt()}, top=${bounds.top.toInt()}, right=${bounds.right.toInt()}, bottom=${bounds.bottom.toInt()}, links=${navigationLinks}")
    }
    
    /**
     * Unregister a focusable element (e.g., when it's removed from composition).
     */
    fun unregister(id: String) {
        elements.remove(id)
        if (currentFocusedId == id) {
            currentFocusedId = null
        }
    }
    
    /**
     * Mark an element as currently focused.
     */
    fun setFocused(id: String) {
        currentFocusedId = id
    }
    
    /**
     * Get the currently focused element ID.
     */
    fun getCurrentFocusedId(): String? = currentFocusedId
    
    /**
     * Navigate directly to a specific element by ID.
     * Returns true if navigation was successful.
     */
    fun navigateTo(targetId: String): Boolean {
        val target = elements[targetId] ?: run {
            Timber.tag(TAG).d("Navigate to '$targetId' - element not found! Registered: ${elements.keys}")
            return false
        }
        Timber.tag(TAG).d("Navigate directly to '$targetId'")
        return requestFocusOn(target)
    }
    
    /**
     * Navigate from the specified element in the given direction.
     * First checks for explicit navigation links, then falls back to spatial navigation.
     * Returns true if navigation was successful.
     */
    fun navigateInDirection(fromId: String, direction: Direction): Boolean {
        val fromElement = elements[fromId] ?: run {
            Timber.tag(TAG).d("Navigate from '$fromId' $direction - element not found! Registered: ${elements.keys}")
            return false
        }
        
        Timber.tag(TAG).d("Navigate from '$fromId' $direction (bounds: ${fromElement.bounds})")
        
        // Check for explicit navigation link first
        val explicitTargetId = getExplicitTarget(fromElement, direction)
        if (explicitTargetId != null) {
            val explicitTarget = elements[explicitTargetId]
            if (explicitTarget != null) {
                Timber.tag(TAG).d("Using explicit navigation link to '$explicitTargetId'")
                return requestFocusOn(explicitTarget)
            } else {
                Timber.tag(TAG).w("Explicit navigation target '$explicitTargetId' not found, falling back to spatial")
            }
        }
        
        // Fall back to spatial navigation
        val target = findNearestInDirection(fromElement.bounds, direction, fromId)
        
        return if (target != null) {
            Timber.tag(TAG).d("Found target '${target.id}' for $direction navigation (spatial)")
            requestFocusOn(target)
        } else {
            Timber.tag(TAG).d("No target found for $direction navigation from '$fromId'")
            false
        }
    }
    
    /**
     * Get the explicit navigation target ID for the given direction, if set.
     */
    private fun getExplicitTarget(element: FocusableElement, direction: Direction): String? {
        return when (direction) {
            Direction.UP -> element.navigationLinks.up
            Direction.DOWN -> element.navigationLinks.down
            Direction.LEFT -> element.navigationLinks.left
            Direction.RIGHT -> element.navigationLinks.right
        }
    }
    
    /**
     * Request focus on the target element.
     */
    private fun requestFocusOn(target: FocusableElement): Boolean {
        return try {
            target.focusRequester.requestFocus()
            currentFocusedId = target.id
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to request focus on '${target.id}'")
            false
        }
    }
    
    /**
     * Find the nearest element in the specified direction from the source bounds.
     * Uses a two-pass algorithm:
     * 1. First, prefer elements that are "aligned" (overlapping on the secondary axis)
     * 2. If no aligned elements, fall back to all candidates
     */
    private fun findNearestInDirection(
        from: Rect,
        direction: Direction,
        excludeId: String
    ): FocusableElement? {
        Timber.tag(TAG).d("Finding nearest $direction from bounds: $from")
        Timber.tag(TAG).d("All registered elements: ${elements.keys}")
        
        val allCandidates = elements.values.filter { element ->
            val isCandidate = element.id != excludeId && isInDirection(from, element.bounds, direction)
            if (element.id != excludeId) {
                Timber.tag(TAG).d("Checking '${element.id}' - isInDirection($direction): $isCandidate, bounds: ${element.bounds}")
            }
            isCandidate
        }
        
        if (allCandidates.isEmpty()) {
            Timber.tag(TAG).d("No candidates found for $direction")
            return null
        }
        
        // First pass: find elements that are "aligned" (overlapping on secondary axis)
        val alignedCandidates = allCandidates.filter { element ->
            isAligned(from, element.bounds, direction)
        }
        
        // Use aligned candidates if any exist, otherwise fall back to all candidates
        val candidates = if (alignedCandidates.isNotEmpty()) {
            Timber.tag(TAG).d("Aligned candidates for $direction: ${alignedCandidates.map { it.id }}")
            alignedCandidates
        } else {
            Timber.tag(TAG).d("No aligned candidates, using all: ${allCandidates.map { it.id }}")
            allCandidates
        }
        
        // Score each candidate - lower score is better
        val fromCenterX = from.left + from.width / 2
        val fromCenterY = from.top + from.height / 2
        Timber.tag(TAG).d("Source center: ($fromCenterX, $fromCenterY)")
        
        val scored = candidates.map { element ->
            val candidateCenterX = element.bounds.left + element.bounds.width / 2
            val candidateCenterY = element.bounds.top + element.bounds.height / 2
            val score = calculateScore(from, element.bounds, direction)
            val vertDist = abs(candidateCenterY - fromCenterY)
            val horizDist = abs(candidateCenterX - fromCenterX)
            Timber.tag(TAG).d("Score for '${element.id}': $score (vertDist=$vertDist, horizDist=$horizDist, center=($candidateCenterX, $candidateCenterY))")
            element to score
        }
        
        return scored.minByOrNull { it.second }?.first
    }
    
    /**
     * Check if two bounds are "aligned" for the given direction.
     * 
     * For LEFT/RIGHT: elements are aligned if their vertical centers are close (same row)
     * For UP/DOWN: no alignment filtering - any element above/below is a candidate
     * 
     * This ensures:
     * - LEFT/RIGHT stays on the same row (filter ↔ add, search ↔ profile)
     * - UP/DOWN can reach elements even if they're not horizontally aligned
     */
    private fun isAligned(from: Rect, candidate: Rect, direction: Direction): Boolean {
        val fromCenterY = from.top + from.height / 2
        val candidateCenterY = candidate.top + candidate.height / 2
        
        return when (direction) {
            Direction.UP, Direction.DOWN -> {
                // No alignment filtering for UP/DOWN - any element above/below is a candidate
                // The scoring will pick the closest one
                true
            }
            Direction.LEFT, Direction.RIGHT -> {
                // Check if vertical centers are within a fixed threshold (same row)
                val verticalOffset = abs(candidateCenterY - fromCenterY)
                verticalOffset <= SAME_ROW_THRESHOLD
            }
        }
    }
    
    /**
     * Check if the candidate bounds are in the specified direction from the source.
     * Uses center points for more intuitive navigation, especially when elements overlap.
     * 
     * For UP/DOWN: Also excludes elements that are on the "same row" (vertical centers within threshold)
     * This prevents navigation between search ↔ profile via UP/DOWN
     */
    private fun isInDirection(from: Rect, candidate: Rect, direction: Direction): Boolean {
        val fromCenterX = from.left + from.width / 2
        val fromCenterY = from.top + from.height / 2
        val candidateCenterX = candidate.left + candidate.width / 2
        val candidateCenterY = candidate.top + candidate.height / 2
        
        return when (direction) {
            Direction.DOWN -> {
                // Candidate's center must be below AND not on the same row
                val verticalDiff = candidateCenterY - fromCenterY
                verticalDiff > SAME_ROW_THRESHOLD
            }
            Direction.UP -> {
                // Candidate's center must be above AND not on the same row
                val verticalDiff = fromCenterY - candidateCenterY
                verticalDiff > SAME_ROW_THRESHOLD
            }
            // Candidate's center is left of source's center
            Direction.LEFT -> candidateCenterX < fromCenterX
            // Candidate's center is right of source's center
            Direction.RIGHT -> candidateCenterX > fromCenterX
        }
    }
    
    /**
     * Calculate a score for the candidate element.
     * Lower scores indicate better navigation targets.
     * 
     * Uses center-to-center distance for more intuitive scoring:
     * - Primary axis distance (vertical for UP/DOWN, horizontal for LEFT/RIGHT)
     * - Secondary axis offset as a tiebreaker
     */
    private fun calculateScore(from: Rect, candidate: Rect, direction: Direction): Float {
        val fromCenterX = from.left + from.width / 2
        val fromCenterY = from.top + from.height / 2
        val candidateCenterX = candidate.left + candidate.width / 2
        val candidateCenterY = candidate.top + candidate.height / 2
        
        return when (direction) {
            Direction.DOWN, Direction.UP -> {
                // Primary: vertical center-to-center distance
                val verticalDistance = abs(candidateCenterY - fromCenterY)
                val horizontalOffset = abs(candidateCenterX - fromCenterX)
                verticalDistance + (horizontalOffset * SECONDARY_AXIS_WEIGHT)
            }
            Direction.LEFT, Direction.RIGHT -> {
                // Primary: horizontal center-to-center distance
                val horizontalDistance = abs(candidateCenterX - fromCenterX)
                val verticalOffset = abs(candidateCenterY - fromCenterY)
                horizontalDistance + (verticalOffset * SECONDARY_AXIS_WEIGHT)
            }
        }
    }
    
    companion object {
        private const val TAG = "SpatialFocus"
        
        // Multiplier for secondary axis offset when scoring
        // Higher values prefer elements more directly in the navigation direction
        // 0.2 means: 100px horizontal offset adds 20 to the score
        private const val SECONDARY_AXIS_WEIGHT = 0.2f
        
        // Fixed threshold in pixels for considering elements on the "same row"
        // Elements with vertical centers within this distance are on the same row
        // 200px works well for typical button heights (50-150px)
        private const val SAME_ROW_THRESHOLD = 200f
    }
}

/**
 * CompositionLocal for providing the SpatialFocusManager to themed components.
 */
val LocalSpatialFocusManager = compositionLocalOf<SpatialFocusManager?> { null }

