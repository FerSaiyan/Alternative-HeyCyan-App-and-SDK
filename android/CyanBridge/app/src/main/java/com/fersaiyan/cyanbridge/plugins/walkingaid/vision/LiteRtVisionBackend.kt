package com.fersaiyan.cyanbridge.plugins.walkingaid.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LiteRtVisionBackend(
    private val context: Context,
    private val computeBackend: LocalComputeBackend? = null,
) : VisionBackend {

    private var detectorInterpreter: Interpreter? = null
    private var depthInterpreter: Interpreter? = null

    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private var activeAcceleratorInfo: AcceleratorInfo = AcceleratorInfo(
        type = AcceleratorType.XNNPACK_CPU,
        delegatedOperators = 0,
        totalOperators = 0,
        details = "Initializing...",
    )

    var detectorInitError: String? = null
        private set
    var depthInitError: String? = null
        private set

    val isDetectorModelLoaded: Boolean
        get() = detectorInterpreter != null

    val isDepthModelLoaded: Boolean
        get() = depthInterpreter != null

    private var modelWidth = 640
    private var modelHeight = 640
    private var depthWidth = 518
    private var depthHeight = 518
    private var detectorInputNchw = false
    private var detectorInputType = DataType.FLOAT32
    private var detectorInputScale = 1f
    private var detectorInputZeroPoint = 0
    private var depthInputNchw = false

    init {
        initDetector()
        initDepthModel()
    }

    private fun initDetector() {
        try {
            val isYoloWorld = com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences.getYoloModelType(context) == com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD
            val modelFile = if (isYoloWorld) {
                locateModel("yolo_world.tflite")
                    ?: locateModel("yolo_world_v2_s.tflite")
                    ?: locateModel("yoloworld.tflite")
            } else {
                locateModel("yolo11n_float16.tflite") ?: locateModel("yolov11n.tflite")
            }
            if (modelFile == null) {
                detectorInitError = "YOLO TFLite model file missing or empty"
                Log.w(TAG, "YOLO TFLite model not found in app files or assets yet; vision detection will await model installation.")
                return
            }

            val modelBuffer = loadModelFile(modelFile)
            val options = Interpreter.Options()

            val targetBackend = computeBackend ?: runCatching {
                LocalModelSettingsRepository.getForModel(context, "default").computeBackend
            }.getOrDefault(LocalComputeBackend.CPU)

            var accelType = AcceleratorType.XNNPACK_CPU
            var details = "Defaulting to XNNPACK CPU acceleration"
            var delegatedOps = 0
            when (targetBackend) {
                LocalComputeBackend.NPU_EXPERIMENTAL -> {
                    try {
                        val nnApi = NnApiDelegate(
                            NnApiDelegate.Options().apply {
                                setUseNnapiCpu(false)
                                setAllowFp16(true)
                            }
                        )
                        options.addDelegate(nnApi)
                        detectorInterpreter = Interpreter(modelBuffer, options)
                        nnApiDelegate = nnApi
                        accelType = AcceleratorType.NPU_ON_DEVICE
                        details = "Snapdragon NPU / NNAPI Delegate initialized successfully"
                        Log.i(TAG, details)
                    } catch (e: Exception) {
                        Log.w(TAG, "NPU / NNAPI delegation failed, falling back to GPU: ${e.message}")
                        options.delegates.clear()
                        tryGpuDelegate(modelBuffer, options)?.let { (acc, det, ops) ->
                            accelType = acc
                            details = det
                            delegatedOps = ops
                        }
                    }
                }
                LocalComputeBackend.GPU -> {
                    tryGpuDelegate(modelBuffer, options)?.let { (acc, det, ops) ->
                        accelType = acc
                        details = det
                        delegatedOps = ops
                    }
                }
                LocalComputeBackend.CPU -> {
                    Log.i(TAG, "CPU compute backend explicitly selected for vision detection.")
                }
            }

            if (detectorInterpreter == null) {
                options.setUseXNNPACK(true)
                options.setNumThreads(4)
                detectorInterpreter = Interpreter(modelBuffer, options)
                accelType = AcceleratorType.XNNPACK_CPU
                details = "LiteRT XNNPACK CPU engine initialized (4 threads)"
                Log.i(TAG, details)
            }

            // Read model dimensions if available
            detectorInterpreter?.getInputTensor(0)?.shape()?.let { shape ->
                if (shape.size == 4) {
                    detectorInputNchw = shape[1] == 3
                    modelHeight = if (detectorInputNchw) shape[2] else shape[1]
                    modelWidth = if (detectorInputNchw) shape[3] else shape[2]
                }
            }
            detectorInterpreter?.getInputTensor(0)?.let { tensor ->
                detectorInputType = tensor.dataType()
                detectorInputScale = tensor.quantizationParams().scale.takeIf { it > 0f } ?: 1f
                detectorInputZeroPoint = tensor.quantizationParams().zeroPoint
            }
            val tensorSummary = detectorInterpreter?.let { interpreter ->
                val input = interpreter.getInputTensor(0)
                val outputs = (0 until interpreter.outputTensorCount).joinToString { index ->
                    val tensor = interpreter.getOutputTensor(index)
                    "${tensor.name()}=${tensor.shape().contentToString()}:${tensor.dataType()}" +
                        "(scale=${tensor.quantizationParams().scale},zero=${tensor.quantizationParams().zeroPoint})"
                }
                "input=${input.shape().contentToString()}:${input.dataType()}" +
                    "(scale=${input.quantizationParams().scale},zero=${input.quantizationParams().zeroPoint}), " +
                    "outputs=[$outputs]"
            }.orEmpty()
            activeAcceleratorInfo = AcceleratorInfo(
                type = accelType,
                delegatedOperators = delegatedOps,
                totalOperators = 0,
                details = "$details; $tensorSummary",
            )
            Log.i(TAG, "YOLO tensor layout: $tensorSummary")
        } catch (e: Exception) {
            detectorInitError = "Failed initializing YOLO detector in LiteRT: ${e.message}"
            Log.e(TAG, "Failed initializing YOLO detector in LiteRT: ${e.message}", e)
        }
    }

    private fun tryGpuDelegate(
        modelBuffer: ByteBuffer,
        options: Interpreter.Options,
    ): Triple<AcceleratorType, String, Int>? {
        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            try {
                val gpu = GpuDelegate()
                options.addDelegate(gpu)
                detectorInterpreter = Interpreter(modelBuffer, options)
                gpuDelegate = gpu
                val details = "LiteRT GPU Delegate initialized successfully"
                Log.i(TAG, details)
                return Triple(AcceleratorType.GPU_DELEGATE, details, 0)
            } catch (gpuEx: Exception) {
                Log.w(TAG, "LiteRT GPU delegate failed, falling back to CPU: ${gpuEx.message}")
                options.delegates.clear()
            }
        }
        return null
    }

    private fun initDepthModel() {
        try {
            val depthFile = locateModel("depth_anything_3_small.tflite")
                ?: locateModel("depth_anything_v2_small.tflite")
                ?: run {
                    depthInitError = "Depth Anything TFLite model file missing or empty"
                    return
                }
            val buffer = loadModelFile(depthFile)
            val options = Interpreter.Options().apply {
                setUseXNNPACK(true)
                setNumThreads(3)
            }
            depthInterpreter = Interpreter(buffer, options)
            depthInterpreter?.getInputTensor(0)?.shape()?.let { shape ->
                if (shape.size == 4) {
                    depthInputNchw = shape[1] == 3
                    if (!depthInputNchw && shape[1] > 10 && shape[2] > 10) {
                        depthHeight = shape[1]
                        depthWidth = shape[2]
                    } else if (depthInputNchw && shape[2] > 10 && shape[3] > 10) {
                        depthHeight = shape[2]
                        depthWidth = shape[3]
                    }
                }
            }
            Log.i(TAG, "Relative depth model (${depthWidth}x${depthHeight}) initialized via LiteRT")
        } catch (e: Exception) {
            depthInitError = "Failed initializing Depth Anything model: ${e.message}"
            Log.e(TAG, "Failed initializing Depth model: ${e.message}", e)
        }
    }

    private fun locateModel(fileName: String): File? {
        val file = File(context.filesDir, fileName)
        if (file.exists() && file.length() > 0L) return file
        val external = File(context.getExternalFilesDir(null), fileName)
        if (external.exists() && external.length() > 0L) return external
        return null
    }

    private fun loadModelFile(file: File): ByteBuffer {
        FileInputStream(file).use { fis ->
            val channel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).apply {
                order(ByteOrder.nativeOrder())
            }
        }
    }

    override suspend fun detect(frame: VisionFrame): DetectionResult = withContext(Dispatchers.Default) {
        val interp = detectorInterpreter
        if (interp == null) {
            val err = detectorInitError ?: "LiteRT detector interpreter unavailable"
            return@withContext DetectionResult(
                objects = emptyList(),
                acquisitionTimeMs = System.currentTimeMillis() - frame.timestampMs,
                isError = true,
                errorMessage = err,
            )
        }

        val t0 = SystemClock.elapsedRealtime()
        val acqTime = max(0L, System.currentTimeMillis() - frame.timestampMs)

        // Preprocessing: Resize & normalize to float [0..1]
        val resized = Bitmap.createScaledBitmap(frame.bitmap, modelWidth, modelHeight, true)
        val inputBuffer = ByteBuffer.allocateDirect(interp.getInputTensor(0).numBytes()).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(modelWidth * modelHeight)
        resized.getPixels(pixels, 0, modelWidth, 0, 0, modelWidth, modelHeight)
        if (detectorInputNchw) {
            for (channel in 0..2) {
                for (pixel in pixels) {
                    putDetectorInputValue(inputBuffer, pixelChannel(pixel, channel))
                }
            }
        } else {
            for (pixel in pixels) {
                putDetectorInputValue(inputBuffer, pixelChannel(pixel, 0))
                putDetectorInputValue(inputBuffer, pixelChannel(pixel, 1))
                putDetectorInputValue(inputBuffer, pixelChannel(pixel, 2))
            }
        }
        val t1 = SystemClock.elapsedRealtime()
        val prepTime = t1 - t0

        inputBuffer.rewind()
        val output = try {
            runDetector(interp, inputBuffer)
        } catch (error: Exception) {
            val message = "YOLO detector output is unsupported: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, message, error)
            return@withContext DetectionResult(
                objects = emptyList(),
                acquisitionTimeMs = acqTime,
                preprocessTimeMs = prepTime,
                isError = true,
                errorMessage = message,
            )
        }
        val t2 = SystemClock.elapsedRealtime()
        val infTime = t2 - t1

        // Postprocessing: Decode bounding boxes, filter by confidence (>0.35), run NMS
        val rawCandidates = mutableListOf<DetectedObject>()
        val numBoxes = output.boxCount
        val numChannels = output.channelCount

        for (i in 0 until numBoxes) {
            val directClassIndices = output.classIndices
            val directScores = output.confidenceScores
            val classIdx: Int
            val maxScore: Float
            if (directClassIndices != null && directScores != null) {
                classIdx = directClassIndices[i].roundToInt()
                maxScore = directScores[i]
            } else {
                var bestScore = 0f
                var bestClass = -1
                for (c in 4 until numChannels) {
                    val score = output.values[c * numBoxes + i]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = c - 4
                    }
                }
                classIdx = bestClass
                maxScore = bestScore
            }
            if (maxScore >= CONFIDENCE_THRESHOLD) {
                val labelName = if (classIdx in COCO_CLASSES.indices) {
                    COCO_CLASSES[classIdx]
                } else {
                    "object"
                }

                val first = output.values[i]
                val second = output.values[numBoxes + i]
                val third = output.values[2 * numBoxes + i]
                val fourth = output.values[3 * numBoxes + i]
                val (left, top, right, bottom) = if (output.boxesUseYoloWorldDistances) {
                    decodeYoloWorldBox(i, first, second, third, fourth)
                } else {
                    val rawLeft = if (output.boxesAreCorners) first else first - third / 2f
                    val rawTop = if (output.boxesAreCorners) second else second - fourth / 2f
                    val rawRight = if (output.boxesAreCorners) third else first + third / 2f
                    val rawBottom = if (output.boxesAreCorners) fourth else second + fourth / 2f
                    listOf(
                        normalizeCoordinate(rawLeft, modelWidth),
                        normalizeCoordinate(rawTop, modelHeight),
                        normalizeCoordinate(rawRight, modelWidth),
                        normalizeCoordinate(rawBottom, modelHeight),
                    )
                }
                val cx = (left + right) / 2f

                val box = RectF(left, top, right, bottom)
                val pos = when {
                    cx < 0.33f -> "left"
                    cx > 0.66f -> "right"
                    else -> "center"
                }

                rawCandidates.add(
                    DetectedObject(
                        label = labelName,
                        confidence = maxScore,
                        boundingBox = box,
                        position = pos,
                    )
                )
            }
        }

        // Non-Maximum Suppression (NMS)
        val nmsResults = nonMaxSuppression(rawCandidates, IOU_THRESHOLD)

        val t3 = SystemClock.elapsedRealtime()
        val postTime = t3 - t2

        DetectionResult(
            objects = nmsResults,
            acquisitionTimeMs = acqTime,
            preprocessTimeMs = prepTime,
            inferenceTimeMs = infTime,
            postprocessTimeMs = postTime,
        )
    }

    private data class DetectorOutput(
        val channelCount: Int,
        val boxCount: Int,
        val values: FloatArray,
        val boxesAreCorners: Boolean = false,
        val classIndices: FloatArray? = null,
        val confidenceScores: FloatArray? = null,
        val boxesUseYoloWorldDistances: Boolean = false,
    )

    private fun decodeYoloWorldBox(
        candidateIndex: Int,
        leftDistance: Float,
        topDistance: Float,
        rightDistance: Float,
        bottomDistance: Float,
    ): List<Float> {
        val (levelOffset, gridSize, stride) = when {
            candidateIndex < 80 * 80 -> Triple(0, 80, 8f)
            candidateIndex < 80 * 80 + 40 * 40 -> Triple(80 * 80, 40, 16f)
            else -> Triple(80 * 80 + 40 * 40, 20, 32f)
        }
        val levelIndex = candidateIndex - levelOffset
        val anchorX = (levelIndex % gridSize + 0.5f) * stride
        val anchorY = (levelIndex / gridSize + 0.5f) * stride
        return listOf(
            ((anchorX - leftDistance * stride) / modelWidth).coerceIn(0f, 1f),
            ((anchorY - topDistance * stride) / modelHeight).coerceIn(0f, 1f),
            ((anchorX + rightDistance * stride) / modelWidth).coerceIn(0f, 1f),
            ((anchorY + bottomDistance * stride) / modelHeight).coerceIn(0f, 1f),
        )
    }

    private fun normalizeCoordinate(value: Float, dimension: Int): Float {
        val normalized = if (abs(value) > 1.5f) value / dimension.toFloat() else value
        return normalized.coerceIn(0f, 1f)
    }

    private fun pixelChannel(pixel: Int, channel: Int): Float = when (channel) {
        0 -> ((pixel shr 16) and 0xFF) / 255f
        1 -> ((pixel shr 8) and 0xFF) / 255f
        else -> (pixel and 0xFF) / 255f
    }

    private fun putDetectorInputValue(buffer: ByteBuffer, value: Float) {
        when (detectorInputType) {
            DataType.FLOAT32 -> buffer.putFloat(value)
            DataType.UINT8 -> buffer.put(
                (value / detectorInputScale + detectorInputZeroPoint)
                    .roundToInt()
                    .coerceIn(0, 255)
                    .toByte(),
            )
            DataType.INT8 -> buffer.put(
                (value / detectorInputScale + detectorInputZeroPoint)
                    .roundToInt()
                    .coerceIn(-128, 127)
                    .toByte(),
            )
            else -> throw IllegalStateException("Unsupported YOLO input type: $detectorInputType")
        }
    }

    private fun runDetector(interpreter: Interpreter, input: ByteBuffer): DetectorOutput {
        val outputTensors = (0 until interpreter.outputTensorCount).map(interpreter::getOutputTensor)
        val outputBuffers = outputTensors.mapIndexed { index, tensor ->
            index to ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        }.toMap()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputBuffers)

        val decodedOutputs = outputTensors.indices.map { index ->
            outputTensors[index] to readDetectorOutput(outputTensors[index], outputBuffers.getValue(index))
        }
        val primaryEntry = decodedOutputs.firstOrNull { (tensor, output) ->
            tensor.name().contains("box", ignoreCase = true) && output.channelCount == 4
        } ?: decodedOutputs.first()
        val primary = primaryEntry.second
        if (primary.channelCount >= 5) return primary

        require(primary.channelCount == 4) {
            "Unsupported YOLO boxes shape: ${primaryEntry.first.shape().contentToString()}"
        }

        // Qualcomm AI Hub YOLO exports expose decoded boxes, one class index, and one score
        // per candidate instead of the usual [1, 84, 8400] tensor.
        val classIds = decodedOutputs.firstOrNull { (tensor, output) ->
            tensor !== primaryEntry.first &&
                tensor.name().contains("class", ignoreCase = true) &&
                output.boxCount == primary.boxCount
        }?.second
        val confidenceScores = decodedOutputs.firstOrNull { (tensor, output) ->
            tensor !== primaryEntry.first &&
                (tensor.name().contains("score", ignoreCase = true) ||
                    tensor.name().contains("confidence", ignoreCase = true)) &&
                output.boxCount == primary.boxCount
        }?.second
        if (classIds != null && confidenceScores != null) {
            return DetectorOutput(
                channelCount = 4,
                boxCount = primary.boxCount,
                values = primary.values,
                boxesAreCorners = true,
                classIndices = classIds.values,
                confidenceScores = confidenceScores.values,
            )
        }

        // Other split exports expose boxes plus a complete class-score tensor.
        val scores = decodedOutputs.asSequence()
            .filter { it.first !== primaryEntry.first }
            .map { it.second }
            .firstOrNull { it.boxCount == primary.boxCount && it.channelCount > 1 }
            ?: throw IllegalArgumentException(
                "YOLO boxes have no matching scores/classes; outputs=" +
                    outputTensors.joinToString { "${it.name()}=${it.shape().contentToString()}" },
            )

        val channels = primary.channelCount + scores.channelCount
        val values = FloatArray(channels * primary.boxCount)
        primary.values.copyInto(values)
        scores.values.copyInto(
            destination = values,
            destinationOffset = primary.channelCount * primary.boxCount,
        )
        // This YOLO-World split [1,N,4] + [1,N,C] export emits left/top/right/bottom
        // distances from the 80x80, 40x40, and 20x20 grid anchors. Only the Qualcomm
        // boxes/classes/scores export above emits decoded corners directly.
        val isYoloWorldDistanceOutput = primary.boxCount == YOLO_WORLD_CANDIDATE_COUNT &&
            scores.channelCount == COCO_CLASSES.size
        return DetectorOutput(
            channelCount = channels,
            boxCount = primary.boxCount,
            values = values,
            boxesAreCorners = false,
            boxesUseYoloWorldDistances = isYoloWorldDistanceOutput,
        )
    }

    private fun readDetectorOutput(tensor: Tensor, rawOutput: ByteBuffer): DetectorOutput {
        val shape = tensor.shape()
        if (shape.size == 2 && shape[0] == 1 && shape[1] > 0) {
            rawOutput.rewind()
            return DetectorOutput(
                channelCount = 1,
                boxCount = shape[1],
                values = readTensorValues(rawOutput, tensor),
            )
        }
        require(shape.size == 3 && shape[0] == 1) {
            "Unsupported YOLO output shape: ${shape.contentToString()}"
        }
        val channelsFirst = shape[1] <= shape[2]
        val channels = if (channelsFirst) shape[1] else shape[2]
        val boxes = if (channelsFirst) shape[2] else shape[1]
        require(channels >= 4 && boxes > 0) {
            "Unsupported YOLO output shape: ${shape.contentToString()}"
        }
        rawOutput.rewind()
        val flatValues = readTensorValues(rawOutput, tensor)
        val values = FloatArray(channels * boxes)
        for (channel in 0 until channels) {
            for (box in 0 until boxes) {
                val sourceIndex = if (channelsFirst) channel * boxes + box else box * channels + channel
                values[channel * boxes + box] = flatValues[sourceIndex]
            }
        }
        return DetectorOutput(channels, boxes, values)
    }

    private fun readTensorValues(buffer: ByteBuffer, tensor: Tensor): FloatArray {
        buffer.rewind()
        val values = FloatArray(tensor.numBytes() / tensor.dataType().byteSize())
        val scale = tensor.quantizationParams().scale
        val zeroPoint = tensor.quantizationParams().zeroPoint
        for (index in values.indices) {
            values[index] = when (tensor.dataType()) {
                DataType.FLOAT32 -> buffer.float
                DataType.UINT8 -> ((buffer.get().toInt() and 0xFF) - zeroPoint) * scale
                DataType.INT8 -> (buffer.get().toInt() - zeroPoint) * scale
                DataType.INT32 -> buffer.int.toFloat()
                DataType.INT64 -> buffer.long.toFloat()
                else -> throw IllegalStateException("Unsupported YOLO output type: ${tensor.dataType()}")
            }
        }
        return values
    }

    private fun readDepthMap(buffer: ByteBuffer, tensor: Tensor): Array<FloatArray> {
        val shape = tensor.shape()
        val height: Int
        val width: Int
        when {
            shape.contentEquals(intArrayOf(1, depthHeight, depthWidth)) -> {
                height = shape[1]
                width = shape[2]
            }
            shape.contentEquals(intArrayOf(1, 1, depthHeight, depthWidth)) -> {
                height = shape[2]
                width = shape[3]
            }
            else -> throw IllegalStateException("Unsupported depth output shape: ${shape.contentToString()}")
        }
        val values = readTensorValues(buffer, tensor)
        require(values.size == height * width) {
            "Unsupported depth output size: ${values.size}"
        }
        return Array(height) { y -> FloatArray(width) { x -> values[y * width + x] } }
    }

    private fun nonMaxSuppression(boxes: List<DetectedObject>, iouThreshold: Float): List<DetectedObject> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectedObject>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { other ->
                best.label == other.label && computeIoU(best.boundingBox, other.boundingBox) >= iouThreshold
            }
        }
        return selected
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return interArea / (areaA + areaB - interArea)
    }

    override suspend fun estimateDepth(frame: VisionFrame): DepthResult? = withContext(Dispatchers.Default) {
        val interp = depthInterpreter
        val t0 = SystemClock.elapsedRealtime()

        if (interp == null) {
            // Heuristic fallback when Depth TFLite model is not loaded:
            // Analyze bottom-third pixel variance / horizontal gradients to detect sharp contrast lines (curbs/steps/drops)
            val groundBmp = Bitmap.createBitmap(
                frame.bitmap,
                0,
                (frame.bitmap.height * 0.65f).toInt().coerceIn(0, frame.bitmap.height - 1),
                frame.bitmap.width,
                (frame.bitmap.height * 0.35f).toInt().coerceAtLeast(1)
            )
            val scaledGround = Bitmap.createScaledBitmap(groundBmp, 128, 64, true)
            val pixels = IntArray(128 * 64)
            scaledGround.getPixels(pixels, 0, 128, 0, 0, 128, 64)

            var maxGrad = 0f
            var prevRowAvg = 0f
            for (y in 0 until 64) {
                var rowSum = 0f
                for (x in 0 until 128) {
                    val p = pixels[y * 128 + x]
                    val lum = (((p shr 16) and 0xFF) * 0.299f) + (((p shr 8) and 0xFF) * 0.587f) + ((p and 0xFF) * 0.114f)
                    rowSum += lum
                }
                val rowAvg = rowSum / 128f
                if (y > 0) {
                    val diff = kotlin.math.abs(rowAvg - prevRowAvg)
                    if (diff > maxGrad) maxGrad = diff
                }
                prevRowAvg = rowAvg
            }

            val discontinuity = maxGrad > 38f
            val t1 = SystemClock.elapsedRealtime()
            return@withContext DepthResult(
                relativeDepthSummary = if (discontinuity) "Potential ground discontinuity/step detected ahead" else "Ground floor trajectory uniform",
                groundDiscontinuityDetected = discontinuity,
                closestRegion = "center",
                inferenceTimeMs = t1 - t0,
            )
        }

        // 1. Preprocess bitmap for Depth Anything (depthWidth x depthHeight)
        val resized = Bitmap.createScaledBitmap(frame.bitmap, depthWidth, depthHeight, true)
        val inputBuffer = ByteBuffer.allocateDirect(interp.getInputTensor(0).numBytes()).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(depthWidth * depthHeight)
        resized.getPixels(pixels, 0, depthWidth, 0, 0, depthWidth, depthHeight)

        // Standard ImageNet normalization; the DA3 LiteRT export uses NCHW.
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        if (depthInputNchw) {
            for (channel in 0..2) {
                for (pixel in pixels) {
                    inputBuffer.putFloat((pixelChannel(pixel, channel) - mean[channel]) / std[channel])
                }
            }
        } else {
            for (pixel in pixels) {
                for (channel in 0..2) {
                    inputBuffer.putFloat((pixelChannel(pixel, channel) - mean[channel]) / std[channel])
                }
            }
        }

        val outputTensor = interp.getOutputTensor(0)
        val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
        inputBuffer.rewind()
        interp.run(inputBuffer, outputBuffer)

        val t1 = SystemClock.elapsedRealtime()
        val depthMap = readDepthMap(outputBuffer, outputTensor)

        // 2. Postprocess Depth Map: calculate relative depth per region
        val colWidth = depthWidth / 3
        var minLeftDepth = Float.MAX_VALUE
        var minCenterDepth = Float.MAX_VALUE
        var minRightDepth = Float.MAX_VALUE

        val groundStartY = (depthHeight * 0.60f).toInt()
        var groundSum = 0f
        var groundCount = 0
        var groundMin = Float.MAX_VALUE
        var groundMax = -Float.MAX_VALUE

        for (y in groundStartY until depthHeight) {
            for (x in 0 until depthWidth) {
                val d = depthMap[y][x]
                if (d < groundMin) groundMin = d
                if (d > groundMax) groundMax = d
                groundSum += d
                groundCount++

                when {
                    x < colWidth -> if (d < minLeftDepth) minLeftDepth = d
                    x < 2 * colWidth -> if (d < minCenterDepth) minCenterDepth = d
                    else -> if (d < minRightDepth) minRightDepth = d
                }
            }
        }

        val closestRegion = when {
            minLeftDepth <= minCenterDepth && minLeftDepth <= minRightDepth -> "left"
            minRightDepth <= minCenterDepth && minRightDepth <= minLeftDepth -> "right"
            else -> "center"
        }

        // Ground discontinuity detection: sudden variance/drop in lower-center ground region relative depth
        val groundSpread = groundMax - groundMin
        val groundDiscontinuity = groundSpread > 0.35f || (groundCount > 0 && (groundMax - (groundSum / groundCount)) > 0.25f)

        DepthResult(
            relativeDepthSummary = "Relative depth map analyzed: closest surface in $closestRegion region (discontinuity: ${if (groundDiscontinuity) "YES" else "NONE"})",
            groundDiscontinuityDetected = groundDiscontinuity,
            closestRegion = closestRegion,
            inferenceTimeMs = t1 - t0,
        )
    }

    override fun acceleratorInfo(): AcceleratorInfo = activeAcceleratorInfo

    override fun close() {
        detectorInterpreter?.close()
        depthInterpreter?.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
        detectorInterpreter = null
        depthInterpreter = null
    }

    companion object {
        private const val TAG = "LiteRtVisionBackend"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val YOLO_WORLD_CANDIDATE_COUNT = 8_400

        private val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }
}
