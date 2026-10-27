package com.v2ray.ang.ui.qsn

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/**
 * The "add configuration" dialog, shared by the Connection and the
 * Configurations screens so both entry points behave identically.
 *
 * It only collects text: validation, parsing, duplicate detection and the
 * eligibility rule all happen in [QsViewModel.importManualConfig], which
 * delegates to the existing batch importer and parsers.
 *
 * The Paste button reads the clipboard once, on an explicit user tap; the
 * dialog never polls it.
 */
@Composable
fun QsAddConfigDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qs_import_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.qs_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(
                        onClick = {
                            val pasted = clipboardText(context)
                            if (pasted != null) text = pasted
                        },
                    ) {
                        Text(stringResource(R.string.qs_paste))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 6,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.qs_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.qs_cancel))
            }
        },
    )
}

/**
 * The clipboard's text, or null when it is empty, unavailable or not text.
 *
 * Kept as a plain function so the dialog stays free of clipboard state and the
 * read happens only when the user asks for it.
 */
internal fun clipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)
        .coerceToText(context)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
