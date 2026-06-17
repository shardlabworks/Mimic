package com.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

@Composable
fun PoseOverlay(
    poseProvider: () -> SmoothedPose?,
    imageWidth: Int,
    imageHeight: Int,
    isFrontCamera: Boolean = false,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val pose = poseProvider()
        if (pose == null || imageWidth == 0 || imageHeight == 0) return@Canvas

        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight
        val scale = maxOf(scaleX, scaleY)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        fun translateX(x: Float): Float {
            val rawX = x * scale + offsetX
            return if (isFrontCamera) size.width - rawX else rawX
        }
        fun translateY(y: Float): Float = y * scale + offsetY

        fun getConfidenceColor(confidence: Float): Color {
            return when {
                confidence > 0.75f -> Color.White // High confidence
                confidence > 0.4f -> Color.White.copy(alpha = 0.5f)  // Medium confidence
                else -> Color.White.copy(alpha = 0.2f) // Low confidence
            }
        }

        fun drawLineOrEmpty(start: SmoothedPoseLandmark?, end: SmoothedPoseLandmark?) {
            if (start != null && end != null) {
                // If either is very low confidence, don't draw or draw faint red
                if (start.inFrameLikelihood < 0.2f || end.inFrameLikelihood < 0.2f) return

                val avgConfidence = (start.inFrameLikelihood + end.inFrameLikelihood) / 2f
                val color = getConfidenceColor(avgConfidence)

                // Glow Path
                drawLine(
                    color = color.copy(alpha = 0.3f),
                    start = Offset(translateX(start.x), translateY(start.y)),
                    end = Offset(translateX(end.x), translateY(end.y)),
                    strokeWidth = 14f
                )
                
                // Core Path
                drawLine(
                    color = color,
                    start = Offset(translateX(start.x), translateY(start.y)),
                    end = Offset(translateX(end.x), translateY(end.y)),
                    strokeWidth = 4f
                )
            }
        }

        fun drawPoint(landmark: SmoothedPoseLandmark?) {
            if (landmark != null && landmark.inFrameLikelihood > 0.2f) {
                val color = getConfidenceColor(landmark.inFrameLikelihood)
                drawCircle(
                    color = color.copy(alpha = 0.3f),
                    radius = 12f,
                    center = Offset(translateX(landmark.x), translateY(landmark.y))
                )
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = Offset(translateX(landmark.x), translateY(landmark.y))
                )
            }
        }

        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) return@Canvas

        // Draw connections
        val leftShoulder = pose.getPoseLandmark(11) // PoseLandmark.LEFT_SHOULDER
        val rightShoulder = pose.getPoseLandmark(12) // PoseLandmark.RIGHT_SHOULDER
        val leftElbow = pose.getPoseLandmark(13) // PoseLandmark.LEFT_ELBOW
        val rightElbow = pose.getPoseLandmark(14) // PoseLandmark.RIGHT_ELBOW
        val leftWrist = pose.getPoseLandmark(15) // PoseLandmark.LEFT_WRIST
        val rightWrist = pose.getPoseLandmark(16) // PoseLandmark.RIGHT_WRIST
        val leftHip = pose.getPoseLandmark(23) // PoseLandmark.LEFT_HIP
        val rightHip = pose.getPoseLandmark(24) // PoseLandmark.RIGHT_HIP
        val leftKnee = pose.getPoseLandmark(25) // PoseLandmark.LEFT_KNEE
        val rightKnee = pose.getPoseLandmark(26) // PoseLandmark.RIGHT_KNEE
        val leftAnkle = pose.getPoseLandmark(27) // PoseLandmark.LEFT_ANKLE
        val rightAnkle = pose.getPoseLandmark(28) // PoseLandmark.RIGHT_ANKLE
        val lowerSpine = pose.getPoseLandmark(33) // Custom Lower Spine
        val upperSpine = pose.getPoseLandmark(34) // Custom Upper Spine

        drawLineOrEmpty(leftShoulder, rightShoulder)
        drawLineOrEmpty(leftHip, rightHip)
        drawLineOrEmpty(leftShoulder, leftHip)
        drawLineOrEmpty(rightShoulder, rightHip)

        // Draw custom flexible spine
        val midShoulder = if (leftShoulder != null && rightShoulder != null) {
            SmoothedPoseLandmark(
                99,
                (leftShoulder.x + rightShoulder.x) / 2f,
                (leftShoulder.y + rightShoulder.y) / 2f,
                (leftShoulder.z + rightShoulder.z) / 2f,
                minOf(leftShoulder.inFrameLikelihood, rightShoulder.inFrameLikelihood)
            )
        } else null
        
        val midHip = if (leftHip != null && rightHip != null) {
            SmoothedPoseLandmark(
                99,
                (leftHip.x + rightHip.x) / 2f,
                (leftHip.y + rightHip.y) / 2f,
                (leftHip.z + rightHip.z) / 2f,
                minOf(leftHip.inFrameLikelihood, rightHip.inFrameLikelihood)
            )
        } else null

        if (midHip != null && lowerSpine != null) drawLineOrEmpty(midHip, lowerSpine)
        if (lowerSpine != null && upperSpine != null) drawLineOrEmpty(lowerSpine, upperSpine)
        if (upperSpine != null && midShoulder != null) drawLineOrEmpty(upperSpine, midShoulder)

        drawLineOrEmpty(leftShoulder, leftElbow)
        drawLineOrEmpty(leftElbow, leftWrist)
        drawLineOrEmpty(rightShoulder, rightElbow)
        drawLineOrEmpty(rightElbow, rightWrist)

        drawLineOrEmpty(leftHip, leftKnee)
        drawLineOrEmpty(leftKnee, leftAnkle)
        drawLineOrEmpty(rightHip, rightKnee)
        drawLineOrEmpty(rightKnee, rightAnkle)

        // Draw points
        for (landmark in landmarks) {
            drawPoint(landmark)
        }
    }
}
