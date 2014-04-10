; Regression testing for Drake: checkpointing/temp targets
; Relevant URLs:
; https://github.com/Factual/drake/issues/88

~temp1 <- start
  echo -n $INPUTS > $OUTPUT
  echo -n "t1" >> stepsrun
  
~temp2 <- temp1
  echo -n $INPUTS > $OUTPUT
  echo -n "t2" >> stepsrun

~temp3, perm1 <- temp2
  echo -n INPUTS > $OUTPUT0
  echo -n $INPUTS > $OUTPUT1
  echo -n "t3p1" >> stepsrun

perm2 <- perm1
  echo -n $INPUTS > $OUTPUT
  echo -n "p2" >> stepsrun

perm3 <- temp2
  echo -n $INPUTS > $OUTPUT
  echo -n "p3" >> stepsrun

perm4 <- temp3
  echo -n $INPUTS > $OUTPUT
  echo -n "p4" >> stepsrun

