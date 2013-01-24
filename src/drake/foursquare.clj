;; Provides convenience support for accessing Foursquare's API.
;; Internally maps Foursquare Java objects into hash-maps.
;;
;; Docs for the underlying Java object model:
;; https://code.google.com/p/foursquare-api-java/source/browse/foursquare-api/src/main/java/fi/foyt/foursquare/api/?r=262#api%253Fstate%253Dclosed
;;
;; TODO(aaron): move this out of Drake
(ns drake.foursquare
  (:import [fi.foyt.foursquare.api FoursquareApi]))


(def ^:dynamic *auth*)

(defn init! [{:keys [key secret]}]
  (def ^:dynamic *auth* (FoursquareApi.
                         key
                         secret
                         nil)))

(defn response-code [result]
  (-> result .getMeta .getCode))

(defn response-msg [result]
  (-> result .getMeta .getErrorDetail))

(defn success? [resp]
  (= 200 (response-code resp)))

;; Look up by venue id Helpers
(defn fetch-venue-resp [venue-id]
  (.venue *auth* venue-id))

(defn fetch-venue-result
  "Returns the result object from querying Foursquare for venue-id. This will be a Java Object
   of class fi.foyt.foursquare.api.entities.CompleteVenue.

   Throws an Exception on a bad response from Foursquare."
  [venue-id]
  (let [resp (fetch-venue-resp venue-id)]
    (if (success? resp)
      (.getResult resp)
      (throw (Exception. (format "Bad Foursquare response for venue-id %s, code: %s, msg: %s"
                                 venue-id (response-code resp) (response-msg resp)))))))

(defn fetch-venue-location [venue-id]
  (.getLocation (fetch-venue-result venue-id)))

(defn search-venue-resp
  "Helper function to search by a param"
  [search-params]
  (.venuesSearch *auth* search-params))

(defn search-venue-result
  "Returns the result object from querying Foursquare with search-parameters. This will
   be a Java Object of class fi.foyt.foursquare.api.entities.VenueSearchResult.

   Throws an Exception on a bad response from Foursquare."
  [search-params]
  (let [resp (search-venue-resp search-params)]
    (if (success? resp)
      (.getResult resp)
      (throw (Exception. (format "Bad Foursquare response for search, code: %s, msg: %s"
                                 (response-code resp) (response-msg resp)))))))

;; Parsing Helpers
(defn category-as-map [cat]
  {:name        (.getName cat)
   :id          (.getId cat)
   :icon        (.getIcon cat)
   :parents     (into [] (.getParents cat))
   :primary?    (.getPrimary cat)
   :plural-name (.getPluralName cat)})

(defn venue-as-map
  "Returns a nested hash-map representation of v, which is expected to be a
   Java CompactVenue object."
  [v]
  (let [loc      (.getLocation v)
        contact  (.getContact v)]
    {:id            (.getId v)
     :name          (.getName v)
     :verified      (.getVerified v)
     :url           (.getUrl v)

     :categories    (map category-as-map (.getCategories v))

     :address       (.getAddress loc)
     :cross-street  (.getCrossStreet loc)
     :city          (.getCity loc)
     :state         (.getState loc)
     :postal-code   (.getPostalCode loc)
     :country       (.getCountry loc)
     :location-name (.getName loc)
     :lat           (.getLat loc)
     :lng           (.getLng loc)
     :distance      (.getDistance loc)

     :email         (.getEmail contact)
     :facebook      (.getFacebook contact)
     :twitter       (.getTwitter contact)
     :phone         (.getPhone contact)}))

;; C4 API Functions
(defn fetch-venue
  "Returns a hash-map holding the attributes of the specified venue,
   populated with results from Foursquare.

   Throws an Exception on a bad response from Foursquare."
  [venue-id]
  (venue-as-map
   (fetch-venue-result venue-id)))

(defn search
  "Searches foursquare with data from a json conforming to factual location labels,
   returns a seq of hash-maps holding the attributes of venues.
   See https://developer.foursquare.com/docs/venues/search for params.

   Throws an Exception on a bad response from Foursquare."
  [search-params]
  (let [result (search-venue-result search-params)
        venues (.getVenues result)]
    (map venue-as-map venues)))

(defn get-core-data [venue]
  {"name"             (venue :name)
   "address"          (venue :address)
   "address_extended" ""
   "locality"         (venue :city)
   "region"           (venue :state)
   "postcode"         (venue :postal-code)
   "tel"              (venue :phone)
   "latitude"         (venue :lat)
   "longitude"        (venue :lng)
   "category"         (clojure.string/join "," (map :name (venue :categories)))
   "country"          ""})
