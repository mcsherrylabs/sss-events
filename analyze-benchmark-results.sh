#!/bin/bash
# Analyze benchmark results from JSON file

if [ $# -eq 0 ]; then
    echo "Usage: $0 <results-file.json>"
    echo ""
    echo "Available results files:"
    ls -1t benchmark-results/*.json 2>/dev/null | head -5 || echo "  (no results files found)"
    exit 1
fi

RESULTS_FILE="$1"

if [ ! -f "$RESULTS_FILE" ]; then
    echo "Error: File not found: $RESULTS_FILE"
    exit 1
fi

echo "==================================="
echo "Benchmark Results Analysis"
echo "==================================="
echo "File: $RESULTS_FILE"
echo ""

# Check if jq is available
if ! command -v jq &> /dev/null; then
    echo "Error: jq is required but not installed."
    echo "Install with: sudo apt-get install jq"
    exit 1
fi

# Summary table
echo "Summary of Results:"
echo "-----------------------------------"
jq -r '.[] | "\(.benchmark | split(".") | last)\t\(.mode)\t\(.primaryMetric.score | tostring | .[0:10])\t\(.primaryMetric.scoreUnit)"' "$RESULTS_FILE" | column -t -s $'\t' -N "Benchmark,Mode,Score,Unit"

echo ""
echo "-----------------------------------"
echo "Detailed Statistics:"
echo "-----------------------------------"

jq -r '.[] | "
Benchmark: \(.benchmark | split(".") | last)
  Score: \(.primaryMetric.score) ± \(.primaryMetric.scoreError) \(.primaryMetric.scoreUnit)
  Samples: \(.primaryMetric.scoreConfidence[0]) - \(.primaryMetric.scoreConfidence[1])
  Percentiles:
    50th: \(.primaryMetric.scorePercentiles."50.0")
    95th: \(.primaryMetric.scorePercentiles."95.0")
    99th: \(.primaryMetric.scorePercentiles."99.0")
"' "$RESULTS_FILE"

echo ""
echo "==================================="
echo "Full JSON available in: $RESULTS_FILE"
echo "==================================="
