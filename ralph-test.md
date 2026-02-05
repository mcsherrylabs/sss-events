# Ralph Test Task List

This task list tests all retry paths in ralph.sh. Each task should complete in under 1 minute.

## Test Tasks

- [x] TEST-1-IMMEDIATE-SUCCESS: Create file `test1-immediate.txt` with content "immediate success". This should succeed on the first try. Mark [x] when done.

- [ ] TEST-2-FAIL-ONCE-THEN-SUCCESS: Read this file (ralph-test.md) and check this task's current marker. If it's [ ] or [1], intentionally fail by running `bash -c "exit 1"`. If it's [2], create file `test2-retry-once.txt` with content "succeeded after 1 retry" and mark [x].

- [ ] TEST-3-FAIL-TWICE-THEN-SUCCESS: Read this file and check this task's marker. If it's [ ] or [1] or [2], intentionally fail by running `bash -c "exit 1"`. If it's [3], create file `test3-retry-twice.txt` with content "succeeded after 2 retries" and mark [x].

- [ ] TEST-4-ALWAYS-FAIL: Intentionally fail by running `bash -c "exit 1"`. This task should fail on all attempts and eventually be marked [f] after 3 attempts.

## Expected Results

After running `./ralph.sh ralph-test.md`:

**Files created:**
- `test1-immediate.txt` - content: "immediate success"
- `test2-retry-once.txt` - content: "succeeded after 1 retry"
- `test3-retry-twice.txt` - content: "succeeded after 2 retries"
- `test4-always-fail.txt` - should NOT exist

**Task markers:**
- TEST-1: [x] - succeeded immediately
- TEST-2: [x] - succeeded on attempt 2
- TEST-3: [x] - succeeded on attempt 3
- TEST-4: [f] - failed after 3 attempts

## Verification Commands

```bash
# Check all test files exist (except test4)
ls -la test*.txt

# Check file contents
cat test1-immediate.txt
cat test2-retry-once.txt
cat test3-retry-twice.txt

# Verify test4 file was NOT created
test -f test4-always-fail.txt && echo "ERROR: test4 should not exist" || echo "CORRECT: test4 does not exist"

# Check final task markers in ralph-test.md
grep "TEST-" ralph-test.md
```
