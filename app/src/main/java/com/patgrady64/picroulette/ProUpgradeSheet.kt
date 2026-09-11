package com.patgrady64.picroulette

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUpgradeSheet(
    installationId: String,
    isPro: Boolean,
    onActivate: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var activationCode by remember { mutableStateOf("") }
    var activationMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("PicRoulette Pro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Create custom albums, protect your favorites, and choose where their edited copies are stored.")
            Text(
                "• Create and manage Albums\n" +
                    "• Add photos to multiple Albums\n" +
                    "• Start an Album Roulette\n" +
                    "• Change the PR_FAVS location\n" +
                    "• Back up and restore Favorites"
            )

            Spacer(Modifier.height(4.dp))
            Text("Complimentary Pro activation", fontWeight = FontWeight.Bold)
            Text(
                "Send this Installation ID to the person providing your complimentary Pro code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(installationId, fontWeight = FontWeight.Bold)
                TextButton(onClick = { clipboard.setText(AnnotatedString(installationId)) }) {
                    Text("Copy ID")
                }
            }

            OutlinedTextField(
                value = activationCode,
                onValueChange = {
                    activationCode = it
                    activationMessage = null
                },
                label = { Text("Activation code") },
                placeholder = { Text("PRA1-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2
            )

            Button(
                onClick = {
                    activationMessage = if (onActivate(activationCode)) {
                        "Complimentary PicRoulette Pro activated."
                    } else {
                        "That code is not valid for this installation."
                    }
                },
                enabled = activationCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Activate Pro") }

            activationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (isPro) {
                Text(
                    "PicRoulette Pro is active on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    "A one-time Pro purchase will also be available when PicRoulette is published on Google Play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
