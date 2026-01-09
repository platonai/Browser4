#!/usr/bin/env python3

import os
import sys
import json
import time
import requests
import argparse
from pathlib import Path
from datetime import datetime

def send_command(api_base, command):
    """Send command to server and get command ID"""
    command_endpoint = f"{api_base}/api/commands/plain?mode=async"
    
    print("Sending command to server...")
    
    try:
        response = requests.post(
            command_endpoint,
            data=command,
            headers={"Content-Type": "text/plain"},
            timeout=30
        )
        response.raise_for_status()
        command_id = response.text.strip()
        
        if not command_id:
            raise ValueError("Empty command ID received from server")
            
        return command_id
        
    except requests.RequestException as e:
        print(f"Error: Failed to get command ID from server. {e}", file=sys.stderr)
        sys.exit(1)

def get_final_result(api_base, command_id):
    """Fetch and display the final result"""
    result_url = f"{api_base}/api/commands/{command_id}/result"
    
    print("\n=== FETCHING FINAL RESULT ===")
    
    try:
        response = requests.get(result_url, timeout=30)
        response.raise_for_status()
        
        print("\n=== FINAL RESULT ===")
        print(f"Command ID: {command_id}")
        print(f"Timestamp: {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S')} UTC")
        print("Status: COMPLETED")
        
        # Try to parse as JSON for better formatting
        try:
            if response.headers.get('content-type', '').startswith('application/json'):
                result = response.json()
                print("\nStructured Result:")
                print(json.dumps(result, indent=2, ensure_ascii=False))
            else:
                # Try to parse text as JSON
                result = json.loads(response.text)
                print("\nStructured Result:")
                print(json.dumps(result, indent=2, ensure_ascii=False))
        except (json.JSONDecodeError, ValueError):
            print("\nRaw Result:")
            print(response.text)
        
        print("\n=== END OF RESULT ===")
        
    except requests.RequestException as e:
        print(f"Warning: Failed to fetch final result: {e}")
        print(f"You can manually check the result at: {result_url}")

def process_sse_stream(api_base, command_id):
    """Process Server-Sent Events stream"""
    sse_url = f"{api_base}/api/commands/{command_id}/stream"
    
    print("Connecting to SSE stream...")
    
    try:
        # Create SSE request with appropriate headers
        headers = {
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache"
        }
        
        response = requests.get(sse_url, headers=headers, stream=True, timeout=1800)
        response.raise_for_status()
        
        print("Reading SSE stream...")
        
        is_done = False
        last_update = ""
        timeout_seconds = 900  # 15 minutes
        start_time = time.time()
        
        # Process SSE stream line by line
        for line in response.iter_lines(decode_unicode=True):
            # Check timeout
            elapsed = time.time() - start_time
            if elapsed > timeout_seconds:
                print(f"ERROR: SSE stream timed out after {timeout_seconds} seconds without receiving done signal", file=sys.stderr)
                sys.exit(1)
            
            if line is None or line.strip() == "" or line.startswith(":"):
                continue
                
            # Extract data field
            if line.startswith("data:"):
                data = line[5:].strip()
                
                # Avoid duplicate updates
                if data and data != last_update:
                    print(f"SSE update: {data}")
                    last_update = data
                
                # Check if task is completed
                if '"done"' in data and ':' in data:
                    try:
                        # Try to parse as JSON to check done status
                        json_data = json.loads(data)
                        if json_data.get('done', False):
                            is_done = True
                            print("\nReceived done signal, preparing to exit...")
                            print("Task completed! Fetching final result...")
                            time.sleep(2)  # Wait for server to fully process
                            get_final_result(api_base, command_id)
                            break
                    except json.JSONDecodeError:
                        # Fallback to string matching
                        if '"done": true' in data or '"done":true' in data:
                            is_done = True
                            print("\nReceived done signal, preparing to exit...")
                            print("Task completed! Fetching final result...")
                            time.sleep(2)
                            get_final_result(api_base, command_id)
                            break
            
            # Small delay to avoid excessive CPU usage
            time.sleep(0.05)
        
        if not is_done:
            print("Warning: SSE stream ended but task may not be completed.")
            print("Attempting to fetch result anyway...")
            get_final_result(api_base, command_id)
            
    except requests.RequestException as e:
        print(f"Error during SSE processing: {e}", file=sys.stderr)
        print("Attempting to fetch any available result...")
        get_final_result(api_base, command_id)

def main():
    """Main function"""
    # Natural language command content
    command = """
    Go to https://www.amazon.com/dp/B08PP5MSVB

    After browser launch: clear browser cookies.
    After page load: scroll to the middle.

    Summarize the product.
    Extract: product name, price, ratings.
    Find all links containing /dp/."""
    
    # API configuration
    api_base = "http://localhost:8182"
    
    try:
        # Send command and get ID
        command_id = send_command(api_base, command)
        print(f"Command ID: {command_id}")
        
        # Process SSE stream
        process_sse_stream(api_base, command_id)
        
    except KeyboardInterrupt:
        print("\nScript interrupted by user.")
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    
    print("\nFinished command-sse.py script.")

if __name__ == "__main__":
    main()