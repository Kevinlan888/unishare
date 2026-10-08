package com.unishare.app.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.unishare.app.R

@Composable
fun <T> ManagementChoice(label: String, selected: T, choices: List<T>, display: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Popup creates another Android composition owner. Resolve labels in this locale first.
    val selectedLabel = display(selected)
    val choiceLabels = choices.map { it to display(it) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.management_choice, label, selectedLabel))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choiceLabels.forEach { (choice, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(choice) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}
