S3_BASE=$[s3base]
BASE=$[S3_BASE]

merged_s3 <- s3_1, s3_2
  TMPFILE0=$(mktemp)
  TMPFILE1=$(mktemp)
  $[s3get] $INPUT0 $TMPFILE0 
  $[s3get] $INPUT1 $TMPFILE1
  cat $TMPFILE1 >> $TMPFILE0
  $[s3put] $TMPFILE0 $OUTPUT
  rm $TMPFILE0 $TMPFILE1

BASE=""

merged_local <- local_1, local_2
  cat $[INPUTS] > $OUTPUT

merged <- merged_local, $[S3_BASE]/merged_s3
  TMPFILE=$(mktemp)
  echo $[s3get] $INPUT1 $TMPFILE --force
  $[s3get] $INPUT1 $TMPFILE --force
  cat $INPUT0 $TMPFILE > $OUTPUT
  rm $TMPFILE

error <- merged
  this-is-an-error
