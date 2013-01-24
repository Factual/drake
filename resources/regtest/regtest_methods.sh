#!/bin/bash
# Regression testing for Drake: methods
# Most tests for methods are implemented as unittests, only some rare
# cases are covered here.

source $(dirname $0)/regtest_utils.sh

run() {
  run_d regtest_methods.d -a $@
}

echo "---------------------"
echo "TESTS: empty commands"
echo "---------------------"
# C4 should work with empty commands...
rm -f $(dirname $0)/out_c4.csv
echo "test">$(dirname $0)/in_c4.csv
echo "A">$(dirname $0)/in_c4.csv.header
run out_c4.csv
check_grep $(dirname $0)/out_c4.csv "test"
check_grep $(dirname $0)/out_c4.csv.header "A"

# ...but shell shouldn't
touch $(dirname $0)/in_shell.csv
run out_shell.csv>/dev/null 2>&1
check_grep $(dirname $0)/drake.log "requires non-empty commands"

echo "ALL PASSED"