#!/bin/bash
#
# Ralph Loop - Automated task processor with retry tracking
#
# Usage: ./ralph.sh <task_file> [max_iterations] [max_attempts]
#   task_file: Required. Path to the task file (e.g., PRD.md, IMPLEMENTATION_PLAN.md)
#   max_iterations: Optional. Maximum number of tasks to process (default: unlimited)
#   max_attempts: Optional. Maximum attempts per task before marking as failed (default: 3)
#
# Example:
#   ./ralph.sh PRD.md           # Process all tasks in PRD.md, 3 attempts per task
#   ./ralph.sh PRD.md 5         # Process maximum of 5 tasks from PRD.md
#   ./ralph.sh PRD.md 10 5      # Process maximum of 10 tasks, 5 attempts per task
#
# Task status markers:
#   [ ]  - Not started
#   [1]  - First attempt in progress
#   [2]  - Second attempt in progress (after failure)
#   [x]  - Successfully completed
#   [f]  - Failed after max_attempts

# Parse command-line arguments
TASK_FILE=${1}
MAX_ITERATIONS=${2:-999999}  # Default to a very large number if not specified
MAX_ATTEMPTS=${3:-3}          # Default to 3 attempts per task

# Check if task file argument is provided
if [ -z "$TASK_FILE" ]; then
    echo "❌ ERROR: Task file not specified"
    echo "Usage: $0 <task_file> [max_iterations] [max_attempts]"
    exit 1
fi

# Total session start time
TOTAL_START=$SECONDS
ITERATION_COUNT=0

# Check if task file exists
if [ ! -f "$TASK_FILE" ]; then
    echo "❌ ERROR: Task file '$TASK_FILE' not found in $(pwd)"
    exit 1
fi

echo "🚀 Ralph Loop started at $(date +%H:%M:%S)"
echo "📁 Task file: $TASK_FILE"
echo "📊 Maximum iterations: $MAX_ITERATIONS"
echo "🔄 Maximum attempts per task: $MAX_ATTEMPTS"
echo "------------------------------------------------"

while grep -qE "^- \[[[:space:]]*[0-9]*[[:space:]]*\]" "$TASK_FILE" && [ $ITERATION_COUNT -lt $MAX_ITERATIONS ]; do
    ITERATION_COUNT=$((ITERATION_COUNT + 1))
    ITERATION_START=$SECONDS

    echo "🛠  ITERATION $ITERATION_COUNT/$MAX_ITERATIONS [$(date +%H:%M:%S)]"

    # Run Claude with error handling
    # Use streaming output for real-time progress visibility
    echo "⏱  Starting Claude..."
    echo "📊 Watch run.log for real-time progress updates"

    claude -p "Read $TASK_FILE and work on EXACTLY ONE task - the FIRST task marked [ ] or [N] where N is a number. Ignore tasks marked [x] (done) or [f] (failed).

STEP 1 - UPDATE ATTEMPT COUNT (DO THIS FIRST):
- If task is [ ], mark it [1]
- If task is [1], mark it [2]
- If task is [2], mark it [3]
- If task is [3], mark it [f] and skip to next task
This MUST happen BEFORE you start working, in case of crashes.

STEP 2 - DO THE WORK:
State which task you are working on, then implement, test, and commit ONLY this task.

STEP 3 - MARK COMPLETE:
If successful, mark the task [x]. If you encounter unresolvable errors, mark it [f].

CRITICAL: After completing this ONE task, you MUST exit immediately. Do NOT work on additional tasks. The script will handle the next task in the next iteration.

IMPORTANT: You are responsible for managing your own time and resources:
- If a task is taking too long (>20 minutes), consider whether you're stuck
- If you encounter errors you cannot resolve, mark the task as [f] for failed
- If you complete the task successfully, mark it as [x]
- Work on ONE task only, then exit gracefully" \
           --dangerously-skip-permissions \
           --verbose \
           --output-format=stream-json 2>&1 | tee ralph.stream.json | while IFS= read -r line; do
        # Parse streaming JSON and extract text content for run.log
        TEXT=$(echo "$line" | jq -r 'select(.type == "assistant") | .message.content[]? | select(.type == "text") | .text // empty' 2>/dev/null)
        if [ -n "$TEXT" ]; then
            echo "[$(date +%H:%M:%S)] $TEXT" >> run.log
        fi
    done

    CLAUDE_EXIT_CODE=$?

    # Check if Claude failed
    if [ $CLAUDE_EXIT_CODE -ne 0 ]; then
        echo "❌ ERROR: Claude exited with code $CLAUDE_EXIT_CODE"
        echo "🔄 Task will be retried on next iteration"
        echo "------------------------------------------------"
        sleep 2
        continue
    fi

    # Calculate times
    ITERATION_DURATION=$((SECONDS - ITERATION_START))
    TOTAL_ELAPSED=$((SECONDS - TOTAL_START))

    # Format times into MM:SS for readability
    ITER_MIN=$((ITERATION_DURATION / 60))
    ITER_SEC=$((ITERATION_DURATION % 60))
    TOTAL_MIN=$((TOTAL_ELAPSED / 60))
    TOTAL_SEC=$((TOTAL_ELAPSED % 60))

    echo "✅ Task finished."
    printf "⏱  Iteration time: %02d:%02d\n" $ITER_MIN $ITER_SEC
    printf "⌛ Total time so far: %02d:%02d\n" $TOTAL_MIN $TOTAL_SEC
    echo "------------------------------------------------"

    sleep 2
done

if [ $ITERATION_COUNT -ge $MAX_ITERATIONS ]; then
    echo "⏹️  Stopped: Maximum iterations ($MAX_ITERATIONS) reached"
else
    echo "🎉 All tasks complete!"
fi
echo "🏁 Completed $ITERATION_COUNT iterations"
echo "🏁 Final Total Duration: $((TOTAL_ELAPSED / 60))m $((TOTAL_ELAPSED % 60))s"
