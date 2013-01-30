#!/bin/bash
# Regression testing for Drake: inputs/outputs

source $(dirname $0)/regtest_utils.sh

run() {
  run_d regtest_inputs_outputs.d -a $@
}

check_missing_input() {
  if run =missing_input_output ||
     grep -qF "should not happen" $(dirname $0)/missing_input_output ||
     ! grep -qF "no input data found in locations" $(dirname $0)/drake.log; then
    echo "FAILED"
    exit -1
  else
    echo "PASSED"
  fi
}

rm $(dirname $0)/missing_input 2>/dev/null
rm $(dirname $0)/missing_input_output 2>/dev/null

echo "--------------------------"
echo "TESTS: missing input data"
echo "--------------------------"
check_missing_input =missing_input_output
# now again when the output file already existing (this case used to crash)
touch $(dirname $0)/missing_input_output
check_missing_input =missing_input_output
# still the same even though specified through a dependency
check_missing_input dep -missing_input
# but this should run, because the dependency will generate missing_input
run dep
check_grep $(dirname $0)/missing_input_output "should not happen"

echo "ALL PASSED"