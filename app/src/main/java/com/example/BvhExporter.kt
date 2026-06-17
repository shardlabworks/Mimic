package com.example

import java.io.File
import kotlin.math.*

class BvhExporter {
    
    private val SCALE_FACTOR = 100.0f
    
    data class FrameData(
        val joints: List<FloatArray>,
        val rawLandmarks: Array<FloatArray>
    )
    
    fun export(jsonFile: File, outputFile: File) {
        val framesPos = mutableListOf<FrameData>()
        var metadataTPose: FrameData? = null
        
        // Define Joints and Hierarchy
        val jointNames = listOf(
            "Hips", 
            "LeftHip", "LeftKnee", "LeftAnkle", 
            "RightHip", "RightKnee", "RightAnkle", 
            "Spine", "Neck", "Head",
            "LeftShoulder", "LeftElbow", "LeftWrist", 
            "LeftHandIndex", "LeftHandPinky", "LeftHandThumb",
            "RightShoulder", "RightElbow", "RightWrist",
            "RightHandIndex", "RightHandPinky", "RightHandThumb"
        )
        val jointIds = listOf(
            -1, // Hips
            23, 25, 27, // L Leg
            24, 26, 28, // R Leg
            -2, -3, 0,  // Spine, Neck, Head (0 is Nose)
            11, 13, 15, // L Arm
            19, 17, 21, // L Fingers (Index, Pinky, Thumb)
            12, 14, 16, // R Arm
            20, 18, 22  // R Fingers (Index, Pinky, Thumb)
        )
        val parentIndices = listOf(
            -1, // Hips
            0, 1, 2, // L Leg
            0, 4, 5, // R Leg
            0, 7, 8, // Spine, Neck, Head (Head's parent is Neck)
            8, 10, 11, // L Arm
            12, 12, 12, // L Fingers (parent is LeftWrist)
            8, 16, 17, // R Arm
            18, 18, 18  // R Fingers (parent is RightWrist)
        )
        
        try {
            val inputStream = java.io.FileInputStream(jsonFile)
            val reader = if (jsonFile.extension == "mimic") {
                val gzis = java.util.zip.GZIPInputStream(inputStream)
                android.util.JsonReader(java.io.InputStreamReader(gzis, "UTF-8"))
            } else {
                android.util.JsonReader(java.io.InputStreamReader(inputStream, "UTF-8"))
            }
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                
                var isMetadata = false
                val posMap = mutableMapOf<Int, FloatArray>()
                
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    when (name) {
                        "type" -> {
                            if (reader.nextString() == "metadata") isMetadata = true
                        }
                        "tpose" -> {
                            if (isMetadata) {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    var id = 0; var x = 0f; var y = 0f; var z = 0f
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "id" -> id = reader.nextInt()
                                            "x" -> x = (reader.nextDouble() * SCALE_FACTOR).toFloat()
                                            "y" -> y = (-reader.nextDouble() * SCALE_FACTOR).toFloat()
                                            "z" -> z = (reader.nextDouble() * SCALE_FACTOR).toFloat()
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    posMap[id] = floatArrayOf(x, y, z)
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        "landmarks" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                var id = 0; var x = 0f; var y = 0f; var z = 0f
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "id" -> id = reader.nextInt()
                                        "x" -> x = (reader.nextDouble() * SCALE_FACTOR).toFloat()
                                        "y" -> y = (-reader.nextDouble() * SCALE_FACTOR).toFloat()
                                        "z" -> z = (reader.nextDouble() * SCALE_FACTOR).toFloat()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                posMap[id] = floatArrayOf(x, y, z)
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                
                if (posMap.isNotEmpty()) {
                    val hips = avgPos(posMap[23], posMap[24])
                    val neck = avgPos(posMap[11], posMap[12])
                    val spine = avgPos(hips, neck)
                    posMap[-1] = hips
                    posMap[-2] = spine
                    posMap[-3] = neck
                    
                    val processedList = jointIds.map { id -> posMap[id] ?: floatArrayOf(0f,0f,0f) }
                    val rawArr = Array(35) { i -> posMap[i] ?: floatArrayOf(0f,0f,0f) }
                    
                    val frameData = FrameData(
                        joints = processedList,
                        rawLandmarks = rawArr
                    )
                    
                    if (isMetadata) {
                        metadataTPose = frameData
                    } else {
                        framesPos.add(frameData)
                    }
                }
            }
            reader.endArray()
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            outputFile.writeText("ERROR: Failed to parse file.")
            return
        }
        
        if (framesPos.isEmpty()) {
            outputFile.writeText("ERROR: No valid frames found.")
            return
        }
        
        // Try getting T-Pose from metadata
        var tpose: FrameData? = metadataTPose
        
        if (tpose == null || tpose.joints.size != jointIds.size) {
            tpose = framesPos.first()
        }
        
        val offsets = Array(tpose.joints.size) { floatArrayOf(0f,0f,0f) }
        for (i in 1 until tpose.joints.size) {
            val pIdx = parentIndices[i]
            offsets[i] = floatArrayOf(
                tpose.joints[i][0] - tpose.joints[pIdx][0],
                tpose.joints[i][1] - tpose.joints[pIdx][1],
                tpose.joints[i][2] - tpose.joints[pIdx][2]
            )
        }
        offsets[0] = floatArrayOf(0f, 0f, 0f) // Hips offset initially
        
        outputFile.bufferedWriter().use { writer ->
            writer.write("HIERARCHY\n")
            
            // Build Hierarchy recursively
            fun buildNode(idx: Int, indent: String) {
                if (idx == 0) {
                    writer.write("${indent}ROOT ${jointNames[idx]}\n")
                } else {
                    writer.write("${indent}JOINT ${jointNames[idx]}\n")
                }
                writer.write("${indent}{\n")
                writer.write(java.util.Locale.US.let { java.lang.String.format(it, "${indent}  OFFSET %.4f %.4f %.4f\n", offsets[idx][0], offsets[idx][1], offsets[idx][2]) })
                if (idx == 0) {
                    writer.write("${indent}  CHANNELS 6 Xposition Yposition Zposition Zrotation Xrotation Yrotation\n")
                } else {
                    writer.write("${indent}  CHANNELS 3 Zrotation Xrotation Yrotation\n")
                }
                
                val children = parentIndices.indices.filter { parentIndices[it] == idx }
                if (children.isEmpty()) {
                    writer.write("${indent}  End Site\n")
                    writer.write("${indent}  {\n")
                    val fakeOff = floatArrayOf(0f, -10f, 0f)
                    writer.write(java.util.Locale.US.let { java.lang.String.format(it, "${indent}    OFFSET %.4f %.4f %.4f\n", fakeOff[0], fakeOff[1], fakeOff[2]) })
                    writer.write("${indent}  }\n")
                } else {
                    for (c in children) {
                        buildNode(c, "$indent  ")
                    }
                }
                writer.write("${indent}}\n")
            }
            buildNode(0, "")
            
            writer.write("MOTION\n")
            writer.write("Frames: ${framesPos.size}\n")
            writer.write("Frame Time: 0.0333333\n")
            
            for (f in framesPos.indices) {
                val currPos = framesPos[f]
                val globalRots = Array(tpose.joints.size) { floatArrayOf(1f,0f,0f,0f) }
                
                for (i in tpose.joints.indices) {
                    val children = parentIndices.indices.filter { parentIndices[it] == i }
                    
                    var q = floatArrayOf(1f,0f,0f,0f)
                    val name = jointNames[i]
                    
                    if (children.isEmpty()) {
                        if (name == "Head") {
                            val upRest = normalize(sub(tpose.joints[i], tpose.joints[parentIndices[i]])) // Head - Neck
                            val rightRest = normalize(sub(tpose.rawLandmarks[8], tpose.rawLandmarks[7])) // Right Ear - Left Ear
                            val fwdRest = normalize(cross(upRest, rightRest))

                            val upCurr = normalize(sub(currPos.joints[i], currPos.joints[parentIndices[i]]))
                            val rightCurr = normalize(sub(currPos.rawLandmarks[8], currPos.rawLandmarks[7]))
                            val fwdCurr = normalize(cross(upCurr, rightCurr))

                            q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                        } else if (name == "LeftAnkle" || name == "RightAnkle") {
                            val isLeft = name == "LeftAnkle"
                            val heelId = if (isLeft) 29 else 30
                            val footIdx = if (isLeft) 31 else 32
                            
                            val fwdRest = normalize(sub(tpose.rawLandmarks[footIdx], tpose.rawLandmarks[heelId]))
                            val upRestRaw = normalize(sub(tpose.joints[i], tpose.rawLandmarks[heelId]))
                            val rightRest = normalize(cross(upRestRaw, fwdRest))
                            val upRest = normalize(cross(fwdRest, rightRest))
                            
                            val fwdCurr = normalize(sub(currPos.rawLandmarks[footIdx], currPos.rawLandmarks[heelId]))
                            val upCurrRaw = normalize(sub(currPos.joints[i], currPos.rawLandmarks[heelId]))
                            val rightCurr = normalize(cross(upCurrRaw, fwdCurr))
                            val upCurr = normalize(cross(fwdCurr, rightCurr))
                            
                            q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                        } else {
                            q = globalRots[parentIndices[i]] // Fingers follow wrist
                        }
                    } else if (name == "Hips") {
                        val upRest = normalize(sub(tpose.joints[jointNames.indexOf("Spine")], tpose.joints[i]))
                        val rightRest = normalize(sub(tpose.joints[jointNames.indexOf("RightHip")], tpose.joints[jointNames.indexOf("LeftHip")]))
                        val fwdRest = normalize(cross(upRest, rightRest))
                        
                        val upCurr = normalize(sub(currPos.joints[jointNames.indexOf("Spine")], currPos.joints[i]))
                        val rightCurr = normalize(sub(currPos.joints[jointNames.indexOf("RightHip")], currPos.joints[jointNames.indexOf("LeftHip")]))
                        val fwdCurr = normalize(cross(upCurr, rightCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "Spine") {
                        val upRest = normalize(sub(tpose.joints[jointNames.indexOf("Neck")], tpose.joints[i]))
                        val rightRest = normalize(sub(tpose.joints[jointNames.indexOf("RightShoulder")], tpose.joints[jointNames.indexOf("LeftShoulder")])) 
                        val fwdRest = normalize(cross(upRest, rightRest))
                        
                        val upCurr = normalize(sub(currPos.joints[jointNames.indexOf("Neck")], currPos.joints[i]))
                        val rightCurr = normalize(sub(currPos.joints[jointNames.indexOf("RightShoulder")], currPos.joints[jointNames.indexOf("LeftShoulder")]))
                        val fwdCurr = normalize(cross(upCurr, rightCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "LeftWrist") {
                        val midRest = avgPos(tpose.rawLandmarks[15], tpose.rawLandmarks[17]) // 15=L wrist, 17=L pinky
                        val fwdRest = normalize(sub(midRest, tpose.joints[i]))
                        val rightRest = normalize(sub(tpose.rawLandmarks[15], tpose.rawLandmarks[17]))
                        val upRest = normalize(cross(fwdRest, rightRest))
                        
                        val midCurr = avgPos(currPos.rawLandmarks[15], currPos.rawLandmarks[17])
                        val fwdCurr = normalize(sub(midCurr, currPos.joints[i]))
                        val rightCurr = normalize(sub(currPos.rawLandmarks[15], currPos.rawLandmarks[17]))
                        val upCurr = normalize(cross(fwdCurr, rightCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "RightWrist") {
                        val midRest = avgPos(tpose.rawLandmarks[16], tpose.rawLandmarks[18])
                        val fwdRest = normalize(sub(midRest, tpose.joints[i]))
                        val rightRest = normalize(sub(tpose.rawLandmarks[18], tpose.rawLandmarks[16]))
                        val upRest = normalize(cross(fwdRest, rightRest))
                        
                        val midCurr = avgPos(currPos.rawLandmarks[16], currPos.rawLandmarks[18])
                        val fwdCurr = normalize(sub(midCurr, currPos.joints[i]))
                        val rightCurr = normalize(sub(currPos.rawLandmarks[18], currPos.rawLandmarks[16]))
                        val upCurr = normalize(cross(fwdCurr, rightCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "LeftShoulder" || name == "RightShoulder") {
                        val elbowIdx = children.first()
                        val wristIdx = parentIndices.indexOf(elbowIdx) // The child of the elbow
                        
                        // Rest Pose Plane
                        val fwdRest = normalize(sub(tpose.joints[elbowIdx], tpose.joints[i]))
                        val lowerArmRest = normalize(sub(tpose.joints[wristIdx], tpose.joints[elbowIdx]))
                        val upRest = normalize(cross(fwdRest, lowerArmRest))
                        val rightRest = normalize(cross(upRest, fwdRest))
                        
                        // Current Pose Plane
                        val fwdCurr = normalize(sub(currPos.joints[elbowIdx], currPos.joints[i]))
                        val lowerArmCurr = normalize(sub(currPos.joints[wristIdx], currPos.joints[elbowIdx]))
                        val upCurr = normalize(cross(fwdCurr, lowerArmCurr))
                        val rightCurr = normalize(cross(upCurr, fwdCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "LeftElbow" || name == "RightElbow") {
                        // Left Elbow (10) or Right Elbow (13) - Inject Pronation/Supination Twist!
                        val wristIdx = children.first()
                        
                        // Calculate the twisting plane using the Pinky finger
                        val pinkyRest = if (name == "LeftElbow") tpose.rawLandmarks[17] else tpose.rawLandmarks[18]
                        val fwdRest = normalize(sub(tpose.joints[wristIdx], tpose.joints[i]))
                        val upRest = normalize(cross(fwdRest, normalize(sub(pinkyRest, tpose.joints[wristIdx]))))
                        val rightRest = normalize(cross(upRest, fwdRest))

                        val pinkyCurr = if (name == "LeftElbow") currPos.rawLandmarks[17] else currPos.rawLandmarks[18]
                        val fwdCurr = normalize(sub(currPos.joints[wristIdx], currPos.joints[i]))
                        val upCurr = normalize(cross(fwdCurr, normalize(sub(pinkyCurr, currPos.joints[wristIdx]))))
                        val rightCurr = normalize(cross(upCurr, fwdCurr))
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "LeftHip" || name == "RightHip") {
                        val kneeIdx = children.first()
                        val ankleIdx = parentIndices.indexOf(kneeIdx) // The child of the knee
                        
                        // Rest Pose Plane
                        val fwdRest = normalize(sub(tpose.joints[kneeIdx], tpose.joints[i]))
                        val lowerLegRest = normalize(sub(tpose.joints[ankleIdx], tpose.joints[kneeIdx]))
                        val upRest = normalize(cross(fwdRest, lowerLegRest))
                        val rightRest = normalize(cross(upRest, fwdRest))
                        
                        // Current Pose Plane
                        val fwdCurr = normalize(sub(currPos.joints[kneeIdx], currPos.joints[i]))
                        val lowerLegCurr = normalize(sub(currPos.joints[ankleIdx], currPos.joints[kneeIdx]))
                        val upCurr = normalize(cross(fwdCurr, lowerLegCurr))
                        val rightCurr = normalize(cross(upCurr, fwdCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else if (name == "LeftKnee" || name == "RightKnee") {
                        val ankleIdx = children.first()
                        val hipIdx = parentIndices[i]
                        
                        // Rest Pose Plane
                        val fwdRest = normalize(sub(tpose.joints[ankleIdx], tpose.joints[i]))
                        val upperLegRest = normalize(sub(tpose.joints[i], tpose.joints[hipIdx]))
                        val upRest = normalize(cross(upperLegRest, fwdRest))
                        val rightRest = normalize(cross(upRest, fwdRest))
                        
                        // Current Pose Plane
                        val fwdCurr = normalize(sub(currPos.joints[ankleIdx], currPos.joints[i]))
                        val upperLegCurr = normalize(sub(currPos.joints[i], currPos.joints[hipIdx]))
                        val upCurr = normalize(cross(upperLegCurr, fwdCurr))
                        val rightCurr = normalize(cross(upCurr, fwdCurr))
                        
                        q = quatFromBaseVectors(rightRest, upRest, fwdRest, rightCurr, upCurr, fwdCurr)
                    } else {
                        val c = children.first()
                        val vRest = normalize(sub(tpose.joints[c], tpose.joints[i]))
                        val vCurr = normalize(sub(currPos.joints[c], currPos.joints[i]))
                        q = fromToRotation(vRest, vCurr)
                    }
                    globalRots[i] = q
                }
                
                val localRots = Array(tpose.joints.size) { floatArrayOf(1f,0f,0f,0f) }
                localRots[0] = globalRots[0]
                for (i in 1 until tpose.joints.size) {
                    val pIdx = parentIndices[i]
                    localRots[i] = quatMul(quatInv(globalRots[pIdx]), globalRots[i])
                }
                
                val hipPos = currPos.joints[0]
                writer.write(java.util.Locale.US.let { java.lang.String.format(it, "%.4f %.4f %.4f ", hipPos[0], hipPos[1], hipPos[2]) })
                
                for (i in tpose.joints.indices) {
                    val euler = quatToEulerZXY(localRots[i])
                    val ez = Math.toDegrees(euler[0].toDouble())
                    val ex = Math.toDegrees(euler[1].toDouble())
                    val ey = Math.toDegrees(euler[2].toDouble())
                    writer.write(java.util.Locale.US.let { java.lang.String.format(it, "%.4f %.4f %.4f ", ez, ex, ey) })
                }
                writer.write("\n")
            }
        }
    }
    
    private fun avgPos(p1: FloatArray?, p2: FloatArray?): FloatArray {
        if (p1 == null || p2 == null) return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf((p1[0]+p2[0])/2f, (p1[1]+p2[1])/2f, (p1[2]+p2[2])/2f)
    }
    
    private fun sub(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(a[0]-b[0], a[1]-b[1], a[2]-b[2])
    }
    
    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1]*b[2] - a[2]*b[1],
            a[2]*b[0] - a[0]*b[2],
            a[0]*b[1] - a[1]*b[0]
        )
    }
    
    private fun dot(a: FloatArray, b: FloatArray): Float {
        return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
    }
    
    private fun length(a: FloatArray): Float {
        return sqrt(dot(a, a))
    }
    
    private fun normalize(a: FloatArray): FloatArray {
        val l = length(a)
        if (l < 0.0001f) return floatArrayOf(1f, 0f, 0f)
        return floatArrayOf(a[0]/l, a[1]/l, a[2]/l)
    }
    
    private fun fromToRotation(v1: FloatArray, v2: FloatArray): FloatArray {
        val u1 = normalize(v1)
        val u2 = normalize(v2)
        val d = dot(u1, u2)
        if (d > 0.9999f) return floatArrayOf(1f, 0f, 0f, 0f)
        if (d < -0.9999f) {
            var ortho = cross(floatArrayOf(1f,0f,0f), u1)
            if (length(ortho) < 0.01f) ortho = cross(floatArrayOf(0f,1f,0f), u1)
            ortho = normalize(ortho)
            return floatArrayOf(0f, ortho[0], ortho[1], ortho[2])
        }
        val w = cross(u1, u2)
        val q = floatArrayOf(1f + d, w[0], w[1], w[2])
        val ql = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        return floatArrayOf(q[0]/ql, q[1]/ql, q[2]/ql, q[3]/ql)
    }
    
    private fun quatMul(q1: FloatArray, q2: FloatArray): FloatArray {
        return floatArrayOf(
            q1[0]*q2[0] - q1[1]*q2[1] - q1[2]*q2[2] - q1[3]*q2[3],
            q1[0]*q2[1] + q1[1]*q2[0] + q1[2]*q2[3] - q1[3]*q2[2],
            q1[0]*q2[2] - q1[1]*q2[3] + q1[2]*q2[0] + q1[3]*q2[1],
            q1[0]*q2[3] + q1[1]*q2[2] - q1[2]*q2[1] + q1[3]*q2[0]
        )
    }
    
    private fun quatInv(q: FloatArray): FloatArray {
        return floatArrayOf(q[0], -q[1], -q[2], -q[3])
    }
    
    private fun quatFromBaseVectors(
        rx1: FloatArray, ry1: FloatArray, rz1: FloatArray,
        rx2: FloatArray, ry2: FloatArray, rz2: FloatArray
    ): FloatArray {
        val m00 = rx2[0]*rx1[0] + ry2[0]*ry1[0] + rz2[0]*rz1[0]
        val m01 = rx2[0]*rx1[1] + ry2[0]*ry1[1] + rz2[0]*rz1[1]
        val m02 = rx2[0]*rx1[2] + ry2[0]*ry1[2] + rz2[0]*rz1[2]
        val m10 = rx2[1]*rx1[0] + ry2[1]*ry1[0] + rz2[1]*rz1[0]
        val m11 = rx2[1]*rx1[1] + ry2[1]*ry1[1] + rz2[1]*rz1[1]
        val m12 = rx2[1]*rx1[2] + ry2[1]*ry1[2] + rz2[1]*rz1[2]
        val m20 = rx2[2]*rx1[0] + ry2[2]*ry1[0] + rz2[2]*rz1[0]
        val m21 = rx2[2]*rx1[1] + ry2[2]*ry1[1] + rz2[2]*rz1[1]
        val m22 = rx2[2]*rx1[2] + ry2[2]*ry1[2] + rz2[2]*rz1[2]
        
        val tr = m00 + m11 + m22
        var qw: Float; var qx: Float; var qy: Float; var qz: Float
        if (tr > 0) {
            val S = sqrt(tr + 1.0f) * 2f
            qw = 0.25f * S
            qx = (m21 - m12) / S
            qy = (m02 - m20) / S
            qz = (m10 - m01) / S
        } else if ((m00 > m11) && (m00 > m22)) {
            val S = sqrt(1.0f + m00 - m11 - m22) * 2f
            qw = (m21 - m12) / S
            qx = 0.25f * S
            qy = (m01 + m10) / S
            qz = (m02 + m20) / S
        } else if (m11 > m22) {
            val S = sqrt(1.0f + m11 - m00 - m22) * 2f
            qw = (m02 - m20) / S
            qx = (m01 + m10) / S
            qy = 0.25f * S
            qz = (m12 + m21) / S
        } else {
            val S = sqrt(1.0f + m22 - m00 - m11) * 2f
            qw = (m10 - m01) / S
            qx = (m02 + m20) / S
            qy = (m12 + m21) / S
            qz = 0.25f * S
        }
        val ql = sqrt(qw*qw + qx*qx + qy*qy + qz*qz)
        return floatArrayOf(qw/ql, qx/ql, qy/ql, qz/ql)
    }
    
    private fun quatToEulerZXY(q: FloatArray): FloatArray {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val rx = asin(max(-1f, min(1f, 2f*(w*x - y*z))))
        val ry = atan2(2f*(w*y + x*z), w*w - x*x - y*y + z*z)
        val rz = atan2(2f*(w*z + x*y), w*w - x*x + y*y - z*z)
        return floatArrayOf(rz, rx, ry)
    }
}
