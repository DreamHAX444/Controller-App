package com.livetracker.controller.auth.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
    val authState by viewModel.authState.collectAsState()
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
            // App Logo or Icon placeholder
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LTC", 
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            val message = when (authState) {
                is AuthState.PinNotConfigured -> "Create a 4-digit PIN"
                is AuthState.SetupConfirm -> "Confirm your PIN"
                is AuthState.Locked -> "Enter your PIN"
                is AuthState.Verifying -> "Verifying..."
                is AuthState.Error -> (authState as AuthState.Error).message
                else -> "Initializing..."
            }
            
            val textColor = if (authState is AuthState.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            }

            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                color = textColor,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // PIN Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until 4) {
                    val isFilled = i < input.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (isFilled) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
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
                            Text("⌫", fontSize = 24.sp)
                        }
                    } else {
                        Button(
                            onClick = { onDigitClick(item) },
                            enabled = enabled,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(item, fontSize = 28.sp)
                        }
                    }
                }
            }
        }
    }
}
