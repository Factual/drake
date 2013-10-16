#!/bin/bash
# Regression test for Drake involving S3
# Should be run from this script's directory

# This test assumes that you have s3cmd installed, with
# a configuration file containing your aws credentials
# found at ~/.s3cfs, and that you have access to the 
# civ-test-drake bucket. 
source $(dirname $0)/regtest_utils.sh
export bucket="civ-test-drake"

# do we have access to s3
if s3cmd ls s3://$bucket >/dev/null 2>&1; then
  echo s3cmd binary and access to bucket detected. Including S3 tests...
  export prefix="test"
  export s3base="s3://$bucket/$prefix"
  export s3get="s3cmd get --force"
  export s3put="s3cmd put"
  export s3rm="s3cmd del --recursive"
else
  echo s3cmd binary not found. Skipping S3 tests...
  export prefix=""
  export s3base=""
  export s3get="cp"
  export s3put="cp"
  export s3rm="rm -rf"  
fi

S3_TEST_DIR="${s3base}"

create_local_file_full_path() {
  echo -n "$2" > $1
}

create_local_file() {
  create_local_file_full_path "$(dirname $0)/$1" "$2"
}

create_s3_file() {
  $s3rm "$1" >/dev/null 2>/dev/null
  TMPFILE="$(mktemp -p /tmp)"
  create_local_file_full_path $TMPFILE "$2"
  $s3put $TMPFILE "$1" 2>/dev/null
  rm $TMPFILE
}

check_local() {
  check "$(cat $(dirname $0)/$1)" "$2"
}

check_s3() {
  TMPFILE="$(mktemp -p /tmp)"
  $s3get $1 $TMPFILE
  check "$(cat $TMPFILE $1 2>/dev/null)" "$2"
  rm $TMPFILE
}

check_targets() {
  check_grep $(dirname $0)/drake.log "Done ($1 steps run)." "Should have run $1 targets"
}

run_targets() {
  run_d regtest_s3.d -a -s ~/.s3cfg $@
}

run() {
  run_targets merged
}

# Set up
echo Setting up...

# Delete
$s3rm ${S3_TEST_DIR}/ >/dev/null 2>/dev/null
rm local_1 local_2 merged_local merged 2>/dev/null
# Create
create_s3_file $S3_TEST_DIR/s3_1 S1
create_s3_file $S3_TEST_DIR/s3_2 S2
create_local_file local_1 L1
create_local_file local_2 L2

echo "-------------"
echo "TEST: Run all"
echo "-------------"
run
check_targets 3
check_local merged_local "L1L2"
check_s3 ${S3_TEST_DIR}/merged_s3 "S1S2"
check_local merged "L1L2S1S2"

echo "------------------"
echo "TEST: merged_local"
echo "------------------"
create_local_file merged_local "LL"
run
check_targets 1
check_local merged_local "LL"
check_s3 ${S3_TEST_DIR}/merged_s3 "S1S2"
check_local merged "LLS1S2"

echo "-----------------"
echo "TEST: merged_s3"
echo "-----------------"
create_s3_file ${S3_TEST_DIR}/merged_s3 "SS"
run
check_targets 1
check_local merged_local "LL"
check_s3 ${S3_TEST_DIR}/merged_s3 "SS"
check_local merged "LLSS"

echo "-------------------"
echo "TEST: change s3_2"
echo "-------------------"
create_s3_file ${S3_TEST_DIR}/s3_2 "s2"
run
check_targets 2
check_local merged_local "LL"
check_s3 ${S3_TEST_DIR}/merged_s3 "S1s2"
check_local merged "LLS1s2"

echo "--------------------"
echo "TEST: change local_2"
echo "--------------------"
create_local_file local_2 "l2"
run
check_targets 2
check_local merged_local "L1l2"
check_s3 ${S3_TEST_DIR}/merged_s3 "S1s2"
check_local merged "L1l2S1s2"

echo "--------------------"
echo "TEST: failed command"
echo "--------------------"
# will try to build all targets including 'error'
if run_targets; then
  echo "FAIL: should have failed!"
  exit 1
fi
echo "PASS"

echo "ALL PASSED"
