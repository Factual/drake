#!/bin/bash
# Regression testing for Drake: inline shell commands

source $(dirname $0)/regtest_utils.sh

export FILES=dude.txt\ babe.txt\ belle.txt\ bogus.txt

cleanup() {
  for FILE in $FILES
  do
    rm -f $(dirname $0)/$FILE>/dev/null 2>&1
  done
}

echo "-----------"
echo "TESTS: loop"
echo "-----------"

# First cleanup any existing files
cleanup

# Run loop test
run_d regtest_inline_shell.d -a

# Check all results
for FILE in $FILES
do
check_grep $(dirname $0)/$FILE "$FILE"
done
echo "ALL PASSED"

# Clean up again
cleanup

