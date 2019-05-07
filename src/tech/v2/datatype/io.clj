(ns tech.v2.datatype.io
  (:require [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.casting
             :refer [numeric-type? integer-type? numeric-byte-width
                     datatype->host-type]
             :as casting]
            [tech.jna :as jna]
            [clojure.set :as c-set]
            [tech.v2.datatype.fast-copy :as fast-copy]
            [tech.v2.datatype.typecast
             :refer [datatype->reader
                     datatype->writer]
             :as typecast]
            [tech.v2.datatype.reader :as reader]
            [tech.v2.datatype.writer :as writer]
            [tech.v2.datatype.writers.indexed :as indexed-writer]
            [tech.v2.datatype.readers.indexed :as indexed-reader])

  (:import [tech.v2.datatype
            ObjectReader ObjectWriter ObjectMutable
            ByteReader ByteWriter ByteMutable
            ShortReader ShortWriter ShortMutable
            IntReader IntWriter IntMutable
            LongReader LongWriter LongMutable
            FloatReader FloatWriter FloatMutable
            DoubleReader DoubleWriter DoubleMutable
            BooleanReader BooleanWriter BooleanMutable]
           [tech.v2.datatype.protocols PToReader]
           [com.sun.jna Pointer]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn dense-copy!
  [dst src unchecked?]
  (let [dst-dtype (dtype-proto/get-datatype dst)
        src-dtype (dtype-proto/get-datatype src)
        src-buf (or (typecast/as-nio-buffer src)
                    (typecast/as-list src))
        dst-nio (typecast/as-nio-buffer dst)
        src-nio (typecast/as-nio-buffer src)
        dst-list (typecast/as-list dst)
        src-list (typecast/as-list src)
        src-reader? (dtype-proto/convertible-to-reader? src)
        dst-buf (or dst-nio dst-list)
        src-buf-dtype (when src-buf (dtype-proto/get-datatype src-buf))
        dst-buf-dtype (when dst-buf (dtype-proto/get-datatype dst-buf))
        fast-path? (and src-buf
                        dst-buf
                        (or (and (numeric-type? dst-dtype)
                                 (= dst-dtype src-dtype)
                                 (= dst-buf-dtype (casting/datatype->host-datatype dst-dtype))
                                 (= src-buf-dtype (casting/datatype->host-datatype src-dtype)))
                            (and (integer-type? dst-dtype)
                                 (integer-type? src-dtype)
                                 (= (casting/datatype->host-datatype dst-dtype)
                                    dst-buf-dtype)
                                 (= (casting/datatype->host-datatype src-dtype)
                                    src-buf-dtype)
                                 unchecked?
                                 (= (numeric-byte-width dst-dtype)
                                    (numeric-byte-width src-dtype)))))
        dst-type-matches? (or (= dst-buf-dtype dst-dtype)
                              (and unchecked?
                                   (= (casting/datatype->host-datatype dst-dtype)
                                      dst-buf-dtype)))
        src-type-matches? (or (= src-buf-dtype src-dtype)
                              (and unchecked?
                                   (= (casting/datatype->host-datatype src-dtype)
                                      src-buf-dtype)))]
    ;;Fast path means no conversion is necessary and we can hit optimized
    ;;bulk pathways
    (if fast-path?
      ;;The only real special case is if one side is a ptr
      ;;and the other is an array
      (fast-copy/copy! dst src)
      (cond
        (and dst-nio dst-type-matches? src-reader?)
        (fast-copy/parallel-nio-write! dst-nio src unchecked?)
        (and dst-list dst-type-matches? src-reader?)
        (fast-copy/parallel-list-write! dst-list src unchecked?)
        (and src-nio src-type-matches?)
        (fast-copy/parallel-nio-read! dst src-nio unchecked?)
        (and src-list src-type-matches?)
        (fast-copy/parallel-list-read! dst src-list unchecked?)
        :else
        (do
          (fast-copy/parallel-slow-copy! dst src unchecked?)))))
  dst)


(defn write-indexes!
  [item indexes values options]
  (let [datatype (or (:datatype options) (dtype-proto/get-datatype item))]
    ;;We use parallel reader because values is likely to be a raw buffer and thus
    ;;we can get direct buffer access to it.  We know that we cannot get a raw buffer
    ;;from an indexed writer.
    (fast-copy/parallel-read! (indexed-writer/make-indexed-writer
                               indexes item (assoc options :datatype datatype))
                              values (:unchecked? options))
    item))


(defn read-indexes!
  [item indexes values options]
  (let [datatype (or (:datatype options) (dtype-proto/get-datatype item))]
    (fast-copy/parallel-write! values (indexed-reader/make-indexed-reader
                                       indexes values (assoc options :datatype datatype))
                               true)
    values))


(defmethod dtype-proto/copy! :default
  [dst src options]
  (dense-copy! dst src (:unchecked? options)))
