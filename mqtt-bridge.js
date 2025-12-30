#!/usr/bin/env node

/**
 * MQTT to GitHub Gist Bridge
 * Listens to flash events and updates GitHub Gist
 */

const mqtt = require('mqtt');
const https = require('https');
const fs = require('fs');

// Configuration
const MQTT_BROKER = 'mqtts://0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud:8883';
const MQTT_USERNAME = 'pierreflasher';
const MQTT_PASSWORD = 'Pierre2k23';
const MQTT_TOPIC = 'pierre/flash/events/+';

// GitHub configuration (set as environment variables)
const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const GIST_ID = process.env.GIST_ID;

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

// Load state from file if exists
function loadState() {
    try {
        if (fs.existsSync('counter_state.json')) {
            flashCounter = JSON.parse(fs.readFileSync('counter_state.json', 'utf8'));
            console.log(`✅ Loaded state: ${flashCounter.totalFlashes} total flashes`);
        }
    } catch (error) {
        console.error('❌ Error loading state:', error);
    }
}

// Save state to file
function saveState() {
    try {
        fs.writeFileSync('counter_state.json', JSON.stringify(flashCounter, null, 2));
    } catch (error) {
        console.error('❌ Error saving state:', error);
    }
}

// Update GitHub Gist
function updateGist() {
    return new Promise((resolve, reject) => {
        const data = JSON.stringify({
            files: {
                'flash-counter.json': {
                    content: JSON.stringify(flashCounter, null, 2)
                }
            }
        });

        const options = {
            hostname: 'api.github.com',
            port: 443,
            path: `/gists/${GIST_ID}`,
            method: 'PATCH',
            headers: {
                'Authorization': `token ${GITHUB_TOKEN}`,
                'Accept': 'application/vnd.github.v3+json',
                'Content-Type': 'application/json',
                'Content-Length': data.length,
                'User-Agent': 'Pierre-Flash-Counter'
            }
        };

        const req = https.request(options, (res) => {
            let body = '';
            res.on('data', (chunk) => body += chunk);
            res.on('end', () => {
                if (res.statusCode === 200) {
                    console.log('✅ Gist updated successfully');
                    resolve();
                } else {
                    console.error(`❌ Failed to update Gist: ${res.statusCode}`);
                    console.error(body);
                    reject(new Error(`HTTP ${res.statusCode}`));
                }
            });
        });

        req.on('error', reject);
        req.write(data);
        req.end();
    });
}

// Connect to MQTT broker
console.log('🚀 Starting MQTT to GitHub Gist bridge');
console.log(`📊 Gist ID: ${GIST_ID}`);
console.log(`🔗 Monitoring topic: ${MQTT_TOPIC}`);

// Load existing state
loadState();

// Create MQTT client
const client = mqtt.connect(MQTT_BROKER, {
    username: MQTT_USERNAME,
    password: MQTT_PASSWORD,
    clientId: 'mqtt_bridge_' + Math.random().toString(36).substring(7),
    clean: true,
    connectTimeout: 30000,
    reconnectPeriod: 1000
});

// Handle connection
client.on('connect', () => {
    console.log('✅ Connected to HiveMQ');
    client.subscribe(MQTT_TOPIC, (err) => {
        if (!err) {
            console.log(`📡 Subscribed to ${MQTT_TOPIC}`);
        } else {
            console.error('❌ Subscribe error:', err);
        }
    });
});

// Handle messages
client.on('message', async (topic, message) => {
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

        // Add to recent events (keep last 20)
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

        // Update last update time
        flashCounter.lastUpdate = new Date().toISOString();

        // Save state locally
        saveState();

        // Update GitHub Gist
        if (GITHUB_TOKEN && GIST_ID) {
            await updateGist();
        } else {
            console.log('⚠️ No GitHub credentials configured, skipping Gist update');
        }

        console.log(`📊 Total flashes: ${flashCounter.totalFlashes}`);
        console.log(`📊 Today's flashes: ${flashCounter.todayFlashes}`);

    } catch (error) {
        console.error('❌ Error processing message:', error);
    }
});

// Handle errors
client.on('error', (error) => {
    console.error('❌ MQTT error:', error);
});

// Handle disconnection
client.on('close', () => {
    console.log('🔌 Disconnected from MQTT broker');
});

// Graceful shutdown
process.on('SIGINT', () => {
    console.log('\n👋 Shutting down...');
    saveState();
    client.end();
    process.exit(0);
});