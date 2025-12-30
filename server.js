#!/usr/bin/env node

/**
 * Simple server for Pierre ESP32 Flash Counter
 * Provides both web dashboard and counter storage API
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const mqtt = require('mqtt');

// Configuration
const PORT = process.env.PORT || 3000;
const MQTT_BROKER = 'mqtts://0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud:8883';
const MQTT_USERNAME = 'pierreflasher';
const MQTT_PASSWORD = 'Pierre2k23';
const MQTT_TOPIC = 'pierre/flash/events/+';

// Flash counter state
let flashCounter = {
    totalFlashes: 0,
    devices: {},
    sessions: [],
    todayFlashes: 0,
    todayDate: '',
    recentEvents: [],
    lastUpdate: ''
};

// Load saved counter
const COUNTER_FILE = 'flash-counter.json';
if (fs.existsSync(COUNTER_FILE)) {
    try {
        flashCounter = JSON.parse(fs.readFileSync(COUNTER_FILE, 'utf8'));
        console.log(`✅ Loaded counter: ${flashCounter.totalFlashes} total flashes`);
    } catch (error) {
        console.error('❌ Error loading counter:', error);
    }
}

// Save counter to file
function saveCounter() {
    try {
        fs.writeFileSync(COUNTER_FILE, JSON.stringify(flashCounter, null, 2));
    } catch (error) {
        console.error('❌ Error saving counter:', error);
    }
}

// Create MQTT client
const mqttClient = mqtt.connect(MQTT_BROKER, {
    username: MQTT_USERNAME,
    password: MQTT_PASSWORD,
    clientId: 'server_' + Math.random().toString(36).substring(7),
    clean: true
});

mqttClient.on('connect', () => {
    console.log('✅ Connected to HiveMQ');
    mqttClient.subscribe(MQTT_TOPIC, (err) => {
        if (!err) {
            console.log(`📡 Subscribed to ${MQTT_TOPIC}`);
        }
    });
});

mqttClient.on('message', (topic, message) => {
    try {
        const data = JSON.parse(message.toString());

        const deviceId = data.deviceId || 'unknown';
        const deviceName = data.deviceName || 'Unknown Device';
        const firmwareType = data.firmwareType || 'unknown';
        const flashSize = data.flashSize || 0;
        const flashTime = data.flashTime || 0;
        const timestamp = data.timestamp || new Date().toISOString();

        console.log(`📨 Flash event from ${deviceName}`);

        // Update total counter
        flashCounter.totalFlashes++;

        // Update device counter
        if (!flashCounter.devices[deviceId]) {
            flashCounter.devices[deviceId] = {
                name: deviceName,
                count: 0,
                lastFlash: timestamp
            };
        }
        flashCounter.devices[deviceId].count++;
        flashCounter.devices[deviceId].lastFlash = timestamp;

        // Update today's counter
        const today = new Date().toDateString();
        if (flashCounter.todayDate !== today) {
            flashCounter.todayDate = today;
            flashCounter.todayFlashes = 1;
        } else {
            flashCounter.todayFlashes++;
        }

        // Add to recent events
        const event = {
            deviceId,
            deviceName,
            firmwareType,
            flashSize,
            flashTime,
            timestamp
        };

        flashCounter.recentEvents.unshift(event);
        flashCounter.recentEvents = flashCounter.recentEvents.slice(0, 20);

        flashCounter.lastUpdate = new Date().toISOString();

        // Save to file
        saveCounter();

        console.log(`📊 Total: ${flashCounter.totalFlashes} | Today: ${flashCounter.todayFlashes}`);

    } catch (error) {
        console.error('❌ Error processing message:', error);
    }
});

// Create HTTP server
const server = http.createServer((req, res) => {
    // Enable CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    // API endpoint for counter
    if (req.url === '/api/counter' && req.method === 'GET') {
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end(JSON.stringify(flashCounter));
        return;
    }

    // Serve index.html
    if (req.url === '/' || req.url === '/index.html') {
        const indexPath = path.join(__dirname, 'index.html');
        if (fs.existsSync(indexPath)) {
            const html = fs.readFileSync(indexPath, 'utf8');
            res.writeHead(200, {'Content-Type': 'text/html'});
            res.end(html);
        } else {
            res.writeHead(404);
            res.end('Dashboard not found');
        }
        return;
    }

    // 404 for other routes
    res.writeHead(404);
    res.end('Not found');
});

// Start server
server.listen(PORT, () => {
    console.log('🚀 Pierre ESP32 Flash Counter Server');
    console.log(`📊 Dashboard: http://localhost:${PORT}/`);
    console.log(`🔗 API: http://localhost:${PORT}/api/counter`);
    console.log('');
    console.log('✅ Server is running and monitoring flash events!');
});

// Graceful shutdown
process.on('SIGINT', () => {
    console.log('\n👋 Shutting down...');
    saveCounter();
    mqttClient.end();
    server.close();
    process.exit(0);
});