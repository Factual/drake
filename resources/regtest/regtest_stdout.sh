#!/bin/bash
# Regression testing for Drake: stdout, stderr, stdin

source $(dirname $0)/regtest_utils.sh

run_no_auto() {
  run_d regtest_stdout.d $@>$STDOUT 2>$STDERR
}

run() {
  run_no_auto -a $@
}

run test1
check_grep $STDOUT "output: .*/test1"
check_grep $STDOUT "STDOUT IS HERE"
check_grep $STDERR "STDERR IS HERE"

echo "bla1"|run test2
check_grep $STDOUT "User input: bla1"

run_no_auto test2<<EOF
Y
bla
EOF
check_grep $STDOUT "User input: bla"

echo "bla2"|run test3
check_grep $STDOUT "[bla2]"

cat $(dirname $0)/regtest_stdout.d|run test3
check_grep $STDOUT "\\[test2 <- regtest_stdout.d\\]"

echo "ALL PASSED"