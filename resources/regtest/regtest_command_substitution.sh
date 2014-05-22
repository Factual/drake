#!/bin/bash
# Regression testing for Drake: variable string substitution inside a command substitution
# Relevant URLs:
# https://github.com/Factual/drake/issues/129

source $(dirname $0)/regtest_utils.sh

cleanup() {
  rm -f test_variable.out
}

echo "----------------------------"
echo "TESTS: variable substitution"
echo "----------------------------"

cleanup
run_d regtest_command_substitution_variable.d -a
check "`cat test_variable.out`" '-world-/-world-/-$[VAR]-/-6-'

# All tests passed
echo "ALL PASSED"

# Clean up again
cleanup
