#!/bin/bash
# Run short version of benchmarks for quick validation
# Results will be written to benchmark-results-short.json

set -e

cd "$(dirname "$0")"

RESULTS_DIR="$(pwd)/benchmark-results"
mkdir -p "$RESULTS_DIR"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_FILE="$RESULTS_DIR/results-short-$TIMESTAMP.json"

echo "Running short benchmarks (< 10 minutes)..."
echo "Results will be written to: $RESULTS_FILE"
echo ""

# Run benchmarks with reduced iterations and time
# -wi 1: 1 warmup iteration
# -w 1: 1 second warmup time
# -i 2: 2 measurement iterations
# -r 1: 1 second measurement time
# -f 1: 1 fork
# -rf json: JSON result format
# -rff: result file

sbt "benchmarks/Jmh/run -wi 1 -w 1 -i 2 -r 1 -f 1 -rf json -rff $RESULTS_FILE"

echo ""
echo "Benchmarks complete!"
echo "Results written to: $RESULTS_FILE"
echo ""
echo "To view results:"
echo "  cat $RESULTS_FILE | jq ."
echo ""
echo "To extract just the scores:"
echo "  cat $RESULTS_FILE | jq '.[] | {benchmark: .benchmark, mode: .mode, score: .primaryMetric.score, unit: .primaryMetric.scoreUnit}'"
