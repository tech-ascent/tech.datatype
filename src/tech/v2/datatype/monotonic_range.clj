(ns tech.v2.datatype.monotonic-range
  "Ranges that *are* readers.  And that support some level of algebraic operations
  between pairs of them."
  (:require [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.base :as base]
            [tech.v2.datatype.casting :as casting]
            ;;Complete clojure range support
            [tech.v2.datatype.clj-range :as clj-range])
  (:import [tech.v2.datatype LongReader]
           [clojure.lang LongRange IObj IPersistentMap Range MapEntry]
           [java.lang.reflect Field]
           [java.util Map Map$Entry]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(declare make-range)


(deftype Int64Range [^long start ^long increment ^long n-elems
                     ^IPersistentMap metadata]
  dtype-proto/PDatatype
  (get-datatype [item] :int64)
  LongReader
  (lsize [item] n-elems)
  (read [item idx]
    (+ start (* increment idx)))
  dtype-proto/PBuffer
  (sub-buffer [item offset len]
    (let [offset (long offset)
          len (long len)]
      (when (> (- len offset) (.lsize item))
        (throw (Exception. "Length out of range")))
      (let [new-start (+ start (* offset increment))]
        (Int64Range. new-start
                     increment
                     len
                     metadata))))
  dtype-proto/PConstantTimeMinMax
  (has-constant-time-min-max? [item] true)
  (constant-time-min [item] (dtype-proto/range-min item))
  (constant-time-max [item] (dtype-proto/range-max item))
  dtype-proto/PRangeConvertible
  (convertible-to-range? [item] true)
  (->range [item options] item)
  dtype-proto/PClone
  (clone [this] (Int64Range. start increment n-elems metadata))
  dtype-proto/PRange
  (range-select [lhs rhs]
    (let [r-start (long (dtype-proto/range-start rhs))
          r-n-elems (long (dtype-proto/ecount rhs))
          r-inc (long (dtype-proto/range-increment rhs))
          r-stop (+ r-start (* r-n-elems r-inc))
          new-start (+ start (* r-start increment))
          new-inc (* r-inc increment)]
      (when (or (> r-stop n-elems)
                (>= r-start n-elems))
        (throw (Exception. "select-ranges - righthand side out of range")))
      (Int64Range. new-start new-inc r-n-elems {})))
  (range-start [item] start)
  (range-increment [item] increment)
  (range-min [item]
    (when (= 0 n-elems)
      (throw (Exception. "Range is empty")))
    (if (> increment 0)
      start
      (+ start (* (dec n-elems) increment))))
  (range-max [item]
    (when (= 0 n-elems)
      (throw (Exception. "Range is empty")))
    (if (> increment 0)
      (+ start (* (dec n-elems) increment))
      start))
  (range-offset [item offset]
    (Int64Range. (+ start (long offset))
                 increment n-elems metadata))
  (range->reverse-map [item]
    (reify Map
      (size [m] n-elems)
      (containsKey [m arg]
        (when (and arg
                   (casting/integer-type?
                    (dtype-proto/get-datatype arg)))
          (let [arg (long arg)
                rel-arg (- arg start)]
            (and (== 0 (rem rel-arg increment))
                 (>= arg (long (.range-min item)))
                 (<= arg (long (.range-max item)))))))
      (isEmpty [m] (== 0 (.size m)))
      (entrySet [m]
        ;;This could be bad
        (->> item
             (map-indexed (fn [idx range-val]
                            (MapEntry. range-val idx)))
             set))
      (getOrDefault [m k default-value]
        (if (and k (casting/integer-type? (dtype-proto/get-datatype k)))
          (let [arg (long k)
                rel-arg (- arg start)]
            (if (and (== 0 (rem rel-arg increment))
                     (>= arg (long (.range-min item)))
                     (<= arg (long (.range-max item))))
              (quot rel-arg increment)
              default-value))
          default-value))
      (get [m k]
        (let [arg (long k)
              rel-arg (- arg start)]
          (when (and (== 0 (rem rel-arg increment))
                     (>= arg (long (.range-min item)))
                     (<= arg (long (.range-max item))))
            (quot rel-arg increment))))))
  IObj
  (meta [this] metadata)
  (withMeta [this metadata]
    (Int64Range. start increment n-elems metadata)))


(extend-protocol dtype-proto/PRangeConvertible
  Byte
  (convertible-to-range? [item] true)
  (->range [item options]
    (with-meta (make-range (long item))
      {:scalar? true}))
  Short
  (convertible-to-range? [item] true)
  (->range [item options]
    (with-meta (make-range (long item))
      {:scalar? true}))
  Integer
  (convertible-to-range? [item] true)
  (->range [item options]
    (with-meta (make-range (long item))
      {:scalar? true}))
  Long
  (convertible-to-range? [item] true)
  (->range [item options]
    (with-meta (make-range (long item))
      {:scalar? true})))


(defn make-range
  ([start end increment datatype]
   (when-not (= datatype :int64)
     (throw (Exception. "Only long ranges supported for now")))
   (let [start (long start)
         end (long end)
         increment (long increment)]
     (when (== 0 increment)
       (throw (Exception. "Infinite range detected - zero increment")))
     (let [n-elems (if (> increment 0)
                     (quot (+ (max 0 (- end start))
                              (dec increment))
                           increment)
                     (quot (+ (min 0 (- end start))
                              (inc increment))
                           increment))]
       (Int64Range. start increment n-elems {}))))
  ([start end increment]
   (make-range start end increment (base/get-datatype start)))
  ([start end]
   (make-range start end 1))
  ([end]
   (make-range 0 end 1)))


(extend-type LongRange
  dtype-proto/PRangeConvertible
  (convertible-to-range? [item] true)
  (->range [rng options]
    (let [start (long (first rng))
          step (long (.get ^Field clj-range/lr-step-field rng))
          n-elems (.count rng)]
      (Int64Range. start step n-elems {}))))


(extend-type Range
  dtype-proto/PRangeConvertible
  (convertible-to-range? [item] (casting/integer-type?
                                 (dtype-proto/get-datatype item)))
  (->range [rng options]
    (when-not (casting/integer-type? (dtype-proto/get-datatype rng))
      (throw (Exception. (format "Item is not convertible to range: %s<%s>"
                                 (type rng) (dtype-proto/get-datatype rng)))))
    (let [start (long (first rng))
          step (long  (.get ^Field clj-range/r-step-field rng))
          n-elems (.count rng)]
      (Int64Range. start step n-elems {}))))


(defn reverse-range
  ([len]
   (make-range (unchecked-dec (long len)) -1 -1))
  ([start end]
   (make-range (unchecked-dec (long end)) start -1)))
