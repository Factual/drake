#!/bin/bash
# Regression testing for Drake: stdout, stderr, stdin

source $(dirname $0)/regtest_utils.sh

run_d regtest_protocol_eval.d -a >$STDOUT
check_grep $STDOUT "2 + 2 = 4"
check_grep $STDOUT "64"
echo "ALL PASSED"
