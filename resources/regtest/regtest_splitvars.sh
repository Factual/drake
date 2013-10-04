#!/bin/bash
# Regression testing for Drake: splitting --vars by regex
# Relevant URLs:
# https://github.com/Factual/drake/issues/97

source $(dirname $0)/regtest_utils.sh

export FILE=f1f1f1f1

cleanup() {
  rm -f $(dirname $0)/$FILE>/dev/null 2>&1
}

echo "-----------------"
echo "TESTS: split vars"
echo "-----------------"

# First cleanup any existing files
cleanup

# Run test with default regex
run_d regtest_splitvars.d -a --vars "RANDOMVAR=1,FILE2=$FILE"
check_grep $(dirname $0)/$FILE "$FILE"
cleanup

# Run test with crazy split regex
run_d regtest_splitvars.d -a --split-vars-regex "[a-c]ebopalul[a-z]" --vars "RANDOMVAR=1bebopalulaFILE2=$FILE"
check_grep $(dirname $0)/$FILE "$FILE"
cleanup

echo "ALL PASSED"

