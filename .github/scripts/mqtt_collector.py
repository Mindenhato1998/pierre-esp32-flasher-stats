#!/usr/bin/env python3
"""
ESP32 Stats MQTT Collector for GitHub Actions
Collects MQTT messages from HiveMQ and updates stats-data.json
"""

import os
import sys
import json
import time
import logging
from datetime import datetime, timezone
import paho.mqtt.client as mqtt
import signal

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class ESP32StatsCollector:
    def __init__(self):
        # MQTT Configuration
        self.broker_host = "0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud"
        self.broker_port = 8883
        self.username = os.getenv('HIVEMQ_USERNAME')
        self.password = os.getenv('HIVEMQ_PASSWORD')

        # Topics to subscribe
        self.topics = [
            'pierre/stats/+/+',      # Stats messages
            'pierre/status/+/+',     # Device online/offline
            'pierre/serial/+/+/info' # Device info
        ]

        # Data storage
        self.stats_file = 'stats-data.json'
        self.device_stats = {}
        self.events = []
        self.max_events = 100

        # Connection tracking
        self.connected = False
        self.messages_received = 0
        self.data_changed = False

        # Load existing data
        self.load_existing_data()

        # MQTT Client setup
        self.client = mqtt.Client(client_id=f"server-collector-{int(time.time())}")
        self.client.username_pw_set(self.username, self.password)
        self.client.tls_set()

        # Callbacks
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

    def load_existing_data(self):
        """Load existing stats from JSON file"""
        try:
            if os.path.exists(self.stats_file):
                with open(self.stats_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)

                self.device_stats = data.get('devices', {})
                self.events = data.get('events', [])

                # Convert string dates back to datetime objects for processing
                for device_name, device_data in self.device_stats.items():
                    if 'lastSeen' in device_data:
                        device_data['lastSeen'] = device_data['lastSeen']

                for event in self.events:
                    if 'timestamp' in event:
                        event['timestamp'] = event['timestamp']

                logger.info(f"Loaded {len(self.device_stats)} devices, {len(self.events)} events")
            else:
                logger.info("No existing stats file found, starting fresh")
        except Exception as e:
            logger.error(f"Error loading existing data: {e}")

    def save_data(self):
        """Save current stats to JSON file"""
        if not self.data_changed:
            logger.info("No data changes, skipping save")
            return

        try:
            data = {
                "devices": self.device_stats,
                "events": self.events[:self.max_events],  # Keep only latest events
                "lastUpdate": datetime.now(timezone.utc).isoformat(),
                "version": "1.0"
            }

            with open(self.stats_file, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2, ensure_ascii=False)

            logger.info(f"Stats saved: {len(self.device_stats)} devices, {len(self.events)} events")
            self.data_changed = False
            return True
        except Exception as e:
            logger.error(f"Error saving data: {e}")
            return False

    def on_connect(self, client, userdata, flags, rc):
        """Called when MQTT client connects"""
        if rc == 0:
            self.connected = True
            logger.info("✅ Connected to HiveMQ")

            # Subscribe to topics
            for topic in self.topics:
                client.subscribe(topic, qos=0)
                logger.info(f"📡 Subscribed to {topic}")
        else:
            logger.error(f"❌ Failed to connect to MQTT broker, return code {rc}")

    def on_disconnect(self, client, userdata, rc):
        """Called when MQTT client disconnects"""
        self.connected = False
        logger.info("📴 Disconnected from MQTT broker")

    def on_message(self, client, userdata, msg):
        """Process received MQTT message"""
        try:
            topic = msg.topic
            payload = msg.payload.decode('utf-8')
            self.messages_received += 1

            logger.info(f"📨 [{self.messages_received}] {topic}: {payload}")

            if topic.startswith('pierre/stats/'):
                self.handle_stats_message(topic, payload)
            elif topic.startswith('pierre/status/'):
                self.handle_status_message(topic, payload)
            elif '/info' in topic:
                self.handle_info_message(topic, payload)

        except Exception as e:
            logger.error(f"Error processing message {topic}: {e}")

    def handle_stats_message(self, topic, payload):
        """Handle stats messages (flash/erase operations)"""
        try:
            # Topic format: pierre/stats/{deviceId}/{operation}
            parts = topic.split('/')
            if len(parts) < 4:
                return

            device_id = parts[2]
            operation = parts[3]  # flash or erase

            data = json.loads(payload)
            device_name = data.get('device_name', f'Device {device_id}')
            event_type = data.get('event', '')

            # Initialize device if not exists
            if device_name not in self.device_stats:
                self.device_stats[device_name] = {
                    'flashCount': 0,
                    'eraseCount': 0,
                    'lastSeen': datetime.now(timezone.utc).isoformat(),
                    'online': False,
                    'lastDeviceId': device_id,
                    'appVersion': 'unknown'
                }

            device = self.device_stats[device_name]
            device['lastSeen'] = datetime.now(timezone.utc).isoformat()
            device['lastDeviceId'] = device_id

            if data.get('app_version'):
                device['appVersion'] = data['app_version']

            # Update counters
            if operation == 'flash' and event_type == 'flash_success':
                device['flashCount'] += 1
                self.add_event('flash', f'{device_name} completed flash operation', device_name)
                logger.info(f"📊 {device_name} flash count: {device['flashCount']}")

            elif operation == 'erase' and event_type == 'erase_success':
                device['eraseCount'] += 1
                self.add_event('erase', f'{device_name} completed erase operation', device_name)
                logger.info(f"📊 {device_name} erase count: {device['eraseCount']}")

            elif event_type == 'device_online':
                self.add_event('info', f'{device_name} connected', device_name)

            self.data_changed = True

        except json.JSONDecodeError:
            logger.error(f"Invalid JSON in stats message: {payload}")
        except Exception as e:
            logger.error(f"Error handling stats message: {e}")

    def handle_status_message(self, topic, payload):
        """Handle device online/offline status"""
        try:
            # Topic format: pierre/status/{deviceId}/{online|offline}
            parts = topic.split('/')
            if len(parts) < 4:
                return

            device_id = parts[2]
            status = parts[3]  # online or offline
            is_online = (status == 'online')

            # Find device by deviceId and update status
            for device_name, device_data in self.device_stats.items():
                if device_data.get('lastDeviceId') == device_id:
                    if device_data['online'] != is_online:
                        device_data['online'] = is_online
                        device_data['lastSeen'] = datetime.now(timezone.utc).isoformat()

                        status_msg = "came online" if is_online else "went offline"
                        self.add_event('info', f'{device_name} {status_msg}', device_name)
                        logger.info(f"📱 {device_name} is now {'online' if is_online else 'offline'}")
                        self.data_changed = True
                    break

        except Exception as e:
            logger.error(f"Error handling status message: {e}")

    def handle_info_message(self, topic, payload):
        """Handle device info messages"""
        try:
            # Topic format: pierre/serial/{deviceId}/{sessionId}/info
            # Payload format: "Device Name|BatteryLevel"
            parts = topic.split('/')
            if len(parts) < 4:
                return

            device_id = parts[2]

            if '|' in payload:
                device_name, battery = payload.split('|', 1)

                # Update or create device entry
                if device_name not in self.device_stats:
                    self.device_stats[device_name] = {
                        'flashCount': 0,
                        'eraseCount': 0,
                        'lastSeen': datetime.now(timezone.utc).isoformat(),
                        'online': False,
                        'lastDeviceId': device_id,
                        'appVersion': 'unknown'
                    }

                device = self.device_stats[device_name]
                device['lastSeen'] = datetime.now(timezone.utc).isoformat()
                device['lastDeviceId'] = device_id

                logger.info(f"📱 Device info: {device_name} (Battery: {battery}%)")
                self.data_changed = True

        except Exception as e:
            logger.error(f"Error handling info message: {e}")

    def add_event(self, event_type, message, device_name):
        """Add event to the events list"""
        event = {
            'type': event_type,
            'message': message,
            'deviceName': device_name,
            'timestamp': datetime.now(timezone.utc).isoformat()
        }

        self.events.insert(0, event)  # Add to beginning

        # Keep only latest events
        if len(self.events) > self.max_events:
            self.events = self.events[:self.max_events]

    def collect_for_duration(self, duration_seconds=240):  # 4 minutes
        """Collect messages for specified duration"""
        logger.info(f"🚀 Starting MQTT collection for {duration_seconds} seconds...")

        # Connect to broker
        try:
            self.client.connect(self.broker_host, self.broker_port, 60)
            self.client.loop_start()

            # Wait for connection
            timeout = 10
            while not self.connected and timeout > 0:
                time.sleep(1)
                timeout -= 1

            if not self.connected:
                logger.error("❌ Failed to connect to MQTT broker within timeout")
                return False

            # Collect messages for specified duration
            start_time = time.time()
            while time.time() - start_time < duration_seconds:
                time.sleep(1)

                # Log progress every 30 seconds
                elapsed = int(time.time() - start_time)
                if elapsed % 30 == 0 and elapsed > 0:
                    logger.info(f"⏱️ Progress: {elapsed}s elapsed, {self.messages_received} messages received")

            logger.info(f"✅ Collection completed: {self.messages_received} messages received")

            # Disconnect
            self.client.loop_stop()
            self.client.disconnect()

            # Save data if any changes
            return self.save_data()

        except Exception as e:
            logger.error(f"❌ Error during collection: {e}")
            return False

def main():
    """Main function"""
    logger.info("🚀 ESP32 Stats Collector starting...")

    # Validate environment variables
    if not os.getenv('HIVEMQ_USERNAME') or not os.getenv('HIVEMQ_PASSWORD'):
        logger.error("❌ Missing HIVEMQ credentials in environment variables")
        sys.exit(1)

    # Create collector and run
    collector = ESP32StatsCollector()

    # Collect for 4 minutes (GitHub Actions has 5 minute cron limit)
    success = collector.collect_for_duration(240)

    if success:
        logger.info("✅ Stats collection completed successfully")
        sys.exit(0)
    else:
        logger.error("❌ Stats collection failed")
        sys.exit(1)

if __name__ == "__main__":
    main()