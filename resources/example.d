; our base data directory
BASE=/backup/CPG

; Merge data from all sources
combined.csv <- amazon.csv, walmart.csv
  cat $[INPUTS] > $OUTPUT

; Filter out some bad stuff
filtered.csv <- combined.csv
  grep -v “Scott’s Cakes” $INPUT > $OUTPUT

; Generate clusters file
clusters <- filtered.csv
  java -jar resolve.jar $INPUT $OUTPUT

; Apply UUIDs according to clusters
cpg.json <- filtered.csv, clusters
  rb generate_uuids.rb --data=$INPUT1 --clusters=$INPUT2 --output=$OUTPUT

; Push to production servers
PUBLISH=/apps/extract/v3_data_for_materialization/cpg

; this target has no output files and will always run but after
; user’s confirmation
;<- cpg.json [confirm:true label:push]
;  hadoop fs -cp $INPUT $[PUBLISH]

; Generate some statistics
report.html <- cpg.json [label:stats]
  datatool $INPUT --columns=brand,name --report=$OUTPUT