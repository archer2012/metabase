(ns metabase.sync.analyze.classifiers.fingerprint
  "Logic for inferring the special types of fields based on their Fingerprints.
   These tests only run against Fields that *don't* have existing special types."
  (:require [clojure.core.match :refer [match]]
            [clojure.tools.logging :as log]
            [metabase.sync
             [interface :as i]
             [util :as sync-util]]
            [metabase.util.schema :as su]
            [schema.core :as s])
  (:import (java.time LocalDateTime ZoneOffset)
           java.time.temporal.ChronoUnit))

(def ^:private ^:const ^Double percent-valid-threshold
  "Fields that have at least this percent of values that are satisfy some predicate (such as `u/email?`)
   should be given the corresponding special type (such as `:type/Email`)."
  0.95)

(def ^:private ^Double lower-percent-valid-threshold
  "Fields that have at least this lower percent of values that satisfy some predicate (such as `u/state?`) should be
  given the corresponding special type (such as `:type/State`)"
  0.7)

(s/defn ^:private percent-key-above-threshold? :- s/Bool
  "Is the value of PERCENT-KEY inside TEXT-FINGERPRINT above the `percent-valid-threshold`?"
  [^Double threshold, text-fingerprint :- i/TextFingerprint, percent-key :- s/Keyword]
  (boolean
   (when-let [percent (get text-fingerprint percent-key)]
     (>= percent threshold))))

(def ^:private percent-key->special-type
  "Map of keys inside the `TextFingerprint` to the corresponding special types we should mark a Field as if the value of
  the key is over `percent-valid-thresold`."
  {:percent-json  [:type/SerializedJSON percent-valid-threshold]
   :percent-url   [:type/URL            percent-valid-threshold]
   :percent-email [:type/Email          percent-valid-threshold]
   :percent-state [:type/State          lower-percent-valid-threshold]})

(s/defn ^:private infer-special-type-for-text-fingerprint :- (s/maybe su/FieldType)
  "Check various percentages inside the TEXT-FINGERPRINT and return the corresponding special type to mark the Field
  as if the percent passes the threshold."
  [text-fingerprint :- i/TextFingerprint]
  (some (fn [[percent-key [special-type threshold]]]
          (when (percent-key-above-threshold? threshold text-fingerprint percent-key)
            special-type))
        percent-key->special-type))

(def ^:private ^Long year-threshold
  "An arbitrary threshold for a duration around now that we check for integers inside of in order to mark them as
  UNIXTimestamps."
  20)

(def ^:private past-threshold (.. (LocalDateTime/now)
                                  (minus year-threshold ChronoUnit/YEARS)
                                  (toInstant ZoneOffset/UTC)
                                  (getEpochSecond)))

(def ^:private future-threshold (.. (LocalDateTime/now)
                                    (plus year-threshold ChronoUnit/YEARS)
                                    (toInstant ZoneOffset/UTC)
                                    (getEpochSecond)))

(s/defn ^:private infer-special-type-for-number-fingerprint :- (s/maybe su/FieldType)
  [{:keys [q1 q3] :as _number-fingerprint} :- i/NumberFingerprint]
  (when (and (number? q1) (number? q3))
    (let [within-threshold? (fn [factor] (and (<= (* past-threshold factor) q1)
                                              (<= q3 (* future-threshold factor))))]
      (cond (within-threshold?       1) :type/UNIXTimestampSeconds
            (within-threshold?    1000) :type/UNIXTimestampMilliseconds
            (within-threshold? 1000000) :type/UNIXTimestampMicroseconds))))


(defn- infer-special-type*
  "Helper function that matches on the base type and the matching fingerprint. Currently supports `:type/Text` and
  `:type/Number`."
  [base-type fingerprint]
  (match [base-type fingerprint]
    [(:isa? :type/Text) {:type {:type/Text text-fingerprint}}]
    (infer-special-type-for-text-fingerprint text-fingerprint)

    ;; use :type/Number instead of :type/Integer as Oracle maps to :type/Decimal for their biginteger type
    [(:isa? :type/Number) {:type {:type/Number number-fingerprint}}]
    (infer-special-type-for-number-fingerprint number-fingerprint)

    :else nil))

(defn- can-edit-special-type?
  "We can edit the special type if its currently unset or if it was set during the current analysis phase. The original
  field might exist in the metadata at `:sync.classify/original`. This is an attempt at classifier refinement: we
  never want to overwrite a user selection of special type but we allow for fingerprint results to give a better
  special type than previous classifiers."
  [field]
  (or (nil? (:special_type field))
      (let [original (get (meta field) :sync.classify/original)]
        (and original
             (nil? (:special_type original))))))

(s/defn infer-special-type :- (s/maybe i/FieldInstance)
  "Do classification for `:type/Text` Fields with a valid `TextFingerprint` and `:type/Number` Fields with a valid `NumberFingerprint`.
   Currently this only checks the various recorded percentages, but this is subject to change in the future."
  [field :- i/FieldInstance, fingerprint :- (s/maybe i/Fingerprint)]
  (when (can-edit-special-type? field)
    (when-let [inferred-special-type (infer-special-type* (:base_type field)
                                                          fingerprint)]
      (log/debug (format "Based on the fingerprint of %s, we're marking it as %s."
                         (sync-util/name-for-logging field) inferred-special-type))
      (assoc field
             :special_type inferred-special-type))))