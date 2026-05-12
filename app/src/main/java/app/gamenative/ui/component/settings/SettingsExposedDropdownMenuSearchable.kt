import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

//@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsExposedDropdownMenuSearchable(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enableSearch: Boolean = true,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    content: @Composable ColumnScope.(searchText: String) -> Unit
) {
    Column {
        if (enableSearch) {
            TextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                singleLine = true
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
        ) {
            content(searchText)
        }
    }
}
