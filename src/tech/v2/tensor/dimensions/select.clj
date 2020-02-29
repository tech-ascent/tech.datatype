(ns tech.v2.tensor.dimensions.select
  "Selecting subsets from a larger set of dimensions leads to its own algebra."
  (:require [tech.v2.tensor.dimensions.shape :as shape]
            [tech.v2.tensor.utils :refer [when-not-error]]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.typecast :as typecast]
            [tech.v2.datatype.monotonic-range :as dtype-range])
  (:import [tech.v2.datatype LongReader]))

(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)


(defn- expand-dimension
  [dim]
  (cond
    (number? dim)
    (dtype-range/make-range (long dim))
    (shape/classified-sequence? dim)
    dim
    :else
    (dtype/->reader dim :int64)))


(defn- expand-select-arg
  [select-arg]
  (cond
    (number? select-arg)
    (assoc (dtype-range/make-range (long select-arg))
           :scalar? true)
    (= :all select-arg)
    select-arg
    (= :lla select-arg)
    select-arg

    (shape/classified-sequence? select-arg)
    (shape/classify-sequence select-arg)

    (dtype/reader? select-arg)
    (dtype/->reader select-arg :int64)

    ;;else attempt to make it a reader
    :else
    (-> (vec select-arg)
        (dtype/->reader :int64))))


(defn apply-select-arg-to-dimension
  "Given a dimension and select argument, create a new dimension with
the selection applied."
  [dim select-arg]
  ;;Dim is now a reader
  (let [^LongReader dim (expand-dimension dim)
        ;;Select arg is now a map, a keyword, or a reader
        select-arg (expand-select-arg select-arg)]
    (cond
      (= select-arg :all)
      dim
      (= select-arg :lla)
      (shape/combine-classified-sequences dim
                                          (dtype-range/reverse-range (.lsize dim)))
      (dtype/reader? select-arg)
      (shape/combine-classified-sequences dim select-arg)
      :else
      (throw (Exception. "Unrecognized select argument")))))


(defn dimensions->simpified-dimensions
  "Given the dimensions post selection, produce a new dimension sequence combined with
  an offset that lets us know how much we should offset the base storage type.
  Simplification is important because it allows a backend to hit more fast paths.
Returns:
{:dimension-seq dimension-seq
:offset offset}"
  [dimension-seq stride-seq dim-offset-seq]
  (let [[dimension-seq strides dim-offsets offset]
        (reduce
         (fn [[dimension-seq strides dim-offsets offset]
              [dimension stride dim-offset]]
           (let [dim-type (if (map? dimension)
                            :classified-seqence
                            :reader)
                 [dim-type dimension]
                 (if (and (= :reader dim-type)
                          (= 1 (dtype/ecount dimension)))
                   (let [dim-val (dtype/get-value dimension 0)]
                     [:classified-sequence
                      {:type :+ :min-item dim-val :max-item dim-val}])
                   [dim-type dimension])]
             (case dim-type
               :reader
               [(conj dimension-seq dimension)
                (conj strides stride)
                (conj dim-offsets dim-offset)
                offset]
               :classified-sequence
               ;;Shift the sequence down and record the new offset.
               (let [{:keys [type min-item max-item sequence
                             scalar?]} dimension
                     max-item (- (long max-item) (long min-item))
                     new-offset (+ (long offset)
                                   (* (long stride)
                                      (long (:min-item dimension))))
                     min-item 0
                     dimension (cond-> (assoc dimension
                                              :min-item min-item
                                              :max-item max-item)
                                 sequence
                                 (assoc :sequence (mapv (fn [idx]
                                                          (- (long idx)
                                                             (long min-item)))
                                                        sequence)))
                     ;;Now simplify the dimension if possible
                     dimension (cond
                                 (= min-item max-item)
                                 1
                                 (= :+ type)
                                 (+ 1 max-item)
                                 sequence
                                 (:sequence dimension)
                                 :else
                                 dimension)]
                 ;;A scalar single select arg means drop the dimension.
                 (if-not (and (= 1 dimension)
                              scalar?
                              (= 0 (int dim-offset)))
                   [(conj dimension-seq dimension)
                    (conj strides stride)
                    (conj dim-offsets dim-offset)
                    new-offset]
                   ;;We keep track of offsetting but we don't add the
                   ;;element to the return value.
                   [dimension-seq strides dim-offsets new-offset]))
               ;;Only readers and classified sequences allowed here.
               (throw (ex-info "Bad dimension type"
                               {:dimension dimension})))))
         [[] [] [] 0]
         (map vector dimension-seq stride-seq dim-offset-seq))
        retval

        {:dimension-seq dimension-seq
         :strides strides
         :offsets dim-offsets
         :offset offset
         :length (when (shape/direct-shape? dimension-seq)
                   (apply + 1 (map * (map (comp dec shape/shape-entry->count)
                                          dimension-seq) strides)))}]
    retval))
