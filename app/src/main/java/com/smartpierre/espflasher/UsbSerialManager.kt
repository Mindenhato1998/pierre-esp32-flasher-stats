package com.smartpierre.espflasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * USB soros manager:
 * - Eszköz felderítése, engedélykérés és port megnyitás
 * - Put ESP32 in flash mode (DTR/RTS sequence)
 */
class UsbSerialManager(
    private val context: Context,
    private val log: (String) -> Unit
) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.smartpierre.espflasher.USB_PERMISSION"

    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    var driver: UsbSerialDriver? = null
        private set
    var port: UsbSerialPort? = null
        private set
    var device: UsbDevice? = null
        private set

    init {
        // Clean up any stale state on initialization
        try {
            port?.close()
        } catch (_: Exception) {}
        port = null
        device = null
        driver = null
    }

    /**
     * Egyéni prober táblázat, hogy a leggyakoribb chipek biztosan menjenek.
     */
    private fun buildProber(): UsbSerialProber {
        // A default prober már tartalmazza az összes ismert drivert
        return UsbSerialProber.getDefaultProber()
    }

    /**
     * Megnyitja az első elérhető soros portot és beállítja a paramétereket.
     * Engedélyt kér, ha szükséges (Android dialog jelenhet meg).
     * Most használja a SerialMonitorService statikus USB kapcsolatát ha elérhető.
     */
    suspend fun openFirstPort(
        baudRate: Int = 115200,  // Standard ESP32 bootloader sebesség
        dataBits: Int = 8,
        stopBits: Int = UsbSerialPort.STOPBITS_1,
        parity: Int = UsbSerialPort.PARITY_NONE
    ): Boolean {
        // Clean up any existing connection first
        if (port != null) {
            close()
            delay(300) // Give more time for cleanup
        }

        val prober = buildProber()
        val availableDrivers = prober.findAllDrivers(usbManager)
            .ifEmpty {
                // Ha a custom prober nem talál, próbáld a default-ot is
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            }

        if (availableDrivers.isEmpty()) {
            log("No USB serial devices found")
            return false
        }

        driver = availableDrivers.first()
        device = driver?.device

        val dev = device ?: run {
            log("Device is null")
            return false
        }

        // Engedélykérés
        if (!usbManager.hasPermission(dev)) {
            log("Requesting USB permission...")
            val granted = requestUsbPermission(dev)
            if (!granted) {
                log("USB permission denied")
                return false
            }
        }

        // Always create a fresh connection for the port
        // The service only maintains a background reference to prevent GC
        log("🔌 Creating fresh USB connection for port operations...")
        var connection: android.hardware.usb.UsbDeviceConnection? = null
        var retryCount = 0

        while (retryCount < 5 && connection == null) {  // Increased retries to 5
            connection = usbManager.openDevice(dev)
            if (connection == null) {
                retryCount++
                if (retryCount < 5) {
                    log("USB connection attempt $retryCount/5 failed, retrying...")
                    delay(300L * retryCount) // Progressive delay: 300ms, 600ms, 900ms, 1200ms
                }
            }
        }

        if (connection == null) {
            log("Failed to open USB device after 5 attempts")
            // Clean up before returning
            driver = null
            device = null
            return false
        }

        // Update the service's static connection for background protection
        SerialMonitorService.usbConnection = connection
        SerialMonitorService.connectedDevice = dev
        log("🛡️ USB connection registered with SerialMonitorService for protection")

        val firstPort = driver!!.ports.firstOrNull()
        if (firstPort == null) {
            log("No ports available on USB device")
            connection?.close()
            driver = null
            device = null
            return false
        }

        try {
            firstPort.open(connection)

            // Set parameters immediately after opening
            firstPort.setParameters(baudRate, dataBits, stopBits, parity)

            // Clear any stale data with larger buffer
            try {
                val trash = ByteArray(4096)
                firstPort.read(trash, 100)
            } catch (_: Exception) {}

            // kezdeti vonalállapotok
            try {
                firstPort.setDTR(false)
                firstPort.setRTS(false)
            } catch (_: Exception) { /* nem minden driver támogatja */ }

            port = firstPort
            log("USB connected successfully (screen-lock protected)")
            return true
        } catch (e: Exception) {
            log("Failed to open port: ${e.message}")
            try { firstPort.close() } catch (_: Exception) {}
            connection?.close()
            // Clean up on failure
            driver = null
            device = null
            port = null
            return false
        }
    }

    /**
     * ESP32 flash mode sequence - esptool compatible
     * FIGYELEM: ESP32 dev board-ok különbözőek lehetnek:
     * We try both mappings:
     * Verzió A: DTR -> IO0/GPIO0, RTS -> EN/RESET  
     * Verzió B: RTS -> IO0/GPIO0, DTR -> EN/RESET (fordított)
     * Flash mód: IO0 LOW kell legyen reset közben
     */
    suspend fun enterFlashMode(): Boolean {
        val p = port ?: run {
            log("No open port.")
            return false
        }

        // First try standard methods for all devices
        try {
            log("Activating flash mode...")

            // Clear buffer before starting
            try {
                val trash = ByteArray(1024)
                p.read(trash, 50)
            } catch (_: Exception) {}

            // Esptool.py pontos szekvenciája (ACTIVE-LOW logika!)
            log("🔥 ESPTOOL.PY EXACT SEQUENCE (active-low logic)")
            log("   DTR/RTS inverse logic: True=0V, False=VCC")

            if (tryEsptoolExactSequence(p)) {
                return true
            }

            delay(1000)

            // Try reversed mapping
            log("🔄 Trying reversed mapping")
            if (tryEsptoolExactSequence(p, reversed = true)) {
                return true
            }


            log("❌ ALL methods failed")
            return false

        } catch (e: Exception) {
            log("DTR/RTS állítás hiba: ${e.message}")
            return false
        }
    }
    
    /**
     * Samsung Android 16 specific workaround
     * Uses alternative methods to trigger flash mode
     */
    private suspend fun trySamsungWorkaround(p: UsbSerialPort): Boolean {
        return try {
            log("   Trying Samsung Android 16 workarounds...")

            // Samsung készülékeknél hosszabb időzítések kellenek
            val baseDelay = 150L
            val resetDelay = 400L

            // Method 1: Double reset cycle with longer delays
            log("   Method 1: Double reset cycle")

            // First reset to clear any previous state
            p.setDTR(false)
            p.setRTS(false)
            delay(baseDelay)
            p.setRTS(true) // Reset
            delay(baseDelay)
            p.setRTS(false)
            delay(resetDelay)

            // Second reset with boot mode
            p.setDTR(true)  // GPIO0 LOW (boot mode)
            delay(baseDelay)
            p.setRTS(true)  // Reset while GPIO0 is LOW
            delay(baseDelay * 2) // Longer hold for Samsung
            p.setRTS(false) // Release reset
            delay(resetDelay)
            p.setDTR(false) // Release GPIO0
            delay(baseDelay)

            if (testForFlashMode(p)) {
                log("   ✅ Double reset method worked")
                return true
            }

            // Method 2: Use BREAK signal as alternative (some ESP32 boards support this)
            log("   Method 2: BREAK signal method")
            try {
                p.setBreak(true)
                delay(100)
                p.setBreak(false)
                delay(300)

                if (testForFlashMode(p)) {
                    log("   ✅ BREAK signal method worked")
                    return true
                }
            } catch (e: Exception) {
                log("   BREAK signal not supported: ${e.message}")
            }

            // Method 3: Rapid toggling (sometimes works around timing issues)
            log("   Method 3: Rapid toggle method")
            p.setDTR(false)
            p.setRTS(false)
            delay(50)

            // Rapid toggle to force state change
            repeat(5) {
                p.setDTR(true)
                delay(10)
                p.setDTR(false)
                delay(10)
            }

            // Now do actual sequence
            p.setDTR(true)  // GPIO0 LOW
            delay(50)

            // Rapid reset toggle
            repeat(3) {
                p.setRTS(true)
                delay(20)
                p.setRTS(false)
                delay(20)
            }

            // Final reset with GPIO0 held low
            p.setRTS(true)
            delay(100)
            p.setRTS(false)
            delay(300)
            p.setDTR(false)
            delay(100)

            if (testForFlashMode(p)) {
                log("   ✅ Rapid toggle method worked")
                return true
            }

            log("   ❌ Samsung workarounds failed")
            false

        } catch (e: Exception) {
            log("   Samsung workaround error: ${e.message}")
            false
        }
    }

    /**
     * Esptool.py EXACT sequence implementation
     * Forráskód alapján: https://github.com/espressif/esptool
     * FONTOS: DTR/RTS active-low (True=0V, False=VCC)
     */
    private suspend fun tryEsptoolExactSequence(p: UsbSerialPort, reversed: Boolean = false): Boolean {
        return try {
            val baseDelay = 100L  // Use 100ms which works with AUTO button
            val resetDelay = 200L

            if (!reversed) {
                log("   Using WORKING AUTO sequence (classic esptool.py)")

                // Classic esptool.py sequence that works with AUTO button
                p.setDTR(false)
                p.setRTS(false)
                delay(100)

                p.setDTR(true)
                p.setRTS(false)
                delay(100)

                p.setDTR(false)
                p.setRTS(false)
                delay(100)

                p.setDTR(false)
                p.setRTS(true)
                delay(100)

                p.setDTR(true)
                p.setRTS(false)
                delay(100)

                p.setDTR(false)
                p.setRTS(false)

            } else {
                log("   Reversed: trying alternative sequence")
                // Alternative reversed sequence
                p.setRTS(false)
                p.setDTR(false)
                delay(100)

                p.setRTS(true)
                p.setDTR(false)
                delay(100)

                p.setRTS(false)
                p.setDTR(false)
                delay(100)

                p.setRTS(false)
                p.setDTR(true)
                delay(100)

                p.setRTS(true)
                p.setDTR(false)
                delay(100)

                p.setRTS(false)
                p.setDTR(false)
            }

            log("   ✅ Esptool sequence ready, testing...")
            delay(resetDelay) // ESP32 indulási idő

            return testForFlashMode(p)

        } catch (e: Exception) {
            log("   ❌ Esptool sequence error: ${e.message}")
            false
        }
    }
    
    /**
     * Flash mode sequence attempt with given DTR/RTS mapping
     */
    private suspend fun tryFlashModeSequence(p: UsbSerialPort, dtrAsIo0: Boolean, longTiming: Boolean = false): Boolean {
        return try {
            val baseDelay = if (longTiming) 250 else 100
            val resetDelay = if (longTiming) 500 else 200
            val settleDelay = if (longTiming) 300 else 100
            
            if (dtrAsIo0) {
                log("  DTR->IO0, RTS->EN mapping${if (longTiming) " (long timing)" else ""}")
                // Standard mapping: DTR->IO0, RTS->EN
                // 1. Kezdeti állapot - minden HIGH
                p.setRTS(false)  // EN/RESET HIGH
                p.setDTR(false)  // IO0 HIGH
                delay(baseDelay.toLong())
                
                // 2. IO0-t LOW-ra húzzuk
                p.setDTR(true)   // IO0 -> LOW (boot select)
                delay(baseDelay.toLong())
                
                // 3. Reset impulzus
                p.setRTS(true)   // EN/RESET -> LOW (chip reset)
                delay(baseDelay.toLong())
                
                // 4. Chip újraindul IO0=LOW mellett (flash mód)
                p.setRTS(false)  // EN/RESET -> HIGH (chip indul)
                delay(resetDelay.toLong())
                
                // 5. IO0 elengedése
                p.setDTR(false)  // IO0 -> HIGH
                delay(settleDelay.toLong())
            } else {
                log("  RTS->IO0, DTR->EN mapping (reversed)${if (longTiming) " (long timing)" else ""}")
                // Reversed mapping: RTS->IO0, DTR->EN
                // 1. Kezdeti állapot - minden HIGH
                p.setDTR(false)  // EN/RESET HIGH
                p.setRTS(false)  // IO0 HIGH
                delay(baseDelay.toLong())
                
                // 2. IO0-t LOW-ra húzzuk
                p.setRTS(true)   // IO0 -> LOW (boot select)
                delay(baseDelay.toLong())
                
                // 3. Reset impulzus
                p.setDTR(true)   // EN/RESET -> LOW (chip reset)
                delay(baseDelay.toLong())
                
                // 4. Chip újraindul IO0=LOW mellett (flash mód)
                p.setDTR(false)  // EN/RESET -> HIGH (chip indul)
                delay(resetDelay.toLong())
                
                // 5. IO0 elengedése
                p.setRTS(false)  // IO0 -> HIGH
                delay(settleDelay.toLong())
            }
            
            log("  Sequence completed, checking...")
            return testForFlashMode(p)
            
        } catch (e: Exception) {
            log("  Mapping error: ${e.message}")
            return false
        }
    }
    
    /**
     * Esptool-style reset sequence - more aggressive method
     */
    private suspend fun tryEsptoolStyleReset(p: UsbSerialPort): Boolean {
        return try {
            log("  Esptool-style reset (aggressive)")
            
            // Clean state
            p.setDTR(false)
            p.setRTS(false) 
            delay(100)
            
            // Long reset cycle
            repeat(3) { cycle ->
                log("    Reset cycle ${cycle + 1}/3")
                
                // DTR->GPIO0 sequence
                p.setDTR(true)   // GPIO0 LOW
                delay(50)
                p.setRTS(true)   // EN/RESET LOW  
                delay(50)
                p.setRTS(false)  // EN/RESET HIGH (chip indul GPIO0=LOW mellett)
                delay(200)
                p.setDTR(false)  // GPIO0 HIGH
                delay(100)
                
                // Testing
                val testResult = testForFlashMode(p)
                if (testResult) return true
                
                delay(200)
                
                // RTS->GPIO0 sequence with same cycle
                p.setRTS(true)   // GPIO0 LOW
                delay(50)
                p.setDTR(true)   // EN/RESET LOW
                delay(50)  
                p.setDTR(false)  // EN/RESET HIGH (chip indul GPIO0=LOW mellett)
                delay(200)
                p.setRTS(false)  // GPIO0 HIGH
                delay(100)
                
                // Testing
                val testResult2 = testForFlashMode(p)
                if (testResult2) return true
                
                delay(300)
            }
            
            log("  ❌ Esptool reset failed")
            false
            
        } catch (e: Exception) {
            log("  Esptool reset hiba: ${e.message}")
            false
        }
    }
    
    /**
     * Flash mód tesztelése
     */
    private suspend fun testForFlashMode(p: UsbSerialPort): Boolean {
        return try {
            // IMPORTANT: Don't send anything first, just wait and read
            // The ESP32 will output boot messages automatically
            delay(500) // Give ESP32 time to boot and output messages

            val response = ByteArray(2048)
            val bytesRead = p.read(response, 300)

            if (bytesRead > 0) {
                val responseStr = String(response, 0, bytesRead)
                log("    Boot output: ${responseStr.take(100)}...")

                // Check for flash mode indicators
                if (responseStr.contains("waiting for download", ignoreCase = true) ||
                    responseStr.contains("boot:0x7", ignoreCase = true) ||
                    responseStr.contains("DOWNLOAD_BOOT", ignoreCase = true)) {
                    log("    ✅ FLASH MODE DETECTED!")
                    return true
                }

                // If we get normal boot message, then NOT in flash mode
                if (responseStr.contains("boot:0x13", ignoreCase = true) ||
                    responseStr.contains("boot:0x17", ignoreCase = true) ||
                    responseStr.contains("SPI_FAST_FLASH_BOOT", ignoreCase = true) ||
                    responseStr.contains("heap_init", ignoreCase = true) ||
                    responseStr.contains("gpio:", ignoreCase = true) ||
                    responseStr.contains("I (", ignoreCase = true) ||
                    responseStr.contains("Loaded app", ignoreCase = true)) {
                    log("    ❌ Normal boot detected (not flash mode)")
                    return false
                }
                
                // If other response, probably not in flash mode
                return false
            } else {
                return false // Silent = probably not in flash mode
            }
            
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Hard reset via RTS (esptool kompatibilis)
     */
    suspend fun resetToRun(): Boolean {
        val p = port ?: return false
        return try {
            // Hard reset sequence based on esptool
            p.setDTR(false) // IO0 HIGH (normál boot)
            delay(50)
            p.setRTS(true)  // EN/RESET LOW
            delay(100)
            p.setRTS(false) // EN/RESET HIGH (chip indul)
            delay(500)      // Várunk hogy stabilan elinduljon
            log("ESP hard reset via RTS.")
            true
        } catch (e: Exception) {
            log("Reset hiba: ${e.message}")
            false
        }
    }

    fun close() {
        try {
            // First set DTR/RTS to safe state
            try {
                port?.setDTR(false)
                port?.setRTS(false)
            } catch (_: Exception) {}

            // Give time for commands to be sent
            Thread.sleep(50)

            // Close the port - this automatically releases the interface
            try {
                port?.close()
                log("📎 Port closed successfully")
            } catch (e: Exception) {
                log("Error closing port: ${e.message}")
            }

            // Note: We keep the USB connection reference in the service for protection
            // but each new openFirstPort() call will create a fresh connection
            log("🛡️ USB connection reference maintained by SerialMonitorService")
        } catch (e: Exception) {
            log("Error during close: ${e.message}")
        } finally {
            // Clean up our references
            port = null
            driver = null
            device = null
        }
    }

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        cont.resume(granted)
                    }
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_IMMUTABLE else 0

            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, pendingIntent)

            cont.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
        }
}
