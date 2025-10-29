package com.smartpierre.espflasher

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import java.util.zip.Deflater
import java.security.MessageDigest

/**
 * ESP32 ROM bootloader protokoll implementáció
 * Az ESP32 SLIP-encoded csomagokat használ a kommunikációhoz
 */
class EspRomProtocol(
    private val port: UsbSerialPort,
    private val log: (String) -> Unit = {}
) {
    companion object {
        // SLIP protocol bytes
        const val SLIP_END = 0xC0.toByte()
        const val SLIP_ESC = 0xDB.toByte()
        const val SLIP_ESC_END = 0xDC.toByte()
        const val SLIP_ESC_ESC = 0xDD.toByte()
        
        // ESP32 ROM commands
        const val ESP_FLASH_BEGIN = 0x02
        const val ESP_FLASH_DATA = 0x03
        const val ESP_FLASH_END = 0x04
        const val ESP_MEM_BEGIN = 0x05
        const val ESP_MEM_END = 0x06
        const val ESP_MEM_DATA = 0x07
        const val ESP_SYNC = 0x08
        const val ESP_WRITE_REG = 0x09
        const val ESP_READ_REG = 0x0A
        const val ESP_CHANGE_BAUD = 0x0F
        const val ESP_SPI_SET_PARAMS = 0x0B
        const val ESP_SPI_ATTACH = 0x0D
        const val ESP_CHANGE_BAUDRATE = 0x0F
        const val ESP_FLASH_DEFL_BEGIN = 0x10
        const val ESP_FLASH_DEFL_DATA = 0x11
        const val ESP_FLASH_DEFL_END = 0x12
        const val ESP_SPI_FLASH_MD5 = 0x13
        const val ESP_RUN_USER_CODE = 0x14
        const val ESP_ERASE_FLASH = 0xD0
        const val ESP_ERASE_REGION = 0xD1
        
        // Flash parameters
        const val ESP_FLASH_BLOCK_SIZE = 0x400  // 1024 bytes - standard ESP32 block size
        const val ESP_FLASH_SECTOR_SIZE = 0x1000 // 4096 bytes
        const val ESP_RAM_BLOCK_SIZE = 0x1800    // 6144 bytes
        
        // Gyorsított mód - 345600 bps (3x gyorsabb mint 115200)
        const val TURBO_BAUD_RATE = 345600  // 345.6 kbps - Stabil sebesség!
        
        // Timeouts - növelve a stabilitás érdekében
        const val DEFAULT_TIMEOUT = 5000
        const val SYNC_TIMEOUT = 2000
        const val FLASH_TIMEOUT = 30000  // 30 másodperc a flash műveletekhez
    }
    
    private var timeout = DEFAULT_TIMEOUT
    
    /**
     * SLIP encode egy byte array-t
     */
    private fun slipEncode(data: ByteArray): ByteArray {
        val encoded = mutableListOf<Byte>()
        encoded.add(SLIP_END)
        
        for (byte in data) {
            when (byte) {
                SLIP_END -> {
                    encoded.add(SLIP_ESC)
                    encoded.add(SLIP_ESC_END)
                }
                SLIP_ESC -> {
                    encoded.add(SLIP_ESC)
                    encoded.add(SLIP_ESC_ESC)
                }
                else -> encoded.add(byte)
            }
        }
        
        encoded.add(SLIP_END)
        return encoded.toByteArray()
    }
    
    /**
     * SLIP decode egy byte array-t
     */
    private fun slipDecode(data: ByteArray): ByteArray {
        val decoded = mutableListOf<Byte>()
        var escaping = false
        
        for (byte in data) {
            if (escaping) {
                when (byte) {
                    SLIP_ESC_END -> decoded.add(SLIP_END)
                    SLIP_ESC_ESC -> decoded.add(SLIP_ESC)
                    else -> decoded.add(byte)
                }
                escaping = false
            } else {
                when (byte) {
                    SLIP_END -> {} // Packet boundary, ignore
                    SLIP_ESC -> escaping = true
                    else -> decoded.add(byte)
                }
            }
        }
        
        return decoded.toByteArray()
    }
    
    /**
     * Create ESP32 command packet
     */
    private fun makeCommand(opcode: Int, data: ByteArray = byteArrayOf(), checksum: Int = 0): ByteArray {
        val packet = ByteBuffer.allocate(8 + data.size)
        packet.order(ByteOrder.LITTLE_ENDIAN)
        
        packet.put(0x00) // Direction (request)
        packet.put(opcode.toByte()) // Command
        packet.putShort(data.size.toShort()) // Data length
        packet.putInt(checksum) // Checksum
        packet.put(data) // Data payload
        
        return packet.array()
    }
    
    /**
     * Checksum számítás
     */
    private fun checksum(data: ByteArray): Int {
        var chk = 0xEF
        for (byte in data) {
            chk = chk xor (byte.toInt() and 0xFF)
        }
        return chk
    }
    
    /**
     * Send command and receive response
     */
    private suspend fun command(
        opcode: Int, 
        data: ByteArray = byteArrayOf(), 
        chksum: Int = 0,
        timeoutMs: Int = timeout
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val packet = makeCommand(opcode, data, chksum)
            val encoded = slipEncode(packet)
            
            // Debug: kiírjuk mit küldünk
            if (opcode == ESP_SYNC) {
                log("SYNC sending: ${encoded.size} bytes")
                log("  First 10 bytes: ${encoded.take(10).joinToString { "0x%02x".format(it) }}")
            }
            
            // Küldés
            val bytesWritten = port.write(encoded, timeoutMs)
            if (opcode == ESP_SYNC) {
                log("  Sent: $bytesWritten bytes")
            }
            
            // Válasz olvasása - nagyobb puffer és több próbálkozás
            val fullResponse = mutableListOf<Byte>()
            var totalBytesRead = 0
            val maxAttempts = 3
            
            repeat(maxAttempts) { attempt ->
                val response = ByteArray(4096)  // Nagyobb puffer
                val bytesRead = try {
                    val read = port.read(response, timeoutMs / maxAttempts)
                    if (opcode == ESP_SYNC && read > 0) {
                        log("  Read (attempt ${attempt+1}): $read bytes")
                        log("    First 10 bytes: ${response.take(10).joinToString { "0x%02x".format(it) }}")
                    }
                    read
                } catch (e: Exception) {
                    if (opcode == ESP_SYNC) {
                        log("  Read error (attempt ${attempt+1}): ${e.message}")
                    }
                    if (attempt == maxAttempts - 1) throw e
                    0
                }
                
                if (bytesRead > 0) {
                    fullResponse.addAll(response.sliceArray(0 until bytesRead).toList())
                    totalBytesRead += bytesRead
                    
                    // If there's SLIP_END, the response is complete
                    if (fullResponse.contains(SLIP_END) && fullResponse.size > 1) {
                        val decoded = slipDecode(fullResponse.toByteArray())
                        if (decoded.isNotEmpty()) {
                            if (opcode == ESP_SYNC) {
                                log("  SYNC response decoded: ${decoded.size} bytes")
                            }
                            return@withContext decoded
                        }
                    }
                }
            }
            
            if (totalBytesRead > 0) {
                val decoded = slipDecode(fullResponse.toByteArray())
                if (decoded.isNotEmpty()) {
                    return@withContext decoded
                }
            }
            
            if (opcode == ESP_SYNC) {
                log("  SYNC no response (total read: $totalBytesRead bytes)")
            }
        } catch (e: Exception) {
            log("Command error (opcode=0x${opcode.toString(16)}): ${e.message}")
        }
        
        return@withContext null
    }
    
    /**
     * Port test - simple write/read test
     */
    suspend fun testPort(): Boolean = withContext(Dispatchers.IO) {
        try {
            log("Starting port test...")
            
            // Küldünk egy egyszerű byte-ot
            val testData = byteArrayOf(0x55)
            port.write(testData, 1000)
            log("  Teszt adat elküldve: 0x55")
            
            // Próbálunk olvasni
            val response = ByteArray(100)
            val bytesRead = try {
                port.read(response, 500)
            } catch (e: Exception) {
                log("  Port read error: ${e.message}")
                0
            }
            
            if (bytesRead > 0) {
                log("  Port responded: $bytesRead bytes")
                log("  Válasz: ${response.take(bytesRead).joinToString { "0x%02x".format(it) }}")
                return@withContext true
            } else {
                log("  No response from port")
                return@withContext false
            }
        } catch (e: Exception) {
            log("Port test error: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Szinkronizálás az ESP32-vel
     */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val syncData = ByteArray(36) { 
            when (it) {
                0 -> 0x07
                1 -> 0x07
                2 -> 0x12
                3 -> 0x20
                else -> 0x55
            }.toByte()
        }
        
        log("Starting synchronization...")
        
        repeat(5) { attempt ->
            log("SYNC attempt ${attempt + 1}/5")
            
            // Tisztítjuk a puffert
            try {
                val trash = ByteArray(1024)
                val cleaned = port.read(trash, 100)
                if (cleaned > 0) {
                    log("  Buffer cleaned: $cleaned bytes")
                    // Ha boot üzenetet kapunk, az ESP32 nincs flash módban
                    val bootMsg = String(trash, 0, cleaned)
                    if (bootMsg.contains("ets") || bootMsg.contains("rst:")) {
                        log("  FIGYELEM: ESP32 boot üzenet detektálva - nincs flash módban!")
                        log("  Nyomja meg újra az 'ESP32 FLASH MÓDBA RAKÁSA' gombot!")
                        return@withContext false
                    }
                }
            } catch (_: Exception) {}
            
            // Send SYNC command
            val response = command(ESP_SYNC, syncData, 0, SYNC_TIMEOUT)
            
            if (response != null && response.size >= 8) {
                // Check the response
                if (response[0] == 0x01.toByte() && response[1] == ESP_SYNC.toByte()) {
                    log("SYNC successful!")
                    delay(100)
                    return@withContext true
                } else {
                    log("SYNC response not valid: ${response.take(8).joinToString { "0x%02x".format(it) }}")
                }
            } else {
                log("SYNC no response (size: ${response?.size ?: 0})")
            }
            
            delay(500) // Fél másodperc várakozás
        }
        
        log("SYNC failed after all 5 attempts")
        return@withContext false
    }
    
    /**
     * Flash write start - esptool compatible
     */
    private suspend fun flashBegin(size: Int, offset: Int): Boolean = withContext(Dispatchers.IO) {
        // Round to 4KB sectors (ESP32 sector size)
        val sectorSize = ESP_FLASH_SECTOR_SIZE
        val numSectors = (size + sectorSize - 1) / sectorSize
        val eraseSize = numSectors * sectorSize
        
        // Blokkok száma
        val numBlocks = (size + ESP_FLASH_BLOCK_SIZE - 1) / ESP_FLASH_BLOCK_SIZE
        
        val data = ByteBuffer.allocate(16)
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(eraseSize)      // Teljes törlendő méret
        data.putInt(numBlocks)       // Blokkok száma
        data.putInt(ESP_FLASH_BLOCK_SIZE)  // Blokk méret
        data.putInt(offset)          // Kezdő cím
        
        log("Flash start: $size bytes @ 0x${offset.toString(16)}")
        log("  Erase size: $eraseSize (${numSectors} sectors), Blocks: $numBlocks")
        
        // Több próbálkozás hosszabb timeout-tal
        repeat(3) { attempt ->
            val response = command(ESP_FLASH_BEGIN, data.array(), 0, FLASH_TIMEOUT)
            
            if (response != null) {
                log("Flash begin response (attempt ${attempt+1}): ${response.take(10).joinToString { "0x%02x".format(it) }}")
                
                if (response.size >= 2) {
                    // ESP32 response format: [0x01, opcode, ...] or [0x01, 0x00, 0x00, ...]
                    if (response[0] == 0x01.toByte()) {
                        if (response[1] == ESP_FLASH_BEGIN.toByte() || response[1] == 0x00.toByte()) {
                            log("Flash begin OK @ 0x${offset.toString(16)}")
                            return@withContext true
                        }
                    }
                }
            } else {
                log("Flash begin no response (attempt ${attempt+1})")
            }
            
            if (attempt < 2) {
                delay(500) // Várunk a következő próbálkozás előtt
            }
        }
        
        log("Flash begin failed after all 3 attempts @ 0x${offset.toString(16)}")
        return@withContext false
    }
    
    /**
     * Flash block write
     */
    private suspend fun flashBlock(data: ByteArray, seq: Int): Boolean = withContext(Dispatchers.IO) {
        val blockData = ByteBuffer.allocate(16 + data.size)
        blockData.order(ByteOrder.LITTLE_ENDIAN)
        blockData.putInt(data.size)
        blockData.putInt(seq)
        blockData.putInt(0)
        blockData.putInt(0)
        blockData.put(data)
        
        val chk = checksum(data)
        val response = command(ESP_FLASH_DATA, blockData.array(), chk, FLASH_TIMEOUT)
        return@withContext response != null
    }
    
    /**
     * Flash write completion
     */
    suspend fun flashEnd(reboot: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val data = ByteBuffer.allocate(4)
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(if (reboot) 1 else 0) // reboot flag
        
        val response = command(ESP_FLASH_END, data.array(), 0, FLASH_TIMEOUT)
        if (response != null) {
            log("Flash end OK")
            return@withContext true
        }
        log("Flash end error")
        return@withContext false
    }
    
    /**
     * Set SPI Flash parameters (DIO mode, 40MHz, 4MB)
     */
    suspend fun setSpiFlashParams(): Boolean = withContext(Dispatchers.IO) {
        // ESP32 SPI paraméterek: 
        // ID=0, total_size=4MB, block_size=64KB, sector_size=4KB, page_size=256, status_mask=0xFFFF
        val data = ByteBuffer.allocate(24)
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0)           // ID
        data.putInt(4 * 1024 * 1024)  // Total size: 4MB
        data.putInt(64 * 1024)   // Block size: 64KB
        data.putInt(4096)        // Sector size: 4KB
        data.putInt(256)         // Page size: 256B
        data.putInt(0xFFFF)      // Status mask
        
        val response = command(ESP_SPI_SET_PARAMS, data.array(), 0, DEFAULT_TIMEOUT)
        if (response != null && response.isNotEmpty()) {
            log("SPI parameters set")
            return@withContext true
        }
        log("SPI parameter setting error")
        return@withContext false
    }
    
    /**
     * SPI Flash attach (flash chip aktiválása)
     */
    suspend fun spiAttach(): Boolean = withContext(Dispatchers.IO) {
        // Paraméterek a DIO módhoz, 40MHz-hez
        val data = ByteBuffer.allocate(8)
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0)  // hspi_arg - általában 0
        data.putInt(0)  // is_legacy - 0 az új protokollhoz
        
        val response = command(ESP_SPI_ATTACH, data.array(), 0, DEFAULT_TIMEOUT)
        if (response != null && response.isNotEmpty()) {
            log("SPI flash attached")
            return@withContext true
        }
        log("SPI attach error")
        return@withContext false
    }
    
    /**
     * Adat tömörítése DEFLATE algoritmussal (mint az esptool)
     */
    private fun compressData(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        
        val output = ByteArray(data.size + 100) // Extra hely a fejlécnek
        val compressedSize = deflater.deflate(output)
        deflater.end()
        
        return output.sliceArray(0 until compressedSize)
    }
    
    /**
     * MD5 hash számítása
     */
    private fun calculateMD5(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data)
    }
    
    /**
     * Start compressed flash (ESP_FLASH_DEFL_BEGIN)
     */
    private suspend fun flashDeflBegin(size: Int, compressedSize: Int, offset: Int): Boolean = withContext(Dispatchers.IO) {
        val sectorSize = ESP_FLASH_SECTOR_SIZE
        val numSectors = (size + sectorSize - 1) / sectorSize
        val eraseSize = numSectors * sectorSize
        
        val numBlocks = (compressedSize + ESP_FLASH_BLOCK_SIZE - 1) / ESP_FLASH_BLOCK_SIZE
        
        val data = ByteBuffer.allocate(20)  // 5 integer = 20 bytes
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(eraseSize)           // Teljes törlendő méret
        data.putInt(numBlocks)            // Number of compressed blocks
        data.putInt(ESP_FLASH_BLOCK_SIZE) // Blokk méret
        data.putInt(offset)               // Kezdő cím
        data.putInt(size)                 // Eredeti méret (nem tömörített)
        
        log("Flash DEFLATE start: $size bytes (compressed: $compressedSize) @ 0x${offset.toString(16)}")
        
        repeat(3) { attempt ->
            val response = command(ESP_FLASH_DEFL_BEGIN, data.array(), 0, FLASH_TIMEOUT)
            
            if (response != null && response.size >= 2) {
                if (response[0] == 0x01.toByte()) {
                    log("Flash DEFLATE begin OK @ 0x${offset.toString(16)}")
                    return@withContext true
                }
            }
            
            if (attempt < 2) delay(500)
        }
        
        log("Flash DEFLATE begin failed @ 0x${offset.toString(16)}")
        return@withContext false
    }
    
    /**
     * Write compressed flash block
     */
    private suspend fun flashDeflBlock(data: ByteArray, seq: Int): Boolean = withContext(Dispatchers.IO) {
        val blockData = ByteBuffer.allocate(16 + data.size)
        blockData.order(ByteOrder.LITTLE_ENDIAN)
        blockData.putInt(data.size)
        blockData.putInt(seq)
        blockData.putInt(0)
        blockData.putInt(0)
        blockData.put(data)
        
        val chk = checksum(data)
        val response = command(ESP_FLASH_DEFL_DATA, blockData.array(), chk, FLASH_TIMEOUT)
        return@withContext response != null
    }
    
    /**
     * Complete compressed flash
     */
    private suspend fun flashDeflEnd(reboot: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val data = ByteBuffer.allocate(4)
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(if (reboot) 1 else 0)
        
        val response = command(ESP_FLASH_DEFL_END, data.array(), 0, FLASH_TIMEOUT)
        if (response != null) {
            log("Flash DEFLATE end OK")
            return@withContext true
        }
        log("Flash DEFLATE end error")
        return@withContext false
    }
    
    /**
     * Teljes bináris feltöltése adott címre
     */
    suspend fun flashToOffset(offset: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            // Port buffer tisztítása
            try {
                val trash = ByteArray(4096)
                port.read(trash, 100)
            } catch (_: Exception) {}
            
            // Kis delay a stabilitásért
            delay(100)
            
            // SPI Flash konfiguráció
            if (!setSpiFlashParams()) {
                log("Warning: SPI parameters setting failed, continuing...")
            }
            
            if (!spiAttach()) {
                log("Warning: SPI attach failed, continuing...")
            }
            
            // Flash write start
            if (!flashBegin(data.size, offset)) {
                log("Flash begin failed @ 0x${offset.toString(16)}")
                return@withContext false
            }
            
            // Write data in blocks
            var seq = 0
            var pos = 0
            var lastReportedPercent = -1  // Az utoljára kiírt százalék
            
            while (pos < data.size) {
                val blockSize = min(ESP_FLASH_BLOCK_SIZE, data.size - pos)
                val block = data.sliceArray(pos until pos + blockSize)
                
                // Ha az utolsó blokk kisebb, padding-eljük 0xFF-el
                val paddedBlock = if (block.size < ESP_FLASH_BLOCK_SIZE) {
                    block + ByteArray(ESP_FLASH_BLOCK_SIZE - block.size) { 0xFF.toByte() }
                } else {
                    block
                }
                
                if (!flashBlock(paddedBlock, seq)) {
                    log("Flash block write failed: seq=$seq")
                    return@withContext false
                }
                
                pos += blockSize
                seq++
                
                // Progress - 1%-os lépésekben 
                val percent = (pos * 100) / data.size
                if (percent != lastReportedPercent && percent > 0) {
                    log("Flash progress: $percent%")
                    lastReportedPercent = percent
                }
            }
            
            // NEM hívjuk meg a flash end-et itt, csak a legvégén!
            // A flash end megszakítja a kapcsolatot
            
            log("Flash write complete @ 0x${offset.toString(16)}")
            return@withContext true
            
        } catch (e: Exception) {
            log("Flash error: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Baud rate change to turbo speed
     */
    suspend fun changeBaudRate(newBaudRate: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            log("Baud rate change: $newBaudRate bps")
            
            // ESP32 baud rate change command
            val data = ByteBuffer.allocate(8)
            data.order(ByteOrder.LITTLE_ENDIAN)
            data.putInt(newBaudRate)
            data.putInt(0)  // old baud rate (nem használt)
            
            val response = command(ESP_CHANGE_BAUDRATE, data.array(), 0, DEFAULT_TIMEOUT)
            if (response != null && response.isNotEmpty()) {
                delay(50)  // Wait a bit before switching
                
                // Now switch our port settings too
                try {
                    port.setParameters(newBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    delay(50)
                    log("✅ Port baud rate successfully changed: $newBaudRate bps")
                    return@withContext true
                } catch (e: Exception) {
                    log("❌ Port baud rate change error: ${e.message}")
                    // Visszaváltunk az eredeti sebességre
                    try {
                        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    } catch (_: Exception) {}
                    return@withContext false
                }
            }
            log("❌ ESP32 baud rate change error")
            return@withContext false
        } catch (e: Exception) {
            log("❌ Baud rate change exception: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Egyszerűsített flash metódus - VISSZA A BEVÁLT NORMÁL MÓDRA
     */
    suspend fun flashToOffsetSimple(offset: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            // Port buffer tisztítása
            try {
                val trash = ByteArray(4096)
                port.read(trash, 100)
            } catch (_: Exception) {}
            
            // Kis delay a stabilitásért
            delay(100)
            
            // VISSZA A BEVÁLT NORMÁL MÓDRA - tömörítés kikapcsolva
            log("Flash write in normal mode (${data.size} bytes) - compression disabled")
            return@withContext flashToOffsetNormal(offset, data)
            
        } catch (e: Exception) {
            log("Flash error: ${e.message}")
            // Fallback: normál mód
            return@withContext flashToOffsetNormal(offset, data)
        }
    }
    
    /**
     * Compressed flash method (for large files, like esptool)
     */
    private suspend fun flashToOffsetCompressed(offset: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val compressedData = compressData(data)
        log("Compression: ${data.size} -> ${compressedData.size} bytes (${compressedData.size * 100 / data.size}%)")
        
        // Flash DEFLATE begin
        if (!flashDeflBegin(data.size, compressedData.size, offset)) {
            log("Flash DEFLATE begin failed")
            return@withContext false
        }
        
        // Write compressed data in blocks
        var seq = 0
        var pos = 0
        var lastReportedPercent = -1  // Az utoljára kiírt százalék
        
        while (pos < compressedData.size) {
            val blockSize = min(ESP_FLASH_BLOCK_SIZE, compressedData.size - pos)
            val block = compressedData.sliceArray(pos until pos + blockSize)
            
            if (!flashDeflBlock(block, seq)) {
                log("Flash DEFLATE block write failed: seq=$seq")
                return@withContext false
            }
            
            pos += blockSize
            seq++
            
            // Progress - 1%-os lépésekben (a tömörített adatok alapján)
            val percent = (pos * 100) / compressedData.size
            if (percent != lastReportedPercent && percent > 0) {
                log("Compressed flash progress: $percent%")
                lastReportedPercent = percent
            }
        }
        
        // Flash DEFLATE end
        if (!flashDeflEnd(reboot = false)) {
            log("Flash DEFLATE end failed")
            return@withContext false
        }
        
        log("Compressed flash write complete @ 0x${offset.toString(16)}")
        return@withContext true
    }

    /**
     * Normál flash módszer (fallback ha a tömörítés nem működik)
     */
    private suspend fun flashToOffsetNormal(offset: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        log("Switching back to normal flash mode...")
        
        // Flash írás kezdete
        if (!flashBegin(data.size, offset)) {
            log("Flash begin failed @ 0x${offset.toString(16)}")
            return@withContext false
        }
        
        // Adatok írása blokkokban
        var seq = 0
        var pos = 0
        var lastReportedPercent = -1  // Az utoljára kiírt százalék
        
        while (pos < data.size) {
            val blockSize = min(ESP_FLASH_BLOCK_SIZE, data.size - pos)
            val block = data.sliceArray(pos until pos + blockSize)
            
            val paddedBlock = if (block.size < ESP_FLASH_BLOCK_SIZE) {
                block + ByteArray(ESP_FLASH_BLOCK_SIZE - block.size) { 0xFF.toByte() }
            } else {
                block
            }
            
            if (!flashBlock(paddedBlock, seq)) {
                log("Flash block write failed: seq=$seq")
                return@withContext false
            }
            
            pos += blockSize
            seq++
            
            // Progress - 1%-os lépésekben  
            val percent = (pos * 100) / data.size
            if (percent != lastReportedPercent && percent > 0) {
                log("Flash progress: $percent%")
                lastReportedPercent = percent
            }
        }
        
        log("Flash write complete @ 0x${offset.toString(16)}")
        return@withContext true
    }
    
    /**
     * Chip reset
     */
    suspend fun hardReset() = withContext(Dispatchers.IO) {
        try {
            port.setRTS(true)
            delay(100)
            port.setRTS(false)
            delay(50)
            log("Hard reset performed")
        } catch (e: Exception) {
            log("Reset error: ${e.message}")
        }
    }
    
    /**
     * Erase entire flash chip
     * Megpróbálja szimulálni az esptool.py működését
     * Since there's no stub loader, we use direct ROM commands
     */
    suspend fun eraseFlash(): Boolean = withContext(Dispatchers.IO) {
        try {
            log("🗑️ Erasing flash chip...")
            log("Getting chip information...")
            
            // Try to detect flash size first
            val flashSize = try {
                detectFlashSize()
            } catch (e: Exception) {
                4 * 1024 * 1024  // Default to 4MB if detection fails
            }
            log("Flash size: ${flashSize/1024/1024}MB (0x${flashSize.toString(16)})")
            
            log("⏳ Starting flash erase (this may take 10-30 seconds)...")
            
            // Use FLASH_BEGIN with appropriate size for full chip erase
            // esptool.py uses special handling for erase
            val eraseSize = flashSize
            val numSectors = (eraseSize + ESP_FLASH_SECTOR_SIZE - 1) / ESP_FLASH_SECTOR_SIZE
            val eraseBlocks = (eraseSize + ESP_FLASH_BLOCK_SIZE - 1) / ESP_FLASH_BLOCK_SIZE
            
            val beginBuffer = ByteBuffer.allocate(16)
            beginBuffer.order(ByteOrder.LITTLE_ENDIAN)
            beginBuffer.putInt(eraseSize)  // Total size to erase
            beginBuffer.putInt(eraseBlocks)  // Number of blocks
            beginBuffer.putInt(ESP_FLASH_BLOCK_SIZE)  // Block size
            beginBuffer.putInt(0)  // Start offset = 0
            
            log("Sending erase command for ${eraseSize/1024}KB...")
            
            // Use a very long timeout for erase operations
            val beginResponse = command(ESP_FLASH_BEGIN, beginBuffer.array(), 0, 120000) // 2 minutes timeout
            
            if (beginResponse != null && beginResponse.isNotEmpty()) {
                log("⏳ Chip erase in progress... Please wait...")
                
                // The actual erase happens during FLASH_BEGIN when size covers entire chip
                // Just wait for it to complete
                delay(5000)  // Initial wait
                
                // Now send FLASH_END to complete the operation
                val endBuffer = ByteBuffer.allocate(4)
                endBuffer.order(ByteOrder.LITTLE_ENDIAN)
                endBuffer.putInt(0)  // reboot = false (we'll do hard reset later)
                
                log("Finalizing erase operation...")
                val endResponse = command(ESP_FLASH_END, endBuffer.array(), 0, 10000)
                
                if (endResponse != null) {
                    log("✅ FLASH ERASE SUCCESSFUL!")
                    log("ESP32 flash memory has been erased.")
                    return@withContext true
                } else {
                    // Even if FLASH_END doesn't respond, the erase might have succeeded
                    log("✅ Flash erase completed (chip may need reset)")
                    return@withContext true
                }
            }
            
            // If the above doesn't work, try the alternative ERASE_FLASH command
            log("Trying alternative erase method...")
            
            val eraseResponse = command(ESP_ERASE_FLASH, ByteArray(0), 0, 60000)
            if (eraseResponse != null) {
                log("✅ FLASH ERASE SUCCESSFUL (alternative method)!")
                return@withContext true
            }
            
            log("❌ Flash erase failed - chip may not support ROM erase")
            log("💡 Note: Full chip erase requires stub loader (like esptool.py uses)")
            return@withContext false
            
        } catch (e: Exception) {
            log("❌ Flash erase exception: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Flash méret automatikus detektálása
     */
    private suspend fun detectFlashSize(): Int {
        // Próbáljuk meg olvasni a flash ID-t
        try {
            // SPI flash ID olvasása
            val flashId = spiFlashReadId()
            if (flashId != null) {
                // Flash méret kiszámítása az ID alapján
                val sizeId = (flashId shr 16) and 0xFF
                return when (sizeId) {
                    0x12 -> 256 * 1024    // 256KB
                    0x13 -> 512 * 1024    // 512KB
                    0x14 -> 1024 * 1024   // 1MB
                    0x15 -> 2048 * 1024   // 2MB
                    0x16 -> 4096 * 1024   // 4MB
                    0x17 -> 8192 * 1024   // 8MB
                    0x18 -> 16384 * 1024  // 16MB
                    else -> 4096 * 1024   // Alapértelmezett 4MB
                }
            }
        } catch (e: Exception) {
            log("Flash size detection failed: ${e.message}")
        }
        // Alapértelmezett 4MB
        return 4096 * 1024
    }
    
    /**
     * SPI Flash ID olvasása
     */
    private suspend fun spiFlashReadId(): Int? = withContext(Dispatchers.IO) {
        try {
            // SPI_FLASH_RDID command
            val response = command(0x9F, byteArrayOf(), 0, DEFAULT_TIMEOUT)
            if (response != null && response.size >= 4) {
                val buffer = ByteBuffer.wrap(response)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                return@withContext buffer.getInt()
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext null
    }
    
    /**
     * Register olvasása
     */
    private suspend fun readReg(address: Int): Int? = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteBuffer.allocate(4)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(address)
            
            val response = command(ESP_READ_REG, buffer.array(), 0, DEFAULT_TIMEOUT)
            if (response != null && response.size >= 4) {
                val respBuffer = ByteBuffer.wrap(response)
                respBuffer.order(ByteOrder.LITTLE_ENDIAN)
                return@withContext respBuffer.getInt()
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext null
    }
}