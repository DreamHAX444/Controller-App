package com.livetracker.controller.auth.pin

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun PinScreen(
    viewModel: PinViewModel,
    onAuthenticated: () -> Unit
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    // Automatically submit when 4 digits are entered
    LaunchedEffect(input) {
        if (input.length == 4) {
            viewModel.submitPin(input)
            // Wait slightly before clearing to show the 4th dot
            delay(200)
            input = ""
        }
    }

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            onAuthenticated()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isError = authState is AuthState.Error
            val badgeAccent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = badgeAccent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, badgeAccent.copy(alpha = 0.35f)),
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (authState is AuthState.Locked) Icons.Default.Lock else Icons.Default.Shield,
                        contentDescription = "Trace Security PIN",
                        tint = badgeAccent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val message = when (authState) {
                is AuthState.PinNotConfigured -> "Create a 4-digit PIN"
                is AuthState.SetupConfirm -> "Confirm your PIN"
                is AuthState.Locked -> "Enter your PIN"
                is AuthState.Verifying -> "Verifying..."
                is AuthState.Error -> (authState as AuthState.Error).message
                else -> "Initializing..."
            }

            val subtitle = when (authState) {
                is AuthState.PinNotConfigured -> "Protect your controller session with a local passcode"
                is AuthState.SetupConfirm -> "Re-enter the 4-digit passcode to confirm"
                is AuthState.Locked -> "Unlock Trace Controller session"
                is AuthState.Verifying -> "Validating security credentials"
                is AuthState.Error -> "Please check your passcode and try again"
                else -> "Preparing security module"
            }

            val textColor = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            }

            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // PIN Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(24.dp)
            ) {
                for (i in 0 until 4) {
                    val isFilled = i < input.length
                    val dotSize by animateDpAsState(
                        targetValue = if (isFilled) 18.dp else 14.dp,
                        animationSpec = tween(durationMillis = 150),
                        label = "pin_dot_size"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = when {
                            isError -> MaterialTheme.colorScheme.error
                            isFilled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        animationSpec = tween(durationMillis = 150),
                        label = "pin_dot_color"
                    )
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(dotColor)
                            .border(
                                width = 1.dp,
                                color = if (isFilled) badgeAccent else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Number Pad
            NumberPad(
                enabled = authState !is AuthState.Verifying && authState !is AuthState.Initializing && (authState !is AuthState.Error || (authState as AuthState.Error).lockoutTimeMs == 0L),
                onDigitClick = { digit ->
                    if (input.length < 4) {
                        input += digit
                        viewModel.resetError()
                    }
                },
                onBackspaceClick = {
                    if (input.isNotEmpty()) {
                        input = input.dropLast(1)
                        viewModel.resetError()
                    }
                }
            )
        }
    }
}

@Composable
fun NumberPad(
    enabled: Boolean,
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "del")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                for (item in row) {
                    if (item.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else if (item == "del") {
                        FilledTonalButton(
                            onClick = onBackspaceClick,
                            enabled = enabled,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Delete",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Surface(
                            onClick = { onDigitClick(item) },
                            enabled = enabled,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = item,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
