f1 <-
  sleep 2
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT

f2 <- f1
  sleep 2
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT

f3 <- f2
  sleep 2
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT

g1 <-
  sleep 1
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT

g2 <- g1
  sleep 7
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT

g3 <- g2
  sleep 1
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT

h1 <-
  sleep 3
  echo -n "$(basename $OUTPUT)" >> async_order
  echo -n "$OUTPUT" > $OUTPUT
