(ns tech.v2.datatype.sparse.sparse-buffer
  (:require [tech.v2.datatype.sparse.sparse-base :as sparse-base]
            [tech.v2.datatype.sparse.protocols :as sparse-proto]
            [tech.v2.datatype.sparse.reader :as sparse-reader]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.base :as dtype-base]
            [tech.v2.datatype.casting :as casting]
            [tech.v2.datatype.typecast :as typecast]
            [tech.v2.datatype.unary-op :as unary-op]
            [tech.v2.datatype.binary-search :as dtype-search]
            [tech.v2.datatype.argsort :as argsort]
            [tech.v2.datatype.reader :as reader]
            [tech.v2.datatype.readers.range :as range-reader]
            [tech.v2.datatype.readers.const :as const-reader]
            [tech.v2.datatype.nio-access :as nio-access]))


(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)


(declare make-sparse-buffer)


(defmacro make-sparse-writer
  [datatype]
  `(fn [item# desired-dtype# unchecked?#]
     (let [b-offset# (long (:b-offset item#))
           b-elem-count# (long (:b-elem-count item#))
           sparse-value# (casting/datatype->cast-fn :unknown ~datatype
                                                    (:sparse-value item#))
           indexes# (:indexes item#)
           data# (:data item#)
           index-mutable# (typecast/datatype->mutable :int32 indexes#)
           data-mutable# (typecast/datatype->mutable ~datatype data# unchecked?#)
           src-dtype# (dtype-base/get-datatype item#)
           n-elems# (dtype-base/ecount item#)]
       (reify
         ~(typecast/datatype->writer-type datatype)
         (getDatatype [writer#] desired-dtype#)
         (lsize [writer#] n-elems#)
         (write [writer# idx# value#]
           ;;We are not threadsafe.   Nor are we going to be.
           (locking writer#
             (let [idx# (+ b-offset# idx#)
                   [found?# insert-pos#] (dtype-search/binary-search
                                          indexes# idx# {:datatype :int32})
                   insert-pos# (int insert-pos#)]
               (if (= sparse-value# value#)
                 (when found?#
                   (do
                     (.remove index-mutable# insert-pos#)
                     (.remove data-mutable# insert-pos#)))
                 (if found?#
                   (.write (typecast/datatype->writer ~datatype data# unchecked?#)
                           insert-pos# value#)
                   (do
                     (.insert data-mutable# insert-pos# value#)
                     (.insert index-mutable# insert-pos# idx#)))))))

         dtype-proto/PBufferType
         (buffer-type [writer#] :sparse)

         sparse-proto/PToSparse
         (convertible-to-sparse? [writer#] true)
         (->sparse [writer#] (sparse-proto/->sparse item#))

         dtype-proto/PWriteIndexes
         (write-indexes! [writer# indexes# values# options#]
           (dtype-proto/write-indexes! item# indexes# values# options#))

         dtype-proto/PBuffer
         (sub-buffer [writer# offset# length#]
           (-> (dtype-proto/sub-buffer item# offset# length#)
               (dtype-proto/->writer {:datatype desired-dtype#
                                      :unchecked? unchecked?#})))
         dtype-proto/PSetConstant
         (set-constant! [writer# offset# value# elem-count#]
           (dtype-proto/set-constant! item# offset# value# elem-count#))))))


(defmacro make-sparse-writer-table
  []
  `(->> [~@(for [dtype casting/base-host-datatypes]
             [dtype `(make-sparse-writer ~dtype)])]
        (into {})))


(def sparse-writer-table (make-sparse-writer-table))


(defn- make-base-reader
  [{:keys [b-offset b-elem-count sparse-value
           indexes data] :as sparse-buf}]
  (let [b-offset (long b-offset)
        b-elem-count (long b-elem-count)]
    (-> (sparse-reader/make-sparse-reader indexes data
                                          (+ b-elem-count b-offset)
                                          :sparse-value sparse-value
                                          :datatype (dtype-base/get-datatype sparse-buf))
        (dtype-base/sub-buffer b-offset b-elem-count))))


(defmacro make-sparse-merge
  [datatype]
  `(fn [sparse-value# lhs-indexes# lhs-data# rhs-indexes# rhs-data# unchecked?#]
     (let [sparse-value# (casting/datatype->cast-fn :unknown ~datatype sparse-value#)
           lhs-iter# (typecast/datatype->iter :int32 lhs-indexes# unchecked?#)
           rhs-iter# (typecast/datatype->iter :int32 rhs-indexes# unchecked?#)
           lhs-data# (typecast/datatype->iter ~datatype lhs-data# unchecked?#)
           rhs-data# (typecast/datatype->iter ~datatype rhs-data# unchecked?#)
           result-indexes# (dtype-proto/make-container :list :int32 0 {})
           result-data# (dtype-proto/make-container :list ~datatype 0 {:unchecked? unchecked?#})
           result-idx-mut# (typecast/datatype->mutable :int32 result-indexes# true)
           result-data-mut# (typecast/datatype->mutable ~datatype result-data# unchecked?#)]
       (loop [left-has-more?# (.hasNext lhs-iter#)
              right-has-more?# (.hasNext rhs-iter#)]
         (when (or left-has-more?# right-has-more?#)
           (cond
             (and left-has-more?# right-has-more?#)
             (let [left-idx# (.current lhs-iter#)
                   right-idx# (.current rhs-iter#)]
               (cond
                 (< left-idx# right-idx#)
                 (let [left-idx# (.nextInt lhs-iter#)
                       left-val# (typecast/datatype->iter-next-fn ~datatype lhs-data#)]
                   (when-not (= sparse-value# left-val#)
                     (.append result-idx-mut# left-idx#)
                     (.append result-data-mut# left-val#)))
                 (= left-idx# right-idx#)
                 (let [left-idx# (.nextInt lhs-iter#)
                       left-val# (typecast/datatype->iter-next-fn ~datatype lhs-data#)
                       right-idx# (.nextInt rhs-iter#)
                       right-val# (typecast/datatype->iter-next-fn ~datatype rhs-data#)]
                   (when-not (= sparse-value# right-val#)
                     (.append result-idx-mut# right-idx#)
                     (.append result-data-mut# right-val#)))
                 :else
                 (let [right-idx# (.nextInt rhs-iter#)
                       right-val# (typecast/datatype->iter-next-fn ~datatype rhs-data#)]
                   (when-not (= sparse-value# right-val#)
                     (.append result-idx-mut# right-idx#)
                     (.append result-data-mut# right-val#)))))
             left-has-more?#
             (while (.hasNext lhs-iter#)
               (let [left-idx# (.nextInt lhs-iter#)
                     left-val# (typecast/datatype->iter-next-fn ~datatype lhs-data#)]
                 (when-not (= sparse-value# left-val#)
                   (.append result-idx-mut# left-idx#)
                   (.append result-data-mut# left-val#))))
             :else
             (while (.hasNext rhs-iter#)
               (let [right-idx# (.nextInt rhs-iter#)
                     right-val# (typecast/datatype->iter-next-fn ~datatype rhs-data#)]
                 (when-not (= sparse-value# right-val#)
                   (.append result-idx-mut# right-idx#)
                   (.append result-data-mut# right-val#)))))
           (recur (.hasNext lhs-iter#)
                  (.hasNext rhs-iter#))))
       {:indexes result-indexes#
        :data result-data#})))


(defmacro make-sparse-merge-table
  []
  `(->> [~@(for [dtype casting/base-host-datatypes]
             [dtype `(make-sparse-merge ~dtype)])]
        (into {})))


(def sparse-merge-table (make-sparse-merge-table))


(defn- global->local
  ^long [^long index ^long b-offset]
  (+ b-offset))


(defn- local->global
  ^long [^long index ^long b-offset]
  (+ index b-offset))



(defrecord SparseBuffer [^long b-offset
                         ^long b-elem-count
                         sparse-value
                         indexes
                         data
                         buffer-datatype]
  dtype-proto/PCountable
  (ecount [item] b-elem-count)
  dtype-proto/PDatatype
  (get-datatype [item] buffer-datatype)

  dtype-proto/PBuffer
  (sub-buffer [buffer offset length]
    (let [offset (long offset)
          length (long length)
          new-ecount (+ offset length)
          old-ecount (dtype-base/ecount buffer)]
      (when-not (>= old-ecount new-ecount)
        (throw (ex-info (format "Requested length: %s greater than existing: %s"
                                new-ecount old-ecount)
                        {})))
      (let [[indexes data] (if-not (= new-ecount old-ecount)
                             (let [buf-len (int (second (dtype-search/binary-search
                                                         indexes new-ecount
                                                         {:datatype :int32})))]
                               [(dtype-proto/sub-buffer indexes 0 buf-len)
                                (dtype-proto/sub-buffer data 0 buf-len)])
                             [indexes data])]
        (->SparseBuffer (+ b-offset offset)
                        length
                        sparse-value
                        indexes
                        data
                        buffer-datatype))))

  dtype-proto/PCopyRawData
  (copy-raw->item! [item dest dest-offset options]
    (dtype-base/raw-dtype-copy! item dest dest-offset options))

  dtype-proto/PPrototype
  (from-prototype [item datatype shape]
    (dtype-proto/make-container :sparse datatype (dtype-base/shape->ecount shape) {}))


  dtype-proto/PToArray
  (->sub-array [item] nil)
  (->array-copy [item]
    (dtype-proto/->array-copy (sparse-proto/->sparse item)))


  dtype-proto/PBufferType
  (buffer-type [item#] :sparse)


  dtype-proto/PToReader
  (convertible-to-reader? [item] true)
  (->reader [item options]
    (-> (sparse-proto/->sparse item)
        (dtype-proto/->reader options)))


  dtype-proto/PToIterable
  (convertible-to-iterable? [item] true)
  (->iterable [item options]
    (dtype-proto/->reader item options))


  dtype-proto/PToWriter
  (convertible-to-writer? [item] true)
  (->writer [item options]
    (let [data-datatype (dtype-proto/get-datatype data)
          writer-datatype (or (:datatype options) data-datatype)
          writer-fn (get sparse-writer-table (casting/safe-flatten data-datatype))]
      (-> (writer-fn item data-datatype (:unchecked? options))
          (dtype-proto/->writer options))))


  dtype-proto/PWriteIndexes
  (write-indexes! [item new-indexes new-values options]
    (locking item
      (let [n-elems (dtype-base/ecount new-indexes)]
        (when-not (= n-elems 0)
          (let [{new-indexes :indexes
                 new-values :data}
                (sparse-base/unordered-global-space->ordered-local-space
                 new-indexes new-values b-offset
                 (:indexes-in-order? options))
                idx-reader (typecast/datatype->reader :int32 new-indexes)
                start-idx (.read idx-reader 0)
                end-idx (.read idx-reader (- n-elems 1))
                offset (long (second (dtype-search/binary-search indexes start-idx
                                                                 {:datatype :int32})))
                [found? end-offset] (dtype-search/binary-search indexes end-idx
                                                                {:datatype :int32})
                length (- (long (if found?
                                  (+ (long end-offset) 1)
                                  end-offset))
                          offset)
                sub-indexes (dtype-base/sub-buffer indexes offset length)
                sub-data (dtype-base/sub-buffer data offset length)
                merge-fn (get sparse-merge-table (casting/safe-flatten
                                                  (dtype-base/get-datatype item)))
                {union-indexes :indexes
                 union-data :data}
                (merge-fn sparse-value sub-indexes sub-data
                          new-indexes new-values (:unchecked? options))]
            (dtype-base/remove-range! indexes offset length)
            (dtype-base/remove-range! data offset length)
            (dtype-base/insert-block! indexes offset union-indexes {:unchecked? true})
            (dtype-base/insert-block! data offset union-data {:unchecked? true})))))
    item)


  dtype-proto/PSetConstant
  (set-constant! [item offset value length]
    (locking item
      (when-not (<= (+ (int offset) (int length))
                    (dtype-base/ecount item))
        (throw (ex-info (format "Request count (%s) out of range (%s)")
                        (+ (int offset) (int length))
                        (dtype-base/ecount item))))
      (let [item-dtype (dtype-base/get-datatype item)
            value (casting/cast value item-dtype)
            offset (int offset)
            length (int length)]
        (if (= sparse-value value)
          (let [start-idx (global->local offset b-offset)
                end-idx (+ start-idx (max 0 (- length 1)))
                start-pos (int (second (dtype-search/binary-search indexes start-idx {:datatype :int32})))
                end-pos (int (second (dtype-search/binary-search indexes end-idx {:datatype :int32})))
                length (- end-pos start-pos)]
            (dtype-base/remove-range! indexes start-pos length)
            (dtype-base/remove-range! data start-pos length))
          (dtype-proto/write-indexes! item
                                      (range-reader/reader-range :int32 offset (+ length offset))
                                      (const-reader/make-const-reader value item-dtype length)
                                      {:unchecked? true
                                       :indexes-in-order? true}))))
    item)


  sparse-proto/PToSparse
  (convertible-to-sparse? [item] true)
  (->sparse [item] (make-base-reader item)))


(defn copy-sparse->any
  "Src *must* be a sparse buffer."
  [src dst options]
  (when-not (sparse-proto/sparse-convertible? src)
    (throw (ex-info "Source item must be sparse" {})))
  (let [src (sparse-proto/->sparse src)
        n-elems (dtype-base/ecount src)
        {src-indexes :indexes
         src-data :data} (sparse-proto/readers src)]
    (dtype-proto/set-constant! dst 0 (sparse-proto/sparse-value src) n-elems)
    (dtype-proto/write-indexes! dst src-indexes src-data
                                (assoc options :indexes-in-order? true))
    dst))


(defn copy-dense->sparse
  [src dst options]
  (let [n-elems (dtype-base/ecount src)]
    (dtype-proto/write-indexes! dst (range-reader/reader-range :int32 0 n-elems) src
                                (assoc options :indexes-in-order? true))
    dst))


(defn make-sparse-buffer
  [index-reader data-reader n-elems {:keys [sparse-value
                                            datatype]}]
  (let [datatype (or datatype (dtype-base/get-datatype data-reader))
        sparse-value (casting/cast (or sparse-value
                                       (sparse-reader/make-sparse-value datatype))
                                   datatype)
        index-list (if (dtype-proto/convertible-to-mutable? index-reader)
                     index-reader
                     (dtype-proto/make-container :list :int32 index-reader {}))
        data-list (if (and (dtype-proto/convertible-to-mutable? data-reader)
                           (= datatype (dtype-base/get-datatype data-reader)))
                    data-reader
                    (dtype-proto/make-container :list datatype data-reader {}))]
    (->SparseBuffer 0 n-elems
                    sparse-value
                    index-list data-list
                    datatype)))


(defmethod dtype-proto/make-container :sparse
  [container-type datatype elem-seq options]
  (if (number? elem-seq)
    (make-sparse-buffer (dtype-proto/make-container :list :int32 0 {})
                        (dtype-proto/make-container :list datatype 0 {})
                        (long elem-seq)
                        (assoc options :datatype datatype))
    (let [sparse-value (or (:sparse-value options)
                           (sparse-reader/make-sparse-value
                            datatype))
          reader-data (sparse-base/data->sparse-reader
                       (dtype-proto/->iterable
                        elem-seq {:datatype datatype
                                  :unchecked? (:unchecked? options)})
                       (merge options
                              {:datatype datatype
                               :sparse-value sparse-value}))
          {:keys [indexes data]} (sparse-proto/readers reader-data)
          [indexes data] (map dtype-proto/as-list [indexes data])]
      (make-sparse-buffer indexes data
                          (dtype-base/ecount reader-data)
                          {:sparse-value (sparse-proto/sparse-value reader-data)
                           :datatype datatype}))))


(defmethod dtype-proto/copy! [:sparse :dense]
  [dst src options]
  (copy-sparse->any src dst options))


(defmethod dtype-proto/copy! [:sparse :sparse]
  [dst src options]
  (copy-sparse->any src dst options))


(defmethod dtype-proto/copy! [:dense :sparse]
  [dst src options]
  (copy-dense->sparse src dst options))
