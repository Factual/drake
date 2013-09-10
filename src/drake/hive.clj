(ns drake.hive
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]))

(defn datasource [& {:keys [host port user password]
                     :or {host "127.0.0.1" user "hive" password ""}}]
  {:classname "org.apache.hive.jdbc.HiveDriver"
   :subprotocol "hive2"
   :subname (format "//%s:%s" host (str (or port 10000)))
   :user user
   :password password})

(defn- expect [rx [line & tail]]
  (when line
    (if-let [[[_ & matches]] (re-seq rx line)]
      (cons tail matches)
      (recur rx tail))))

(defn table-properties [ds schema table]
  (let [sql         (format "show create table %s.%s" schema table)
        lines       (->> (jdbc/query ds [sql] :as-arrays? true)
                         (rest)
                         (map first))
        [lines]     (expect #"^LOCATION$" lines)
        [lines loc] (expect #"^\s*'(.*)'\s*$" lines)
        [lines]     (expect #"^TBLPROPERTIES\s.*$" lines)
        [lines ts]  (expect #"^\s*'transient_lastDdlTime'='(\d+)'.*$" lines)]
    {:schema schema
     :table table
     :location loc
     :modified (and ts (-> ts
                           (Long/parseLong)
                           (* 1000)))}))

(defn query [ds sql & args]
  (->> (jdbc/query ds (cons sql args) :as-arrays? true)
       (rest)))

(defn list-schemas [ds]
  (->> (query ds "show databases")
       (map first)))

(defn list-tables [ds schema & [table]]
  (let [schema (or schema "default")
        table (or table "%")
        connection (jdbc/get-connection ds)
        metadata (.getMetaData connection)
        types (into-array String ["MANAGED_TABLE" "EXTERNAL_TABLE"])
        tables (.getTables metadata nil schema table types)]
    (for [_ (range) :while (.next tables)]
      {:schema (.getString tables 2)
       :table (.getString tables 3)})))

(defn schema-exists? [ds schema]
  (some #{schema} (list-schemas ds)))

(defn table-exists? [ds schema table]
  (= {:schema schema :table table}
     (first (list-tables ds schema table))))
