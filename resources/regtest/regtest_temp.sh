#!/bin/bash
# Regression testing for Drake: checkpointing/temp targets
# Relevant URLs:
# https://github.com/Factual/drake/issues/88

source $(dirname $0)/regtest_utils.sh

export FILES=start\ stepsrun\ temp1\ temp2\ temp3\ perm1\ perm2\ perm3\ perm4

cleanup() {
  for FILE in $FILES
  do
    rm -f $(dirname $0)/$FILE>/dev/null 2>&1
  done
}

checkfiles() {
check_does_not_exist temp1 temp2 temp3
check_exists perm1 perm2 perm3 perm4
}

echo "------------------"
echo "TESTS: checkpoints"
echo "------------------"

# First cleanup any existing files
cleanup

# Run script from scratch, make sure temp files are deleted
touch start
run_d regtest_temp.d -a
check_grep stepsrun "t1t2t3p1p2p3p4"
checkfiles

# Run script again, make sure nothing happens even though temp files are deleted
rm stepsrun
run_d regtest_temp.d -a
check_does_not_exist stepsrun
checkfiles

# Touch start, run script again, make sure all files are built
sleep 2 # get around the 1 second accuracy of lastModified() in Java
#rm stepsrun
touch start
run_d regtest_temp.d -a
check_grep stepsrun "t1t2t3p1p2p3p4"
checkfiles

# Remove perm2, make sure only perm2 step is run
sleep 2 # get around the 1 second accuracy of lastModified() in Java
rm stepsrun
rm perm2
run_d regtest_temp.d -a
check_grep stepsrun "p2"
checkfiles

# Touch temp2, make sure temp3, perm1, perm2, perm3, perm4 are built
sleep 2 # get around the 1 second accuracy of lastModified() in Java
rm stepsrun
touch temp2
run_d regtest_temp.d -a
check_grep stepsrun "t3p1p2p3p4"
checkfiles

# Touch temp3, make sure perm4 is run
sleep 2 # get around the 1 second accuracy of lastModified() in Java
rm stepsrun
touch temp3
run_d regtest_temp.d -a
check_grep stepsrun "p4"
checkfiles

# Touch start, build only perm3, make sure temp1, temp2, perm3 are run
sleep 2 # get around the 1 second accuracy of lastModified() in Java
rm stepsrun
touch start
run_d regtest_temp.d -a perm3
check_grep stepsrun "t1t2p3"
checkfiles

# Cleanup, build only temp3, make sure temp1, temp2, temp3, perm1 are run
# Also make sure temp3 still exists
cleanup
touch start
run_d regtest_temp.d -a temp3 
check_grep stepsrun "t1t2t3p1"
check_does_not_exist temp1 temp2
check_exists perm1 temp3

# Final cleanup 
cleanup

echo "ALL PASSED"

