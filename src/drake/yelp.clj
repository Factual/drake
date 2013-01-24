;;This was shamelessly stolen from a library I found that (I think) was originally
;;published by Eduardo Emilio Julián Pereyra on github:
;;https://github.com/eduardoejp
;;But apparently it's been deleted. So I've taken the original source, put it in this
;;namespace, and upgraded it to work with Clojure 1.4.
;;This is the dependency coords I used to get the original: [clj-yelp "0.1.0"]
;;  --Aaron, 2012-08-24
;;
;;TODO(aaron): figure out licensing ramifications, factor into new lib?
;;
;;--- From original source:
;;Version: 0.1.00
;;Copyright: Eduardo Emilio Julián Pereyra, 2011
;;Email: eduardoejp@gmail.com
;;License: EPL 1.0 -> http://www.eclipse.org/legal/epl-v10.html
(ns drake.yelp
  #^{:author "Eduardo Emilio Julián Pereyra",
     :doc "This namespace gives access to the v2.0 APIs: Search API, Business API."}
  (:import (org.scribe.builder.api DefaultApi10a)
    (org.scribe.builder ServiceBuilder)
    (org.scribe.model Token Verb OAuthRequest)
    (org.scribe.oauth OAuthService))
  (:require
   [cheshire.core :as json]))

(def YelpAPI2
  (class
    (proxy [org.scribe.builder.api.DefaultApi10a] []
      (getAccessTokenEndpoint [] nil)
      (getAuthorizationUrl [token] nil)
      (getRequestTokenEndpoint [] nil))))

;; Stores the current OAuth Service
(def ^:dynamic *oauth-service* nil)
;; Stores the current Access Token
(def ^:dynamic *access-token* nil)

(defn make-service
  "Given the consumer key and secrets, builds an OAuth Service."
  [consumer-key, consumer-secret]
  (-> (ServiceBuilder.) (.provider YelpAPI2) (.apiKey consumer-key) (.apiSecret consumer-secret) .build))

(defn make-access-token
  "Given a request token and token secret, creates an access token."
  [token, token-secret]
  (Token. token token-secret))

(defn init! [{:keys [consumer-key, consumer-secret, token, token-secret]}]
  (def ^:dynamic *oauth-service* (make-service consumer-key consumer-secret))
  (def ^:dynamic *access-token* (make-access-token token token-secret)))

(defmacro with-oauth
"Given a hash-map like {:consumer-key \"xyz\", :consumer-secret \"xyz\", :token \"xyz\", :token-secret \"xyz\"},
binds up the *oauth-service* and *access-token* vars to be used by v2.0 fns."
  [{:keys [consumer-key, consumer-secret, token, token-secret]}, & forms]
  `(binding [*oauth-service* (make-service ~consumer-key ~consumer-secret),
             *access-token* (make-access-token ~token ~token-secret)]
     ~@forms))

(defmacro add-param [request key val]
  `(when ~val (.addQuerystringParameter ~request ~key ~val)))

(defmacro json-try [sexp]
  `(let [res# ~sexp]
     (if (:error res#)
       (throw (Exception. (str (-> res# :error :id) ": " (-> res# :error :text))))
       res#)))

; Search API
(defn search-businesses
"Given the desired optional values, searches for businesses.
Take into account that:
coordinates (provided as a [vector]) == ll
current-coordinates (provided as a [vector]) == cll

The rest of the params are pretty obvious and require no explanation."
  [{:keys [term limit results-offset sort-mode category
           radius country-code language bounds location
           coordinates current-coordinates]}]
  (let [request (OAuthRequest. Verb/GET "http://api.yelp.com/v2/search")]
    (do (add-param request "term" term) (add-param request "sort" sort-mode)
      (add-param request "limit" limit) (add-param request "offset" results-offset)
      (add-param request "category_filter" category) (add-param request "radius_filter" radius)
      (add-param request "cc" country-code) (add-param request "lang" language)
      (add-param request "bounds" (when bounds (str (first bounds) "," (second bounds) "|" (nth bounds 2) "," (nth bounds 3))))
      (add-param request "location" location)
      (add-param request "ll" (when coordinates (apply str (butlast (interleave coordinates (repeat ","))))))
      (add-param request "cll" (when current-coordinates (str (first current-coordinates) "," (second current-coordinates)))))
    (.signRequest *oauth-service* *access-token* request)
    (-> request .send .getBody json/parse-string json-try)))

; Business API
(defn lookup-business
  "Given the business-id as a \"string\", looks it up and returns the results."
  [business-id]
  (let [request (OAuthRequest. Verb/GET (str "http://api.yelp.com/v2/business/" business-id))]
    (.signRequest *oauth-service* *access-token* request)
    (-> request .send .getBody json/parse-string json-try)))

(defn get-core-data [venue]
  (let [loc   (venue "location")
        coord (loc "coordinate")]
    {"name"             (venue "name")
     "address"          (clojure.string/join "," (loc "address"))
     "address_extended" ""
     "locality"         (loc "city")
     "region"           (loc "state_code")
     "postcode"         (loc "postal_code")
     "tel"              (venue "phone")
     "latitude"         (coord "latitude")
     "longitude"        (coord "longitude")
     "category"         (clojure.string/join "," (map first (venue "categories")))
     "country"          ""}))