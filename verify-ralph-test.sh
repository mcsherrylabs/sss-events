#!/bin/bash
#
# Verify ralph-test.md results
#

echo "🔍 Verifying Ralph Test Results"
echo "================================"
echo ""

PASS=0
FAIL=0

# Check TEST-1: immediate success
echo "📋 TEST-1: Immediate Success"
if [ -f "test1-immediate.txt" ] && grep -q "immediate success" test1-immediate.txt; then
    echo "   ✅ test1-immediate.txt exists with correct content"
    ((PASS++))
else
    echo "   ❌ test1-immediate.txt missing or incorrect"
    ((FAIL++))
fi

if grep -q "TEST-1.*\[x\]" ralph-test.md; then
    echo "   ✅ Task marked [x]"
    ((PASS++))
else
    echo "   ❌ Task not marked [x]"
    ((FAIL++))
fi
echo ""

# Check TEST-2: retry once
echo "📋 TEST-2: Fail Once, Then Success"
if [ -f "test2-retry-once.txt" ] && grep -q "succeeded after 1 retry" test2-retry-once.txt; then
    echo "   ✅ test2-retry-once.txt exists with correct content"
    ((PASS++))
else
    echo "   ❌ test2-retry-once.txt missing or incorrect"
    ((FAIL++))
fi

if grep -q "TEST-2.*\[x\]" ralph-test.md; then
    echo "   ✅ Task marked [x]"
    ((PASS++))
else
    echo "   ❌ Task not marked [x]"
    ((FAIL++))
fi
echo ""

# Check TEST-3: retry twice
echo "📋 TEST-3: Fail Twice, Then Success"
if [ -f "test3-retry-twice.txt" ] && grep -q "succeeded after 2 retries" test3-retry-twice.txt; then
    echo "   ✅ test3-retry-twice.txt exists with correct content"
    ((PASS++))
else
    echo "   ❌ test3-retry-twice.txt missing or incorrect"
    ((FAIL++))
fi

if grep -q "TEST-3.*\[x\]" ralph-test.md; then
    echo "   ✅ Task marked [x]"
    ((PASS++))
else
    echo "   ❌ Task not marked [x]"
    ((FAIL++))
fi
echo ""

# Check TEST-4: always fail
echo "📋 TEST-4: Always Fail"
if [ ! -f "test4-always-fail.txt" ]; then
    echo "   ✅ test4-always-fail.txt correctly does not exist"
    ((PASS++))
else
    echo "   ❌ test4-always-fail.txt should not exist"
    ((FAIL++))
fi

if grep -q "TEST-4.*\[f\]" ralph-test.md; then
    echo "   ✅ Task marked [f]"
    ((PASS++))
else
    echo "   ❌ Task not marked [f]"
    ((FAIL++))
fi
echo ""

# Summary
echo "================================"
echo "📊 Results: $PASS passed, $FAIL failed"
echo ""

if [ $FAIL -eq 0 ]; then
    echo "🎉 All tests passed!"
    exit 0
else
    echo "❌ Some tests failed"
    echo ""
    echo "Current task markers:"
    grep "TEST-" ralph-test.md
    exit 1
fi
