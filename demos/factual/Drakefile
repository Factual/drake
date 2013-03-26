;
; Simple demo of using c4 to integrate with Factual's public API.
;
; You will need to setup your Factual API credentials first. Create
; the file ~/.factual/factual-auth.yaml and make it look like:
;   ---
;   key: YOUR_KEY 
;   secret: YOUR_SECRET
;
;
; Run from project root:
;
;   lein run --auto --workflow demos/factual
;
; Output will be created in demos/factual
;
; Or, if you have Drake installed, you can cd to demos/factual and just run drake.
;

;
; Adds attributes populated by data found in Factual.
;
factual.out.json <- factual.in.json [c4row]
  (when-let [entity (first (select places
                          (where (= :factual_id (row "fid")))))]
      (-> row
        (assoc "name" (entity :name))
        (assoc "lat"  (entity :latitude))
        (assoc "lon"  (entity :longitude))))
