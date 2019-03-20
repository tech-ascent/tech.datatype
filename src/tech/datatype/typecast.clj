(ns tech.datatype.typecast
  (:require [tech.datatype.protocols :as dtype-proto]
            [tech.jna :as jna])
  (:import [tech.datatype
            ObjectWriter ObjectReader ObjectMutable
            ByteWriter ByteReader ByteMutable
            ShortWriter ShortReader ShortMutable
            IntWriter IntReader IntMutable
            LongWriter LongReader LongMutable
            FloatWriter FloatReader FloatMutable
            DoubleWriter DoubleReader DoubleMutable
            BooleanWriter BooleanReader BooleanMutable]
           [com.sun.jna Pointer]
           [java.nio Buffer ByteBuffer ShortBuffer
            IntBuffer LongBuffer FloatBuffer DoubleBuffer]
           [it.unimi.dsi.fastutil.booleans BooleanList BooleanArrayList]
           [it.unimi.dsi.fastutil.objects ObjectList ObjectArrayList]))



(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defmacro writer-type->datatype
  [writer-type]
  (case writer-type
    ObjectWriter `:object
    ByteWriter `:int8
    ShortWriter `:int16
    IntWriter `:int32
    LongWriter `:int64
    FloatWriter `:float32
    DoubleWriter `:float64
    BooleanWriter `:boolean))


(defmacro datatype->writer-type
  [datatype]
  (case datatype
    :int8 `ByteWriter
    :uint8 `ShortWriter
    :int16 `ShortWriter
    :uint16 `IntWriter
    :int32 `IntWriter
    :uint32 `LongWriter
    :int64 `LongWriter
    :uint64 `LongWriter
    :float32 `FloatWriter
    :float64 `DoubleWriter
    :boolean `BooleanWriter
    :object `ObjectWriter))



(defmacro implement-writer-cast
  [datatype]
  `(if (instance? (datatype->writer-type ~datatype) ~'item)
     ~'item
     (dtype-proto/->writer-of-type ~'item ~datatype ~'unchecked?)))


(defn ->byte-writer ^ByteWriter [item unchecked?] (implement-writer-cast :int8))
(defn ->short-writer ^ShortWriter [item unchecked?] (implement-writer-cast :int16))
(defn ->int-writer ^IntWriter [item unchecked?] (implement-writer-cast :int32))
(defn ->long-writer ^LongWriter [item unchecked?] (implement-writer-cast :int64))
(defn ->float-writer ^FloatWriter [item unchecked?] (implement-writer-cast :float32))
(defn ->double-writer ^DoubleWriter [item unchecked?] (implement-writer-cast :float64))
(defn ->boolean-writer ^BooleanWriter [item unchecked?] (implement-writer-cast :boolean))
(defn ->object-writer ^ObjectWriter [item unchecked?] (implement-writer-cast :object))


(defmacro datatype->writer
  [datatype writer unchecked?]
  (case datatype
    :int8 `(->byte-writer ~writer ~unchecked?)
    :uint8 `(->short-writer ~writer ~unchecked?)
    :int16 `(->short-writer ~writer ~unchecked?)
    :uint16 `(->int-writer ~writer ~unchecked?)
    :int32 `(->int-writer ~writer ~unchecked?)
    :uint32 `(->long-writer ~writer ~unchecked?)
    :int64 `(->long-writer ~writer ~unchecked?)
    :uint64 `(->long-writer ~writer ~unchecked?)
    :float32 `(->float-writer ~writer ~unchecked?)
    :float64 `(->double-writer ~writer ~unchecked?)
    :boolean `(->boolean-writer ~writer ~unchecked?)
    :object `(->object-writer ~writer ~unchecked?)))


(defmacro reader-type->datatype
  [reader-type]
  (case reader-type
    ObjectReader `:object
    ByteReader `:int8
    ShortReader `:int16
    IntReader `:int32
    LongReader `:int64
    FloatReader `:float32
    DoubleReader `:float64
    BooleanReader `:boolean))


(defmacro datatype->reader-type
  [datatype]
  (case datatype
    :int8 `ByteReader
    :uint8 `ShortReader
    :int16 `ShortReader
    :uint16 `IntReader
    :int32 `IntReader
    :uint32 `LongReader
    :int64 `LongReader
    :uint64 `LongReader
    :float32 `FloatReader
    :float64 `DoubleReader
    :boolean `BooleanReader
    :object `ObjectReader))


(defmacro implement-reader-cast
  [datatype]
  `(if (instance? (datatype->reader-type ~datatype) ~'item)
     ~'item
     (dtype-proto/->reader-of-type ~'item ~datatype ~'unchecked?)))


(defn ->byte-reader ^ByteReader [item unchecked?] (implement-reader-cast :int8))
(defn ->short-reader ^ShortReader [item unchecked?] (implement-reader-cast :int16))
(defn ->int-reader ^IntReader [item unchecked?] (implement-reader-cast :int32))
(defn ->long-reader ^LongReader [item unchecked?] (implement-reader-cast :int64))
(defn ->float-reader ^FloatReader [item unchecked?] (implement-reader-cast :float32))
(defn ->double-reader ^DoubleReader [item unchecked?] (implement-reader-cast :float64))
(defn ->boolean-reader ^BooleanReader [item unchecked?] (implement-reader-cast :boolean))
(defn ->object-reader ^ObjectReader [item unchecked?] (implement-reader-cast :object))


(defmacro datatype->reader
  [datatype reader unchecked?]
  (case datatype
    :int8 `(->byte-reader ~reader ~unchecked?)
    :uint8 `(->short-reader ~reader ~unchecked?)
    :int16 `(->short-reader ~reader ~unchecked?)
    :uint16 `(->int-reader ~reader ~unchecked?)
    :int32 `(->int-reader ~reader ~unchecked?)
    :uint32 `(->long-reader ~reader ~unchecked?)
    :int64 `(->long-reader ~reader ~unchecked?)
    :uint64 `(->long-reader ~reader ~unchecked?)
    :float32 `(->float-reader ~reader ~unchecked?)
    :float64 `(->double-reader ~reader ~unchecked?)
    :boolean `(->boolean-reader ~reader ~unchecked?)
    :object `(->object-reader ~reader ~unchecked?)))



(defmacro implement-mutable-cast
  [mut-type item]
  `(if (instance? ~mut-type ~item)
     ~item
     (throw (ex-info (format "Item is not desired mutable type: %s" ~mut-type)
                     {:desired-type ~mut-type}))))

(defn byte-mutable-cast ^ByteMutable [item] (implement-mutable-cast ByteMutable item))
(defn short-mutable-cast ^ShortMutable [item] (implement-mutable-cast ShortMutable item))
(defn int-mutable-cast ^IntMutable [item] (implement-mutable-cast IntMutable item))
(defn long-mutable-cast ^LongMutable [item] (implement-mutable-cast LongMutable item))
(defn float-mutable-cast ^FloatMutable [item] (implement-mutable-cast FloatMutable item))
(defn double-mutable-cast ^DoubleMutable [item] (implement-mutable-cast DoubleMutable item))
(defn boolean-mutable-cast ^BooleanMutable [item] (implement-mutable-cast BooleanMutable item))
(defn object-mutable-cast ^ObjectMutable [item] (implement-mutable-cast ObjectMutable item))


(defmacro datatype->mutable
  [datatype item]
  (case datatype
    :int8 `(byte-mutable-cast item)
    :int16 `(short-mutable-cast item)
    :int32 `(int-mutable-cast item)
    :int64 `(long-mutable-cast item)
    :float32 `(float-mutable-cast item)
    :float64 `(double-mutable-cast item)
    :boolean `(boolean-mutable-cast item)
    :object `(object-mutable-cast item)))


(defn as-byte-array
  ^bytes [obj] obj)

(defn as-short-array
  ^shorts [obj] obj)

(defn as-int-array
  ^ints [obj] obj)

(defn as-long-array
  ^longs [obj] obj)

(defn as-float-array
  ^floats [obj] obj)

(defn as-double-array
  ^doubles [obj] obj)

(defn as-boolean-array
  ^"[Z" [obj] obj)

(defn as-object-array
  ^"[Ljava.lang.Object;"
  [obj] obj)



(defmacro datatype->array-cast-fn
  [dtype buf]
  (case dtype
    :int8 `(as-byte-array ~buf)
    :int16 `(as-short-array ~buf)
    :int32 `(as-int-array ~buf)
    :int64 `(as-long-array ~buf)
    :float32 `(as-float-array ~buf)
    :float64 `(as-double-array ~buf)
    :boolean `(as-boolean-array ~buf)
    `(as-object-array ~buf)))


(defn ensure-ptr-like
  "JNA is extremely flexible in what it can take as an argument.  Anything convertible
  to a nio buffer, be it direct or array backend is fine."
  [item]
  (cond
    (satisfies? jna/PToPtr item)
    (jna/->ptr-backing-store item)
    :else
    (dtype-proto/->buffer-backing-store item)))


(defn as-ptr
  ^Pointer [item]
  (when (satisfies? jna/PToPtr item)
    (jna/->ptr-backing-store item)))


(defn as-array
  [item]
  (when (satisfies? dtype-proto/PToArray item)
    (dtype-proto/->sub-array item)))


(defn as-nio-buffer
  [item]
  (when (satisfies? dtype-proto/PToNioBuffer item)
    (dtype-proto/->buffer-backing-store item)))


(defn as-list
  [item]
  (when (satisfies? dtype-proto/PToList item)
    (dtype-proto/->list-backing-store item)))


(defn as-byte-buffer
  ^ByteBuffer [obj] obj)

(defn as-short-buffer
  ^ShortBuffer [obj] obj)

(defn as-int-buffer
  ^IntBuffer [obj] obj)

(defn as-long-buffer
  ^LongBuffer [obj] obj)

(defn as-float-buffer
  ^FloatBuffer [obj] obj)

(defn as-double-buffer
  ^DoubleBuffer [obj] obj)

(defn as-boolean-buffer
  ^BooleanList [obj] obj)

(defn as-object-buffer
  ^ObjectList [obj] obj)



(defmacro datatype->buffer-cast-fn
  [dtype buf]
  (condp = dtype
    :int8 `(as-byte-buffer ~buf)
    :int16 `(as-short-buffer ~buf)
    :int32 `(as-int-buffer ~buf)
    :int64 `(as-long-buffer ~buf)
    :float32 `(as-float-buffer ~buf)
    :float64 `(as-double-buffer ~buf)
    :boolean `(as-boolean-buffer ~buf)
    :object `(as-object-buffer ~buf)))