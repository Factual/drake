#!/bin/bash
# Regression testing for Drake: inline shell commands
# Relevant URLs:
# https://github.com/Factual/drake/issues/63
# https://github.com/Factual/drake/pull/85

source $(dirname $0)/regtest_utils.sh

export FILES=f1\ f2\ f3\ g1\ g2\ g3\ h1\ async_order

cleanup() {
  for FILE in $FILES
  do
    rm -f $(dirname $0)/$FILE>/dev/null 2>&1
  done
}

echo "------------"
echo "TESTS: async"
echo "------------"

# First cleanup any existing files
cleanup

# Run test with concurrency = 1, check order
run_d regtest_async.d -a -j 1
check_grep $(dirname $0)/async_order "f1f2f3g1g2g3h1"
cleanup

# Run test with concurrency = 2, check order
run_d regtest_async.d -a -j 2
check_grep $(dirname $0)/async_order "g1f1f2f3g2g3h1"
cleanup

# Run test with concurrency = 3, check order
run_d regtest_async.d -a -j 3
check_grep $(dirname $0)/async_order "g1f1h1f2f3g2g3"
cleanup

echo "ALL PASSED"

