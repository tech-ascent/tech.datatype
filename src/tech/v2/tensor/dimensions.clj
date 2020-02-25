(ns tech.v2.tensor.dimensions
  "Compute tensors dimensions control the shape and stride of the tensor along with
  offsetting into the actual data buffer.  This allows multiple backends to share a
  single implementation of a system that will allow transpose, reshape, etc. assuming
  the backend correctly interprets the shape and stride of the dimension objects.

  Shape vectors may have an index buffer in them at a specific dimension instead of a
  number.  This means that that dimension should be indexed indirectly.  If a shape has
  any index buffers then it is considered an indirect shape."
  (:require [tech.v2.datatype :as dtype]
            [tech.v2.tensor.dimensions.select :as dims-select]
            [tech.v2.tensor.dimensions.shape :as shape]
            [tech.v2.tensor.dimensions.global-to-local :as gtol]
            [tech.v2.datatype.functional :as dtype-fn]
            [tech.v2.datatype.typecast :as typecast]
            [tech.v2.datatype.base :as dtype-base]
            [tech.v2.datatype.unary-op :as unary-op]
            [tech.v2.datatype.binary-op :as binary-op]
            [tech.v2.datatype.boolean-op :as boolean-op]
            [tech.v2.datatype.readers.const :as const-reader]
            [tech.v2.datatype.readers.indexed :as indexed-reader]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.argsort :as argsort]
            [tech.v2.tensor.utils
             :refer [when-not-error reversev]
             :as utils])
  (:import [tech.v2.datatype
            IndexingSystem$Backward
            IntReader
            LongReader]
           [java.util List]
           [it.unimi.dsi.fastutil.ints IntArrayList]
           [clojure.lang IDeref]))


(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)


(defn extend-strides
  "With no error checking, setup a new stride for the given shape.
  If some of the original strides are known, they can be passed in."
  [shape & [original-strides]]
  (let [n-shape (dtype/ecount shape)
        n-original-strides (count original-strides)
        max-stride-idx (if (= 0 n-original-strides)
                         0
                         (dtype-fn/argmax {:datatype :int32} original-strides))
        strides (int-array n-shape)]
    (loop [idx 0
           max-stride-idx (int max-stride-idx)]
      (when (< idx n-shape)
        (let [local-idx (- n-shape idx 1)
              max-stride-idx
              (if (< idx n-original-strides)
                (let [org-idx (- n-original-strides idx 1)]
                  (aset strides local-idx (int (get original-strides org-idx)))
                  (if (= org-idx max-stride-idx)
                    local-idx
                    max-stride-idx))
                (if (= idx 0)
                  (do
                    (aset strides local-idx 1)
                    local-idx)
                  (do
                    (aset strides local-idx (* (aget strides max-stride-idx)
                                               (int (shape (+ local-idx 1)))))
                    local-idx)))]
          (recur (unchecked-inc idx) (int max-stride-idx)))))
    ;;Using persistent vectors for short things is often faster.
    (vec strides)))


(declare create-dimension-transforms)


(defrecord Dimensions [shape strides offsets max-shape dense?
                       ;;Implementations of IDeref
                       global->local
                       local->global])

(defn calculate-dense?
  "This gets called a *lot*."
  [shape strides]
  (let [n-shape (count shape)
        ^List shape shape
        ^List strides strides]
    (and (shape/direct-shape? shape)
         (if (= 1 n-shape)
           (= 1 (long (first strides)))
           (let [[max-stride num-shape]
                 (loop [item-idx 0
                        max-stride (long 1)
                        num-shape (long 1)]
                   (if (< item-idx n-shape)
                     (let [shape-entry (long (.get shape item-idx))
                           stride-entry (long (.get strides item-idx))
                           new-max-stride (if-not (= 1 shape-entry)
                                            (* stride-entry shape-entry)
                                            max-stride)]
                       (recur (inc item-idx)
                              (if (> new-max-stride max-stride)
                                new-max-stride
                                max-stride)
                              (* num-shape shape-entry)))
                     [max-stride num-shape]))]
             (= max-stride num-shape))))))


(defn dimensions
  "Dimensions contain information about how to map logical global indexes to local
  buffer addresses."
  [shape & {:keys [strides offsets max-shape]}]
  (let [shape (if (dtype/reader? shape) shape (vec shape))
        shape (if max-shape
                (let [num-extend (- (dtype-base/ecount max-shape)
                                    (dtype-base/ecount shape))]
                  (if-not (= num-extend 0)
                    (->> (concat (repeat num-extend 1)
                                 shape)
                         vec)
                    shape))
                shape)
        n-elems (dtype-base/ecount shape)]
    (if (and (= n-elems 1)
             (nil? max-shape)
             (nil? strides)
             (nil? offsets))
      (create-dimension-transforms
       (->Dimensions shape [1] [0]
                     (shape/shape->count-vec shape)
                     true
                     nil nil))
      (let [strides (if (= n-elems (dtype-base/ecount strides))
                      strides
                      (extend-strides shape strides))
            num-offsets (dtype-base/ecount offsets)
            num-extension (- n-elems num-offsets)
            offsets (if-not (= 0 num-extension)
                      (->> (concat (repeat num-extension 0)
                                   offsets)
                           vec)
                      offsets)
            max-shape (or max-shape (shape/shape->count-vec shape))
            half-retval (->Dimensions
                         shape strides offsets max-shape
                         (calculate-dense? shape strides)
                         nil nil)]
        (create-dimension-transforms half-retval)))))


(defn ecount
  "Return the element count indicated by the dimension map"
  ^long [{:keys [max-shape]}]
  (long (apply * max-shape)))


(defn buffer-ecount
  "What is the necessary ecount for a given buffer"
  ^long [{:keys [shape strides]}]
  ;;In this case the length of strides is so small as to make the general
  ;;method fast to just use object methods.
  (let [stride-idx (dtype-fn/argmax {:datatype :object} strides)
        stride-val (dtype/get-value strides stride-idx)
        shape-val (dtype/get-value shape stride-idx)
        shape-val (long
                   (cond
                     (number? shape-val)
                     shape-val
                     (shape/classified-sequence? shape-val)
                     (shape/classified-sequence-max shape-val)
                     :else
                     (apply max (dtype/->reader
                                 shape-val :int32))))]
    (* shape-val (long stride-val))))


(defn ->2d-shape
  "Given dimensions, return new dimensions with the lowest (fastest-changing) dimension
  unchanged and the rest of the dimensions multiplied into the higher dimension."
  [{:keys [shape]}]
  (shape/->2d shape))


(defn ->batch-shape
  "Given dimensions, return new dimensions with the lowest (fastest-changing) dimension
  unchanged and the rest of the dimensions multiplied into the higher dimension."
  [{:keys [shape]}]
  (shape/->2d shape))


(defn shape
  [{:keys [max-shape]}]
  max-shape)


(defn strides
  [{:keys [strides]}]
  strides)


(defn offsets
  [{:keys [offsets]}]
  offsets)


(defn dense?
  [dimensions]
  (:dense? dimensions))


(defn direct?
  [{:keys [shape]}]
  (shape/direct-shape? shape))


(defn indirect?
  [dims]
  (not (direct? dims)))


(defn access-increasing?
  "Are these dimensions setup such a naive seq through the data will be accessing memory
  in order.  This is necessary for external library interfaces (blas, cudnn).  An
  example would be after any nontrivial transpose that is not made concrete (copied)
  this condition will not hold."
  [{:keys [shape strides offsets]}]
  (and (shape/direct-shape? shape)
       (apply >= strides)
       (= 0 (apply + 0 offsets))))


(defn ->most-rapidly-changing-dimension
  "Get the size of the most rapidly changing dimension"
  ^long [{:keys [shape]}]
  (shape/shape-entry->count (last shape)))


(defn ->least-rapidly-changing-dimension
  "Get the size of the least rapidly changing dimension"
  ^long [{:keys [shape]}]
  (shape/shape-entry->count (first shape)))


(defn local-address->local-shape
  "Shape and strides are not transposed.  Returns
  [valid? local-shape-as-list]"
  [shape offsets strides shape-mins addr]
  (let [strides (typecast/datatype->reader :int32 strides)
        shape (typecast/datatype->reader :int32 shape)
        offsets (typecast/datatype->reader :int32 offsets)
        addr (int addr)
        n-elems (.lsize strides)
        retval (dtype/make-container :list :int32 0)
        retval-mut (typecast/datatype->mutable :int32 retval)]
    (loop [idx 0
           addr addr]
      (if (< idx n-elems)
        (let [local-stride (.read strides idx)
              shape-idx (quot addr local-stride)
              local-shape (.read shape idx)]
          (if (and (< shape-idx local-shape)
                   (>= shape-idx (long (shape-mins idx))))
            (let [shape-idx (- shape-idx (.read offsets idx))
                  shape-idx (if (< shape-idx 0)
                              (+ shape-idx local-shape)
                              shape-idx)]
              (.append retval-mut (int shape-idx))
              (recur (unchecked-inc idx) (rem addr local-stride)))
            (recur n-elems -1)))
        (when (= 0 addr)
          retval)))))


(defn dense-integer-dot-product
  ^long [^IntReader lhs ^IntReader rhs]
  (let [n-elems (.lsize lhs)]
    (loop [idx 0
           sum 0]
      (if (< idx n-elems)
        (recur (unchecked-inc idx)
               (+ sum (* (.read lhs idx)
                         (.read rhs idx))))
        sum))))


(defn get-elem-dims-local->global
  "Harder translation than above.  May return nil in the case where the inverse
  operation hasn't yet been derived.  In this case, the best you can do is a O(N)
  iteration similar to dense math."
  ^IndexingSystem$Backward
  [{:keys [shape strides offsets] :as dims}]
  (let [dims-direct? (direct? dims)
        strides-increasing? (apply >= strides)
        max-shape (:max-shape dims)
        broadcasting? (not= shape max-shape)
        n-shape (count shape)
        offsets (or offsets (const-reader/make-const-reader 0 :int32 n-shape))
        shape-mins (if dims-direct?
                     (const-reader/make-const-reader 0 :int32 n-shape)
                     (mapv (fn [shape-entry]
                             (cond
                               (number? shape-entry)
                               0
                               (shape/classified-sequence? shape-entry)
                               (:min shape-entry)
                               :else
                               (apply min (dtype/->iterable shape-entry))))
                           shape))
        [ordered-shape ordered-strides ordered-offsets
         ordered-shape-mins transpose-vec]
        (if strides-increasing?
          [shape strides offsets shape-mins nil]
          (let [index-ary (argsort/argsort strides {:datatype :int32 :reverse? true})]
            [(indexed-reader/make-indexed-reader index-ary shape {:datatype :object})
             (indexed-reader/make-indexed-reader index-ary strides {:datatype :int32})
             (indexed-reader/make-indexed-reader index-ary offsets {:datatype :int32})
             (if dims-direct?
               shape-mins
               (indexed-reader/make-indexed-reader index-ary shape-mins
                                                   {:datatype :int32}))
             ;;In order to invert an arbitrary transposition, argsort it
             (argsort/argsort index-ary {:datatype :int32})]))
        shape-obj-reader shape
        ;;Convert ordered shape into pure addresses
        ordered-shape (typecast/datatype->reader
                       :int32
                       (if dims-direct?
                         ordered-shape
                         (mapv (fn [shape-entry]
                                 (cond
                                   (number? shape-entry)
                                   shape-entry
                                   (shape/classified-sequence? shape-entry)
                                   (+ 1 (long (:max shape-entry)))
                                   :else
                                   (+ 1 (long
                                         (apply max (dtype/->iterable shape-entry))))))
                               ordered-shape)))
        ordered-strides (typecast/datatype->reader :int32 ordered-strides)
        ordered-offsets (typecast/datatype->reader :int32 ordered-offsets)
        global-shape (typecast/datatype->reader :int32 (if (and strides-increasing?
                                                                (not broadcasting?))
                                                         ordered-shape
                                                         (int-array max-shape)))
        global-strides (typecast/datatype->reader :int32
                                                  (extend-strides global-shape))
        [shape-mults shape-int-reader]
        (when broadcasting?
          (let [safe-shape (shape/shape->count-vec shape)]
            [(typecast/datatype->reader :int32 (mapv quot max-shape safe-shape))
             (typecast/datatype->reader :int32 safe-shape)]))
        ^IntReader shape-mults shape-mults
        ^IntReader shape-int-reader shape-int-reader
        n-global-shape (.lsize global-shape)]
    (cond
      (and direct?
           strides-increasing?
           (not broadcasting?))
      (reify
        IndexingSystem$Backward
        (localToGlobal [item local-idx]
          (let [[valid? addr]
                (loop [idx 0
                       addr (int local-idx)
                       sum (int 0)]
                  (if (< idx n-global-shape)
                    (let [local-stride (.read ordered-strides idx)
                          shape-idx (quot addr local-stride)
                          local-shape (.read ordered-shape idx)]
                      (if (< shape-idx local-shape)
                        (let [shape-idx (- shape-idx (.read ordered-offsets idx))
                              shape-idx (if (< shape-idx 0)
                                          (+ shape-idx local-shape)
                                          shape-idx)]
                          (recur (unchecked-inc idx)
                                 (rem addr local-stride)
                                 (+ sum (* shape-idx (.read global-strides idx)))))
                        ;;Terminate loop under failure condition
                        (recur n-global-shape
                               addr
                               sum)))
                    [(= 0 addr) sum]))]
            (when valid?
              [addr]))))
      :else
      (reify
        IndexingSystem$Backward
        (localToGlobal [item local-idx]
          (when-let [local-shape (local-address->local-shape
                                  ordered-shape ordered-offsets
                                  ordered-strides
                                  ordered-shape-mins local-idx)]
            (let [
                  ;;move the local shape into the global space
                  local-shape (if transpose-vec
                                (typecast/datatype->reader
                                 :int32
                                 (indexed-reader/make-indexed-reader
                                  transpose-vec local-shape {:datatype :int32
                                                             :unchecked? true}))
                                local-shape)
                  local-shape
                  (if dims-direct?
                    local-shape
                    (let [local-shape
                          (->> (map (fn [local-idx shape-entry _]
                                      (cond
                                        (number? shape-entry)
                                        local-idx
                                        (shape/classified-sequence? shape-entry)
                                        (shape/classified-sequence->global-addr
                                         shape-entry local-idx)
                                        :else
                                        (when-let [addr
                                                   (boolean-op/argfind
                                                    {:datatype :int32}
                                                    (boolean-op/make-boolean-unary-op
                                                     :int32
                                                     (= x local-idx))
                                                    shape-entry)]
                                          addr)))
                                    local-shape shape-obj-reader shape-mins)
                               (remove nil?)
                               vec)]
                      (when (= (count local-shape)
                               n-global-shape)
                        local-shape)))]
              (when (and local-shape)
                (let [local-shape (typecast/datatype->reader :int32
                                                             local-shape
                                                             true)
                      global-base-addr (int (dense-integer-dot-product
                                             local-shape global-strides))]
                  (if-not broadcasting?
                    [global-base-addr]
                    ;;Expansion out into global space
                    (let [retval (dtype/make-container :list :int32 0)]
                      (.add ^IntArrayList retval global-base-addr)
                      (loop [idx (int 0)]
                        (when (< idx n-global-shape)
                          (let [local-idx (- n-global-shape idx 1)
                                num-repeat (- (.read shape-mults local-idx) 1)
                                multiplier (* (.read global-strides local-idx)
                                              (.read shape-int-reader local-idx))
                                ;;This is not something that would work in c++.  The unary
                                ;;operation will make a reader out of retval which gets the
                                ;;underyling nio buffer of fixed size.  Then the list will
                                ;;perform insertions at the end evaulating the unary map
                                ;;exactly over this initial reader.
                                initial-reader (typecast/datatype->reader
                                                :int32 retval true)]
                            (loop [repeat-idx 0]
                              (when (< repeat-idx num-repeat)
                                (let [local-mult (* multiplier (+ repeat-idx 1))]
                                  (dtype/insert-block! retval (dtype/ecount retval)
                                                       (unary-op/default-unary-reader-map
                                                        {:datatype :int32 :unchecked? true}
                                                        (unary-op/make-unary-op
                                                         :int32 (+ x local-mult))
                                                        initial-reader)))
                                (recur (unchecked-inc repeat-idx)))))
                          (recur (unchecked-inc idx))))
                      retval)))))))))))


(defn create-dimension-transforms [dims]
  (assoc dims
         :global->local (delay (gtol/dims->global->local dims))
         ;;:global->local (delay (get-elem-dims-global->local dims))
         :local->global (delay (get-elem-dims-local->global dims))))


(defn ->global->local
  ^LongReader [dims]
  @(:global->local dims))


(defn ->local->global
  ^IndexingSystem$Backward [dims]
  @(:local->global dims))



(defn dimension-seq->max-shape
  "Given a sequence of dimensions return the max shape overall all dimensions."
  [& args]
  (let [shapes (map :shape args)
        max-count (long (apply max 0 (map count shapes)))
        ;;One extend shapes that are too small
        shapes (map (fn [shp]
                      (->> (concat (repeat (- max-count (count shp)) 1)
                                   shp)
                           vec))
                    shapes)]
    (vec (apply map (fn [& args]
                      (apply max 0 args))
                (map shape/shape->count-vec shapes)))))


(defn minimize
  "Make the dimensions of smaller rank by doing some minimization -
a. If the dimension is 1, strip it and associated stride.
b. Combine densely-packed dimensions (not as simple)."
  [dimensions]
  (let [stripped (->> (mapv vector
                            (-> (:shape dimensions)
                                shape/shape->count-vec)
                            (:strides dimensions))
                      (remove (fn [[shp _]]
                                (= 1 (long shp)))))]
    (if (= 0 (count stripped))
      {:shape [1] :strides [1]}
      (let [reverse-stripped (reverse stripped)
            reverse-stripped (reduce
                              (fn [reverse-stripped [[cur-shp cur-stride]
                                                     [last-shp last-stride]]]
                                ;;If the dimension is direct and the stride lines up.
                                (if (= (long cur-stride)
                                       (* (long last-shp) (long last-stride)))
                                  (let [[str-shp str-str] (last reverse-stripped)]
                                    (vec (concat (drop-last reverse-stripped)
                                                 [[(* (long str-shp) (long cur-shp))
                                                   str-str]])))
                                  (conj reverse-stripped [cur-shp cur-stride])))
                              [(first reverse-stripped)]
                              (map vector (rest reverse-stripped) reverse-stripped))
            stripped (reversev reverse-stripped)]
       {:shape (mapv first stripped)
        :strides (mapv second stripped)}))))


(defn in-place-reshape
  "Return new dimensions that correspond to an in-place reshape.  This is a very
  difficult algorithm to get correct as it needs to take into account changing strides
  and dense vs non-dense dimensions."
  [existing-dims shape]
  (let [new-dims (dimensions shape)]
    (when-not-error (<= (ecount new-dims)
                        (ecount existing-dims))
      "Reshaped dimensions are larger than tensor"
      {:tensor-ecount (ecount existing-dims)
       :reshape-ecount (ecount new-dims)})
    (when-not-error (direct? dimensions)
      "Dimensions must be direct for in-place-reshape."
      {:dimensions existing-dims})
    (cond
      ;; a dense brick is easiest case, regardless of
      ;; dimensionality.
      (and (access-increasing? existing-dims)
           (dense? existing-dims))
      (dimensions shape)
      ;;Padding creates islands of dense behavior.  We cannot reshape across islands.
      (access-increasing? existing-dims)
      (let [existing-dims (minimize existing-dims)
            existing-rev-shape (reversev (get existing-dims :shape))
            existing-rev-strides (reversev (get existing-dims :strides))
            ;;Find out where there are is padding added.  We cannot combine
            ;;indexes across non-packed boundaries.
            existing-info (mapv vector
                                existing-rev-shape
                                existing-rev-strides)
            new-shape-count (count shape)
            old-shape-count (count existing-info)
            max-old-idx (- old-shape-count 1)
            reverse-shape (reversev shape)
            ;;Index through new shape fitting new shape into old shape.  Each
            ;;time it fits you get a new stride based on the existing shape's
            ;;stride and your previous stride.
            rev-new-strides
            (loop [new-idx 0
                   old-idx 0
                   new-shape reverse-shape
                   existing-info existing-info
                   rev-new-strides []]
              (if (< new-idx new-shape-count)
                (let [[old-dim old-stride _old-packed?] (get existing-info
                                                            (min old-idx
                                                                 max-old-idx))
                      new-dim (long (get new-shape new-idx))
                      old-dim (long old-dim)
                      old-stride (long old-stride)]
                  (when-not-error (or (< old-idx old-shape-count)
                                      (= 1 new-dim))
                    "Ran out of old shape dimensions"
                    {:old-idx old-idx
                     :existing-info existing-info
                     :rev-new-strides rev-new-strides
                     :new-dim new-dim})
                  (cond
                    (= 1 new-dim)
                    (recur (inc new-idx) old-idx
                           new-shape existing-info
                           (conj rev-new-strides
                                 (* (long (or (last rev-new-strides) 1))
                                    (long (or (get reverse-shape (dec new-idx))
                                              1)))))
                    (= old-dim new-dim)
                    (recur (inc new-idx) (inc old-idx)
                           new-shape existing-info
                           (conj rev-new-strides old-stride))
                    (< old-dim new-dim)
                    ;;Due to minimization, this is always an error
                    (throw (ex-info "Cannot combine dimensions across padded boundaries"
                                    {:old-dim old-dim
                                     :new-dim new-dim}))
                    (> old-dim new-dim)
                    (do
                      (when-not-error (= 0 (rem old-dim new-dim))
                        "New dimension not commensurate with old dimension"
                        {:old-dim old-dim
                         :new-dim new-dim})
                      (recur (inc new-idx) old-idx
                             new-shape (assoc existing-info
                                              old-idx [(quot old-dim new-dim)
                                                       (* old-stride new-dim)])
                             (conj rev-new-strides old-stride)))))
                rev-new-strides))]
        (dimensions shape :strides (extend-strides shape (reversev rev-new-strides))))
      :else
      (throw (ex-info "Cannot in-place-reshape transposed or indirect dimensions"
                      {})))))


(defn transpose
  "Transpose the dimensions.  Returns a new dimensions that will access memory in a
  transposed order.
  Dimension 0 is the leftmost (greatest) dimension:

  (transpose tens (range (count (shape tens))))

  is the identity operation."
  [{:keys [shape offsets strides]} reorder-vec]
  (when-not-error (= (count (distinct reorder-vec))
                     (count shape))
    "Every dimension must be represented in the reorder vector"
    {:shape shape
     :reorder-vec reorder-vec})
  (let [shape (mapv #(dtype/get-value shape %) reorder-vec)
        strides (mapv #(dtype/get-value strides %) reorder-vec)
        offsets (mapv #(dtype/get-value offsets %) reorder-vec)]
    (dimensions shape :strides strides :offsets offsets)))



(defn select
  "Expanded implementation of the core.matrix select function call.  Each dimension must
  have an entry and each entry may be:
:all (identity)
:lla (reverse)
persistent-vector: [0 1 2 3 4 4 5] (not supported by all backends)
map: {:type [:+ :-]
      :min-item 0
      :max-item 50}
  Monotonically increasing/decreasing bounded (inclusive) sequences

tensor : int32, dense vector only.  Not supported by all backends.

;;Some examples
https://cloojure.github.io/doc/core.matrix/clojure.core.matrix.html#var-select"
  [dims & args]
  (let [data-shp (shape dims)]
    (when-not-error (= (count data-shp)
                       (count args))
      "arg count must match shape count"
      {:shape data-shp
       :args (vec args)})
    (let [{:keys [shape offsets strides]} dims
          ;;mapv here in order to correctly attribute timings during
          ;;profiling.
          shape (mapv dims-select/apply-select-arg-to-dimension shape args)
          {shape :dimension-seq
           strides :strides
           offsets :offsets
           offset :offset
           buffer-length :length
           :as _simplified-map} (dims-select/dimensions->simpified-dimensions
                                 shape strides offsets)]
      {:dims (dimensions shape :strides strides :offsets offsets)
       :elem-offset offset
       :buffer-length buffer-length})))


(defn rotate
  "Dimensional rotations are applied via offsetting."
  [dims new-offset-vec]
  (if (every? #(= 0 (long %)) new-offset-vec)
    dims
    (let [old-offsets (:offsets dims)
          _ (when-not (= (dtype-base/ecount old-offsets)
                         (dtype-base/ecount new-offset-vec))
              (throw (ex-info "Rotation offset vector count mismatch."
                              {:old-offset-count (dtype-base/ecount old-offsets)
                               :new-offsets-count (dtype-base/ecount new-offset-vec)})))
          new-offsets (mapv (fn [old-offset new-offset dim]
                              (let [potential-new-offset (+ (long old-offset)
                                                            (long new-offset))
                                    dim (long dim)]
                                (if (< potential-new-offset 0)
                                  (+ potential-new-offset
                                     (* (quot (+ (- potential-new-offset)
                                                 (- dim 1))
                                              dim)
                                        dim))
                                  (rem potential-new-offset dim))))
                            old-offsets
                            new-offset-vec
                            (shape/shape->count-vec (:shape dims)))]
      (dimensions (:shape dims)
                  :strides (:strides dims)
                  :offsets new-offsets))))


(defn dimensions->column-stride
  ^long [{:keys [shape strides]}]
  (long
   (let [dim-count (count strides)]
     (if (> dim-count 1)
       ;;get the second from the last stride
       (get strides (- dim-count 2))
       ;;Get the dimension count
       (get shape 0 1)))))


(defn trans-2d-shape
  [trans-a? dims]
  (let [[rows cols] (->2d-shape dims)]
    (if trans-a?
      [cols rows]
      [rows cols])))


(defn matrix-column-stride
  "Returns the larger of the 2 strides"
  ^long [{:keys [shape strides] :as dims}]
  (when-not-error (= 2 (count shape))
    "Not a matrix" {:dimensions dims})
  (apply max strides))


(defn matrix-element-stride
  ^long [{:keys [shape strides] :as dims}]
  (when-not-error (= 2 (count shape))
    "Not a matrix" {:dimensions dims})
  (apply min strides))


(defn contiguous-shape
  "Starting from the right, return a sequence that counts the total number of contigous
  elements see so far.  Used to decide if contiguous copy routines are worthwhile
  or if just normal parallelized reader copy is fine."
  [{:keys [shape strides]}]
  (loop [rev-shape (reverse shape)
         rev-strides (reverse strides)
         start-elem 1
         retval []]
    (if (and (number? (first rev-shape))
             (= (first rev-strides)
                start-elem))
      (let [n-elems (* (long (first rev-shape))
                       (long (first rev-strides)))]
        (recur (rest rev-shape)
               (rest rev-strides)
               n-elems
               (conj retval n-elems)))
      (seq (reverse retval)))))
