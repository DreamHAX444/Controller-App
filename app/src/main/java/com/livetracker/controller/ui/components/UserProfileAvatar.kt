package com.livetracker.controller.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.auth.domain.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val avatarMemoryCache = androidx.collection.LruCache<String, Bitmap>(20)

/**
 * Lightweight, zero-dependency async avatar loader adhering to /ponytail:
 * - Asynchronously downloads image streams on Dispatchers.IO via HttpURLConnection.
 * - Caches in-memory via LruCache to prevent redundant network fetches on recompositions.
 * - Instantly displays elegant Material 3 initials fallback with role-themed accents.
 */
@Composable
fun UserProfileAvatar(
    avatarUrl: String?,
    displayName: String?,
    role: Role = Role.OWNER,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val bitmapState = produceState<Bitmap?>(initialValue = avatarUrl?.let { avatarMemoryCache.get(it) }, key1 = avatarUrl) {
        if (avatarUrl.isNullOrBlank()) {
            value = null
            return@produceState
        }

        val cached = avatarMemoryCache.get(avatarUrl)
        if (cached != null) {
            value = cached
            return@produceState
        }

        val downloaded = withContext(Dispatchers.IO) {
            try {
                val conn = URL(avatarUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doInput = true
                conn.connect()
                if (conn.responseCode == 200) {
                    val stream = conn.inputStream
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream.close()
                    conn.disconnect()
                    bmp?.also { avatarMemoryCache.put(avatarUrl, it) }
                } else {
                    conn.disconnect()
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
        value = downloaded
    }

    val initials = remember(displayName) {
        val name = displayName?.trim().orEmpty()
        val parts = name.split(" ", "@", ".", "_", "-").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
        } else if (parts.isNotEmpty()) {
            parts[0].take(2).uppercase()
        } else {
            "OP"
        }
    }

    val roleBorderColor = when (role) {
        Role.OWNER -> Color(0xFF00E5FF)
        Role.ADMIN -> Color(0xFFA78BFA)
        Role.USER -> Color(0xFF60A5FA)
        Role.VISITOR -> Color(0xFFFFD54F)
    }

    val roleBgColor = when (role) {
        Role.OWNER -> Color(0xFF00363A)
        Role.ADMIN -> Color(0xFF2E1065)
        Role.USER -> Color(0xFF1E3A8A)
        Role.VISITOR -> Color(0xFF422006)
    }

    Surface(
        shape = CircleShape,
        color = roleBgColor,
        border = BorderStroke(1.5.dp, roleBorderColor),
        modifier = modifier.size(size)
    ) {
        val bitmap = bitmapState.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = displayName ?: "User Profile Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.38f).sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
