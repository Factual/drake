; Inputs/outputs regression tests
; TODO(artem)
;   Add tests for no-input/no-output targets

missing_input <-
  touch $[OUTPUT]

missing_input_output <- missing_input
  echo Inputs: $[INPUTS]
  echo should not happen > $[OUTPUT]

dep <- missing_input_output
  # Nothing
