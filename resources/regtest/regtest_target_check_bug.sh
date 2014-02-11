#!/bin/bash
# Regression testing for Drake: target check but
# This is a bug that manifests itself when a step is run
# and the target is already built, but this was not detected
# at the beginning during predict-steps
#
# Relevant URLs:

source $(dirname $0)/regtest_utils.sh

export FILES=dude.txt\ babe.txt\ belle.txt\ bogus.txt

cleanup() {
  for FILE in $FILES
  do
    rm -f $(dirname $0)/$FILE>/dev/null 2>&1
  done
}

echo "-------------------"
echo "TESTS: target check"
echo "-------------------"

# First cleanup any existing files
cleanup

# Run loop test
run_d regtest_target_check_bug.d -a

# Check results
for FILE in $FILES
do
check_grep $(dirname $0)/$FILE "$FILE"
done
echo "ALL PASSED"

# Clean up again
cleanup

