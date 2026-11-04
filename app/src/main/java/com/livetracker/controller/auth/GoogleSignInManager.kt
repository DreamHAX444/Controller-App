@file:Suppress("DEPRECATION")

package com.livetracker.controller.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

sealed interface GoogleSignInResult {
    data class Success(
        val idToken: String,
        val email: String,
        val displayName: String = "",
        val photoUrl: String = ""
    ) : GoogleSignInResult

    object Cancelled : GoogleSignInResult
    data class Error(val message: String, val cause: Throwable? = null) : GoogleSignInResult
}

class GoogleSignInManager(private val context: Context) {
    private val credentialManager by lazy { CredentialManager.create(context) }

    /**
     * Executes the Google Sign-In flow:
     * 1. Tier 1: Modern Google Credential Manager (GIS bottom sheet).
     * 2. Tier 2: If Tier 1 encounters any exception (e.g. SHA-1 signature mismatch,
     *    unconfigured OAuth client, or Play Services error), gracefully cascades to launch
     *    the official Play Services GoogleSignInClient intent.
     * 3. Clean Cancellation: If the user cancels/dismisses, cleanly halts without errors.
     */
    suspend fun startSignIn(
        activity: ComponentActivity,
        serverClientId: String,
        fallbackLauncher: ActivityResultLauncher<Intent>,
        onResult: (GoogleSignInResult) -> Unit
    ) {
        if (serverClientId.isBlank()) {
            onResult(GoogleSignInResult.Error("Google Web Client ID is not configured."))
            return
        }

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = credentialManager.getCredential(activity, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val email = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                    ?: formatDisplayNameFromEmail(email)
                val photoUrl = googleIdTokenCredential.profilePictureUri?.toString()
                    ?: DEFAULT_AVATAR_URL
                val idToken = googleIdTokenCredential.idToken

                if (idToken.isBlank()) {
                    onResult(GoogleSignInResult.Error("Google Credential Manager returned an empty ID token."))
                    return
                }

                onResult(
                    GoogleSignInResult.Success(
                        idToken = idToken,
                        email = email,
                        displayName = displayName,
                        photoUrl = photoUrl
                    )
                )
                return
            }
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Credential Manager was cancelled by the user")
            onResult(GoogleSignInResult.Cancelled)
            return
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "Tier 1 CredentialManager encounter: ${e.javaClass.simpleName} (${e.message}). Cascading to GoogleSignInClient...",
                e
            )
        }

        // Tier 2: Launch Official Google Play Services Sign-In Intent
        try {
            val intent = buildFallbackIntent(serverClientId)
            fallbackLauncher.launch(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch GoogleSignInClient intent", e)
            onResult(
                GoogleSignInResult.Error(
                    message = "Unable to open Google Sign-In on this device: ${e.localizedMessage ?: "Unknown error"}",
                    cause = e
                )
            )
        }
    }

    /**
     * Builds the official Google Play Services GoogleSignInClient intent.
     * Guaranteed to display the Google Account Chooser bottom sheet/dialog on all Android OEMs.
     */
    fun buildFallbackIntent(serverClientId: String): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .apply {
                if (serverClientId.isNotBlank()) {
                    requestIdToken(serverClientId)
                }
            }
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        // Clear cached account to guarantee account picker dialog is presented on every tap
        client.signOut()
        return client.signInIntent
    }

    /**
     * Parses the result intent from GoogleSignInClient with strict result order,
     * exhaustive status code mapping, and guaranteed non-silent error paths.
     */
    fun parseSignInResult(resultCode: Int, data: Intent?): GoogleSignInResult {
        // Step 1: If data is present, inspect for completed account or specific ApiException FIRST.
        // Google Play Services may return resultCode = RESULT_CANCELED with an ApiException in data (e.g. Developer Error 10).
        if (data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account == null) {
                    return GoogleSignInResult.Error(
                        "Google account details could not be retrieved from sign-in response."
                    )
                }

                val idToken = account.idToken
                val email = account.email
                val displayName = account.displayName ?: formatDisplayNameFromEmail(email ?: "")
                val photoUrl = account.photoUrl?.toString() ?: DEFAULT_AVATAR_URL

                if (idToken.isNullOrBlank()) {
                    return GoogleSignInResult.Error(
                        "Google did not provide a verified ID token. Verify Web Client ID and SHA-1 certificate fingerprint in Google Cloud Console."
                    )
                }
                if (email.isNullOrBlank()) {
                    return GoogleSignInResult.Error("No email address provided by selected Google account.")
                }

                return GoogleSignInResult.Success(
                    idToken = idToken,
                    email = email,
                    displayName = displayName,
                    photoUrl = photoUrl
                )
            } catch (e: ApiException) {
                val message = when (e.statusCode) {
                    CommonStatusCodes.DEVELOPER_ERROR -> // 10
                        "Google Sign-In configuration error (Developer Error 10). The APK's signing certificate SHA-1 is not registered in Google Cloud Console, or package name (com.livetracker.controller) does not match OAuth client."
                    GoogleSignInStatusCodes.SIGN_IN_FAILED, 12500 -> // 12500
                        "Google Sign-In failed (Code 12500). Please check Google Play Services version, device internet connection, and system date/time."
                    GoogleSignInStatusCodes.SIGN_IN_CANCELLED, 12501 -> // 12501
                        return GoogleSignInResult.Cancelled
                    GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS, 12502 -> // 12502
                        "Google Sign-In is already in progress (Code 12502). Please wait and try again."
                    CommonStatusCodes.NETWORK_ERROR, 7 -> // 7
                        "Network error during Google Sign-In (Code 7). Check your internet connection."
                    CommonStatusCodes.INTERNAL_ERROR, 8 -> // 8
                        "Google Play Services internal error (Code 8). Please update Google Play Services and try again."
                    CommonStatusCodes.SIGN_IN_REQUIRED, 4 -> // 4
                        "Google account sign-in required (Code 4). Please add or sign in to a Google account on this device."
                    CommonStatusCodes.INVALID_ACCOUNT, 5 -> // 5
                        "Invalid Google account specified (Code 5)."
                    CommonStatusCodes.RESOLUTION_REQUIRED, 6 -> // 6
                        "Resolution required (Code 6). User interaction is needed to complete sign-in."
                    CommonStatusCodes.TIMEOUT, 15 -> // 15
                        "Google Sign-In timed out (Code 15). Check your internet connection and try again."
                    CommonStatusCodes.CANCELED, 16 -> // 16
                        return GoogleSignInResult.Cancelled
                    else ->
                        "Google Sign-In failed (Code ${e.statusCode}): ${e.localizedMessage ?: "Unknown error"}"
                }
                Log.e(TAG, "GoogleSignIn ApiException: statusCode=${e.statusCode}, message=$message", e)
                return GoogleSignInResult.Error(message, e)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed parsing GoogleSignIn result: ${e.message}", e)
                return GoogleSignInResult.Error("Failed to parse Google account: ${e.localizedMessage ?: e.javaClass.simpleName}", e)
            }
        }

        // Step 2: If data is null and result code is CANCELED, cleanly report cancellation
        if (resultCode == Activity.RESULT_CANCELED) {
            return GoogleSignInResult.Cancelled
        }

        // Step 3: If result code is OK but data was null
        if (resultCode == Activity.RESULT_OK) {
            return GoogleSignInResult.Error("Google Sign-In returned OK status, but no account data was returned by the system.")
        }

        // Step 4: Any other unexpected result code
        return GoogleSignInResult.Error("Google Sign-In was not completed (Result code: $resultCode)")
    }

    /**
     * Executes clean Google Sign-Out teardown:
     * 1. Clears AndroidX Credential Manager state so Android 14+ devices present account chooser dialog.
     * 2. Clears Play Services GoogleSignInClient cache.
     */
    suspend fun signOut() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
        runCatching {
            GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        }
    }

    companion object {
        private const val TAG = "GoogleSignInManager"
        const val DEFAULT_AVATAR_URL = "https://lh3.googleusercontent.com/a/default-user"

        fun formatDisplayNameFromEmail(email: String): String {
            val raw = email.substringBefore("@")
            val cleaned = raw.replace(Regex("[0-9]"), "").trim()
            val target = if (cleaned.length >= 2) cleaned else raw
            return target.split(".", "_", "-")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                .ifBlank { "Google User" }
        }
    }
}
