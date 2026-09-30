#!/bin/bash

set -e

###############################################
# Configuration
###############################################

PROJECT_DIR="/c/Projects/hibernate.gsheets"

# Leave empty ("") to create a new deployment.
# Otherwise the existing deployment will be updated.
DEPLOYMENT_ID_TO_USE="AKfycbzAi9V4b5WMBU-_lUJxdBeVm--k42Gc_SZ4cw5z8K9khSsWd8-ySQ3Lo_91gkSgSxqX"

###############################################
# Script
###############################################

cd "$PROJECT_DIR"

TIMESTAMP=$(date +"%Y-%m-%d %H:%M:%S")

echo "=================================================="
echo "Project      : $PROJECT_DIR"
echo "Timestamp    : $TIMESTAMP"
echo "=================================================="

echo
echo "Pushing latest code..."
npx clasp push

echo

if [ -n "$DEPLOYMENT_ID_TO_USE" ]; then
    echo "Updating existing deployment..."
    OUTPUT=$(npx clasp deploy \
        --deploymentId "$DEPLOYMENT_ID_TO_USE" \
        -d "$TIMESTAMP")

    DEPLOYMENT_ID="$DEPLOYMENT_ID_TO_USE"
else
    echo "Creating new deployment..."
    OUTPUT=$(npx clasp deploy \
        -d "$TIMESTAMP")

    DEPLOYMENT_ID=$(echo "$OUTPUT" | grep -oE '[A-Za-z0-9_-]{30,}' | head -1)
fi

echo
echo "$OUTPUT"

WEBAPP_URL="https://script.google.com/macros/s/${DEPLOYMENT_ID}/exec"

echo
echo "=================================================="
echo "Deployment ID : $DEPLOYMENT_ID"
echo "Web App URL   : $WEBAPP_URL"
echo "=================================================="

# Copy URL to clipboard
if command -v pbcopy >/dev/null 2>&1; then
    printf "%s" "$WEBAPP_URL" | pbcopy
    echo "Copied URL to clipboard (macOS)."
elif command -v xclip >/dev/null 2>&1; then
    printf "%s" "$WEBAPP_URL" | xclip -selection clipboard
    echo "Copied URL to clipboard (Linux)."
elif command -v clip >/dev/null 2>&1; then
    printf "%s" "$WEBAPP_URL" | clip
    echo "Copied URL to clipboard (Windows)."
else
    echo "Clipboard utility not found."
fi