#!/usr/bin/env python3
import html
import sys

# Read the Groovy script
with open('LogicMonitor_Portal_Alert_Statistics_COLLECT.groovy', 'r') as f:
    script_content = f.read()

# XML encode the script
encoded = html.escape(script_content, quote=True)

# Write the encoded version
with open('encoded_script.txt', 'w') as f:
    f.write(encoded)

print("Script encoded and saved to encoded_script.txt")