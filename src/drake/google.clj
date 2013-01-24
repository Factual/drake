(ns drake.google
  "Supports queries against Google's Places API."
  (:import (com.google.api.client.http.apache ApacheHttpTransport)
           (com.google.api.client.http HttpRequestFactory HttpRequestInitializer GenericUrl)
           (com.google.api.client.googleapis GoogleHeaders)
           (com.google.api.client.http.json JsonHttpParser)
           (com.google.api.client.json.jackson JacksonFactory)
           (javax.net.ssl HttpsURLConnection HostnameVerifier))
  (:require [cheshire.core :as json]))


(def ^:private ^:dynamic key-atom (atom nil))

(def PLACES_SEARCH "https://maps.googleapis.com/maps/api/place/search/json?")

(def PLACES_LOOKUP "https://maps.googleapis.com/maps/api/place/details/json?")

(defn auth-key []
  (if-let [key @key-atom]
    key
    (throw (IllegalArgumentException. "The Google wrapper must be initialized using init!"))))

(defn init!
  "Initializes authentication. Takes a hash-map as the single argument, and
   expects the hash-map to contain a valid :key entry, where the value is a
   valid Google Places key."
  [{key :key}]
  (reset! key-atom key))

(def REQ-FACTORY
  (.createRequestFactory (ApacheHttpTransport.)))

(defn as-result
  "Turns the HttpResponse object from Google into a results sequence, where
   each element in the sequence is a record.

   The sequence will be wrapped in metadata that includes the response
   metadata from Google, such as the response status."
  [resp]
  (let [parsed (json/parse-string (.parseAsString resp))
        data (or
              ;; e.g, a reference lookup
              (parsed "result")
              ;; e.g., a search
              (parsed "results"))]
    (when data
      (with-meta data (dissoc parsed "result" "results")))))

(defn parametrize
  "Returns url, parametrized with the key value pairs specified by
   the query hash-map."
  [url query]
  (doseq [[k v] query]
    (.put url
          (if (keyword? k) (name k) k)
          v))
  url)

(defn make-url
  "Returns a GenericUrl based on endpoint and parametrized with the
   key value pairs specified by the query hash-map."
  [endpoint query]
  (let [url (doto (GenericUrl. endpoint)
              (.put "key" (auth-key))
              (.put "sensor" "false"))]
    (if query
      (parametrize url query)
      url)))

(defn make-req [endpoint query]
  (.buildGetRequest REQ-FACTORY (make-url endpoint query)))

(defn get-result [endpoint query]
  (as-result (.execute (make-req endpoint query))))

(defn search-places
  "Runs a search query against Google Places and returns the results.
   The results will be a sequence of records, where each record is a
   hash-map representing a Google Place that matched the query.

   The returned results will also be wrapped in metadata parsed from
   the response metadata returned by Google, such as response status."
  [query]
  (get-result PLACES_SEARCH query))

(defn lookup-place
  "Runs a lookup request against Google Places and returns the Google
   Place with the specified reference, represented as a hash-map..

   The result will also be wrapped in metadata parsed from the
   response metadata returned by Google, such as response status."
  [ref]
  (assoc
    (get-result PLACES_LOOKUP {:reference ref})
    :reference ref))

(defn addr-parts [place]
  (when-let [parts (place "address_components")]
    (into {} (map #(vector (first (% "types")) (% "short_name")) parts))))

(defn addr-from-parts [parts]
  (let [num   (parts "street_number")
        delim (if (empty? num) "" " ")
        route (parts "route")]
    (str num delim route)))

(defn get-core-data
  "Pulls out data from a place detail record obtained via lookup-place, and returns
   it in a standard 'core data' format."
  [place]
  (let [addr-parts (or (addr-parts place) {})]
    {"name"             (place "name")
     "address"          (addr-from-parts addr-parts)
     "address_extended" (place "formatted_address")
     "locality"         (addr-parts "locality")
     "region"           (addr-parts "administrative_area_level_1")
     "postcode"         (addr-parts "postal_code")
     "tel"              (place "formatted_phone_number")
     "latitude"         (get-in place ["geometry" "location" "lat"])
     "longitude"        (get-in place ["geometry" "location" "lng"])
     "category"         (place "types")
     "country"          (addr-parts "country")
     "reference"        (place "reference")}))

(defn first-place
  "Returns the first valid place by going through refs and looking them
   up. Explicitly ensures laziness."
  [refs]
    (if (empty? refs)
      nil
      (let [place (lookup-place (first refs))]
        (if (= "OK" (get (meta place) "status"))
          place
          (first-place (rest refs))))))

(comment
  ;; An example Place structure
  (def PLACE
    {"address_components"
     [{"long_name" "290", "short_name" "290", "types" ["street_number"]}
      {"long_name" "Upper Richmond Rd",
       "short_name" "Upper Richmond Rd",
       "types" ["route"]}
      {"long_name" "Putney",
       "short_name" "Putney",
       "types" ["locality" "political"]}
      {"long_name" "London",
       "short_name" "London",
       "types" ["administrative_area_level_1" "political"]}
      {"long_name" "GB",
       "short_name" "GB",
       "types" ["country" "political"]}
      {"long_name" "SW15 6TH",
       "short_name" "SW15 6TH",
       "types" ["postal_code"]}],
     "opening_hours"
     {"open_now" false,
      "periods"
      [{"close" {"day" 0, "time" "1500"}, "open" {"day" 0, "time" "1000"}}
       {"close" {"day" 1, "time" "2000"}, "open" {"day" 1, "time" "0930"}}
       {"close" {"day" 2, "time" "2000"}, "open" {"day" 2, "time" "0930"}}
       {"close" {"day" 3, "time" "2000"}, "open" {"day" 3, "time" "0930"}}
       {"close" {"day" 4, "time" "2000"}, "open" {"day" 4, "time" "0930"}}
       {"close" {"day" 5, "time" "2000"}, "open" {"day" 5, "time" "0930"}}
       {"close" {"day" 6, "time" "1900"},
        "open" {"day" 6, "time" "0930"}}]},
     "international_phone_number" "+44 20 8780 9350",
     "name" "the flower yard",
     "reference"
     "CnRsAAAAZCqKWzRA1De9XSepptZD8saftUsGddGF6bsZu6XrOL68_EnQdZqpPq0LPFa2feC1x6XzF0AbkEMKqe1jbD3axR6XiCbVgEBb-Z4Mab_gwhMhI82NJZBGXz8pA_Zn8hBHOnK6FodkwwJEgX5M1d43whIQp74xZuSYqsBV6rFhlERjuBoUT4IuXCjLdwesjAFdNhKFf2fn8nc",
     "utc_offset" 0,
     "url" "https://plus.google.com/116524504426652824023/about?hl=en-US",
     "formatted_address" "290 Upper Richmond Rd, Putney, United Kingdom",
     "geometry" {"location" {"lat" 51.461967, "lng" -0.221015}},
     "icon"
     "http://maps.gstatic.com/mapfiles/place_api/icons/shopping-71.png",
     "types" ["florist" "store" "establishment"],
     "vicinity" "290 Upper Richmond Rd, Putney",
     "website" "http://www.thefloweryard.net/",
     "id" "0454f409c736b77ccee812ee13fd2c33d5037b2a",
     "formatted_phone_number" "020 8780 9350"}))