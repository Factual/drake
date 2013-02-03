HDFS_BASE=$[prefix]/tmp/drake-test

BASE=$[HDFS_BASE]

merged_hdfs <- hdfs_1, hdfs_2
  TMPFILE=$(mktemp -t tmp)
  $[hadoop_cat] $[INPUTS] > $TMPFILE
  $[hadoop_rm]  $OUTPUT >/dev/null 2>/dev/null
  $[hadoop_cp]  $TMPFILE $OUTPUT

BASE=""

merged_local <- local_1, local_2
  cat $[INPUTS] > $OUTPUT

merged <- merged_local, $[HDFS_BASE]/merged_hdfs
  (cat $INPUT0; $[hadoop_cat] $INPUT1) > $OUTPUT

error <- merged
  this-is-an-error
