package app.gamenative.ui.component.dialog

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.inputcontrols.Binding

/**
 * Dialog for selecting controller button bindings.
 *
 * Provides a searchable, categorized list of available bindings (keyboard, mouse, gamepad)
 * with search functionality for quick filtering.
 *
 * @param buttonName Display name of the button being configured
 * @param currentBinding Currently assigned binding (if any)
 * @param onDismiss Callback when dialog is dismissed without selection
 * @param onBindingSelected Callback when a binding is selected (null for NONE)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerBindingDialog(
    buttonName: String,
    currentBinding: Binding?,
    onDismiss: () -> Unit,
    onBindingSelected: (Binding?) -> Unit
) {

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Int?>(0) } // 0 = Keyboard, 1 = Mouse, 2 = Gamepad, 3 = Extra, null = All
    var isSearchExpanded by remember { mutableStateOf(false) }

    // Get bindings by category
    val keyboardBindings = remember { Binding.keyboardBindingValues().toList() }
    val mouseBindings = remember { Binding.mouseBindingValues().toList() }
    val gamepadBindings = remember { Binding.gamepadBindingValues().toList() }
    val extraBindings = remember { Binding.extraBindingValues().toList() }

    // Data class to hold binding with its category for cross-category search
    data class BindingWithCategory(val binding: Binding, val categoryIndex: Int)

    // All bindings for cross-category search
    val allBindings = remember {
        keyboardBindings.map { BindingWithCategory(it, 0) } +
        mouseBindings.map { BindingWithCategory(it, 1) } +
        gamepadBindings.map { BindingWithCategory(it, 2) } +
        extraBindings.map { BindingWithCategory(it, 3) }
    }

    // Filter bindings based on search and selected category
    val filteredBindingsWithCategory = remember(searchQuery, selectedCategory) {
        if (searchQuery.isBlank()) {
            // No search - show bindings from selected category
            val currentBindings = when (selectedCategory) {
                0 -> keyboardBindings
                1 -> mouseBindings
                2 -> gamepadBindings
                3 -> extraBindings
                else -> emptyList()
            }
            currentBindings.map { BindingWithCategory(it, selectedCategory ?: 0) }
        } else {
            // Search mode - filter all or specific category
            val searchResults = allBindings.filter {
                it.binding.toString().contains(searchQuery, ignoreCase = true)
            }

            // If a category is selected, filter to only that category
            if (selectedCategory != null) {
                searchResults.filter { it.categoryIndex == selectedCategory }
            } else {
                searchResults
            }
        }
    }

    val filteredBindings = filteredBindingsWithCategory.map { it.binding }

    // Count matches per category for search mode
    val categoryMatchCounts = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            null
        } else {
            mapOf(
                0 to allBindings.count { it.categoryIndex == 0 && it.binding.toString().contains(searchQuery, ignoreCase = true) },
                1 to allBindings.count { it.categoryIndex == 1 && it.binding.toString().contains(searchQuery, ignoreCase = true) },
                2 to allBindings.count { it.categoryIndex == 2 && it.binding.toString().contains(searchQuery, ignoreCase = true) },
                3 to allBindings.count { it.categoryIndex == 3 && it.binding.toString().contains(searchQuery, ignoreCase = true) }
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,  // Allow custom width beyond platform default
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.98f)  // Nearly full width for better space utilization
                .fillMaxHeight(0.92f),  // Taller to maximize vertical space
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with title, current binding, and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Title and current binding
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(app.gamenative.R.string.bind_button, buttonName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (currentBinding != null) {
                            Text(
                                text = stringResource(app.gamenative.R.string.current_binding, currentBinding.toString()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                // Two-column layout: Left = Controls, Right = Bindings list
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left column: Search and Category selection
                    Column(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Collapsible search - starts as icon button, expands to full search field
                        if (!isSearchExpanded) {
                            // Compact search button
                            OutlinedButton(
                                onClick = { isSearchExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(app.gamenative.R.string.search_placeholder),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            // Expanded search field
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    // When search starts, switch to "all categories" mode
                                    if (it.isNotBlank() && selectedCategory != null) {
                                        selectedCategory = null
                                    }
                                },
                                placeholder = { Text(stringResource(app.gamenative.R.string.search_placeholder), style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            selectedCategory = 0 // Return to Keyboard category
                                            isSearchExpanded = false // Collapse search
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(app.gamenative.R.string.clear_search),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp), // Even more compact when expanded
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Category buttons (vertical stack)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(app.gamenative.R.string.category),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            // Helper function to render category button
                            @Composable
                            fun CategoryButton(
                                categoryIndex: Int,
                                label: String,
                                matchCount: Int? = null
                            ) {
                                val isSelected = selectedCategory == categoryIndex
                                val isSearching = searchQuery.isNotBlank()

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isSearching) {
                                                // In search mode: clicking filters to this category
                                                selectedCategory = if (selectedCategory == categoryIndex) null else categoryIndex
                                            } else {
                                                // Normal mode: switch to this category
                                                selectedCategory = categoryIndex
                                            }
                                        },
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp), // Slightly reduced padding for smaller screens
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (matchCount != null && isSearching) {
                                            Text(
                                                text = "($matchCount)",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            // Keyboard button
                            CategoryButton(
                                categoryIndex = 0,
                                label = stringResource(app.gamenative.R.string.keyboard),
                                matchCount = categoryMatchCounts?.get(0)
                            )

                            // Mouse button
                            CategoryButton(
                                categoryIndex = 1,
                                label = stringResource(app.gamenative.R.string.mouse),
                                matchCount = categoryMatchCounts?.get(1)
                            )

                            // Gamepad button
                            CategoryButton(
                                categoryIndex = 2,
                                label = stringResource(app.gamenative.R.string.gamepad),
                                matchCount = categoryMatchCounts?.get(2)
                            )

                            // Extra button
                            CategoryButton(
                                categoryIndex = 3,
                                label = stringResource(app.gamenative.R.string.extra),
                                matchCount = categoryMatchCounts?.get(3)
                            )

                            // Clear Binding button - more compact for smaller screens
                            if (currentBinding != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            Log.d("ControllerBindingDialog", "Clearing binding for $buttonName")
                                            onBindingSelected(null)
                                        },
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp), // Reduced padding for smaller screens
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp) // Slightly smaller icon
                                        )
                                        Text(
                                            text = stringResource(app.gamenative.R.string.clear_binding),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right column: Bindings list
                    Column(
                        modifier = Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (filteredBindings.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(app.gamenative.R.string.no_bindings_found),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            filteredBindings.forEach { binding ->
                                BindingOption(
                                    binding = binding,
                                    isSelected = binding == currentBinding,
                                    onClick = {
                                        Log.d("ControllerBindingDialog", "Binding selected for $buttonName: ${binding.name}")
                                        onBindingSelected(binding)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BindingOption(
    binding: Binding,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = binding.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
