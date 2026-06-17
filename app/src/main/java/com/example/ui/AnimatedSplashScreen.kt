package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AnimatedSplashScreen(
    onAnimationFinished: () -> Unit
) {
    val bgDark = MaterialTheme.colorScheme.background
    val accentBlue = MaterialTheme.colorScheme.primary

    val letterSpacing = remember { Animatable(40f) }
    val alpha = remember { Animatable(0f) }
    val scannerSweep = remember { Animatable(-1f) }
    val scale = remember { Animatable(1f) }
    val signatureAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 1. The Skeleton Snap (0.0s - 0.8s)
        launch {
            alpha.animateTo(1f, tween(800, easing = LinearOutSlowInEasing))
        }
        launch {
            letterSpacing.animateTo(
                targetValue = 8f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }

        // 2. The Scanner Sweep (0.5s - 1.5s)
        delay(500)
        scannerSweep.animateTo(
            targetValue = 2f,
            animationSpec = tween(1000, easing = LinearEasing)
        )

        // 3. The Mocap Pulse (1.5s - 2.0s)
        launch {
            scale.animateTo(1.05f, tween(250, easing = FastOutSlowInEasing))
            scale.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
        }

        // 4. The Signature Reveal (1.8s - 2.5s)
        delay(300)
        launch {
            signatureAlpha.animateTo(1f, tween(700, easing = LinearOutSlowInEasing))
        }

        delay(700)
        onAnimationFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark),
        contentAlignment = Alignment.Center
    ) {
        val brush = Brush.linearGradient(
            colors = listOf(
                accentBlue.copy(alpha = 0.5f),
                Color.White,
                accentBlue.copy(alpha = 0.5f)
            ),
            start = Offset(x = scannerSweep.value * 1000f, y = 0f),
            end = Offset(x = scannerSweep.value * 1000f + 200f, y = 0f)
        )

        val textBrush = if (scannerSweep.value > -1f && scannerSweep.value < 2f) {
            brush
        } else {
            SolidColor(accentBlue)
        }

        Text(
            text = "M I M I C",
            style = TextStyle(
                brush = textBrush,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = letterSpacing.value.sp
            ),
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        )

        Text(
            text = "Presented by ShardLabWorks",
            color = Color.Gray.copy(alpha = signatureAlpha.value),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }
}
