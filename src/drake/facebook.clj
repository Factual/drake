(ns drake.facebook
  (:require clj-facebook-graph.auth
            [clj-facebook-graph.client :as client])
  (:import clj_facebook_graph.FacebookGraphException))

(def ^:dynamic *access-token*)

(defn init! [{:keys [access-token]}]
  (def ^:dynamic *access-token* (ring.util.codec/url-encode access-token)))

(def nearby-base-url "https://graph.facebook.com/search")

(defn compose-nearby-url
  "q for query word
   center should be a comma seperated string, like 37.76,-122.427
   distance is in meters, for exmaple 1000"
  [q & [center distance]]
  (let [encoded-q (ring.util.codec/url-encode q)
        query-url (str nearby-base-url "?access_token=" *access-token* "&type=place&q=" encoded-q)]
    (if center
      (str query-url "&center=" center "&distance=" distance)
      query-url)))

(defn search-nearby
  [q & [center distance]]
  (let [resp (client/get (compose-nearby-url q center distance))
        businesses ((resp :body) :data)]
    businesses))

(defn lookup-business [fb-id]
  (letfn [(lookup [fb-id] (:body (client/get [fb-id])))]
         (try
           (lookup fb-id)
           (catch FacebookGraphException e
             (let [error-message (.getMessage e)]
               (when (> (.indexOf error-message "Please update your API calls to the new ID") 0)
                 (let [redirect-id (second (re-seq #"\d{5,}" error-message))]
                   (lookup redirect-id))))))))

(defn search-nearby-details
  [q & [center distance]]
  "returns a more detailed results for a nearby search
   will call lookup-business on each of the returned fb-ids"
  (let [search-results (search-nearby q center distance)
        loc-ids        (map #(% :id) search-results)
        loc-details    (map #(lookup-business %) loc-ids)]
    loc-details))

(defn get-core-data [venue]
  (let [loc (venue :location)]
    {"name"             (venue :name)
     "address"          (loc :street)
     "address_extended" ""
     "locality"         (loc :city)
     "region"           (loc :state)
     "postcode"         (loc :zip)
     "tel"              (venue :phone)
     "latitude"         (loc :latitude)
     "longitude"        (loc :longitude)
     "category"         (venue :categories)
     "country"          (loc :country)
     "fb-id"            (venue :id)}))
