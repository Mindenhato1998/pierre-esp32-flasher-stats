package com.smartpierre.espflasher

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

class UsbEspFlasher(
    private val port: UsbSerialPort,
    private val resources: Resources,
    private val context: Context,
    private val log: (String) -> Unit
) {

    suspend fun enterBootloaderSequence() {
        try {
            // Tiszta kezdeti állapot
            port.setDTR(false)
            port.setRTS(false)
            delay(100)
            
            // Flash mode sequence - standard ESP32 dev board
            // IO0 LOW kell legyen reset alatt a download módhoz
            port.setDTR(true)     // IO0 -> LOW (boot select)
            delay(100)
            
            port.setRTS(true)     // EN/RESET -> LOW (reset aktív)
            delay(100)
            port.setRTS(false)    // EN/RESET -> HIGH (reset vége)
            delay(100)
            
            // Várunk még egy kicsit IO0 LOW-val
            delay(100)
            
            port.setDTR(false)    // IO0 -> HIGH (elenged)
            delay(100)
            
            log("Bootloader sequence completed")
        } catch (e: Exception) {
            log("DTR/RTS setting error: ${e.message}")
        }
    }
    
    suspend fun enterBootloaderSequenceAlternate() {
        // Alternative sequence - inverse logic or different wiring
        try {
            // Tiszta kezdeti állapot
            port.setDTR(false)
            port.setRTS(false)
            delay(100)
            
            // Fordított sorrend: először reset, aztán IO0
            port.setRTS(true)     // EN/RESET -> LOW
            delay(50)
            port.setDTR(true)     // IO0 -> LOW 
            delay(100)
            port.setRTS(false)    // EN/RESET -> HIGH (boot flash módban)
            delay(100)
            port.setDTR(false)    // IO0 -> HIGH
            delay(100)
            
            log("Alternative bootloader sequence completed")
        } catch (e: Exception) {
            log("Alternative DTR/RTS error: ${e.message}")
        }
    }

    suspend fun syncRom(maxAttempts: Int = 5): Boolean {
        val proto = EspRomProtocol(port)
        repeat(maxAttempts) { attempt ->
            if (proto.sync()) return true
            delay(120)
        }
        return false
    }

    suspend fun flashEmbeddedBins(includeBootloader: Boolean = true): String {
        val proto = EspRomProtocol(port, log)
        
        // ESP32 flash offsets
        val parts = mutableListOf<FlashPart>()
        
        // BOOTLOADER MINDIG KELL!
        parts.add(FlashPart(0x1000, R.raw.bootloader))
        log("Bootloader @ 0x1000 (required)")
        
        // ESPTOOL SORREND: bootloader → production → partition → ota_data
        parts.addAll(listOf(
            FlashPart(0x10000, R.raw.production),         // Production app ELSŐKÉNT (mint esptool)
            FlashPart(0x8000, R.raw.partition_table),     // Partition table @ 0x8000
            FlashPart(0xe000, R.raw.ota_data_initial)     // OTA data @ 0xe000
        ))
        
        // SPI settings are REQUIRED for the ROM bootloader!
        log("Setting SPI parameters...")
        if (!proto.setSpiFlashParams()) {
            log("⚠️ SPI parameters setting failed")
            return "SPI parameter error"
        }
        
        if (!proto.spiAttach()) {
            log("⚠️ SPI attach failed") 
            return "SPI attach error"
        }
        log("✅ SPI configuration OK")
        
        // Gyorsított baud rate - 345600 bps (3x gyorsabb mint 115200)
        log("⚡ Turbo mode: changing baud rate to 345600 bps...")
        if (proto.changeBaudRate(345600)) {
            log("✅ Baud rate successfully changed: 345600 bps")
        } else {
            log("⚠️ Baud rate change failed, staying at 115200 bps")
        }
        
        var totalBytes = 0
        parts.forEach { p ->
            val input = resources.openRawResource(p.resId)
            val data = input.readBytes()
            input.close()
            
            totalBytes += data.size
            log(String.format("Writing to 0x%08x: %,d bytes", p.offset, data.size))
            
            if (!proto.flashToOffsetSimple(p.offset, data)) {
                return "FLASH error at 0x%08x".format(p.offset)
            }
            
            log("✓ Successfully written to 0x%08x".format(p.offset))
        }
        
        // Flash end csak a legvégén!
        log("Finishing flash...")
        if (!proto.flashEnd(reboot = false)) {
            log("Flash end failed, but data was written")
        }
        
        // Hard reset az ESP32-n (mint az esptool)
        log("Performing hard reset...")
        proto.hardReset()
        
        return "FLASH SUCCESSFUL! Total %,d bytes written. ESP32 restarted.".format(totalBytes)
    }
    
    suspend fun flashCustomFiles(files: Map<String, Uri>): String {
        val proto = EspRomProtocol(port, log)
        
        // Standard ESP32 offsets a fájl típusokhoz - PONTOS CÍMEK
        val offsetMap = mapOf(
            "Bootloader" to 0x1000,      // KÖTELEZŐ!
            "Partition Table" to 0x8000,
            "OTA Data" to 0xe000,
            "Production" to 0x10000
        )
        
        // WARNING: If no bootloader selected, use the built-in one
        if (!files.containsKey("Bootloader")) {
            log("⚠️ WARNING: No bootloader selected!")
            log("❌ ESP32 WILL NOT BOOT without bootloader!")
            return "ERROR: Bootloader is required! Please select a bootloader file."
        }
        
        // SPI settings are REQUIRED for the ROM bootloader!
        log("Setting SPI parameters...")
        if (!proto.setSpiFlashParams()) {
            log("⚠️ SPI parameters setting failed")
            return "SPI parameter error"
        }
        
        if (!proto.spiAttach()) {
            log("⚠️ SPI attach failed") 
            return "SPI attach error"
        }
        log("✅ SPI configuration OK")
        
        // Gyorsított baud rate - 345600 bps (3x gyorsabb mint 115200)
        log("⚡ Turbo mode: changing baud rate to 345600 bps...")
        if (proto.changeBaudRate(345600)) {
            log("✅ Baud rate successfully changed: 345600 bps")
        } else {
            log("⚠️ Baud rate change failed, staying at 115200 bps")
        }
        
        var totalBytes = 0
        
        // Sorrendben flasheljük (fontos a sorrend!)
        val sortedTypes = listOf("Bootloader", "Partition Table", "OTA Data", "Production")
        
        for (type in sortedTypes) {
            val uri = files[type]
            if (uri != null) {
                val offset = offsetMap[type]!!
                
                try {
                    // Uri-ból byte array olvasása
                    val data = readUriToByteArray(uri)
                    totalBytes += data.size
                    
                    log("Custom file: $type @ 0x%08x (%,d bytes)".format(offset, data.size))
                    
                    if (!proto.flashToOffsetSimple(offset, data)) {
                        return "FLASH error: $type @ 0x%08x".format(offset)
                    }
                    
                    log("✓ Successful: $type @ 0x%08x".format(offset))
                    
                } catch (e: Exception) {
                    return "File read error: $type - ${e.message}"
                }
            } else {
                log("Skipped: $type (not selected)")
            }
        }
        
        // Flash end csak a legvégén!
        if (totalBytes > 0) {
            log("Finishing flash...")
            if (!proto.flashEnd(reboot = false)) {
                log("Flash end failed, but data was written")
            }
            
            // Hard reset az ESP32-n (mint az esptool)
            log("Performing hard reset...")
            proto.hardReset()
        }
        
        return if (totalBytes > 0) {
            "Custom firmware SUCCESSFUL! Total %,d bytes written. ESP32 restarted.".format(totalBytes)
        } else {
            "No custom file selected"
        }
    }
    
    /**
     * Uri-ból ByteArray olvasása
     */
    private suspend fun readUriToByteArray(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IllegalStateException("Failed to open URI: $uri")
    }

    /**
     * ESP32 Flash erase (erase_flash command)
     * This erases the entire flash memory
     */
    suspend fun eraseFlash(): Boolean {
        val proto = EspRomProtocol(port, log)
        
        try {
            // SPI settings - these are important for flash operations
            log("SPI configuration...")
            if (!proto.setSpiFlashParams()) {
                log("⚠️ SPI parameters setting failed, continuing...")
            }
            
            if (!proto.spiAttach()) {
                log("⚠️ SPI attach failed, continuing...")
            }
            
            // Baud rate marad 115200 bps - stabilitás miatt
            log("🔧 Erasing at stable 115200 bps speed")
            
            // Flash chip erase - tries multiple methods
            val eraseResult = proto.eraseFlash()
            
            if (eraseResult) {
                // Hard reset ESP32 after erase
                log("🔄 Restarting ESP32...")
                proto.hardReset()
                
                // Kis várakozás a reset után
                delay(500)
                
                return true
            } else {
                log("❌ Flash erase permanently failed")
                // Reset anyway
                proto.hardReset()
                return false
            }
            
        } catch (e: Exception) {
            log("❌ Critical erase error: ${e.message}")
            // Reset even on error
            try {
                proto.hardReset()
            } catch (resetError: Exception) {
                log("Reset error: ${resetError.message}")
            }
            return false
        }
    }
    
    /**
     * Flash with downloaded production firmware
     * Same as flashEmbeddedBins but uses downloaded production.bin instead of embedded
     */
    suspend fun flashDownloadedProduction(productionFile: File): String {
        val proto = EspRomProtocol(port, log)
        
        // ESP32 flash offsets
        val parts = mutableListOf<FlashPart>()
        
        // BOOTLOADER MINDIG KELL!
        parts.add(FlashPart(0x1000, R.raw.bootloader))
        log("Bootloader @ 0x1000 (required)")
        
        // Use downloaded production file for 0x10000
        log("Downloaded production @ 0x10000 (${productionFile.length() / 1024} KB)")
        
        // Other embedded files remain the same
        parts.addAll(listOf(
            FlashPart(0x8000, R.raw.partition_table),     // Partition table @ 0x8000
            FlashPart(0xe000, R.raw.ota_data_initial)     // OTA data @ 0xe000
        ))
        
        // SPI settings are REQUIRED for the ROM bootloader!
        log("Setting SPI parameters...")
        if (!proto.setSpiFlashParams()) {
            log("⚠️ SPI parameters setting failed")
            return "SPI parameter error"
        }
        
        if (!proto.spiAttach()) {
            log("⚠️ SPI attach failed") 
            return "SPI attach error"
        }
        log("✅ SPI configuration OK")
        
        // Gyorsított baud rate - 345600 bps (3x gyorsabb mint 115200)
        log("⚡ Turbo mode: changing baud rate to 345600 bps...")
        if (proto.changeBaudRate(345600)) {
            log("✅ Baud rate successfully changed: 345600 bps")
        } else {
            log("⚠️ Baud rate change failed, staying at 115200 bps")
        }
        
        var totalBytes = 0
        
        // Flash embedded files first
        parts.forEach { p ->
            val input = resources.openRawResource(p.resId)
            val data = input.readBytes()
            input.close()
            
            totalBytes += data.size
            log(String.format("Writing to 0x%08x: %,d bytes", p.offset, data.size))
            
            if (!proto.flashToOffsetSimple(p.offset, data)) {
                return "FLASH error at 0x%08x".format(p.offset)
            }
            
            log("✓ Successfully written to 0x%08x".format(p.offset))
        }
        
        // Flash downloaded production file at 0x10000
        try {
            val productionData = productionFile.readBytes()
            totalBytes += productionData.size
            log(String.format("Writing downloaded production to 0x%08x: %,d bytes", 0x10000, productionData.size))
            
            if (!proto.flashToOffsetSimple(0x10000, productionData)) {
                return "FLASH error: Downloaded production @ 0x10000"
            }
            
            log("✓ Successfully written downloaded production to 0x10000")
        } catch (e: Exception) {
            return "Downloaded production file error: ${e.message}"
        }
        
        // Flash end csak a legvégén!
        log("Finishing flash...")
        if (!proto.flashEnd(reboot = false)) {
            log("Flash end failed, but data was written")
        }
        
        // Hard reset az ESP32-n (mint az esptool)
        log("Performing hard reset...")
        proto.hardReset()
        
        return "FLASH SUCCESSFUL! Total %,d bytes written (with latest production). ESP32 restarted.".format(totalBytes)
    }
    
    data class FlashPart(val offset: Int, val resId: Int)
}
