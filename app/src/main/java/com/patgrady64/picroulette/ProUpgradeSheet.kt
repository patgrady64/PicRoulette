package com.patgrady64.picroulette

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUpgradeSheet(onDismiss: () -> Unit) {
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
            Text(
                "One-time Pro purchase will be available when PicRoulette is published on Google Play.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
