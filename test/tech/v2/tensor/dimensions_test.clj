(ns tech.v2.tensor.dimensions-test
  (:require [tech.v2.tensor.dimensions :as ct-dims]
            [clojure.test :refer :all]))


(defn ->raw
  [dimensions]
  (->> (select-keys dimensions [:shape :strides])
       (map (fn [[k v]]
              [k (vec v)]))
       (into {})))


(deftest in-place-reshape-test
  (is (= {:shape [6 2]
          :strides [2 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [3 2 2] [4 2 1]) [6 2]))))
  (is (= {:shape [3 4]
          :strides [4 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [3 2 2] [4 2 1])
           [3 4]))))
  #_(is (= {:shape [3 4]
          :strides [5 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [3 2 2] [5 2 1])
           [3 4]))))
  #_(is (= {:shape [20 8] :strides [10 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [4 5 8] [50 10 1])
           [20 8]))))
  #_(is (= {:shape [20 8 1 1] :strides [10 1 1 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [4 5 8] [50 10 1])
           [20 8 1 1]))))
  #_(is (= {:shape [1 1 20 8] :strides [200 200 10 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [4 5 8] [50 10 1])
           [1 1 20 8]))))
  (is (= {:shape [169 5] :strides [5 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [845] [1])
           [169 5]))))
   ;;This test is just f-ed up.  But the thing is that if the dimensions are dense then
  ;;in-place reshape that preserves ecount is possible; it is just an arbitrary
  ;;reinterpretation of the data.
  (is (= {:shape [10 4 9] :strides [36 9 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [10 1 18 2] [36 36 2 1])
           [10 4 9]))))
  #_(is (= {:shape [845 1] :strides [25 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [13 13 5 1] [1625 125 25 1])
           [845 1]))))
  (is (= {:shape [1 1] :strides [1 1]}
         (->raw
          (ct-dims/in-place-reshape
           (ct-dims/dimensions [1] [1])
           [1 1])))))
