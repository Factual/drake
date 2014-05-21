VAR="world"
TEXT1=$(echo -$[VAR]-)
TEXT2=$(echo -"$[VAR]"-)
TEXT3=$(echo -'$[VAR]'-)
TEXT4=$(echo -"$(echo $[VAR] | wc -c)"-)

test_variable.out <-
    echo $TEXT1/$TEXT2/$TEXT3/$TEXT4 > $OUTPUT
