(ns tech.datatype
  "Generalized efficient manipulations of sequences of primitive datatype.  Includes
  specializations for java arrays and nio buffers, fastutil lists, and native buffers.
  There are specializations to allow implementations to provide efficient full typed
  copy functions when the types can be ascertained.

  Generic operations include:
  1. datatype of this sequence.
  2. Writing to, reading from.
  3. Construction.
  4. Efficient mutable copy from one sequence to another.
  5. Mutation of underlying storage amount assuming list based.

  Lists furthermore support add/removing elements at arbitrary indexes.

  Base datatypes are:
  :int8 :uint8 :int16 :uint16 :int32 :uint32 :int64 :uint64
  :float32 :float64 :boolean :string :object"
  (:require [clojure.core.matrix.macros :refer [c-for]]
            [clojure.core.matrix.protocols :as mp]
            [clojure.core.matrix :as m]
            [tech.datatype.base :as base]
            [tech.datatype.casting :as casting]
            [tech.datatype.protocols :as dtype-proto]
            [tech.datatype.array :as dtype-array]
            [tech.datatype.nio-buffer :as dtype-nio]
            [tech.datatype.typed-buffer :as dtype-tbuf]
            [tech.datatype.jna :as dtype-jna]
            [tech.datatype.binary-search :as dtype-search]
            [tech.datatype.argsort :as dtype-sort]
            [tech.datatype.reader :as dtype-reader]
            [tech.datatype.writer :as dtype-writer]
            [tech.datatype.iterator :as dtype-iter]
            [tech.parallel :as parallel])
  (:import [tech.datatype MutableRemove ObjectMutable])
  (:refer-clojure :exclude [cast]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn get-datatype
  [item]
  (base/get-datatype item))


(defn make-container
  "Generically make a container of the given type.  Built in types are:

  :java-array - Support for host primitive types, boolean, and all object types:

  :nio-buffer - Support for host primitive types.

  :native-buffer - Support for all (including unsigned) numeric types.  Native backed.

  :typed-buffer - Support all known datatypes, java array backend

  :list - Support all known datatypes and allow mutation.  fastutil array-list backed"
  [container-type datatype elem-count-or-seq & [options]]
  (dtype-proto/make-container container-type datatype
                              elem-count-or-seq options))


(defn make-jvm-container
  "Jvm container of fixed element count."
  [datatype elem-count-or-seq & [options]]
  (make-container :typed-buffer datatype elem-count-or-seq options))


(defn make-array-of-type
  "Make a jvm array."
  [datatype elem-count-or-seq & [options]]
  (dtype-proto/make-container :java-array datatype
                              elem-count-or-seq options))


(defn make-buffer-of-type
  "Make an array backed nio buffer."
  [datatype elem-count-or-seq & [options]]
  (dtype-proto/make-container :nio-buffer datatype
                              elem-count-or-seq (or options {})))


(defn make-native-container
  "Native container of fixed element count."
  [datatype elem-count-or-seq & [options]]
  (make-container :native-buffer datatype elem-count-or-seq options))


(defn make-jvm-list
  "Jvm container that allows adding/removing of elements which
  means is supports the ->mutable-of-type protocol."
  [datatype elem-count-or-seq & [options]]
  (make-container :list datatype elem-count-or-seq options))


(defn set-value!
  "Set a value on a container."
  [item offset value]
  (base/set-value! item offset value))


(defn set-constant!
  "Set a constant value on a container."
  [item offset value elem-count]
  (base/set-constant! item offset value elem-count))


(defn get-value
  "Get a value from a container."
  [item offset]
  (base/get-value item offset))


(defn ecount
  "Type hinted ecount so numeric expressions run faster.
Calls clojure.core.matrix/ecount."
  ^long [item]
  (if (nil? item)
    0
    (base/ecount item)))


(defn shape
  "m/shape with fallback to m/ecount if m/shape is not available."
  [item]
  (base/shape item))


(defn shape->ecount
  "Get an ecount from a shape."
  ^long [shape-or-num]
  (if (nil? shape-or-num)
    0
    (base/shape->ecount shape-or-num)))


(defn datatype->byte-size
  "Get the byte size of a given (numeric only) datatype."
  ^long [datatype]
  (base/datatype->byte-size datatype))


(defn cast
  "Cast a value to a datatype.  Throw exception if out or range."
  [value datatype]
  (casting/cast value datatype))


(defn unchecked-cast
  "Cast a value to a datatype ignoring errors."
  [value datatype]
  (casting/unchecked-cast value datatype))


(defn ->vector
  "Conversion to persistent vector"
  [item]
  (base/->vector item))


(defn from-prototype
  "Create a new thing like the old thing.  Does not require copying
  data into the new thing."
  [item & {:keys [datatype shape]}]
  (dtype-proto/from-prototype item
                       (or datatype (get-datatype item))
                       (or shape (base/shape item))))


(defn clone
  "Create a new thing from the old thing and copy data into new thing."
  [item & {:keys [datatype]}]
  (dtype-proto/clone item (or datatype (get-datatype item))))


(defn ->array
  "Returns nil of item does not share a backing store with an array."
  [item]
  (dtype-proto/->array item))


(defn ->sub-array
  "Returns map of the backing array plus a length and offset
  of nil of this item is not array backed.  The sub array may not match
  the container in datatype.
  {:array-data :offset :length}"
  [item]
  (dtype-proto/->sub-array item))


(defn ->array-copy
  "Copy the data into an array that can correctly hold the datatype.  This
  array may not have the same datatype as the source item"
  [item]
  (dtype-proto/->array-copy item))


(defn copy!
  "Copy a block of data from one container to another"
  ([src src-offset dst dst-offset n-elems options]
   (base/copy! src src-offset dst dst-offset n-elems options))
  ([src src-offset dst dst-offset n-elems]
   (base/copy! src src-offset dst dst-offset n-elems {}))
  ([src dst]
   (base/copy! src 0 dst 0 (ecount src) {})))


(defn copy-raw->item!
  "Copy a non-datatype sequence of data into a datatype library container.
  [dtype-container result-offset-after-copy]"
  ([raw-data container container-offset options]
   (dtype-proto/copy-raw->item! raw-data container
                                container-offset options))
  ([raw-data container container-offset]
   (copy-raw->item! raw-data container container-offset {}))
  ([raw-data container]
   (copy-raw->item! raw-data container 0)))


(defn write-block!
  "Write a block of data to an object.  Values must support ->reader-of-type,
  get-datatype, and core.matrix.protocols/element-count."
  [item offset values & [options]]
  (dtype-proto/write-block! item offset values options))


(defn read-block!
  "Read a block from an object.  Values must support ->writer-of-type,
  get-datatype, and core.matrix.protocols/element-count."
  [item offset values & [options]]
  (dtype-proto/read-block! item offset values options))


(defn write-indexes!
  "Write a block of data to specific indexes in the object.
  indexes, values must support same protocols as write and read block."
  [item indexes values & [options]]
  (dtype-proto/write-indexes! item indexes values options))


(defn read-indexes!
  "Write a block of data to specific indexes in the object.
  indexes, values must support same protocols as write and read block."
  [item indexes values & [options]]
  (dtype-proto/read-indexes! item indexes values options))


(defn insert!
  [item idx value]
  (.insert ^ObjectMutable (dtype-proto/->mutable-of-type item :object false)
           idx value))


(defn remove!
  [item idx]
  (let [mut-item ^MutableRemove (dtype-proto/->mutable-of-type
                                 item (get-datatype item) true)]
    (.remove mut-item (int idx))))


(defn insert-block!
  [item idx values & [options]]
  (dtype-proto/insert-block! item idx values options))


(defn remove-range!
  [item start-idx n-elems]
  (let [mut-item ^MutableRemove (dtype-proto/->mutable-of-type
                                 item (get-datatype item) true)]
    (.removeRange mut-item start-idx n-elems)))


(defn ->buffer-backing-store
  "Convert to nio buffer that stores the data for the object.  This may have
  a different datatype than the object, so for instance the backing store for
  the uint8 datatype is a nio buffer of type int8."
  [src-ary]
  (dtype-proto/->buffer-backing-store src-ary))


(defn ->list-backing-store
  "Convert to a list that stores data for the object.  This may have a different
  datatype than the object."
  [src-item]
  (dtype-proto/->list-backing-store src-item))


(defn sub-buffer
  "Create a sub buffer that shares the backing store with the main buffer."
  [buffer offset & [length]]
  (let [length (or length (max 0 (- (ecount buffer)
                                    (long offset))))]
    (dtype-proto/sub-buffer buffer offset length)))

(defn alias?
  "Do these two buffers alias each other?  Meaning do they start at the same address
  and overlap completely?"
  [lhs-buffer rhs-buffer]
  (dtype-proto/alias? lhs-buffer rhs-buffer))

(defn partially-alias?
  "Do these two buffers partially alias each other?  Does some sub-range of their
  data overlap?"
  [lhs-buffer rhs-buffer]
  (dtype-proto/partially-alias? lhs-buffer rhs-buffer))


(defn ->writer-of-type
  "Create a writer of a specific type."
  [src-item datatype & [options]]
  (dtype-proto/->writer-of-type src-item datatype (:unchecked? options)))


(defn ->reader-of-type
  "Create a reader of a specific type."
  [src-item datatype & [options]]
  (dtype-proto/->reader-of-type src-item datatype (:unchecked? options)))


(defn ->mutable-of-type
  "Create an object capable of mutating the underlying structure of the data storage.
  Only works for list-backed types."
  [src-item datatype & [options]]
  (dtype-proto/->mutable-of-type src-item datatype (:unchecked? options)))
