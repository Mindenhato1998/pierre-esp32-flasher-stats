#!/usr/bin/env python3
"""
MQTT to GitHub Actions bridge
Listens to MQTT flash events and triggers GitHub Actions to update Gist
"""

import json
import time
import paho.mqtt.client as mqtt
import requests
from datetime import datetime
import os

# Configuration
MQTT_BROKER = "0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud"
MQTT_PORT = 8883
MQTT_USERNAME = "pierreflasher"
MQTT_PASSWORD = "Pierre2k23"
MQTT_TOPIC = "pierre/flash/events/+"

# GitHub configuration
GITHUB_REPO = "Mindenhato1998/pierre-esp32-flasher-stats"  # Replace with actual repo
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN", "")  # Set as environment variable

# Flash counter state
flash_counter = {
    "totalFlashes": 0,
    "devices": {},
    "sessions": [],
    "todayFlashes": 0,
    "todayDate": "",
    "recentEvents": [],
    "lastUpdate": ""
}

def load_state():
    """Load counter state from file"""
    global flash_counter
    try:
        if os.path.exists("counter_state.json"):
            with open("counter_state.json", "r") as f:
                flash_counter = json.load(f)
                print(f"✅ Loaded state: {flash_counter['totalFlashes']} total flashes")
    except Exception as e:
        print(f"❌ Error loading state: {e}")

def save_state():
    """Save counter state to file"""
    try:
        with open("counter_state.json", "w") as f:
            json.dump(flash_counter, f, indent=2)
    except Exception as e:
        print(f"❌ Error saving state: {e}")

def trigger_github_action(data):
    """Trigger GitHub Action to update Gist"""
    try:
        url = f"https://api.github.com/repos/{GITHUB_REPO}/dispatches"
        headers = {
            "Authorization": f"token {GITHUB_TOKEN}",
            "Accept": "application/vnd.github.v3+json"
        }

        payload = {
            "event_type": "update-counter",
            "client_payload": {
                "data": data
            }
        }

        response = requests.post(url, headers=headers, json=payload)

        if response.status_code == 204:
            print(f"✅ GitHub Action triggered successfully")
            return True
        else:
            print(f"❌ Failed to trigger GitHub Action: {response.status_code}")
            print(response.text)
            return False

    except Exception as e:
        print(f"❌ Error triggering GitHub Action: {e}")
        return False

def on_connect(client, userdata, flags, rc):
    """Callback when connected to MQTT broker"""
    if rc == 0:
        print("✅ Connected to HiveMQ")
        client.subscribe(MQTT_TOPIC)
        print(f"📡 Subscribed to {MQTT_TOPIC}")
    else:
        print(f"❌ Connection failed with code {rc}")

def on_message(client, userdata, msg):
    """Callback when MQTT message received"""
    global flash_counter

    try:
        payload = msg.payload.decode('utf-8')
        data = json.loads(payload)

        device_id = data.get('deviceId', 'unknown')
        device_name = data.get('deviceName', 'Unknown Device')
        firmware_type = data.get('firmwareType', 'unknown')
        flash_size = data.get('flashSize', 0)
        flash_time = data.get('flashTime', 0)
        timestamp = data.get('timestamp', datetime.now().isoformat())

        print(f"📨 Flash event from {device_name}")

        # Update total counter
        flash_counter['totalFlashes'] += 1

        # Update device counter
        if device_id not in flash_counter['devices']:
            flash_counter['devices'][device_id] = {
                'name': device_name,
                'count': 0,
                'lastFlash': timestamp
            }
        flash_counter['devices'][device_id]['count'] += 1
        flash_counter['devices'][device_id]['lastFlash'] = timestamp

        # Update today's counter
        today = datetime.now().strftime('%Y-%m-%d')
        if flash_counter['todayDate'] != today:
            flash_counter['todayDate'] = today
            flash_counter['todayFlashes'] = 1
        else:
            flash_counter['todayFlashes'] += 1

        # Add to recent events (keep last 20)
        event = {
            'deviceId': device_id,
            'deviceName': device_name,
            'firmwareType': firmware_type,
            'flashSize': flash_size,
            'flashTime': flash_time,
            'timestamp': timestamp
        }
        flash_counter['recentEvents'].insert(0, event)
        flash_counter['recentEvents'] = flash_counter['recentEvents'][:20]

        # Update last update time
        flash_counter['lastUpdate'] = datetime.now().isoformat()

        # Save state
        save_state()

        # Trigger GitHub Action to update Gist
        if GITHUB_TOKEN:
            trigger_github_action(flash_counter)
        else:
            print("⚠️ No GitHub token configured, skipping Gist update")

        print(f"📊 Total flashes: {flash_counter['totalFlashes']}")
        print(f"📊 Today's flashes: {flash_counter['todayFlashes']}")

    except Exception as e:
        print(f"❌ Error processing message: {e}")

def main():
    """Main function"""
    print("🚀 Starting MQTT to GitHub Actions bridge")
    print(f"📊 Repository: {GITHUB_REPO}")
    print(f"🔗 Monitoring topic: {MQTT_TOPIC}")

    # Load existing state
    load_state()

    # Create MQTT client
    client = mqtt.Client()
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.tls_set()

    # Set callbacks
    client.on_connect = on_connect
    client.on_message = on_message

    # Connect to broker
    print("🔌 Connecting to HiveMQ...")
    client.connect(MQTT_BROKER, MQTT_PORT, 60)

    # Start loop
    try:
        client.loop_forever()
    except KeyboardInterrupt:
        print("\n👋 Shutting down...")
        # Final save
        save_state()
        client.disconnect()

if __name__ == "__main__":
    main()