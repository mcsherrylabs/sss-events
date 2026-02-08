#!/bin/bash
# Run full benchmarks with all iterations
# Results will be written to benchmark-results-full.json

set -e

cd "$(dirname "$0")"

RESULTS_DIR="$(pwd)/benchmark-results"
mkdir -p "$RESULTS_DIR"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_FILE="$RESULTS_DIR/results-full-$TIMESTAMP.json"

echo "Running FULL benchmarks (this will take a while)..."
echo "Results will be written to: $RESULTS_FILE"
echo ""

# Run benchmarks with default JMH settings from annotations
# -rf json: JSON result format
# -rff: result file

sbt "benchmarks/Jmh/run -rf json -rff $RESULTS_FILE"

echo ""
echo "Benchmarks complete!"
echo "Results written to: $RESULTS_FILE"
echo ""
echo "To view results:"
echo "  cat $RESULTS_FILE | jq ."
echo ""
echo "To extract just the scores:"
echo "  cat $RESULTS_FILE | jq '.[] | {benchmark: .benchmark, mode: .mode, score: .primaryMetric.score, unit: .primaryMetric.scoreUnit}'"
