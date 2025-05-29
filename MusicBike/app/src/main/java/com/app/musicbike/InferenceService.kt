package com.app.musicbike

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Data class for sensor readings
data class SensorReading(
    val pitch: Float,
    val roll: Float,
    val yaw: Float,
    val gforce: Float,
    val timestamp: Long = System.currentTimeMillis()
)

class InferenceService : Service() {

    private var serviceLooper: Looper? = null
    private var serviceHandler: ServiceHandler? = null
    private lateinit var tflite: Interpreter
    private val TFLITE_MODEL_FILENAME = "your_model.tflite" // Replace with your model filename
    private val TAG = "InferenceService"

    // Rolling buffer configuration
    private val BUFFER_SIZE = 220
    private val INFERENCE_TRIGGER_COUNT = 100
    private val sensorBuffer = ArrayList<SensorReading>(BUFFER_SIZE)
    private var writeCount = 0
    private val bufferLock = ReentrantLock()

    // Model input configuration - adjust based on your model
    // Assuming model expects: [batch_size, sequence_length, num_features]
    // Where num_features = 4 (pitch, roll, yaw, gforce)
    private val INPUT_SEQUENCE_LENGTH = 220  // Buffer size
    private val NUM_FEATURES = 4  // pitch, roll, yaw, gforce

    // Binder given to clients
    private val binder = LocalBinder()

    // LiveData to broadcast results
    private val _inferenceResult = MutableLiveData<String>()
    val inferenceResult: LiveData<String> get() = _inferenceResult

    // Handler that receives messages from the thread
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_ADD_SENSOR_DATA -> {
                        val sensorReading = msg.obj as? SensorReading
                        if (sensorReading != null) {
                            addSensorDataToBuffer(sensorReading)
                        }
                    }
                    MSG_RUN_INFERENCE -> {
                        Log.d(TAG, "Running inference on current buffer...")
                        performInference()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in ServiceHandler: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val MSG_ADD_SENSOR_DATA = 1
        private const val MSG_RUN_INFERENCE = 2
    }

    /**
     * Class used for the client Binder.
     */
    inner class LocalBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        
        // Initialize the handler thread
        HandlerThread("InferenceServiceThread", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = ServiceHandler(looper)
        }

        // Initialize the sensor buffer
        initializeSensorBuffer()

        // Try to load the model, but don't stop the service if it fails
        loadTFLiteModel()
    }

    private fun initializeSensorBuffer() {
        bufferLock.withLock {
            sensorBuffer.clear()
            writeCount = 0
        }
        Log.d(TAG, "Sensor buffer initialized with size $BUFFER_SIZE")
    }

    private fun logModelInfo() {
        try {
            val inputShape = tflite.getInputTensor(0).shape()
            val outputShape = tflite.getOutputTensor(0).shape()
            Log.d(TAG, "Model input shape: ${inputShape.joinToString()}")
            Log.d(TAG, "Model output shape: ${outputShape.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting model info: ${e.message}")
        }
    }

    // Public method to add sensor data
    fun addSensorData(pitch: Float, roll: Float, yaw: Float, gforce: Float) {
        val sensorReading = SensorReading(pitch, roll, yaw, gforce)
        val msg = serviceHandler?.obtainMessage(MSG_ADD_SENSOR_DATA, sensorReading)
        serviceHandler?.sendMessage(msg!!)
    }

    private fun addSensorDataToBuffer(sensorReading: SensorReading) {
        bufferLock.withLock {
            // Add to buffer
            if (sensorBuffer.size >= BUFFER_SIZE) {
                // Remove oldest entry
                sensorBuffer.removeAt(0)
            }
            sensorBuffer.add(sensorReading)
            writeCount++

            Log.v(TAG, "Added sensor data: ${sensorReading}, Buffer size: ${sensorBuffer.size}, Write count: $writeCount")

            // Trigger inference every INFERENCE_TRIGGER_COUNT writes
            if (writeCount % INFERENCE_TRIGGER_COUNT == 0 && sensorBuffer.size == BUFFER_SIZE) {
                Log.d(TAG, "Triggering inference after $writeCount writes")
                val inferenceMsg = serviceHandler?.obtainMessage(MSG_RUN_INFERENCE)
                serviceHandler?.sendMessage(inferenceMsg!!)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand, startId: $startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        if (::tflite.isInitialized) {
            tflite.close()
            Log.d(TAG, "TFLite interpreter closed.")
        }
        serviceLooper?.quitSafely()
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd(TFLITE_MODEL_FILENAME)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // --- Input Preparation for Sensor Data ---
    private fun prepareInputData(): ByteBuffer? {
        Log.d(TAG, "Preparing sensor input data...")
        
        val currentBuffer: List<SensorReading>
        bufferLock.withLock {
            if (sensorBuffer.size != BUFFER_SIZE) {
                Log.w(TAG, "Buffer not full (${sensorBuffer.size}/$BUFFER_SIZE), skipping inference")
                return null
            }
            currentBuffer = ArrayList(sensorBuffer)
        }

        // Create input buffer: [1, sequence_length, num_features]
        val inputBufferSize = 1 * INPUT_SEQUENCE_LENGTH * NUM_FEATURES * 4 // 4 bytes per float
        val inputBuffer = ByteBuffer.allocateDirect(inputBufferSize)
        inputBuffer.order(ByteOrder.nativeOrder())

        // Fill the buffer with sensor data
        for (reading in currentBuffer) {
            inputBuffer.putFloat(reading.pitch)
            inputBuffer.putFloat(reading.roll)
            inputBuffer.putFloat(reading.yaw)
            inputBuffer.putFloat(reading.gforce)
        }

        inputBuffer.rewind()
        Log.d(TAG, "Input data prepared. Buffer size: ${currentBuffer.size} readings")
        return inputBuffer
    }

    // --- Perform Inference ---
    private fun performInference() {
        if (!::tflite.isInitialized) {
            Log.e(TAG, "TFLite interpreter not initialized.")
            return
        }

        val modelInput = prepareInputData()
        if (modelInput == null) {
            Log.e(TAG, "Failed to prepare input data.")
            return
        }

        try {
            // Prepare output buffer
            val outputShape = tflite.getOutputTensor(0).shape()
            val outputDataType = tflite.getOutputTensor(0).dataType()
            Log.d(TAG, "Output tensor shape: ${outputShape.joinToString()}, Data type: $outputDataType")

            val outputBufferSize = outputShape.reduce { acc, i -> acc * i } * when (outputDataType) {
                org.tensorflow.lite.DataType.FLOAT32 -> 4
                org.tensorflow.lite.DataType.UINT8 -> 1
                else -> throw IllegalArgumentException("Unsupported output data type: $outputDataType")
            }
            val outputBuffer = ByteBuffer.allocateDirect(outputBufferSize)
            outputBuffer.order(ByteOrder.nativeOrder())

            Log.d(TAG, "Running inference...")
            val startTime = System.currentTimeMillis()
            tflite.run(modelInput, outputBuffer)
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inference completed in ${inferenceTime}ms")
            
            outputBuffer.rewind()
            processOutput(outputBuffer, inferenceTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error running inference: ${e.message}", e)
            sendResultToClient("Error: ${e.message}")
        }
    }

    // --- Process Output ---
    private fun processOutput(outputBuffer: ByteBuffer, inferenceTime: Long) {
        Log.d(TAG, "Processing output...")
        
        val outputShape = tflite.getOutputTensor(0).shape()
        val outputDataType = tflite.getOutputTensor(0).dataType()

        when (outputDataType) {
            org.tensorflow.lite.DataType.FLOAT32 -> {
                val outputSize = outputShape.reduce { acc, i -> acc * i }
                val results = FloatArray(outputSize)
                outputBuffer.asFloatBuffer().get(results)
                
                // Process results based on your model's output format
                Log.d(TAG, "Inference results: ${results.joinToString(limit = 10)}")
                
                // Example: If it's a classification model
                if (results.size > 1) {
                    val maxIndex = results.indices.maxByOrNull { results[it] } ?: -1
                    val confidence = if (maxIndex >= 0) results[maxIndex] else 0f
                    sendResultToClient("Prediction: Class $maxIndex, Confidence: $confidence, Time: ${inferenceTime}ms")
                } else {
                    // Single output value
                    sendResultToClient("Result: ${results[0]}, Time: ${inferenceTime}ms")
                }
            }
            org.tensorflow.lite.DataType.UINT8 -> {
                val bytes = ByteArray(outputShape.reduce { acc, i -> acc * i })
                outputBuffer.get(bytes)
                Log.d(TAG, "UINT8 Output length: ${bytes.size}")
                sendResultToClient("UINT8 result received. Length: ${bytes.size}, Time: ${inferenceTime}ms")
            }
            else -> {
                Log.e(TAG, "Unsupported output data type: $outputDataType")
                sendResultToClient("Error: Unsupported output data type")
            }
        }
    }

    // --- Send results back to client ---
    private fun sendResultToClient(result: String) {
        val intent = Intent("com.app.musicbike.INFERENCE_RESULT")
        intent.putExtra("result_data", result)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Result broadcasted: $result")
        
        // Also update LiveData
        _inferenceResult.postValue(result)
    }

    // Public method to manually trigger inference (for testing)
    fun triggerInference() {
        val msg = serviceHandler?.obtainMessage(MSG_RUN_INFERENCE)
        serviceHandler?.sendMessage(msg!!)
    }

    // Retry loading the model (useful after dependency updates)
    fun retryLoadModel() {
        Log.d(TAG, "Retrying model load...")
        loadTFLiteModel()
    }

    // Check if model is loaded and ready
    fun isModelReady(): Boolean {
        return ::tflite.isInitialized
    }

    // Get current buffer status
    fun getBufferStatus(): String {
        bufferLock.withLock {
            val modelStatus = if (::tflite.isInitialized) "Model: Ready" else "Model: Not loaded"
            return "Buffer: ${sensorBuffer.size}/$BUFFER_SIZE, Writes: $writeCount, $modelStatus"
        }
    }

    private fun loadTFLiteModel() {
        try {
            // Create interpreter options for better compatibility
            val options = Interpreter.Options().apply {
                // Enable experimental operations that might be needed
                setUseXNNPACK(true)
                // Set number of threads for better performance
                setNumThreads(4)
                // Enable CPU kernels fallback
                setAllowFp16PrecisionForFp32(true)
                // Add experimental delegate for newer operations
                setAllowBufferHandleOutput(true)
            }
            
            tflite = Interpreter(loadModelFile(), options)
            Log.d(TAG, "TFLite model loaded successfully with enhanced options.")
            logModelInfo()
        } catch (e: IOException) {
            Log.e(TAG, "Error loading TFLite model file: ${e.message}", e)
            sendResultToClient("Error: Failed to load model file - ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "TFLite compatibility error: ${e.message}", e)
            // Try alternative loading approach
            tryAlternativeModelLoading()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading TFLite model: ${e.message}", e)
            sendResultToClient("Error: Unexpected model loading error - ${e.message}")
        }
    }

    private fun tryAlternativeModelLoading() {
        Log.d(TAG, "Trying alternative model loading approach...")
        try {
            // Try with minimal options
            val minimalOptions = Interpreter.Options().apply {
                setNumThreads(1)  // Use single thread
                setUseXNNPACK(false)  // Disable XNNPACK
            }
            
            tflite = Interpreter(loadModelFile(), minimalOptions)
            Log.d(TAG, "TFLite model loaded successfully with minimal options.")
            logModelInfo()
            sendResultToClient("Model loaded with compatibility mode")
        } catch (e: Exception) {
            Log.e(TAG, "Alternative model loading also failed: ${e.message}", e)
            sendResultToClient("Error: Model incompatible with current TensorFlow Lite version. Please check model compatibility.")
        }
    }
} 