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

    val paypalHandle =
        PAYPAL_ME_HANDLE.trim()

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
                        text = "Patrick R. Grady",
                        style =
                            MaterialTheme.typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        text = "Software Engineer | AI Integration Specialist",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Text(
                        text = "Patrick builds full-stack systems and optimized mobile applications, with a focus on practical tools, intelligent workflows, and software that makes everyday experiences more useful and personal. He also enjoys reverse-engineering retro game architecture and creating projects inspired by the games and technology he loves. Much of his creativity is inspired by his loving twin boys, who motivate him to keep learning, building, and creating meaningful experiences.",
                        style =
                            MaterialTheme.typography
                                .bodyMedium
                    )
                }
            }

            item {
                AboutCard {
                    Text(
                        text = "Find Me Online",
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
                        label = "LinkedIn",
                        url = "https://www.linkedin.com/in/patgrady64/",
                        context = context
                    )

                    LinkButton(
                        label = "GitHub",
                        url = "https://github.com/patgrady64",
                        context = context
                    )

                    LinkButton(
                        label = "YouTube",
                        url = "https://www.youtube.com/@iminvisibl2u",
                        context = context
                    )

                    LinkButton(
                        label = "Twitch",
                        url = "https://www.twitch.tv/iminvizibl2u",
                        context = context
                    )
                }
            }

            item {
                AboutCard {
                    Text(
                        text = "Support the Developer",
                        style =
                            MaterialTheme.typography
                                .titleLarge,
                        fontWeight =
                            FontWeight.Bold,
                        color = themeColor
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text = "PicRoulette is free to use. Tips are completely optional. 100% of each tip goes directly to Patrick, and no features, content, badges, or recognition are provided in return.",
                        style =
                            MaterialTheme.typography
                                .bodyMedium
                    )

                    Spacer(
                        modifier = Modifier.height(14.dp)
                    )

                    TipOptionButton(
                        label = "Buy a Coffee",
                        price = "$0.99",
                        amountForUrl = "0.99USD",
                        paypalHandle = paypalHandle,
                        context = context
                    )

                    TipOptionButton(
                        label = "Support the App",
                        price = "$2.99",
                        amountForUrl = "2.99USD",
                        paypalHandle = paypalHandle,
                        context = context
                    )

                    TipOptionButton(
                        label = "Extra Support",
                        price = "$4.99",
                        amountForUrl = "4.99USD",
                        paypalHandle = paypalHandle,
                        context = context
                    )

                    if (paypalHandle.isBlank()) {
                        Text(
                            text = "To activate the tip buttons, put your PayPal.Me name in PAYPAL_ME_HANDLE inside TipJarConfig.kt.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
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

@Composable
private fun TipOptionButton(
    label: String,
    price: String,
    amountForUrl: String,
    paypalHandle: String,
    context: Context
) {
    Button(
        onClick = {
            openUrl(
                context = context,
                url =
                    "https://www.paypal.me/" +
                            paypalHandle +
                            "/" +
                            amountForUrl
            )
        },
        enabled = paypalHandle.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("$label — $price")
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