#!/bin/bash
# Regression testing for Drake: inline shell commands
# Relevant URLs:
# https://github.com/Factual/drake/issues/63
# https://github.com/Factual/drake/pull/85

source $(dirname $0)/regtest_utils.sh

export FILES=dude.txt\ babe.txt\ belle.txt\ bogus.txt

cleanup() {
  for FILE in $FILES
  do
    rm -f $(dirname $0)/$FILE>/dev/null 2>&1
    rm -f date1
    rm -f date2
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

# Make sure memoized shell runs do not prevent running a shell command multiple times
# https://github.com/Factual/drake/issues/118

echo "-----------"
echo "TESTS: memo"
echo "-----------"

run_d regtest_inline_shell_memo.d -a
check_not_equal `cat date1` `cat date2`

echo "ALL PASSED"


# Clean up again
cleanup

