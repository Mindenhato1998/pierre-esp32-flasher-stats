package com.smartpierre.espflasher

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlin.math.sqrt
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.net.URL
import java.io.FileOutputStream
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
// Data class for error detection pattern
data class ErrorPattern(
    val id: String,
    val patterns: List<String>,
    val displayName: String,
    val errorMessage: String,
    val enabled: Boolean
)

class MainActivity : ComponentActivity(), SensorEventListener {
    
    companion object {
        fun getAppVersion(context: android.content.Context): String {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName ?: "1.2"
            } catch (e: Exception) {
                "1.2"
            }
        }
    }
    
    private lateinit var usbManager: UsbSerialManager
    private val logs = mutableStateListOf<String>()
    private var errorPatterns = listOf<ErrorPattern>()
    

    // MQTT for HiveMQ Cloud sharing
    internal var mqttManager: MqttManager? = null
    private var mqttConnected = mutableStateOf(false)
    private var mqttShareUrl = mutableStateOf<String?>(null)

    // Statistics Manager for flash/erase tracking
    private lateinit var statisticsManager: StatisticsManager
    
    // Wake lock to keep app running with screen off
    private lateinit var wakeLock: PowerManager.WakeLock
    
    
    // Shake detection variables (Facebook-style implementation)
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    
    // Industry standard 2.7G threshold (used by Facebook, Instagram, etc.)
    private val SHAKE_THRESHOLD_GRAVITY = 2.7f  // Must be greater than 1G
    private val SHAKE_SLOP_TIME_MS = 500  // Ignore shakes too close to each other
    private val SHAKE_COUNT_RESET_TIME_MS = 3000  // Reset shake count after 3 seconds
    private val MIN_SHAKE_COUNT = 2  // Minimum number of shakes required
    
    private var shakeTimestamp = 0L
    private var shakeCount = 0
    private var showBugReportDialog = mutableStateOf(false)
    
    private fun loadErrorPatterns() {
        try {
            val jsonString = assets.open("error_detection.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val patternsArray = jsonObject.getJSONArray("error_patterns")
            
            val patterns = mutableListOf<ErrorPattern>()
            for (i in 0 until patternsArray.length()) {
                val pattern = patternsArray.getJSONObject(i)
                val patternsList = mutableListOf<String>()
                
                // Check if it has "patterns" array or single "pattern" string (for backward compatibility)
                if (pattern.has("patterns")) {
                    val patternsJsonArray = pattern.getJSONArray("patterns")
                    for (j in 0 until patternsJsonArray.length()) {
                        patternsList.add(patternsJsonArray.getString(j))
                    }
                } else if (pattern.has("pattern")) {
                    patternsList.add(pattern.getString("pattern"))
                }
                
                patterns.add(
                    ErrorPattern(
                        id = pattern.getString("id"),
                        patterns = patternsList,
                        displayName = pattern.getString("display_name"),
                        errorMessage = pattern.getString("error_message"),
                        enabled = pattern.getBoolean("enabled")
                    )
                )
            }
            errorPatterns = patterns
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading error patterns: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        // Handle USB device attachment when app is already running
        // The BroadcastReceiver in the Composable will handle this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load error detection patterns
        loadErrorPatterns()
        
        // Initialize sensor manager for shake detection
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        // Initialize wake lock to keep app running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PierreFlasher::MainWakeLock"
        )
        
        usbManager = UsbSerialManager(
            context = this,
            log = { msg ->
                lifecycleScope.launch {
                    addLog(msg)
                }
            }
        )

        // Initialize Statistics Manager for flash/erase tracking
        statisticsManager = StatisticsManager(
            context = this,
            onError = { error ->
                Log.w("MainActivity", "Statistics error: $error")
            }
        )

        // Connect to MQTT with LWT (Last Will and Testament)
        try {
            statisticsManager.connect()
            Log.d("MainActivity", "Connected to MQTT with LWT")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to connect to MQTT: ${e.message}")
        }

        // Force clean start - close any existing connections
        try {
            usbManager.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        

        // Initialize MQTT Manager for HiveMQ Cloud
        mqttManager = MqttManager(
            context = this,
            onConnectionStatus = { connected ->
                lifecycleScope.launch {
                    mqttConnected.value = connected
                    if (connected) {
                        mqttShareUrl.value = mqttManager?.getShareUrl()
                        addLog("✅ HiveMQ Cloud connected - Live serial sharing ready")
                        // Don't log the URL to avoid confusion
                    } else {
                        addLog("❌ HiveMQ Cloud disconnected")
                        mqttShareUrl.value = null
                    }
                }
            },
            onError = { error ->
                lifecycleScope.launch {
                    addLog("⚠️ MQTT Error: $error")
                }
            }
        )

        // Connect to HiveMQ in background
        lifecycleScope.launch(Dispatchers.IO) {
            mqttManager?.connect()
        }

        // Request battery optimization whitelist for uninterrupted USB operations
        requestBatteryOptimizationWhitelist()

        // Start foreground service for background operation
        startForegroundService()
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ESP32FlasherApp(
                        usbManager = usbManager,
                        logs = logs,
                        onLog = ::addLog,
                        showBugReportDialog = showBugReportDialog,
                        mqttConnected = mqttConnected,
                        mqttShareUrl = mqttShareUrl,
                        vibrateSuccess = ::vibrateSuccess,
                        statisticsManager = statisticsManager,
                        mqttManager = mqttManager
                    )
                }
            }
        }

        // Don't connect here - let the ESP32FlasherApp handle connection
        // This avoids connection before UI is ready
    }
    
    private fun addLog(message: String) {
        logs.add(message)  // Timestamp nélkül
        Log.d("ESP32Flasher", message) // Android logcat-be is írjuk

        // Don't publish system logs to MQTT anymore
        // Serial data is sent directly from the serial read loop
    }
    
    private fun requestBatteryOptimizationWhitelist() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    addLog("🔋 Battery optimization whitelist requested")
                } catch (e: Exception) {
                    addLog("Battery optimization request failed: ${e.message}")
                }
            }
        }
    }


    fun copyLogsToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val logsText = logs.joinToString("\n")
        val clip = ClipData.newPlainText("ESP32 Flasher Logs", logsText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
        addLog("Logs copied to clipboard")
    }

    fun vibrateSuccess() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Modern vibration pattern: short-pause-short-pause-long (success pattern)
                    val pattern = longArrayOf(0, 100, 100, 100, 100, 300)
                    val amplitudes = intArrayOf(0, 150, 0, 150, 0, 255)
                    val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                    vibrator.vibrate(effect)
                } else {
                    // Legacy vibration pattern for older devices
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 100, 100, 100, 100, 300), -1)
                }
            }
        } catch (e: Exception) {
            // Silently ignore vibration errors
        }
    }
    
    fun shareLogs() {
        try {
            val logsText = logs.joinToString("\n")
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

            // Fájl létrehozása a cache könyvtárban
            val file = File(cacheDir, "pierre_flasher_log_$timestamp.txt")
            file.writeText(logsText)

            // URI létrehozása a FileProvider használatával
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            // Megosztás intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"  // Általános fájl típus
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Pierre Flasher Log - $timestamp")
                putExtra(Intent.EXTRA_TEXT, "Pierre Flasher log: $timestamp")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            // Don't use createChooser to avoid USB disconnection
            try {
                startActivity(shareIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No app found to share files", Toast.LENGTH_SHORT).show()
            }
            addLog("Sharing log...")
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing: ${e.message}", Toast.LENGTH_LONG).show()
            addLog("Share error: ${e.message}")
        }
    }

    fun shareLogsDelayed() {
        try {
            val logsText = logs.joinToString("\n")
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

            // Fájl létrehozása a cache könyvtárban
            val file = File(cacheDir, "pierre_flasher_log_$timestamp.txt")
            file.writeText(logsText)

            // URI létrehozása a FileProvider használatával
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            // Megosztás intent with flags to avoid USB disconnection
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Pierre Flasher Log - $timestamp")
                putExtra(Intent.EXTRA_TEXT, "Pierre Flasher log: $timestamp")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            // Don't use createChooser to avoid USB disconnection
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(shareIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No app found to share files", Toast.LENGTH_SHORT).show()
            }
            addLog("Sharing log...")
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing: ${e.message}", Toast.LENGTH_LONG).show()
            addLog("Share error: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()

        // DON'T close USB connections on resume - let them continue during flashing!
        // The SerialMonitorService maintains the connection across screen lock/unlock

        // Register shake detection sensor
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Acquire wake lock to keep running in background
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Keep running in background - don't unregister sensor or release wake lock
        // This allows the app to continue reading serial data with screen off
    }
    
    
    private fun startForegroundService() {
        val serviceIntent = Intent(this, SerialMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
    
    private fun stopForegroundService() {
        val serviceIntent = Intent(this, SerialMonitorService::class.java)
        stopService(serviceIntent)
    }

    override fun onStop() {
        super.onStop()
        // App goes to background - MQTT connection will stay alive
        // LWT will handle offline status if connection is lost
    }

    override fun onDestroy() {
        super.onDestroy()

        // Disconnect from statistics MQTT (LWT will send offline automatically)
        statisticsManager.disconnect()

        // Disconnect from serial MQTT
        mqttManager?.disconnect()

        // Force clean USB shutdown
        try {
            usbManager.close()
        } catch (e: Exception) {
            Log.d("MainActivity", "USB cleanup error: ${e.message}")
        }
        
        // Release wake lock
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        
        // Unregister sensor
        sensorManager.unregisterListener(this)
        
        // Stop foreground service
        stopForegroundService()
        
    }
    
    // Sensor event handlers for shake detection (Facebook-style algorithm)
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // Normalize the accelerometer values to G-force
            // This is the key to Facebook's implementation
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH
            
            // Calculate G-Force (will be ~1 when there is no movement)
            // This is the industry standard formula used by Facebook
            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
            
            // Check if the G-Force exceeds the threshold
            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                val now = System.currentTimeMillis()
                
                // Ignore shake events too close to each other (500ms)
                if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) {
                    return
                }
                
                // Reset the shake count after 3 seconds of no shakes
                if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
                    shakeCount = 0
                }
                
                shakeTimestamp = now
                shakeCount++
                
                // Trigger bug report dialog after minimum shakes
                if (shakeCount >= MIN_SHAKE_COUNT) {
                    shakeCount = 0 // Reset for next time
                    
                    // Only show if not already showing
                    if (!showBugReportDialog.value) {
                        showBugReportDialog.value = true
                    }
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for shake detection
    }
}

@Composable
fun FeatureItem(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "• ",
            color = Color(0xFF146E89),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ESP32FlasherApp(
    usbManager: UsbSerialManager,
    logs: List<String>,
    onLog: (String) -> Unit,
    showBugReportDialog: MutableState<Boolean>,
    mqttConnected: MutableState<Boolean>,
    mqttShareUrl: MutableState<String?>,
    vibrateSuccess: () -> Unit,
    statisticsManager: StatisticsManager,
    mqttManager: MqttManager?
) {
    val context = LocalContext.current as Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    
    var isConnected by remember { mutableStateOf(false) }
    var isFlashing by remember { mutableStateOf(false) }
    var showSerialMonitor by remember { mutableStateOf(false) }
    var isSerialMonitorRunning by remember { mutableStateOf(false) }
    val serialData = remember { mutableStateListOf<String>() }
    var showEraseDialog by remember { mutableStateOf(false) }
    var isErasing by remember { mutableStateOf(false) }
    var showSuccessBorder by remember { mutableStateOf(false) }
    var isUsbOperationInProgress by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchInSerial by remember { mutableStateOf(false) } // false = napló, true = serial
    var activeSearchQuery by remember { mutableStateOf("") } // A tényleges keresés ami aktív marad
    
    // Download states
    var isDownloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf("PRODUCTION") }
    var downloadedProductionFile by remember { mutableStateOf<File?>(null) }
    
    // Custom firmware variables
    var customFirmwareFile by remember { mutableStateOf<File?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var isPressing by remember { mutableStateOf(false) }
    
    // Serial Monitor auto-scroll states
    var isSerialAutoScrollEnabled by remember { mutableStateOf(true) }
    var showSerialAutoScrollButton by remember { mutableStateOf(false) }
    
    // Diagnostics states
    var showStatusMonitor by remember { mutableStateOf(false) }
    var compatibilityResults by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var deviceStatusInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    
    // Info dialog state
    var showInfoDialog by remember { mutableStateOf(false) }
    var showWhatsNewDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogType by remember { mutableStateOf("logs") } // "logs" or "serial"
    
    // Check for app updates on first launch
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastVersion = prefs.getString("last_version", "0.0") ?: "0.0"
        val currentVersion = MainActivity.getAppVersion(context)
        
        if (lastVersion != currentVersion) {
            showWhatsNewDialog = true
            prefs.edit().putString("last_version", currentVersion).apply()
        }
    }
    
    // Serial monitoring job
    var serialJob by remember { mutableStateOf<Job?>(null) }
    
    // Global serial list state for search functionality
    val globalSerialListState = rememberLazyListState()
    
    // File picker launcher for custom firmware
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val tempFile = File(context.cacheDir, "custom_firmware.bin")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                customFirmwareFile = tempFile
                downloadStatus = "CUSTOM"
                onLog("✓ Custom firmware selected: ${uri.lastPathSegment}")
            } catch (e: Exception) {
                onLog("Error loading custom firmware: ${e.message}")
            }
        }
    }
    
    // Completely disabled USB health monitoring during user interactions
    LaunchedEffect(isConnected) {
        if (isConnected) {
            var consecutiveFailures = 0
            while (isConnected) {
                delay(10000) // Check less frequently - every 10 seconds

                // COMPLETELY SKIP health check during ANY user interaction, dialog or serial monitoring
                if (isConnected &&
                    !isUsbOperationInProgress &&
                    !isFlashing &&
                    !isErasing &&
                    !isSerialMonitorRunning &&  // Skip when serial monitor is running
                    !showShareDialog &&
                    !showInfoDialog &&
                    !showWhatsNewDialog &&
                    !showBugReportDialog.value) {

                    // Only do minimal check - don't write anything
                    val isPortValid = withContext(Dispatchers.IO) {
                        try {
                            val port = usbManager.port
                            // Just check if port exists and is open, don't write
                            port != null && port.isOpen
                        } catch (e: Exception) {
                            true // Assume it's OK to avoid false disconnections
                        }
                    }

                    if (!isPortValid) {
                        consecutiveFailures++
                        // Only disconnect after 5 consecutive failures (50 seconds)
                        if (consecutiveFailures >= 5) {
                            onLog("🔌 Connection lost - attempting recovery...")
                            isConnected = false
                            consecutiveFailures = 0

                            // Stop serial monitor
                            if (isSerialMonitorRunning) {
                                isSerialMonitorRunning = false
                                showSerialMonitor = false
                            }

                            // Try reconnect
                            withContext(Dispatchers.IO) {
                                isUsbOperationInProgress = true
                                try {
                                    usbManager.close()
                                    delay(1000)
                                    val success = usbManager.openFirstPort()
                                    withContext(Dispatchers.Main) {
                                        isConnected = success
                                        if (success) {
                                            onLog("✅ USB connection restored")
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isConnected = false
                                    }
                                } finally {
                                    isUsbOperationInProgress = false
                                }
                            }
                            break
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                }
            }
        }
    }
    
    // Initial connection check on app start
    LaunchedEffect(Unit) {
        delay(300) // Small delay for UI initialization
        if (!isConnected) {
            val success = withContext(Dispatchers.IO) {
                usbManager.openFirstPort()
            }
            isConnected = success
        }
    }

    // Automatic USB device detection and connection
    DisposableEffect(Unit) {
        val androidUsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val intentFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        device?.let {
                            // Try to connect automatically if not already connected
                            // and no USB operation is in progress
                            if (!isConnected && !isFlashing && !isErasing && !isUsbOperationInProgress) {
                                scope.launch {
                                    isUsbOperationInProgress = true
                                    // Add small delay to let USB stabilize
                                    delay(200)
                                    val success = withContext(Dispatchers.IO) {
                                        try {
                                            usbManager.openFirstPort()
                                        } catch (e: Exception) {
                                            onLog("USB connection failed: ${e.message}")
                                            false
                                        }
                                    }
                                    isConnected = success
                                    isUsbOperationInProgress = false
                                }
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        device?.let {
                            if (isConnected) {
                                // Stop serial monitor if running
                                if (isSerialMonitorRunning) {
                                    isSerialMonitorRunning = false
                                    showSerialMonitor = false
                                }
                                // Small delay to let operations complete
                                scope.launch {
                                    isUsbOperationInProgress = true
                                    delay(100)
                                    withContext(Dispatchers.IO) {
                                        usbManager.close()
                                    }
                                    isConnected = false
                                    onLog("USB disconnected")
                                    isUsbOperationInProgress = false
                                }
                            }
                        }
                    }
                }
            }
        }
        
        context.registerReceiver(usbReceiver, intentFilter)
        
        // Don't auto-connect on app start - wait for USB events
        // This prevents duplicate connections
        
        // Cleanup receiver when composable is disposed
        onDispose {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                // Receiver might already be unregistered
            }
        }
    }
    
    // Dynamic orientation management
    LaunchedEffect(showSerialMonitor) {
        if (showSerialMonitor) {
            // Allow rotation when Serial Monitor is open
            context.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            // Lock to portrait when Serial Monitor is closed
            context.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    
    // Enhanced log callback that processes completion messages
    val enhancedOnLog: (String) -> Unit = { message: String ->
        onLog(message)

        // Flash completion detection
        if (message.contains("FLASH SUCCESSFUL") || message.contains("🎉 Flash process completed!")) {
            // Vibrate on success
            vibrateSuccess()
            // Zöld keret megjelenítése 2 másodpercre
            showSuccessBorder = true
            kotlinx.coroutines.GlobalScope.launch {
                kotlinx.coroutines.delay(2000)
                showSuccessBorder = false
            }
        }
    }
    
    // Download production firmware function
    suspend fun downloadProductionFirmware(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Create trust manager that accepts all certificates
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                // Install the all-trusting trust manager
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())

                val url = URL("https://rest.smartpierre.com/OTA/3/production.bin")
                val connection = url.openConnection() as HttpsURLConnection

                // Set SSL socket factory
                connection.sslSocketFactory = sslContext.socketFactory
                connection.setHostnameVerifier { _, _ -> true }

                // Set basic authentication
                val credentials = "firmware.controller.1:A123456"
                val auth = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", auth)

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 30000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val outputFile = File(context.cacheDir, "production_latest.bin")
                    val outputStream = FileOutputStream(outputFile)
                    
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var totalBytes = 0
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    
                    outputStream.close()
                    inputStream.close()
                    
                    withContext(Dispatchers.Main) {
                        onLog("✓ Downloaded production firmware (${totalBytes / 1024} KB)")
                        downloadedProductionFile = outputFile
                    }
                    
                    true
                } else {
                    withContext(Dispatchers.Main) {
                        onLog("Download failed: HTTP $responseCode")
                    }
                    false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onLog("Download error: ${e.message}")
                }
                false
            }
        }
    }
    
    val listState = rememberLazyListState()
    
    // Compatibility checker functions - only mandatory requirements
    fun checkDeviceCompatibility(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        // Check USB Host support (MANDATORY)
        results["USB Host Support"] = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        
        // Check Android version compatibility (MANDATORY)
        results["Android 5.0+ Required"] = Build.VERSION.SDK_INT >= 21
        
        return results
    }
    
    fun getDeviceStatusInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        
        info["Device"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        info["Android Version"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        info["USB Host Support"] = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) "✓ Supported" else "✗ Not Supported"
        
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        info["USB Devices Found"] = "${usbManager.deviceList.size}"
        
        info["ESP32 Connection"] = if (isConnected) "✓ Connected" else "○ Waiting..."
        
        
        return info
    }
    
    fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            onLog("Cannot open Developer Settings: ${e.message}")
        }
    }
    
    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } catch (e: Exception) {
            onLog("Cannot open App Settings: ${e.message}")
        }
    }
    
    fun openRelevantSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            context.startActivity(intent)
            onLog("🔧 Opened Developer Options")
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                context.startActivity(intent)
                onLog("🔧 Opened Settings - Look for 'About phone' → Build number (tap 7x) → Developer Options")
            } catch (e2: Exception) {
                onLog("Cannot open Settings: ${e2.message}")
            }
        }
    }

    // Auto scroll a lista végére, ha új elem érkezik
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // Animált scroll az utolsó elemre
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    
    // In landscape mode, show only Serial Monitor if it's open
    if (isLandscape && showSerialMonitor) {
        // Fullscreen Serial Monitor for landscape
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            ),
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Serial monitor gombok
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Serial Monitor",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        
                        if (isSerialMonitorRunning) {
                            Text(
                                text = "● LIVE",
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Question mark icon for info dialog
                        IconButton(
                            onClick = {
                                showInfoDialog = true
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Help,
                                contentDescription = "Info",
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        // Search button
                        IconButton(
                            onClick = {
                                searchInSerial = true
                                searchQuery = ""
                                showSearchDialog = true
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Copy button
                        var copyButtonEnabled by remember { mutableStateOf(true) }
                        IconButton(
                            onClick = {
                                if (copyButtonEnabled) {
                                    copyButtonEnabled = false
                                    try {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Serial Data", serialData.joinToString("\n"))
                                        clipboard.setPrimaryClip(clip)
                                    } catch (e: Exception) {
                                        // Handle clipboard error
                                    }
                                    // Re-enable after 500ms
                                    scope.launch {
                                        delay(500)
                                        copyButtonEnabled = true
                                    }
                                }
                            },
                            enabled = copyButtonEnabled,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        // Share button
                        IconButton(
                            onClick = {
                                shareDialogType = "serial"
                                showShareDialog = true
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Removed MQTT Live Share button and Info button - not needed in serial monitor view

                        // Hardware control buttons removed - functionality integrated into main flash buttons

                        // Clear button
                        var clearButtonEnabled by remember { mutableStateOf(true) }
                        IconButton(
                            onClick = {
                                if (clearButtonEnabled) {
                                    clearButtonEnabled = false
                                    try {
                                        serialData.clear()
                                    } catch (e: Exception) {
                                        // Handle clear error
                                    }
                                    // Re-enable after 500ms
                                    scope.launch {
                                        delay(500)
                                        clearButtonEnabled = true
                                    }
                                }
                            },
                            enabled = clearButtonEnabled,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Close button (exit landscape fullscreen)
                        IconButton(
                            onClick = { showSerialMonitor = false },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                // Auto-scroll to bottom when new data arrives if enabled
                LaunchedEffect(serialData.size) {
                    if (serialData.isNotEmpty() && isSerialAutoScrollEnabled) {
                        delay(50) // Small delay to ensure layout
                        globalSerialListState.animateScrollToItem(serialData.size - 1)
                    }
                }
                
                // Reset when serial monitor opens
                LaunchedEffect(showSerialMonitor) {
                    if (showSerialMonitor) {
                        isSerialAutoScrollEnabled = true
                        showSerialAutoScrollButton = false
                    }
                }
                
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { _ ->
                                        // User touched the screen - disable auto-scroll immediately
                                        if (isSerialAutoScrollEnabled) {
                                            isSerialAutoScrollEnabled = false
                                            showSerialAutoScrollButton = true
                                        }
                                    }
                                )
                            },
                        reverseLayout = false,
                        state = globalSerialListState
                    ) {
                        items(serialData) { line ->
                            val annotatedText = buildAnnotatedString {
                                // Parse ANSI escape codes for colors
                                if (line.contains("\u001B[")) {
                                    val ansiPattern = "\u001B\\[[0-9;]*m".toRegex()
                                    var currentIndex = 0
                                    var currentColor = Color.Green // Default
                                    
                                    ansiPattern.findAll(line).forEach { match ->
                                        // Add text before escape code
                                        if (match.range.first > currentIndex) {
                                            val textPart = line.substring(currentIndex, match.range.first)
                                            withStyle(SpanStyle(color = currentColor)) {
                                                append(textPart)
                                            }
                                        }
                                        
                                        // Parse escape code
                                        val code = match.value
                                        currentColor = when {
                                            code.contains("31") -> Color.Red      // ESP32 error color
                                            code.contains("32") -> Color.Green    // Info color
                                            code.contains("33") -> Color.Yellow   // Warning color
                                            code.contains("34") -> Color.Cyan     // Debug color
                                            code.contains("0m") -> Color.Green    // Reset
                                            else -> currentColor
                                        }
                                        
                                        currentIndex = match.range.last + 1
                                    }
                                    
                                    // Add remaining text
                                    if (currentIndex < line.length) {
                                        val remaining = line.substring(currentIndex)
                                        withStyle(SpanStyle(color = currentColor)) {
                                            append(remaining)
                                        }
                                    }
                                    return@buildAnnotatedString
                                }
                                
                                // No ANSI codes, proceed with normal processing
                                // Check if line contains firmware version patterns
                                when {
                                    line.contains("HTTP REQUEST: Firmware version:", ignoreCase = true) -> {
                                        val firmwareIndex = line.indexOf("HTTP REQUEST: Firmware version:", ignoreCase = true)
                                        
                                        // Add text before "HTTP REQUEST: Firmware version:"
                                        if (firmwareIndex > 0) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(0, firmwareIndex))
                                            }
                                        }
                                        
                                        // Add "HTTP REQUEST: Firmware version:" in bold red
                                        withStyle(style = SpanStyle(
                                            color = Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )) {
                                            append("HTTP REQUEST: Firmware version:")
                                        }
                                        
                                        // Add text after "HTTP REQUEST: Firmware version:"
                                        val endIndex = firmwareIndex + "HTTP REQUEST: Firmware version:".length
                                        if (endIndex < line.length) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(endIndex))
                                            }
                                        }
                                    }
                                    line.contains("DeviceManager: currentFirmwareVersion:", ignoreCase = true) -> {
                                        val deviceIndex = line.indexOf("DeviceManager: currentFirmwareVersion:", ignoreCase = true)
                                        
                                        // Add text before the pattern
                                        if (deviceIndex > 0) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(0, deviceIndex))
                                            }
                                        }
                                        
                                        // Add "DeviceManager: currentFirmwareVersion:" in bold red
                                        withStyle(style = SpanStyle(
                                            color = Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )) {
                                            append("DeviceManager: currentFirmwareVersion:")
                                        }
                                        
                                        // Add text after the pattern
                                        val endIndex = deviceIndex + "DeviceManager: currentFirmwareVersion:".length
                                        if (endIndex < line.length) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(endIndex))
                                            }
                                        }
                                    }
                                    searchInSerial && activeSearchQuery.isNotEmpty() && line.contains(activeSearchQuery, ignoreCase = true) -> {
                                    // Handle search highlighting
                                    var currentIndex = 0
                                    var searchIndex = line.indexOf(activeSearchQuery, currentIndex, ignoreCase = true)
                                    
                                    while (searchIndex >= 0) {
                                        append(line.substring(currentIndex, searchIndex))
                                        withStyle(style = SpanStyle(background = Color(0xFF4CAF50), color = Color.Black)) {
                                            append(line.substring(searchIndex, searchIndex + activeSearchQuery.length))
                                        }
                                        currentIndex = searchIndex + activeSearchQuery.length
                                        searchIndex = line.indexOf(activeSearchQuery, currentIndex, ignoreCase = true)
                                    }
                                    append(line.substring(currentIndex))
                                    }
                                    else -> {
                                        append(line)
                                    }
                                }
                            }
                            
                            Text(
                                text = annotatedText,
                                color = Color.Green,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                    
                    // Floating auto-scroll button (bottom right)
                    if (showSerialAutoScrollButton) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch {
                                    // Re-enable auto-scroll
                                    isSerialAutoScrollEnabled = true
                                    showSerialAutoScrollButton = false
                                    
                                    // Scroll to bottom
                                    if (serialData.isNotEmpty()) {
                                        globalSerialListState.animateScrollToItem(serialData.size - 1)
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            containerColor = Color(0xFF146E89),
                            contentColor = Color.White
                        ) {
                            Icon(
                                painter = painterResource(android.R.drawable.ic_media_next), // Down arrow
                                contentDescription = "Auto-scroll to bottom",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        // Don't return here, continue to show dialogs
    } else {
    // Normal portrait layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Invisible spacer to balance the layout
                Box(modifier = Modifier.size(32.dp))

                Text(
                    text = "Pierre Flasher",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF146E89),
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { /* Normal click - do nothing */ },
                            onLongClick = {
                                if (isConnected && !isFlashing && !isErasing && !showSerialMonitor) {
                                    showEraseDialog = true
                                }
                            }
                        ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Status Monitor & Diagnostics button
                IconButton(
                    onClick = {
                        compatibilityResults = checkDeviceCompatibility()
                        deviceStatusInfo = getDeviceStatusInfo()
                        showStatusMonitor = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Device Status & Diagnostics",
                        tint = Color(0xFF146E89),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            // USB connection status indicator
            Text(
                text = if (isConnected) "✓ USB Connected" else "○ Waiting for USB device...",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) Color(0xFF4CAF50) else Color(0xFF888888),
                fontSize = 12.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Erase Flash gomb (smaller, same style as Production)
        val isEraseButtonEnabled = isConnected && !isFlashing && !isErasing && !showSerialMonitor && !isDownloading
        var eraseButtonClickable by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        !isEraseButtonEnabled -> Color(0xFF353338)  // Custom disabled color
                        isErasing -> Color(0xFFF44336)  // Darker red when erasing
                        else -> Color(0xFFF5F5F5)  // Light gray like other buttons
                    }
                )
                .then(
                    if (isEraseButtonEnabled && eraseButtonClickable) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(bounded = true)
                        ) {
                            eraseButtonClickable = false
                            showEraseDialog = true
                            // Re-enable after 1 second
                            scope.launch {
                                delay(1000)
                                eraseButtonClickable = true
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isErasing) "ERASING..." else "ERASE FLASH",
                fontSize = 14.sp,
                color = when {
                    !isEraseButtonEnabled -> Color(0xFF888888)  // Gray text when disabled
                    isErasing -> Color.White  // White text when erasing
                    else -> Color(0xFFFF0000)  // Bright red text normally
                },
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // PRODUCTION gomb with long press for custom firmware
        val isButtonEnabled = isConnected && !isFlashing && !showSerialMonitor && !isErasing && !isDownloading

        // Rapid click protection for flash button
        var flashButtonClickable by remember { mutableStateOf(true) }

        // Create a normal click handler
        val normalClickHandler = {
            if (flashButtonClickable) {
                flashButtonClickable = false
                scope.launch {
                    delay(1000) // Re-enable after 1 second for flash operations
                    flashButtonClickable = true
                }
                scope.launch {
                // Health check: verify connection is actually working
                if (isConnected) {
                    val isPortValid = withContext(Dispatchers.IO) {
                        try {
                            usbManager.port?.isOpen == true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    
                    if (!isPortValid) {
                        onLog("⚠️ Connection lost - attempting reconnection...")
                        isConnected = false
                        val reconnectSuccess = withContext(Dispatchers.IO) {
                            usbManager.openFirstPort()
                        }
                        isConnected = reconnectSuccess
                        if (!reconnectSuccess) {
                            return@launch
                        }
                    }
                }
                
                if (!isConnected) {
                    onLog("Please connect a device first!")
                    return@launch
                }
                
                // Check if we should flash custom or production
                if (customFirmwareFile != null && downloadStatus == "CUSTOM") {
                    // Flash custom firmware
                    isFlashing = true
                    
                    try {
                        onLog("Flashing custom firmware...")

                        // Send flash start statistics
                        statisticsManager.sendFlashStats("flash_start", "custom", mqttManager?.getCurrentTopic())

                        onLog("Initializing serial connection (like Serial Monitor)...")

                        // Use the exact same sequence as the AUTO button
                        val port = usbManager.port
                        if (port != null) {
                            // Initialize serial exactly like Serial Monitor
                            try {
                                port.setParameters(115200, 8,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                                onLog("Serial parameters set: 115200 baud, 8N1")
                            } catch (e: Exception) {
                                onLog("Warning: Could not set serial parameters: ${e.message}")
                            }

                            // Start continuous serial reading like Serial Monitor
                            var keepReading = true
                            val serialReaderJob = scope.launch {
                                withContext(Dispatchers.IO) {
                                    val buffer = ByteArray(16384) // Same size as Serial Monitor
                                    var flashModeDetected = false

                                    onLog("Starting continuous serial reading...")

                                    while (keepReading && !flashModeDetected) {
                                        try {
                                            // Same read timeout as Serial Monitor (300ms)
                                            val bytesRead = port.read(buffer, 300)
                                            if (bytesRead > 0) {
                                                val data = String(buffer, 0, bytesRead)

                                                // Log serial output
                                                if (data.isNotBlank()) {
                                                    val preview = data.take(50).replace("\n", "\\n").replace("\r", "\\r")
                                                    onLog("Serial: $preview...")
                                                }

                                                // Check for flash mode
                                                if (data.contains("waiting for download", ignoreCase = true) ||
                                                    data.contains("boot:0x7", ignoreCase = true) ||
                                                    data.contains("DOWNLOAD_BOOT", ignoreCase = true)) {
                                                    flashModeDetected = true
                                                    onLog("✅ Flash mode confirmed!")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Continue reading
                                        }
                                    }
                                }
                            }

                            // Wait for serial reading to establish
                            delay(500)

                            onLog("Entering ESP32 flash mode with AUTO sequence...")

                            // Clear any pending data first
                            try {
                                val trash = ByteArray(1024)
                                port.read(trash, 50)
                            } catch (e: Exception) {
                                // Ignore
                            }

                            // Classic esptool.py sequence
                            port.setDTR(false)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(true)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(false)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(false)
                            port.setRTS(true)
                            delay(100)

                            port.setDTR(true)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(false)
                            port.setRTS(false)

                            onLog("✓ AUTO sequence completed")
                            onLog("Checking for flash mode confirmation...")

                            // Wait for flash mode confirmation
                            delay(2000)

                            // Stop the serial reader job
                            keepReading = false
                            serialReaderJob.cancel()
                        } else {
                            onLog("Error: No serial port available")
                            isFlashing = false
                            return@launch
                        }

                        onLog("✓ Flash mode should be active")
                        
                        val flasher = UsbEspFlasher(
                            port = usbManager.port!!,
                            resources = context.resources,
                            context = context,
                            log = enhancedOnLog
                        )
                        
                        // Sync with ESP32
                        enhancedOnLog("Synchronizing with ESP32...")
                        val synced = withContext(Dispatchers.IO) {
                            flasher.syncRom()
                        }
                        
                        if (!synced) {
                            enhancedOnLog("❌ Failed to synchronize")
                            isFlashing = false
                            return@launch
                        }
                        
                        enhancedOnLog("✓ Synchronization successful")
                        enhancedOnLog("Starting custom firmware flash...")

                        // Start timing
                        val flashStartTime = System.currentTimeMillis()

                        // Flash custom firmware
                        val result = withContext(Dispatchers.IO) {
                            flasher.flashDownloadedProduction(customFirmwareFile!!)
                        }

                        enhancedOnLog(result)

                        if (result.contains("SUCCESSFUL")) {
                            // Calculate flash duration
                            val flashDuration = System.currentTimeMillis() - flashStartTime
                            val durationSeconds = flashDuration / 1000.0

                            enhancedOnLog("🎉 Custom firmware flashed successfully!")
                            enhancedOnLog("ESP32 has restarted and is running.")
                            enhancedOnLog("⏱️ Flash duration: ${String.format("%.1f", durationSeconds)} seconds")

                            // Send flash success statistics
                            statisticsManager.sendFlashStats("flash_success", "custom", mqttManager?.getCurrentTopic())
                            
                            // Reset serial to 115200 bps
                            try {
                                usbManager.port?.setParameters(115200, 8,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                                enhancedOnLog("Serial Monitor: 115200 bps")
                            } catch (e: Exception) {
                                enhancedOnLog("Baud rate reset error: ${e.message}")
                            }
                        }

                    } catch (e: Exception) {
                        enhancedOnLog("Error: ${e.message}")
                        // Send flash error statistics
                        statisticsManager.sendFlashStats("flash_error", "custom", mqttManager?.getCurrentTopic())
                    } finally {
                        isFlashing = false
                    }

                } else {
                    // Original production firmware flow
                    isDownloading = true
                    downloadStatus = "Downloading..."
                    onLog("Downloading latest production firmware...")
                    
                    val downloadSuccess = downloadProductionFirmware()
                    if (!downloadSuccess) {
                        downloadStatus = "Download Failed"
                        isDownloading = false
                        delay(2000)
                        downloadStatus = "PRODUCTION"
                        return@launch
                    }
                    
                    downloadStatus = "Success"
                    isDownloading = false
                    delay(1000)
                    
                    // Start flashing process
                    isFlashing = true
                    downloadStatus = "PRODUCTION"

                    // Send flash start statistics
                    statisticsManager.sendFlashStats("flash_start", "production", mqttManager?.getCurrentTopic())

                    try {
                        onLog("Initializing serial connection (like Serial Monitor)...")

                        // Use the exact same sequence as the AUTO button
                        val port = usbManager.port
                        if (port != null) {
                            // First, initialize serial exactly like Serial Monitor does
                            try {
                                port.setParameters(115200, 8,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                                onLog("Serial parameters set: 115200 baud, 8N1")
                            } catch (e: Exception) {
                                onLog("Warning: Could not set serial parameters: ${e.message}")
                            }

                            // Start continuous serial reading like Serial Monitor
                            var keepReading = true
                            val serialReaderJob = scope.launch {
                                withContext(Dispatchers.IO) {
                                    val buffer = ByteArray(16384) // Same buffer size as Serial Monitor
                                    var flashModeDetected = false

                                    onLog("Starting continuous serial reading...")

                                    while (keepReading && !flashModeDetected) {
                                        try {
                                            // Same read timeout as Serial Monitor (300ms)
                                            val bytesRead = port.read(buffer, 300)
                                            if (bytesRead > 0) {
                                                val data = String(buffer, 0, bytesRead)

                                                // Log serial output to help debug
                                                if (data.isNotBlank()) {
                                                    val preview = data.take(50).replace("\n", "\\n").replace("\r", "\\r")
                                                    onLog("Serial: $preview...")
                                                }

                                                // Check for flash mode confirmation
                                                if (data.contains("waiting for download", ignoreCase = true) ||
                                                    data.contains("boot:0x7", ignoreCase = true) ||
                                                    data.contains("DOWNLOAD_BOOT", ignoreCase = true)) {
                                                    flashModeDetected = true
                                                    onLog("✅ Flash mode confirmed!")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Continue reading even on errors
                                        }
                                    }
                                }
                            }

                            // Wait for serial reading to establish
                            delay(500)

                            onLog("Entering ESP32 flash mode with AUTO sequence...")

                            // Clear any pending data first
                            try {
                                val trash = ByteArray(1024)
                                port.read(trash, 50)
                            } catch (e: Exception) {
                                // Ignore
                            }

                            // Classic esptool.py sequence that works with AUTO button
                            port.setDTR(false)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(true)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(false)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(false)
                            port.setRTS(true)
                            delay(100)

                            port.setDTR(true)
                            port.setRTS(false)
                            delay(100)

                            port.setDTR(false)
                            port.setRTS(false)

                            onLog("✓ AUTO sequence completed")
                            onLog("Checking for flash mode confirmation...")

                            // Wait up to 2 seconds for flash mode confirmation
                            delay(2000)

                            // Stop the serial reader job
                            keepReading = false
                            serialReaderJob.cancel()
                        } else {
                            onLog("Error: No serial port available")
                            isFlashing = false
                            return@launch
                        }

                        onLog("✓ Flash mode should be active")
                        
                        val flasher = UsbEspFlasher(
                            port = usbManager.port!!,
                            resources = context.resources,
                            context = context,
                            log = enhancedOnLog
                        )
                        
                        enhancedOnLog("Synchronizing with ESP32...")
                        val synced = withContext(Dispatchers.IO) {
                            flasher.syncRom()
                        }
                        
                        if (!synced) {
                            enhancedOnLog("❌ Failed to synchronize")
                            isFlashing = false
                            return@launch
                        }
                        
                        enhancedOnLog("✓ Synchronization successful")
                        enhancedOnLog("Starting flash process...")

                        // Start timing
                        val flashStartTime = System.currentTimeMillis()

                        val result = withContext(Dispatchers.IO) {
                            flasher.flashDownloadedProduction(downloadedProductionFile!!)
                        }

                        enhancedOnLog(result)

                        if (result.contains("SUCCESSFUL")) {
                            // Calculate flash duration
                            val flashDuration = System.currentTimeMillis() - flashStartTime
                            val durationSeconds = flashDuration / 1000.0

                            enhancedOnLog("🎉 Flash process completed!")
                            enhancedOnLog("ESP32 has restarted and is running.")
                            enhancedOnLog("⏱️ Flash duration: ${String.format("%.1f", durationSeconds)} seconds")

                            // Send flash success statistics
                            statisticsManager.sendFlashStats("flash_success", "production", mqttManager?.getCurrentTopic())
                            
                            try {
                                usbManager.port?.setParameters(115200, 8,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                                    com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                                enhancedOnLog("Serial Monitor: 115200 bps")
                            } catch (e: Exception) {
                                enhancedOnLog("Baud rate reset error: ${e.message}")
                            }
                        }
                        
                    } catch (e: Exception) {
                        enhancedOnLog("Error: ${e.message}")
                        // Send flash error statistics
                        statisticsManager.sendFlashStats("flash_error", "production", mqttManager?.getCurrentTopic())
                    } finally {
                        isFlashing = false
                    }
                }
            }
            }
        }
        
        // PRODUCTION button with custom touch handling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        !isButtonEnabled -> Color(0xFF353338)  // Custom disabled color
                        isFlashing -> Color(0xFF4CAF50)
                        isDownloading -> Color(0xFF2196F3)
                        downloadStatus == "Success" -> Color(0xFF4CAF50)
                        downloadStatus == "Download Failed" -> Color(0xFFF44336)
                        downloadStatus == "CUSTOM" -> Color(0xFF2196F3)  // Same blue as downloading
                        else -> Color(0xFFF5F5F5)
                    }
                )
                .then(
                    if (isButtonEnabled) {
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = rememberRipple(bounded = true),
                                onClick = { /* handled by pointerInput */ }
                            )
                            .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    // Normal tap - execute immediately
                                    normalClickHandler()
                                },
                                onLongPress = {
                                    // This is Android's default long press (~500ms)
                                    // We don't use this, we use our custom 5 second detection
                                },
                                onPress = { offset ->
                                    // Track press start
                                    val pressTime = System.currentTimeMillis()
                                    isPressing = true
                                    
                                    // Launch timer for 5 seconds
                                    val timerJob = scope.launch {
                                        delay(5000)
                                        if (isPressing) {
                                            // Still pressing after 5 seconds
                                            filePickerLauncher.launch("application/octet-stream")
                                            isPressing = false
                                        }
                                    }
                                    
                                    // Wait for release
                                    tryAwaitRelease()
                                    
                                    // Released - cancel timer
                                    isPressing = false
                                    timerJob.cancel()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
                when {
                    isFlashing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    }
                    isDownloading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = downloadStatus,
                                fontSize = 16.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> {
                        if (downloadStatus == "CUSTOM") {
                            // CUSTOM mode with reset X button on the right
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                // Centered text
                                Text(
                                    text = downloadStatus,
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                // X button on the right side
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 16.dp)
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.2f))
                                        .clickable {
                                            // Reset to PRODUCTION mode
                                            customFirmwareFile = null
                                            downloadStatus = "PRODUCTION"
                                            onLog("✓ Reset to Production firmware mode")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Reset to Production",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else {
                            // Normal text for other states
                            Text(
                                text = downloadStatus,
                                fontSize = 18.sp,
                                color = when {
                                    !isButtonEnabled -> Color(0xFF156E89)  // Custom blue text on dark background
                                    downloadStatus == "Success" -> Color.White
                                    downloadStatus == "Download Failed" -> Color.White
                                    else -> Color(0xFF146E89)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // Serial Monitor gomb
        var serialMonitorButtonClickable by remember { mutableStateOf(true) }
        Button(
            onClick = {
                if (!serialMonitorButtonClickable) return@Button
                serialMonitorButtonClickable = false
                // Re-enable after 500ms
                scope.launch {
                    delay(500)
                    serialMonitorButtonClickable = true
                }
                if (showSerialMonitor) {
                    showSerialMonitor = false
                    isSerialMonitorRunning = false
                } else {
                    showSerialMonitor = true
                    // Reset auto-scroll states when opening Serial Monitor
                    isSerialAutoScrollEnabled = true
                    showSerialAutoScrollButton = false
                    
                    if (isConnected && !isFlashing) {
                        // Always set to 115200 when opening Serial Monitor
                        try {
                            usbManager.port?.setParameters(115200, 8, 
                                com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1, 
                                com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                        } catch (e: Exception) {
                            onLog("Baud rate setting error: ${e.message}")
                        }
                        
                        isSerialMonitorRunning = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                var errorCount = 0
                                var lastErrorTime = 0L

                                while (isSerialMonitorRunning && showSerialMonitor) {
                                    try {
                                        val port = usbManager.port
                                        if (port != null && port.isOpen) {
                                            val buffer = ByteArray(16384)
                                            // Increased timeout to 300ms for better stability
                                            // but still fast enough to not miss messages
                                            val bytesRead = port.read(buffer, 300)

                                            if (bytesRead > 0) {
                                                // Reset error count on successful read
                                                errorCount = 0
                                                val data = String(buffer, 0, bytesRead)
                                                data.split('\n').forEach { line ->
                                                    if (line.isNotBlank()) {
                                                        serialData.add(line)
                                                        // Send serial data to MQTT for live sharing
                                                        val mainActivity = context as? MainActivity
                                                        mainActivity?.mqttManager?.publishLog(line)
                                                    }
                                                }
                                            }
                                        } else {
                                            // Port is null or closed, try to reconnect
                                            withContext(Dispatchers.Main) {
                                                if (isConnected) {
                                                    onLog("⚠️ Serial port lost, attempting reconnect...")
                                                    isConnected = false
                                                }
                                            }
                                            delay(1000) // Wait before retry
                                            val reconnected = usbManager.openFirstPort()
                                            withContext(Dispatchers.Main) {
                                                isConnected = reconnected
                                                if (!reconnected) {
                                                    showSerialMonitor = false
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Handle errors with exponential backoff
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastErrorTime > 5000) {
                                            errorCount = 0 // Reset error count after 5 seconds
                                        }
                                        errorCount++
                                        lastErrorTime = currentTime

                                        if (errorCount > 10) {
                                            // Too many errors, likely disconnected
                                            withContext(Dispatchers.Main) {
                                                onLog("Serial read errors, checking connection...")
                                                // Check if really disconnected
                                                val isPortValid = try {
                                                    usbManager.port?.isOpen == true
                                                } catch (e: Exception) {
                                                    false
                                                }
                                                if (!isPortValid) {
                                                    isConnected = false
                                                    showSerialMonitor = false
                                                }
                                            }
                                            errorCount = 0
                                            delay(1000)
                                        }
                                    }
                                    // Adaptive delay based on errors
                                    val delayMs = if (errorCount > 5) {
                                        100L // Slow down if errors
                                    } else if (errorCount > 0) {
                                        50L  // Slight slowdown
                                    } else {
                                        20L  // Normal operation - increased from 5ms to 20ms
                                    }
                                    delay(delayMs)
                                }
                            }
                        }
                    }
                }
            },
            enabled = isConnected && !isFlashing && !isErasing && !isDownloading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (showSerialMonitor) Color(0xFF4CAF50) else Color(0xFFF5F5F5)
            )
        ) {
            Text(
                text = if (showSerialMonitor) "CLOSE SERIAL MONITOR" else "SERIAL MONITOR",
                color = if (showSerialMonitor) Color.White else Color(0xFF146E89),
                fontWeight = FontWeight.Bold
            )
        }
        
        // Serial Monitor ablak - teljes méretben amikor nyitva van
        if (showSerialMonitor) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) {
                            Modifier.heightIn(min = 300.dp, max = 500.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black
                ),
                border = BorderStroke(1.dp, Color.Gray)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Serial monitor gombok
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Serial",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        
                        Row {
                        // Question mark icon for info dialog
                        IconButton(
                            onClick = {
                                showInfoDialog = true
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Help,
                                contentDescription = "Info",
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        IconButton(
                            onClick = {
                                searchInSerial = true
                                searchQuery = activeSearchQuery  // Használjuk az előző keresést
                                showSearchDialog = true
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = if (searchInSerial && activeSearchQuery.isNotEmpty()) Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        if (searchInSerial && activeSearchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    activeSearchQuery = ""
                                    searchQuery = ""
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        
                        var copyButtonEnabled2 by remember { mutableStateOf(true) }
                        IconButton(
                            onClick = {
                                if (copyButtonEnabled2) {
                                    copyButtonEnabled2 = false
                                    try {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val serialText = serialData.joinToString("\n")
                                        val clip = ClipData.newPlainText("Serial Monitor", serialText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Serial data copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        // Handle clipboard error
                                    }
                                    // Re-enable after 500ms
                                    scope.launch {
                                        delay(500)
                                        copyButtonEnabled2 = true
                                    }
                                }
                            },
                            enabled = copyButtonEnabled2,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                shareDialogType = "serial"
                                showShareDialog = true
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        var clearButtonEnabled2 by remember { mutableStateOf(true) }
                        IconButton(
                            onClick = {
                                if (clearButtonEnabled2) {
                                    clearButtonEnabled2 = false
                                    try {
                                        serialData.clear()
                                        onLog("Serial monitor cleared")
                                        // Reset search when clearing
                                        if (searchInSerial) {
                                            activeSearchQuery = ""
                                            searchQuery = ""
                                        }
                                    } catch (e: Exception) {
                                        // Handle clear error
                                    }
                                    // Re-enable after 500ms
                                    scope.launch {
                                        delay(500)
                                        clearButtonEnabled2 = true
                                    }
                                }
                            },
                            enabled = clearButtonEnabled2,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        }
                    }
                    
                    // Use global serial list state for consistent search functionality
                    
                    // Auto-scroll to bottom when new data arrives if enabled
                    LaunchedEffect(serialData.size) {
                        if (serialData.isNotEmpty() && isSerialAutoScrollEnabled) {
                            delay(50) // Small delay to ensure layout
                            globalSerialListState.animateScrollToItem(serialData.size - 1)
                        }
                    }
                    
                    // Reset when serial monitor opens
                    LaunchedEffect(showSerialMonitor) {
                        if (showSerialMonitor) {
                            isSerialAutoScrollEnabled = true
                            showSerialAutoScrollButton = false
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = { _ ->
                                            // User touched the screen - disable auto-scroll immediately
                                            if (isSerialAutoScrollEnabled) {
                                                isSerialAutoScrollEnabled = false
                                                showSerialAutoScrollButton = true
                                            }
                                        }
                                    )
                                },
                            reverseLayout = false,
                            state = globalSerialListState
                        ) {
                        items(serialData) { line ->
                            val annotatedText = buildAnnotatedString {
                                // Parse ANSI escape codes for colors
                                if (line.contains("\u001B[")) {
                                    val ansiPattern = "\u001B\\[[0-9;]*m".toRegex()
                                    var currentIndex = 0
                                    var currentColor = Color.Green // Default
                                    
                                    ansiPattern.findAll(line).forEach { match ->
                                        // Add text before escape code
                                        if (match.range.first > currentIndex) {
                                            val textPart = line.substring(currentIndex, match.range.first)
                                            withStyle(SpanStyle(color = currentColor)) {
                                                append(textPart)
                                            }
                                        }
                                        
                                        // Parse escape code
                                        val code = match.value
                                        currentColor = when {
                                            code.contains("31") -> Color.Red      // ESP32 error color
                                            code.contains("32") -> Color.Green    // Info color
                                            code.contains("33") -> Color.Yellow   // Warning color
                                            code.contains("34") -> Color.Cyan     // Debug color
                                            code.contains("0m") -> Color.Green    // Reset
                                            else -> currentColor
                                        }
                                        
                                        currentIndex = match.range.last + 1
                                    }
                                    
                                    // Add remaining text
                                    if (currentIndex < line.length) {
                                        val remaining = line.substring(currentIndex)
                                        withStyle(SpanStyle(color = currentColor)) {
                                            append(remaining)
                                        }
                                    }
                                    return@buildAnnotatedString
                                }
                                
                                // No ANSI codes, proceed with normal processing
                                // Check if line contains firmware version patterns
                                when {
                                    line.contains("HTTP REQUEST: Firmware version:", ignoreCase = true) -> {
                                        val firmwareIndex = line.indexOf("HTTP REQUEST: Firmware version:", ignoreCase = true)
                                        
                                        // Add text before "HTTP REQUEST: Firmware version:"
                                        if (firmwareIndex > 0) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(0, firmwareIndex))
                                            }
                                        }
                                        
                                        // Add "HTTP REQUEST: Firmware version:" in bold red
                                        withStyle(style = SpanStyle(
                                            color = Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )) {
                                            append("HTTP REQUEST: Firmware version:")
                                        }
                                        
                                        // Add text after "HTTP REQUEST: Firmware version:"
                                        val endIndex = firmwareIndex + "HTTP REQUEST: Firmware version:".length
                                        if (endIndex < line.length) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(endIndex))
                                            }
                                        }
                                    }
                                    line.contains("DeviceManager: currentFirmwareVersion:", ignoreCase = true) -> {
                                        val deviceIndex = line.indexOf("DeviceManager: currentFirmwareVersion:", ignoreCase = true)
                                        
                                        // Add text before the pattern
                                        if (deviceIndex > 0) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(0, deviceIndex))
                                            }
                                        }
                                        
                                        // Add "DeviceManager: currentFirmwareVersion:" in bold red
                                        withStyle(style = SpanStyle(
                                            color = Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )) {
                                            append("DeviceManager: currentFirmwareVersion:")
                                        }
                                        
                                        // Add text after the pattern
                                        val endIndex = deviceIndex + "DeviceManager: currentFirmwareVersion:".length
                                        if (endIndex < line.length) {
                                            withStyle(style = SpanStyle(color = Color.Green)) {
                                                append(line.substring(endIndex))
                                            }
                                        }
                                    }
                                    searchInSerial && activeSearchQuery.isNotEmpty() && line.contains(activeSearchQuery, ignoreCase = true) -> {
                                    // Handle search highlighting
                                    var currentIndex = 0
                                    var searchIndex = line.indexOf(activeSearchQuery, currentIndex, ignoreCase = true)
                                    
                                    while (searchIndex >= 0) {
                                        append(line.substring(currentIndex, searchIndex))
                                        withStyle(style = SpanStyle(background = Color(0xFF4CAF50), color = Color.Black)) {
                                            append(line.substring(searchIndex, searchIndex + activeSearchQuery.length))
                                        }
                                        currentIndex = searchIndex + activeSearchQuery.length
                                        searchIndex = line.indexOf(activeSearchQuery, currentIndex, ignoreCase = true)
                                    }
                                    append(line.substring(currentIndex))
                                    }
                                    else -> {
                                        append(line)
                                    }
                                }
                            }
                            
                            Text(
                                text = annotatedText,
                                color = Color.Green,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                        
                        // Floating auto-scroll button (bottom right)
                        if (showSerialAutoScrollButton) {
                            FloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        // Re-enable auto-scroll
                                        isSerialAutoScrollEnabled = true
                                        showSerialAutoScrollButton = false
                                        
                                        // Scroll to bottom
                                        if (serialData.isNotEmpty()) {
                                            globalSerialListState.animateScrollToItem(serialData.size - 1)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = Color(0xFF146E89),
                                contentColor = Color.White
                            ) {
                                Icon(
                                    painter = painterResource(android.R.drawable.ic_media_next), // Down arrow
                                    contentDescription = "Auto-scroll to bottom",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Napló rész - csak akkor látszik ha a Serial Monitor nincs megnyitva
        if (!showSerialMonitor) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) {
                            Modifier.heightIn(min = 300.dp, max = 500.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black
                ),
                border = BorderStroke(
                    width = 2.dp, 
                    color = if (showSuccessBorder) Color(0xFF4CAF50) else Color.Gray
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Napló gombok
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Log",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        
                        Row {
                            IconButton(
                                onClick = {
                                    searchInSerial = false
                                    searchQuery = activeSearchQuery  // Használjuk az előző keresést
                                    showSearchDialog = true
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = if (!searchInSerial && activeSearchQuery.isNotEmpty()) Color(0xFF4CAF50) else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            
                            if (!searchInSerial && activeSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        activeSearchQuery = ""
                                        searchQuery = ""
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = Color(0xFFFF6B6B),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            var logCopyButtonEnabled by remember { mutableStateOf(true) }
                            IconButton(
                                onClick = {
                                    if (logCopyButtonEnabled) {
                                        logCopyButtonEnabled = false
                                        try {
                                            (context as MainActivity).copyLogsToClipboard()
                                        } catch (e: Exception) {
                                            // Handle clipboard error
                                        }
                                        // Re-enable after 500ms
                                        scope.launch {
                                            delay(500)
                                            logCopyButtonEnabled = true
                                        }
                                    }
                                },
                                enabled = logCopyButtonEnabled,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    shareDialogType = "logs"
                                    showShareDialog = true
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            
                            var logClearButtonEnabled by remember { mutableStateOf(true) }
                            IconButton(
                                onClick = {
                                    if (logClearButtonEnabled) {
                                        logClearButtonEnabled = false
                                        try {
                                            (logs as? androidx.compose.runtime.snapshots.SnapshotStateList)?.clear()
                                            onLog("Log cleared")
                                            // Reset search when clearing
                                            if (!searchInSerial) {
                                                activeSearchQuery = ""
                                                searchQuery = ""
                                            }
                                        } catch (e: Exception) {
                                            // Handle clear error
                                        }
                                        // Re-enable after 500ms
                                        scope.launch {
                                            delay(500)
                                            logClearButtonEnabled = true
                                        }
                                    }
                                },
                                enabled = logClearButtonEnabled,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear",
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        state = listState
                    ) {
                        items(logs) { log ->
                            val annotatedText = if (!searchInSerial && activeSearchQuery.isNotEmpty() && log.contains(activeSearchQuery, ignoreCase = true)) {
                                buildAnnotatedString {
                                    var currentIndex = 0
                                    var searchIndex = log.indexOf(activeSearchQuery, currentIndex, ignoreCase = true)
                                    
                                    while (searchIndex >= 0) {
                                        append(log.substring(currentIndex, searchIndex))
                                        withStyle(style = SpanStyle(background = Color(0xFF4CAF50), color = Color.Black)) {
                                            append(log.substring(searchIndex, searchIndex + activeSearchQuery.length))
                                        }
                                        currentIndex = searchIndex + activeSearchQuery.length
                                        searchIndex = log.indexOf(activeSearchQuery, currentIndex, ignoreCase = true)
                                    }
                                    append(log.substring(currentIndex))
                                }
                            } else {
                                buildAnnotatedString { append(log) }
                            }
                            
                            Text(
                                text = annotatedText,
                                color = Color(0xFF00FF00),  // Zöld szöveg mint a serial monitornál
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                modifier = when {
                                    log.contains("HiveMQ Cloud connected") -> {
                                        Modifier.clickable {
                                            // Copy MQTT share URL to clipboard
                                            val shareUrl = mqttShareUrl.value
                                            if (shareUrl != null) {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("Live Serial Share", shareUrl)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Live share link copied!", Toast.LENGTH_SHORT).show()
                                                onLog("Share URL copied: $shareUrl")
                                            }
                                        }
                                    }
                                    log.contains("http://") -> {
                                        Modifier.clickable {
                                            // Extract URL from log
                                            val urlPattern = "http://[^\\s]+".toRegex()
                                            val url = urlPattern.find(log)?.value
                                            if (url != null) {
                                                // Copy to clipboard
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("Web Server URL", url)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "URL copied to clipboard!", Toast.LENGTH_SHORT).show()
                                                onLog("URL copied to clipboard!")
                                            }
                                        }
                                    }
                                    else -> Modifier
                                }
                            )
                        }
                    }
                }
            }
        }  // Napló if vége
    }  // Portrait Column vége
    }  // else (portrait mode) vége
    
    // Dialogs - these should always be on top, regardless of orientation
    // Search Dialog
    if (showSearchDialog) {
        // Disable auto-scroll while searching
        LaunchedEffect(showSearchDialog) {
            if (showSearchDialog && isSerialAutoScrollEnabled) {
                isSerialAutoScrollEnabled = false
                showSerialAutoScrollButton = true
            }
        }
        
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { 
                Text(
                    text = if (searchInSerial) "Search Serial Monitor" else "Search Log",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    modifier = Modifier.wrapContentHeight()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search text") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default
                    )
                    
                    // Only add content below if there's a search query
                    if (searchQuery.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Találatok megjelenítése
                        val dataToSearch = if (searchInSerial) serialData else logs
                        val searchResults = dataToSearch.filter { line ->
                            line.contains(searchQuery, ignoreCase = true)
                        }
                        
                        Text(
                            text = "Results: ${searchResults.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                        
                        if (searchResults.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Only show the card when there are actual results
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF1A1A1A)
                                )
                            ) {
                                LazyColumn(
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    items(searchResults.take(10)) { result ->
                                        Text(
                                            text = result,
                                            color = Color(0xFF00FF00),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                    
                                    if (searchResults.size > 10) {
                                        item {
                                            Text(
                                                text = "... and ${searchResults.size - 10} more results",
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { 
                        // Mentjük az aktív keresést és ugrunk az első találathoz
                        activeSearchQuery = searchQuery
                        
                        if (searchQuery.isNotEmpty()) {
                            val dataToSearch = if (searchInSerial) serialData else logs
                            val firstMatchIndex = dataToSearch.indexOfFirst { line ->
                                line.contains(searchQuery, ignoreCase = true)
                            }
                            
                            if (firstMatchIndex >= 0) {
                                scope.launch {
                                    if (searchInSerial) {
                                        // Serial list state-hez ugrás
                                        globalSerialListState.animateScrollToItem(firstMatchIndex)
                                    } else {
                                        // Log list state-hez ugrás
                                        listState.animateScrollToItem(firstMatchIndex)
                                    }
                                }
                            }
                        }
                        showSearchDialog = false
                    }
                ) {
                    Text("DONE")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { 
                            showSearchDialog = false
                        }
                    ) {
                        Text("CANCEL")
                    }
                    TextButton(
                        onClick = { 
                            // Clear search but keep dialog open
                            searchQuery = ""
                            activeSearchQuery = ""
                        },
                        enabled = searchQuery.isNotEmpty() || activeSearchQuery.isNotEmpty()
                    ) {
                        Text("CLEAR")
                    }
                }
            }
        )
    }
    
    // Share Dialog - choose between logs or web link
    if (showShareDialog) {
        // Don't block USB operations - let serial monitor continue
        // This prevents USB disconnection

        AlertDialog(
            onDismissRequest = {
                showShareDialog = false
                // USB flag is automatically reset by DisposableEffect
            },
            containerColor = Color(0xFF2A2A2A), // Dark background for dialog
            title = {
                Text(
                    text = "Share Options",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White // White text
                )
            },
            text = null,
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Download Logs button - Modern flat design
                    Button(
                        onClick = {
                            // Capture data before closing dialog
                            val dataToDownload = if (shareDialogType == "serial") {
                                serialData.toList()
                            } else {
                                logs.toList()
                            }
                            val downloadType = shareDialogType

                            // Close dialog
                            showShareDialog = false

                            // Download to Downloads folder
                            scope.launch {
                                delay(100) // Small delay for dialog to close

                                if (dataToDownload.isEmpty()) {
                                    Toast.makeText(context, "No data to download", Toast.LENGTH_SHORT).show()
                                } else {
                                    try {
                                        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                                        val fileName = if (downloadType == "serial") "serial_$timestamp.txt" else "log_$timestamp.txt"
                                        val logsText = dataToDownload.joinToString("\n")

                                        // Save to Downloads folder
                                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        val file = File(downloadsDir, fileName)
                                        file.writeText(logsText)

                                        Toast.makeText(context, "Downloaded: $fileName", Toast.LENGTH_LONG).show()
                                        onLog("Downloaded to: Downloads/$fileName")
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        onLog("Download error: ${e.message}")
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A3A3A), // Dark gray background
                            contentColor = Color.White // White text
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Download",
                                modifier = Modifier.size(22.dp),
                                tint = Color(0xFF4CAF50) // Green icon
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Download Logs",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }

                    // Share Logs button - Modern flat design
                    Button(
                        onClick = {
                            // Capture data before closing dialog
                            val dataToShare = if (shareDialogType == "serial") {
                                serialData.toList() // Make a copy
                            } else {
                                logs.toList()
                            }
                            val shareType = shareDialogType

                            // Close dialog
                            showShareDialog = false

                            // Share without stopping serial monitor
                            scope.launch {
                                // Small delay for dialog to close
                                delay(300)

                                if (dataToShare.isEmpty()) {
                                    Toast.makeText(context, "No data to share", Toast.LENGTH_SHORT).show()
                                } else {
                                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                                    val logsText = dataToShare.joinToString("\n")

                                    // Save to file
                                    val fileName = if (shareType == "serial") "serial_$timestamp.txt" else "log_$timestamp.txt"
                                    val file = File(context.cacheDir, fileName)
                                    file.writeText(logsText)

                                    // Create URI
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.applicationContext.packageName}.fileprovider",
                                        file
                                    )

                                    // Share intent - DON'T stop serial monitor
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, if (shareType == "serial") "Serial Monitor - $timestamp" else "Pierre Flasher Log - $timestamp")
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }

                                    try {
                                        // Launch in new task to minimize disruption
                                        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(shareIntent)
                                        onLog("Sharing logs...")
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "No app found to share files", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A3A3A), // Dark gray background
                            contentColor = Color.White // White text
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(22.dp),
                                tint = Color(0xFF2196F3) // Blue icon
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Share Logs",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }

                    // Share Web Link button - Modern flat design with accent
                    Button(
                        onClick = {
                            // Get URL before closing dialog
                            val shareUrl = mqttShareUrl.value

                            // Close dialog
                            showShareDialog = false

                            if (shareUrl != null) {
                                // Share without stopping serial monitor
                                scope.launch {
                                    // Small delay for dialog to close
                                    delay(300)

                                    // Create share intent
                                    val shareText = "Pierre Live Serial Monitor\n\nView live at:\n$shareUrl\n\nShare this link to allow remote monitoring!"
                                    val shareIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                        putExtra(Intent.EXTRA_SUBJECT, "Pierre Live Serial Monitor")
                                        // Launch in new task
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }

                                    try {
                                        context.startActivity(shareIntent)
                                        onLog("Sharing web link...")
                                    } catch (e: Exception) {
                                        // Fallback to clipboard
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Live Serial Share", shareText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Link copied to clipboard (no share app found)", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "MQTT not connected. Please wait...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF146E89), // Primary blue color
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Web Link",
                                modifier = Modifier.size(22.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Share Web Link",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showShareDialog = false
                        // USB flag is automatically reset by DisposableEffect
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // What's New Dialog - shows after app update
    if (showWhatsNewDialog) {
        AlertDialog(
            onDismissRequest = { showWhatsNewDialog = false },
            title = { 
                Text(
                    text = "Updated Features",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF146E89)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Welcome to Pierre Flasher ${MainActivity.getAppVersion(context)}!",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "Latest improvements:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeatureItem("Expanded device compatibility")
                        FeatureItem("MQTT auto-reconnection stability")
                        FeatureItem("Web viewer connection improved")
                        FeatureItem("Pierre Flasher website favicon")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showWhatsNewDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF146E89)
                    )
                ) {
                    Text("Continue", color = Color.White)
                }
            }
        )
    }
    
    // Info Dialog - shows parsed data from Serial Monitor
    if (showInfoDialog) {
        // Parse firmware version from serial data - always get the latest
        val firmwareVersion = serialData.findLast { line ->
            line.contains("HTTP REQUEST: Firmware version:", ignoreCase = true) || 
            line.contains("DeviceManager: currentFirmwareVersion:", ignoreCase = true)
        }?.let { line ->
            // Extract version number after "Firmware version:" or "currentFirmwareVersion:"
            val pattern = "(Firmware version:|currentFirmwareVersion:)\\s*([\\d.]+)".toRegex(RegexOption.IGNORE_CASE)
            pattern.find(line)?.groupValues?.getOrNull(2) ?: ""
        } ?: ""
        
        // Detect sensor errors using loaded patterns
        val sensorErrors = mutableListOf<Pair<String, String>>()
        
        // Load error patterns from JSON
        val patterns = try {
            val jsonString = context.assets.open("error_detection.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val patternsArray = jsonObject.getJSONArray("error_patterns")
            
            val loadedPatterns = mutableListOf<ErrorPattern>()
            for (i in 0 until patternsArray.length()) {
                val pattern = patternsArray.getJSONObject(i)
                val patternsList = mutableListOf<String>()
                
                // Check if it has "patterns" array or single "pattern" string (for backward compatibility)
                if (pattern.has("patterns")) {
                    val patternsJsonArray = pattern.getJSONArray("patterns")
                    for (j in 0 until patternsJsonArray.length()) {
                        patternsList.add(patternsJsonArray.getString(j))
                    }
                } else if (pattern.has("pattern")) {
                    patternsList.add(pattern.getString("pattern"))
                }
                
                loadedPatterns.add(
                    ErrorPattern(
                        id = pattern.getString("id"),
                        patterns = patternsList,
                        displayName = pattern.getString("display_name"),
                        errorMessage = pattern.getString("error_message"),
                        enabled = pattern.getBoolean("enabled")
                    )
                )
            }
            loadedPatterns
        } catch (e: Exception) {
            listOf<ErrorPattern>()
        }
        
        patterns.filter { it.enabled }.forEach { errorPattern ->
            // Check if any of the patterns match
            val hasError = errorPattern.patterns.any { pattern ->
                serialData.any { line -> line.contains(pattern, ignoreCase = true) }
            }
            if (hasError) {
                sensorErrors.add(errorPattern.displayName to errorPattern.errorMessage)
            }
        }
        
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { 
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Device Information",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    IconButton(
                        onClick = {
                            serialData.clear()
                            onLog("Serial monitor cleared")
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear",
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Firmware version section
                    if (firmwareVersion.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF2A2A2A)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF4CAF50))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Success",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 6.dp)
                                )
                                Text(
                                    text = "Firmware: ",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = firmwareVersion,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    
                    // Sensor errors section
                    if (sensorErrors.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF3A2020)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFFF6B6B))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = Color(0xFFFF6B6B),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .padding(end = 6.dp)
                                    )
                                    Text(
                                        text = "Sensor Issues Detected",
                                        color = Color(0xFFFF6B6B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                
                                sensorErrors.forEach { (sensor, message) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, top = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "• ",
                                            color = Color(0xFFFF6B6B),
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            text = "$sensor: ",
                                            color = Color(0xFFFFAAAA),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            text = message,
                                            color = Color(0xFFCCCCCC),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Show message if no data at all
                    if (firmwareVersion.isEmpty() && sensorErrors.isEmpty()) {
                        Text(
                            text = "No data available yet.\nConnect to device and open Serial Monitor.",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showInfoDialog = false }
                ) {
                    Text("OK")
                }
            }
        )
    }


    // Bug Report Dialog (triggered by shake)
    if (showBugReportDialog.value) {
        // Shake animation for the dialog - shakes for 1 second then stops
        var isShaking by remember { mutableStateOf(true) }
        
        LaunchedEffect(Unit) {
            delay(1000) // Shake for 1 second
            isShaking = false
        }
        
        // Shake animation using horizontal offset
        val offsetX by animateFloatAsState(
            targetValue = if (isShaking) 0f else 0f,
            animationSpec = if (isShaking) {
                infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 100
                        0f at 0
                        -5f at 25
                        5f at 75
                        0f at 100
                    },
                    repeatMode = RepeatMode.Restart
                )
            } else {
                tween(100)
            }
        )
        
        AlertDialog(
            onDismissRequest = { showBugReportDialog.value = false },
            modifier = Modifier.offset(x = offsetX.dp),
            icon = {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Bug Report",
                    tint = Color(0xFF146E89),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { 
                Text(
                    "Report an Issue",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF146E89)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Shake detected! Would you like to report a bug or provide feedback?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        "Your feedback helps us improve Pierre Flasher.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // App version info
                    Text(
                        "Your app version: ${MainActivity.getAppVersion(context)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBugReportDialog.value = false
                        // Prepare bug report with logs
                        val logsText = logs.takeLast(100).joinToString("\n")
                        val deviceInfo = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                                "Android: ${android.os.Build.VERSION.RELEASE}\n" +
                                "App Version: Pierre Flasher ${MainActivity.getAppVersion(context)}\n\n"
                        
                        val bugReportText = """
                            |=== BUG REPORT ===
                            |
                            |$deviceInfo
                            |Please describe the issue:
                            |[User can add description here]
                            |
                            |=== RECENT LOGS ===
                            |$logsText
                            |
                            |=== END REPORT ===
                        """.trimMargin()
                        
                        // Send via email
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("support@pierreflasher.com"))
                            putExtra(Intent.EXTRA_SUBJECT, "Pierre Flasher Bug Report")
                            putExtra(Intent.EXTRA_TEXT, bugReportText)
                        }
                        
                        // Don't use createChooser to avoid USB disconnection
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // If no email app, copy to clipboard
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Bug Report", bugReportText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Bug report copied to clipboard (no email app found)", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF146E89)
                    )
                ) {
                    Text("Send Report", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBugReportDialog.value = false }
                ) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
    
    // Flash Erase Dialog
    if (showEraseDialog) {
        AlertDialog(
            onDismissRequest = { showEraseDialog = false },
            title = { 
                Text(
                    "Erase Flash",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = { 
                Text("❗ Do you really want to erase the entire Pierre flash memory?\n\nThis operation cannot be undone.\n\nDo not disconnect the device during erasure.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEraseDialog = false
                        isErasing = true
                        scope.launch {
                            onLog("Starting flash erase...")

                            // Send erase start statistics
                            statisticsManager.sendEraseStats("erase_start", mqttManager?.getCurrentTopic())

                            try {
                                onLog("Initializing serial connection (like Serial Monitor)...")

                                // Use the exact same sequence as the Production button
                                val port = usbManager.port
                                if (port != null) {
                                    // Initialize serial exactly like Serial Monitor
                                    try {
                                        port.setParameters(115200, 8,
                                            com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                                            com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                                        onLog("Serial parameters set: 115200 baud, 8N1")
                                    } catch (e: Exception) {
                                        onLog("Warning: Could not set serial parameters: ${e.message}")
                                    }

                                    // Start continuous serial reading like Serial Monitor
                                    var keepReading = true
                                    val serialReaderJob = scope.launch {
                                        withContext(Dispatchers.IO) {
                                            val buffer = ByteArray(16384) // Same buffer size as Serial Monitor
                                            var flashModeDetected = false

                                            onLog("Starting continuous serial reading...")

                                            while (keepReading && !flashModeDetected) {
                                                try {
                                                    // Same read timeout as Serial Monitor (300ms)
                                                    val bytesRead = port.read(buffer, 300)
                                                    if (bytesRead > 0) {
                                                        val data = String(buffer, 0, bytesRead)

                                                        // Log serial output to help debug
                                                        if (data.isNotBlank()) {
                                                            val preview = data.take(50).replace("\n", "\\n").replace("\r", "\\r")
                                                            onLog("Serial: $preview...")
                                                        }

                                                        // Check for flash mode confirmation
                                                        if (data.contains("waiting for download", ignoreCase = true) ||
                                                            data.contains("boot:0x7", ignoreCase = true) ||
                                                            data.contains("DOWNLOAD_BOOT", ignoreCase = true)) {
                                                            flashModeDetected = true
                                                            onLog("✅ Flash mode confirmed!")
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    // Continue reading even on errors
                                                }
                                            }
                                        }
                                    }

                                    // Short delay to let the serial reader start
                                    delay(100)

                                    // Flash módba lépés - use exact AUTO button sequence
                                    onLog("Starting AUTO FLASH sequence for erase...")
                                    val enterResult = withContext(Dispatchers.IO) {
                                        try {
                                            // Classic esptool.py sequence - same as AUTO button
                                            port.setDTR(false)
                                            port.setRTS(false)
                                            delay(100)

                                            port.setDTR(true)
                                            port.setRTS(false)
                                            delay(100)

                                            port.setDTR(false)
                                            port.setRTS(false)
                                            delay(100)

                                            port.setDTR(false)
                                            port.setRTS(true)
                                            delay(100)

                                            port.setDTR(true)
                                            port.setRTS(false)
                                            delay(100)

                                            port.setDTR(false)
                                            port.setRTS(false)

                                            onLog("AUTO FLASH sequence completed - checking for flash mode...")

                                            // Wait a bit for ESP32 to respond and enter flash mode
                                            delay(500)
                                            true
                                        } catch (e: Exception) {
                                            onLog("AUTO FLASH error: ${e.message}")
                                            false
                                        }
                                    }

                                    // Stop the serial reader
                                    keepReading = false
                                    serialReaderJob.cancel()

                                    if (!enterResult) {
                                        onLog("Failed to enter flash mode for erasing")
                                        isErasing = false
                                        return@launch
                                    }
                                } else {
                                    onLog("Error: USB port not available")
                                    isErasing = false
                                    return@launch
                                }

                                onLog("✓ Flash mode activated for erasing")
                                
                                val flasher = UsbEspFlasher(
                                    port = usbManager.port!!,
                                    resources = context.resources,
                                    context = context,
                                    log = onLog
                                )
                                
                                // Szinkronizálás
                                val synced = withContext(Dispatchers.IO) {
                                    flasher.syncRom()
                                }
                                
                                if (!synced) {
                                    onLog("Failed to synchronize for erasing")
                                    isErasing = false
                                    return@launch
                                }
                                
                                onLog("✓ Synchronization successful")
                                onLog("🗑️ Erasing flash...")

                                // Start timing
                                val eraseStartTime = System.currentTimeMillis()

                                // Flash törlés végrehajtása
                                val eraseResult = withContext(Dispatchers.IO) {
                                    flasher.eraseFlash()
                                }

                                if (eraseResult) {
                                    // Calculate erase duration
                                    val eraseDuration = System.currentTimeMillis() - eraseStartTime
                                    val durationSeconds = eraseDuration / 1000.0

                                    onLog("✅ FLASH ERASE SUCCESSFUL!")
                                    onLog("ESP32 flash memory erased")
                                    onLog("⏱️ Erase duration: ${String.format("%.1f", durationSeconds)} seconds")

                                    // Send erase success statistics
                                    statisticsManager.sendEraseStats("erase_success", mqttManager?.getCurrentTopic())

                                    // Vibrate to indicate successful erase
                                    vibrateSuccess()

                                    // Reset után visszaállítjuk 115200-ra
                                    delay(500)
                                    try {
                                        usbManager.port?.setParameters(115200, 8, 
                                            com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1, 
                                            com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                                    } catch (e: Exception) {
                                        onLog("Baud rate reset error: ${e.message}")
                                    }
                                } else {
                                    onLog("Flash erase failed")
                                    // Send erase error statistics
                                    statisticsManager.sendEraseStats("erase_error", mqttManager?.getCurrentTopic())
                                }

                            } catch (e: Exception) {
                                onLog("Erase error: ${e.message}")
                                // Send erase error statistics
                                statisticsManager.sendEraseStats("erase_error", mqttManager?.getCurrentTopic())
                            } finally {
                                isErasing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red
                    )
                ) {
                    Text("YES, ERASE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEraseDialog = false }
                ) {
                    Text("CANCEL")
                }
            }
        )
    }
    
    
    // Status Monitor Dialog with Auto-redirect
    if (showStatusMonitor) {
        AlertDialog(
            onDismissRequest = { showStatusMonitor = false },
            title = { 
                Text(
                    "Device Status & Diagnostics",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF146E89)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Device basic info
                    deviceStatusInfo.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$key:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    value.startsWith("✓") -> Color(0xFF4CAF50)
                                    value.startsWith("✗") -> Color(0xFFFF6B6B)
                                    value.startsWith("○") -> Color(0xFF888888)
                                    else -> Color.Unspecified
                                },
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // App version info
                    Text(
                        "App Version:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "Pierre Flasher v${MainActivity.getAppVersion(context)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50),  // Zöld szín
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showStatusMonitor = false }
                ) {
                    Text("Close")
                }
            }
        )
    }
}