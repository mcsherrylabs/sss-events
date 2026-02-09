#!/bin/bash
# Compare two benchmark result files

if [ $# -ne 2 ]; then
    echo "Usage: $0 <baseline.json> <comparison.json>"
    echo ""
    echo "Available results files:"
    ls -1t benchmark-results/*.json 2>/dev/null | head -10 || echo "  (no results files found)"
    exit 1
fi

BASELINE="$1"
COMPARISON="$2"

if [ ! -f "$BASELINE" ]; then
    echo "Error: Baseline file not found: $BASELINE"
    exit 1
fi

if [ ! -f "$COMPARISON" ]; then
    echo "Error: Comparison file not found: $COMPARISON"
    exit 1
fi

# Check if jq is available
if ! command -v jq &> /dev/null; then
    echo "Error: jq is required but not installed."
    echo "Install with: sudo apt-get install jq"
    exit 1
fi

echo "==================================="
echo "Benchmark Comparison"
echo "==================================="
echo "Baseline:   $BASELINE"
echo "Comparison: $COMPARISON"
echo ""

# Create temp files with benchmark scores
TEMP1=$(mktemp)
TEMP2=$(mktemp)

jq -r '.[] | "\(.benchmark)|\(.primaryMetric.score)"' "$BASELINE" | sort > "$TEMP1"
jq -r '.[] | "\(.benchmark)|\(.primaryMetric.score)"' "$COMPARISON" | sort > "$TEMP2"

echo "Performance Changes:"
echo "-----------------------------------"
printf "%-50s %15s %15s %10s\n" "Benchmark" "Baseline" "Current" "Change"
echo "-----------------------------------"

join -t '|' "$TEMP1" "$TEMP2" | while IFS='|' read -r benchmark baseline_score current_score; do
    benchmark_name=$(echo "$benchmark" | rev | cut -d'.' -f1 | rev)

    # Calculate percentage change
    change=$(echo "scale=2; (($current_score - $baseline_score) / $baseline_score) * 100" | bc)

    # Format scores
    baseline_fmt=$(printf "%.2f" "$baseline_score")
    current_fmt=$(printf "%.2f" "$current_score")
    change_fmt=$(printf "%+.1f%%" "$change")

    # Color code the change (if terminal supports it)
    if (( $(echo "$change > 10" | bc -l) )); then
        change_fmt="\033[32m$change_fmt\033[0m"  # Green for >10% improvement
    elif (( $(echo "$change < -10" | bc -l) )); then
        change_fmt="\033[31m$change_fmt\033[0m"  # Red for >10% regression
    fi

    printf "%-50s %15s %15s %10b\n" "$benchmark_name" "$baseline_fmt" "$current_fmt" "$change_fmt"
done

# Cleanup
rm -f "$TEMP1" "$TEMP2"

echo ""
echo "==================================="
