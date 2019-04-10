(ns tech.tensor
  (:require [tech.datatype :as dtype]
            [tech.tensor.impl :as impl]
            [tech.tensor.dimensions :as dims]
            [tech.datatype.functional :as dtype-fn]))


(defn ->tensor
  [data & {:keys [datatype container-type]
           :or {container-type :typed-buffer}
           :as options}]
  (let [data-shape (dtype/shape data)
        datatype (impl/datatype datatype)
        n-elems (apply * 1 data-shape)]
    (impl/construct-tensor
     (first
      (dtype/copy-raw->item!
       data
       (dtype/make-container container-type datatype n-elems options)
       0 options))
     (dims/dimensions data-shape))))


(defn new-tensor
  [shape & {:keys [datatype container-type]
            :or {container-type :typed-buffer}
            :as options}]
  (let [datatype (impl/datatype datatype)
        n-elems (apply * 1 shape)]
    (impl/construct-tensor
     (dtype/make-container container-type datatype n-elems options)
     (dims/dimensions shape))))


(defn rotate
  [tens rotate-vec]
  (let [tens (impl/ensure-tensor tens)]
    (assoc tens :dimensions
           (dims/rotate (impl/tensor->dimensions tens)
                        (mapv #(* -1 (long %)) rotate-vec)))))


(defn reshape
  [tens new-shape]
  (let [tens (impl/ensure-tensor tens)
        new-dims (dims/in-place-reshape (:dimensions tens)
                                        new-shape)]
    (impl/construct-tensor
     (dtype/sub-buffer (impl/tensor->buffer tens)
                       0 (dims/buffer-ecount new-dims))
     new-dims)))


(defn transpose
  [tens transpose-vec]
  (let [tens (impl/ensure-tensor tens)]
    (update tens :dimensions dims/transpose transpose-vec)))


(defn select
  [tens & args]

  (let [tens (impl/ensure-tensor tens)
        {new-dims :dims
         buf-offset :elem-offset
         buf-len :buffer-length}
        (apply dims/select (:dimensions tens) args)]
    (impl/construct-tensor (-> (impl/tensor->buffer tens)
                               (dtype/sub-buffer buf-offset buf-len))
                           new-dims)))


(defn clone
  [tens & {:keys [datatype]}]
  (let [datatype (or datatype
                     (dtype/get-datatype tens))
        new-tens (new-tensor (dtype/shape tens)
                             :datatype datatype)]
    (dtype/copy! (dtype/->reader-of-type tens (:datatype datatype))
                 (dtype/->writer new-tens))
    new-tens))
