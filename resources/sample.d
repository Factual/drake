BASE=/backup/CPG

; Merge data from all sources
combined.csv <- amazon.csv, walmart.csv [protocol:bash]
  cat ${INPUTS} > $OUTPUT

MYVAR=FIRST_POST!

; Filter out some bad stuff
filtered.csv <- combined.csv [protocol:bash]
  grep -v "Scott’s Cakes" $INPUT > $OUTPUT.tmp
  grep -v "Artem's Frosting" $OUTPUT.tmp > $OUTPUT

; Generate clusters file
clusters <- filtered.csv [protocol:bash]
  java -jar resolve.jar $INPUT $OUTPUT

; Apply UUIDs according to clusters
cpg.json <- filtered.csv, clusters [protocol:ruby]
  rb generate_uuids.rb --data=$INPUT1 --clusters=$INPUT2 --output=$OUTPUT

MYVAR=SECOND_POST!

out1.csv, out2.csv <-
input1, input2 [protocol:ruby]
  rb generate_uuids.rb --data=$INPUT1 --clusters=$INPUT2 --store=$OUTPUT1 --cache=$OUTPUT2

outA.csv,
outB.csv,
outC.csv
<-
inputA,
inputB,
inputC
[protocol:jruby]
  rb generate_uuids.rb --inputs $INPUT1 $INPUT2 $INPUT3 --outputs $OUTPUT1 $OUTPUT2 $OUTPUT3

; Push to production servers
#push <- cpg.json [confirm:true]
