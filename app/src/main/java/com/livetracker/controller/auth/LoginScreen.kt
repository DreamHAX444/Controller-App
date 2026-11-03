package com.livetracker.controller.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    onLoginSubmit: (String) -> Unit,
    onGoogleSignIn: () -> Unit = {},
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    var isSignUpMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val isEmailValid = remember(email) {
        Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(email.trim())
    }
    val isPasswordValid = password.length >= 6
    val doPasswordsMatch = password == confirmPassword

    val canSubmitSignIn = email.isNotBlank() && password.isNotBlank() && !isLoading
    val canSubmitSignUp = name.isNotBlank() && isEmailValid && isPasswordValid && doPasswordsMatch && !isLoading

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Tactical Branding
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E222A),
                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSignUpMode) Icons.Default.PersonAdd else Icons.Default.Lock,
                        contentDescription = "Trace Secure Gate",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "TRACE",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 4.sp
            )

            Text(
                text = "CONTROLLER SYSTEM v1.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Auth Mode Segmented Selector (Sign In vs Sign Up)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1A1E24),
                border = BorderStroke(1.dp, Color(0xFF2E3440)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (!isSignUpMode) Color(0xFF00E5FF).copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable(enabled = !isLoading) { isSignUpMode = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SIGN IN",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (!isSignUpMode) FontWeight.Bold else FontWeight.Normal,
                            color = if (!isSignUpMode) Color(0xFF00E5FF) else Color(0xFF8C93A0),
                            letterSpacing = 1.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (isSignUpMode) Color(0xFF00E5FF).copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable(enabled = !isLoading) { isSignUpMode = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SIGN UP",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSignUpMode) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSignUpMode) Color(0xFF00E5FF) else Color(0xFF8C93A0),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Conditional Sign Up: Full Name
            if (isSignUpMode) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Email Address
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Password
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (isSignUpMode) "Password (min 6 characters)" else "Password", fontSize = 13.sp) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            text = if (passwordVisible) "HIDE" else "SHOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            // Conditional Sign Up: Confirm Password
            if (isSignUpMode) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password", fontSize = 13.sp) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                if (confirmPassword.isNotEmpty() && !doPasswordsMatch) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Passwords do not match",
                        color = Color(0xFFCF6679),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Start)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Action Button
            Button(
                onClick = {
                    if (isSignUpMode) {
                        onLoginSubmit("signup:${email.trim()}|${password.trim()}|${name.trim()}")
                    } else {
                        onLoginSubmit("${email.trim()}:${password.trim()}")
                    }
                },
                enabled = if (isSignUpMode) canSubmitSignUp else canSubmitSignIn,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (isSignUpMode) "Create Account & Sign In" else "Sign In with Email",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "  OR  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Authentication: Google Sign-In (Auto login & auto signup)
            GoogleSignInButton(
                onClick = onGoogleSignIn,
                isLoading = isLoading,
                enabled = !isLoading
            )

            if (isSignUpMode) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "No separate sign up needed with Google",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00E5FF).copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Switcher Link
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isSignUpMode) "Already have an account?" else "Don't have an account?",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8C93A0)
                )
                TextButton(
                    onClick = { isSignUpMode = !isSignUpMode },
                    enabled = !isLoading
                ) {
                    Text(
                        text = if (isSignUpMode) "Sign In" else "Sign Up",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2A1515),
                    border = BorderStroke(1.dp, Color(0xFFCF6679)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Authentication Notice",
                            color = Color(0xFFCF6679),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = errorMessage,
                            color = Color(0xFFE0B4B4),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
