(ns tech.v2.datatype.readers.const
  (:require [tech.v2.datatype.casting :as casting]
            [tech.v2.datatype.typecast :as typecast]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.monotonic-range :as dtype-range]))


(defmacro make-const-reader-macro
  [datatype]
  `(fn [item# num-elems# datatype#]
     (let [num-elems# (long (or num-elems# Long/MAX_VALUE))
           item# (casting/datatype->cast-fn
                  :unknown ~datatype item#)]
       (reify
         ~(typecast/datatype->reader-type datatype)
         (getDatatype [reader#] datatype#)
         (lsize [reader#] num-elems#)
         (read [reader# idx#] item#)
         dtype-proto/PConstantTimeMinMax
         (has-constant-time-min-max? [this#] true)
         (constant-time-min [this#] item#)
         (constant-time-max [this#] item#)
         dtype-proto/PRangeConvertible
         (convertible-to-range? [this#]
           (and (== 1 num-elems#)
                (casting/integer-type? ~datatype)))
         (->range [this# options#]
           (when (== 1 num-elems#)
             (dtype-range/make-range item# (unchecked-inc item#))))))))


(def const-reader-table (casting/make-base-datatype-table
                         make-const-reader-macro))


(defn make-const-reader
  [item datatype & [num-elems]]
  (if-let [reader-fn (get const-reader-table (casting/flatten-datatype datatype))]
    (reader-fn item num-elems datatype)
    (throw (ex-info (format "Failed to find reader for datatype %s" datatype) {}))))
