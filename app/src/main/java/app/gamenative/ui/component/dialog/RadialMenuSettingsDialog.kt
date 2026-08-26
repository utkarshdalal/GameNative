package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.RadialMenu
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadialMenuSettingsContent(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val menu = remember(profile) { profile.defaultRadialMenu }
    val initialSlots = remember(menu) { menu.slots.take(RadialMenu.MAX_SLOTS) }
    var slotCount by remember {
        mutableIntStateOf(initialSlots.size.coerceIn(1, RadialMenu.MAX_SLOTS))
    }
    val labels = remember(menu) {
        mutableStateListOf<String>().apply {
            repeat(RadialMenu.MAX_SLOTS) { index ->
                add(initialSlots.getOrNull(index)?.label.orEmpty())
            }
        }
    }
    val bindings = remember(menu) {
        mutableStateListOf<Binding>().apply {
            repeat(RadialMenu.MAX_SLOTS) { index ->
                add(initialSlots.getOrNull(index)?.binding ?: Binding.NONE)
            }
        }
    }
    var bindingSlotToEdit by remember { mutableStateOf<Int?>(null) }

    fun updateSlotCount(nextCount: Int) {
        slotCount = nextCount.coerceIn(1, RadialMenu.MAX_SLOTS)
    }

    fun applyPreset(preset: List<Pair<String, Binding>>) {
        slotCount = preset.size.coerceIn(1, RadialMenu.MAX_SLOTS)
        for (index in 0 until RadialMenu.MAX_SLOTS) {
            labels[index] = preset.getOrNull(index)?.first.orEmpty()
            bindings[index] = preset.getOrNull(index)?.second ?: Binding.NONE
        }
    }

    fun saveMenu() {
        val nextMenu = RadialMenu().apply {
            id = menu.id
            name = menu.name
        }
        for (index in 0 until slotCount) {
            nextMenu.addSlot(RadialMenu.Slot(labels[index], bindings[index]))
        }
        profile.defaultRadialMenu = nextMenu
        profile.save()
        onSave()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.radial_menu_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = ::saveMenu) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(text = stringResource(R.string.radial_menu_slot_count))
            SlotCountSetting(
                slotCount = slotCount,
                onSlotCountChange = ::updateSlotCount,
            )

            SectionHeader(text = stringResource(R.string.quick_presets))
            PresetButtons(
                onCommonPreset = {
                    applyPreset(
                        listOf(
                            "Inventory" to Binding.KEY_I,
                            "Map" to Binding.KEY_M,
                            "Journal" to Binding.KEY_J,
                            "Quick Save" to Binding.KEY_F5,
                            "Quick Load" to Binding.KEY_F9,
                            "Escape" to Binding.KEY_ESC,
                            "Tab" to Binding.KEY_TAB,
                            "Keyboard" to Binding.SHOW_KEYBOARD,
                        ),
                    )
                },
                onWeaponsPreset = {
                    applyPreset((1..8).map { "Weapon $it" to Binding.valueOf("KEY_$it") })
                },
            )

            SectionHeader(text = stringResource(R.string.radial_menu_slots))
            for (index in 0 until slotCount) {
                SlotSetting(
                    index = index,
                    label = labels[index],
                    binding = bindings[index],
                    onLabelChange = { labels[index] = it },
                    onEditBinding = { bindingSlotToEdit = index },
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    bindingSlotToEdit?.let { slotIndex ->
        ControllerBindingDialog(
            buttonName = stringResource(R.string.radial_menu_slot_binding, slotIndex + 1),
            currentBinding = bindings[slotIndex].takeIf { it != Binding.NONE },
            onDismiss = { bindingSlotToEdit = null },
            onBindingSelected = { binding ->
                bindings[slotIndex] = if (binding == Binding.OPEN_RADIAL_MENU) Binding.NONE else binding ?: Binding.NONE
                bindingSlotToEdit = null
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PresetButtons(
    onCommonPreset: () -> Unit,
    onWeaponsPreset: () -> Unit,
) {
    val buttonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onCommonPreset,
            modifier = Modifier.weight(1f),
            colors = buttonColors,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.radial_menu_preset_common))
        }
        OutlinedButton(
            onClick = onWeaponsPreset,
            modifier = Modifier.weight(1f),
            colors = buttonColors,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.radial_menu_preset_weapons))
        }
    }
}

@Composable
private fun SlotCountSetting(
    slotCount: Int,
    onSlotCountChange: (Int) -> Unit,
) {
    fun changeSlotCount(delta: Int) {
        onSlotCountChange((slotCount + delta).coerceIn(1, RadialMenu.MAX_SLOTS))
    }

    val dpadSlotCountModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionLeft -> {
                changeSlotCount(-1)
                true
            }
            Key.DirectionRight -> {
                changeSlotCount(1)
                true
            }
            else -> false
        }
    }

    SettingsMenuLink(
        modifier = dpadSlotCountModifier,
        colors = settingsTileColors(),
        title = { Text(stringResource(R.string.radial_menu_slot_count)) },
        subtitle = { Text(stringResource(R.string.radial_menu_slot_count_subtitle, slotCount)) },
        onClick = {},
    )
    Row(
        modifier = dpadSlotCountModifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = { changeSlotCount(-1) },
            enabled = slotCount > 1,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = stringResource(R.string.radial_menu_decrease_slots),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = slotCount.toFloat(),
            onValueChange = { onSlotCountChange(it.roundToInt()) },
            valueRange = 1f..RadialMenu.MAX_SLOTS.toFloat(),
            steps = RadialMenu.MAX_SLOTS - 2,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                activeTickColor = MaterialTheme.colorScheme.primary,
                inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = slotCount.toString(),
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(
            onClick = { changeSlotCount(1) },
            enabled = slotCount < RadialMenu.MAX_SLOTS,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.radial_menu_increase_slots),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SlotSetting(
    index: Int,
    label: String,
    binding: Binding,
    onLabelChange: (String) -> Unit,
    onEditBinding: () -> Unit,
) {
    val bindingText = binding.takeIf { it != Binding.NONE }?.toString() ?: stringResource(R.string.not_set)
    SettingsMenuLink(
        colors = if (index % 2 == 0) settingsTileColorsAlt() else settingsTileColors(),
        title = { Text(stringResource(R.string.radial_menu_slot_title, index + 1)) },
        subtitle = { Text(stringResource(R.string.radial_menu_slot_binding_summary, bindingText)) },
        action = {
            NoExtractOutlinedTextField(
                value = label,
                onValueChange = onLabelChange,
                modifier = Modifier.width(128.dp),
                placeholder = { Text(stringResource(R.string.radial_menu_slot_label_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
        },
        onClick = onEditBinding,
    )
}
