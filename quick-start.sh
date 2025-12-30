#!/bin/bash

echo "🚀 Pierre ESP32 Flash Counter - Quick Start Setup"
echo "================================================"
echo ""

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js first."
    echo "   Visit: https://nodejs.org/"
    exit 1
fi

echo "✅ Node.js detected: $(node --version)"
echo ""

# Check for required environment variables
if [ -z "$GITHUB_TOKEN" ]; then
    echo "⚠️  GITHUB_TOKEN not set. Please set it:"
    echo "   export GITHUB_TOKEN='your_token_here'"
    echo ""
fi

if [ -z "$GIST_ID" ]; then
    echo "⚠️  GIST_ID not set. Please set it:"
    echo "   export GIST_ID='your_gist_id_here'"
    echo ""
fi

# Install dependencies
echo "📦 Installing dependencies..."
npm install

echo ""
echo "✅ Setup complete!"
echo ""
echo "To start the MQTT bridge:"
echo "  npm start"
echo ""
echo "To run in development mode with auto-restart:"
echo "  npm run dev"
echo ""
echo "Make sure to:"
echo "1. Create a GitHub Gist with flash-counter.json"
echo "2. Set GITHUB_TOKEN and GIST_ID environment variables"
echo "3. Update index.html with your GIST_ID"
echo ""
echo "For detailed instructions, see SETUP.md"