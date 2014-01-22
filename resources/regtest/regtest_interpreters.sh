#!/bin/bash
# Regression testing for Drake: interpreters

source $(dirname $0)/regtest_utils.sh

echo "-------------------------------------"
echo "TESTS: interpreters (Python, Ruby...)"
echo "-------------------------------------"

echo "test_line" > $(dirname $0)/test
run_d regtest_interpreters.d -a

check_grep $(dirname $0)/shell.out "shell_processed:test_line"
check_grep $(dirname $0)/python.out "python_processed:test_line"
check_grep $(dirname $0)/ruby.out "ruby_processed:test_line"
check_grep $(dirname $0)/R.out "R_processed:test_line"

echo "ALL PASSED"
