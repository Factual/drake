#!/bin/bash
# Regression test for Drake involving HDFS
# Should be run from this script's directory
# Should be run on dev0x, not locally

source $(dirname $0)/regtest_utils.sh

HADOOP_TEST_DIR=/tmp/drake-test

create_local_file_full_path() {
  echo -n "$2" > $1
}

create_local_file() {
  create_local_file_full_path "$(dirname $0)/$1" "$2"
}

create_hdfs_file() {
  hadoop fs -rmr "$1" >/dev/null 2>/dev/null
  TMPFILE="$(mktemp)"
  create_local_file_full_path $TMPFILE "$2"
  hadoop fs -copyFromLocal $TMPFILE "$1" 2>/dev/null
}

check_local() {
  check "$(cat $(dirname $0)/$1)" "$2"
}

check_hdfs() {
  check "$(hadoop fs -cat $1 2>/dev/null)" "$2"
}

check_targets() {
  check_grep $(dirname $0)/drake.log "Done ($1 steps run)." "Should have run $1 targets"
}

run_targets() {
  run_d regtest_fs.d -a $@
}

run() {
  run_targets merged
}

# Set up
echo Setting up...

# Delete
hadoop fs -rmr $HADOOP_TEST_DIR >/dev/null 2>/dev/null
rm local_1 local_2 merged_local merged 2>/dev/null

# Create
if ! hadoop fs -mkdir $HADOOP_TEST_DIR 2>/dev/null; then
  echo "Cannot create HDFS directory"
  exit 1
fi
create_hdfs_file $HADOOP_TEST_DIR/hdfs_1 H1
create_hdfs_file $HADOOP_TEST_DIR/hdfs_2 H2
create_local_file local_1 L1
create_local_file local_2 L2

echo "-------------"
echo "TEST: Run all"
echo "-------------"
run
check_targets 3
check_local merged_local "L1L2"
check_hdfs /tmp/drake-test/merged_hdfs "H1H2"
check_local merged "L1L2H1H2"

echo "------------------"
echo "TEST: merged_local"
echo "------------------"
create_local_file merged_local "LL"
run
check_targets 1
check_local merged_local "LL"
check_hdfs /tmp/drake-test/merged_hdfs "H1H2"
check_local merged "LLH1H2"

echo "-----------------"
echo "TEST: merged_hdfs"
echo "-----------------"
create_hdfs_file $HADOOP_TEST_DIR/merged_hdfs "HH"
run
check_targets 1
check_local merged_local "LL"
check_hdfs /tmp/drake-test/merged_hdfs "HH"
check_local merged "LLHH"

echo "-------------------"
echo "TEST: change hdfs_2"
echo "-------------------"
create_hdfs_file $HADOOP_TEST_DIR/hdfs_2 "h2"
run
check_targets 2
check_local merged_local "LL"
check_hdfs /tmp/drake-test/merged_hdfs "H1h2"
check_local merged "LLH1h2"

echo "--------------------"
echo "TEST: change local_2"
echo "--------------------"
create_local_file local_2 "l2"
run
check_targets 2
check_local merged_local "L1l2"
check_hdfs /tmp/drake-test/merged_hdfs "H1h2"
check_local merged "L1l2H1h2"

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