dude.txt <-
  echo "$OUTPUT" > $OUTPUT

babe.txt <- dude.txt
  echo "$OUTPUT" > $OUTPUT
  echo "belle.txt" > belle.txt

belle.txt <- dude.txt
  echo "$OUTPUT" > $OUTPUT

bogus.txt <- babe.txt, belle.txt
  cat $INPUTS > $OUTPUT
  echo "$OUTPUT" > $OUTPUT
