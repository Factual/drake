HDFS_BASE=hdfs:/tmp/drake-test

BASE=$[HDFS_BASE]

merged_hdfs <- hdfs_1, hdfs_2
  TMPFILE=$(mktemp)
  hadoop fs -cat $[INPUTS] > $TMPFILE
  hadoop fs -rmr $OUTPUT >/dev/null 2>/dev/null
  hadoop fs -copyFromLocal $TMPFILE $OUTPUT

BASE=""

merged_local <- local_1, local_2
  cat $[INPUTS] > $OUTPUT

merged <- merged_local, $[HDFS_BASE]/merged_hdfs
  (cat $INPUT0; hadoop fs -cat $INPUT1) > $OUTPUT

error <- merged
  this-is-an-error
