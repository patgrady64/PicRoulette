package com.patgrady64.picroulette

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSupportSheet(
    themeColor: Color,
    onDismiss: () -> Unit
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current

    val versionName =
        remember(context) {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    0
                )
                .versionName
                ?: "Unknown"
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 32.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "PicRoulette",
                        style =
                            MaterialTheme.typography
                                .headlineMedium,
                        fontWeight =
                            FontWeight.Black
                    )

                    Text(
                        text = "Rediscover your library",
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        text = "Version $versionName",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }

            item {
                AboutCard {
                    Text(
                        text = "About PicRoulette",
                        style =
                            MaterialTheme.typography
                                .titleLarge,
                        fontWeight =
                            FontWeight.Bold,
                        color = themeColor
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Text(
                        text = "PicRoulette is a different way to rediscover the photos already on your device. Instead of endlessly scrolling through a gallery, PicRoulette turns your photo collection into a randomized experience—bringing back forgotten pictures, favorite moments, and memories you may not have seen in years.\n\nCreate albums, build custom roulettes, save favorites, and explore your collection one photo at a time. PicRoulette is designed to keep the experience simple, private, and focused on your photos.",
                        style =
                            MaterialTheme.typography
                                .bodyMedium
                    )
                }
            }

            item {
                AboutCard {
                    Text(
                        text = "About the Developer",
                        style =
                            MaterialTheme.typography
                                .titleLarge,
                        fontWeight =
                            FontWeight.Bold,
                        color = themeColor
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Text(
                        text = "Patrick R. Grady is an independent software developer and creator with a passion for turning interesting ideas into useful and enjoyable software. His work spans mobile apps, games, productivity tools, and other digital projects, with a focus on building experiences that are practical, intuitive, and a little different from the ordinary.\n\nPicRoulette grew from a simple idea: finding a more enjoyable way to rediscover the photos we already have instead of letting them disappear into an ever-growing camera roll.",
                        style =
                            MaterialTheme.typography
                                .bodyMedium
                    )
                }
            }

            item {
                AboutCard {
                    Text(
                        text = "Links",
                        style =
                            MaterialTheme.typography
                                .titleLarge,
                        fontWeight =
                            FontWeight.Bold,
                        color = themeColor
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    LinkButton(
                        label = "Website",
                        url = "https://patgrady64.vercel.app/",
                        context = context
                    )

                    LinkButton(
                        label = "Support",
                        url = "mailto:patgrady64@gmail.com?subject=PicRoulette%20Support",
                        context = context
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun LinkButton(
    label: String,
    url: String,
    context: Context
) {
    OutlinedButton(
        onClick = {
            openUrl(
                context = context,
                url = url
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)
    }

    Spacer(
        modifier = Modifier.height(8.dp)
    )
}

private fun openUrl(
    context: Context,
    url: String
) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            )
        )
    }.onFailure {
        Toast.makeText(
            context,
            "Could not open that link.",
            Toast.LENGTH_SHORT
        ).show()
    }
}
