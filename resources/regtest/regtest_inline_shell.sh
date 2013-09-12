#!/bin/bash
# Regression testing for Drake: inline shell commands

source $(dirname $0)/regtest_utils.sh

run() {
  run_d regtest_methods.d -a $@
}

echo "-----------"
echo "TESTS: loop"
echo "-----------"

# First cleanup any existing files
rm -f $(dirname $0)/dude.txt>/dev/null 2>&1
rm -f $(dirname $0)/babe.txt>/dev/null 2>&1
rm -f $(dirname $0)/belle.txt>/dev/null 2>&1

run_d regtest_inline_shell.d -a

check_grep $(dirname $0)/dude.txt "dude.txt"
check_grep $(dirname $0)/babe.txt "babe.txt"
check_grep $(dirname $0)/belle.txt "belle.txt"

echo "ALL PASSED"
