outA <- inA [+artem -crash]
 echo Test

o1 <- i1 [shell]
  grep something *.csv

;; TODO(aaron)
;; Fix - when there was only one %, the 3 lines below were parsed
;; as one big step, with three individual outputs. It shouldn't be happening,
;; there should be an error since all strings starting with % should be
;; special directives. And even if they weren't, outputs should be separated
;; by commas, so there are at least 2 bugs here.

%% (println "OH HAI")
%% (println "I'm in ur parse state, countin' ur" (count (:steps P)) "steps")
%% (assoc P :name :Artem)
