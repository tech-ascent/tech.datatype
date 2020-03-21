# Dimensions and Bytecode Generation


## Translating from N-Dimensional Space to 1 Dimensional Space


We want to be able to take 1 dimensional buffers of data and represent N-dimensional
hyper-rectangular spaces.  Lots of things are n-dimensional and hyperrectangular; things
like images and datasets and volumes and 3d objects so let's take a moment and talk
about the abstractions required efficiently present this interface.


`dimensions` objects describe the N-dimensional addressing scheme and perform the
translation from our n-dimensional 'global' space into our 1-dimensional 'local'
dense address spaces.  In other works they describe how to randomly addressed data
buffers using an n-dimensional addressing scheme that supports numpy style in-place
slicing, transpose, broadcasting, reshape, and APL style rotations.  In this way
they are designed to add such style operations to any randomly addressed system.

Some examples of dimension object operations:


```clojure
user> (require '[tech.v2.tensor :as dtt]))

user> (def tens (dtt/->tensor (partition 2 (range 4))
                              :datatype :int32))

#'user/tens
user> tens
#tech.v2.tensor<int32>[2 2]
[[0 1]
 [2 3]]
user> ;;Select selects subregions and can reorder dimension indexes
user> (dtt/select tens 1 :all)
#tech.v2.tensor<int32>[2]
[2 3]
user> (dtt/select tens 1 [1 0])
#tech.v2.tensor<int32>[2]
[3 2]
user> ;;Transpose reorders dimensions
user> (dtt/transpose tens [1 0])
#tech.v2.tensor<int32>[2 2]
[[0 2]
 [1 3]]
user> ;;Broadcasting duplicates dimensions
user> (dtt/broadcast tens [2 6])
#tech.v2.tensor<int32>[2 6]
[[0 1 0 1 0 1]
 [2 3 2 3 2 3]]
```

These operations are all supported by the
[dimensions](../src/tech/v2/tensor/dimensions.clj) object.  This object is responsible
for, given an address in the (possibly n dimensional) input space, produce an address
in the linearly-addressed 'local' space of the buffer.


```clojure
user> (dtt/tensor->dimensions tens)
{:shape [2 2],
 :strides [2 1],
 :offsets [0 0],
 :max-shape [2 2],
 :dense? true,
 :global->local #<Delay@1d1e2654: :not-delivered>,
 :local->global #<Delay@2caa3a90: :not-delivered>}

user> (dtt/tensor->dimensions (dtt/select tens 1 [1 0]))
{:shape [[1 0]],
 :strides [1],
 :offsets [0],
 :max-shape [2],
 :dense? false,
 :global->local #<Delay@4cf3fcb2: :not-delivered>,
 :local->global #<Delay@103dda2f: :not-delivered>}
 ```


 ## Linearizing the N-Dimensional Global Address Space

 If you have used linear algebra libraries in the past you know they talk about if
 an operation is 'row-major' or 'column-major'.  Fortran presents a 'column-major'
 abstraction while images (jpeg,png) are 'row-major'.

 This terms talk about the linearization of their spaces onto  the underlying storage
 layer.  The datatype library is 'row-major', unlike Fortran.  This means that:

 ```clojure
user> ;; A 2x2 matrix in row major
user> (dtt/->tensor (partition 2 (range 4)))

#tech.v2.tensor<float64>[2 2]
[[0.000 1.000]
 [2.000 3.000]]
user> ;;A 2x2 matrix in column major
user> (dtt/transpose (dtt/->tensor (partition 2 (range 4)))
                     [1 0])
#tech.v2.tensor<float64>[2 2]
[[0.000 2.000]
 [1.000 3.000]]
```

If we are going to iterate through every element in a matrix or N-dimensional object
and we start at index 0 and go to index `(- element-count 1)` then we have linearized
the N-dimensional address space.  This operation happens a *lot* in the datatype
library as it forms the foundation of copying data and applying elementwise operations.

There are some observations we want to make about this linearization that are very
useful for many optimizations.


Transforming into this linear space is very simple, it is a summation of the
input dimension multiplied by the stride at that dimension:

```clojure
user> (dtt/tensor->dimensions (dtt/->tensor (partition 2 (range 4))))

{:shape [2 2],
 :strides [2 1],
 :offsets [0 0],
 :max-shape [2 2],
 :dense? true,
 :global->local #<Delay@4a0f7470: :not-delivered>,
 :local->global #<Delay@70144938: :not-delivered>}
user> ;;translate from global address space [1 0] to linearized global address space
user> (+ (* 1 (nth (:strides *1) 0))
         (* 0 (nth (:strides *1) 1)))
2
```


## Reduced Dimensions

[tech.v2.tensor.dimensions.analysis.clj](../src/tech/v2/tensor/dimensions/analysis.clj)
presents ways to gain insight into some properties of the dimension objects.
One operation it presents is the ability to reduce the dimensionality of an object 
while keeping the row-major iteration order of global->local indexes the same.


Reducing dimensions allows us to define a minimum number of operations required to
go from a global address space to a local address space.

Here are some examples of reducing dimensions.

```clojure
user> (require '[tech.v2.tensor.dimensions :as dims])
nil
user> (require '[tech.v2.tensor.dimensions.analytics :as dims-analytics])
nil
user> (dims-analytics/reduce-dimensionality
       (dims/dimensions [4 4]))
{:shape [16], :strides [1], :offsets nil, :max-shape [16], :max-shape-strides [1]}
user> (dims-analytics/reduce-dimensionality
       (dims/dimensions [2 2 4]))
{:shape [16], :strides [1], :offsets nil, :max-shape [16], :max-shape-strides [1]}
user> (dims-analytics/reduce-dimensionality
       (dims/dimensions [2 2 2 2]))
{:shape [16], :strides [1], :offsets nil, :max-shape [16], :max-shape-strides [1]}


user> ;;Broadcasting changes the max shape but not the shape
user> (dims-analytics/reduce-dimensionality
       (dims/dimensions [4 4]))
{:shape [16], :strides [1], :offsets nil, :max-shape [16], :max-shape-strides [1]}
user> (dims-analytics/reduce-dimensionality
       (dims/dimensions [4 4]
                        :max-shape [8 4]))
{:shape [16], :strides [1], :offsets nil, :max-shape [32], :max-shape-strides [1]}
```


Here are two of many important properties of reduced dimensions:

* If the size of the shape array is 1, the shape is a number, and the stride is 1
and there is no broadcasting or offsets then the underlying buffer is represented
'natively' which means that instead of using the tensor for an elementwise operation
you can substitute the underlying buffer.  This means that coping data into/outof
the tensor can use fast paths such as `System/arrayCopy` and `memcpy`.  In this
case our address space operator simply returns the input.


* If the last stride is 1 and the shape is a number then this object's last
dimension is accessed natively and there is a fast `row-copy` type operation that
can be used to perform copying of, for instance, sub-images into or out of larger
images and thus performing the basis of rendering sprites.  In this case the
address operator's last (most rapidly changing) dimension operation is a 'remainder'
operation of the input global address space.


These types of properties do not fall out of full dimensions as there are many
distinct different non-reduced-dimensions that all correspond to the same
global->local address space transformation.


## Building An Addressing Operator


Regardless of how much we reduce our dimensionality problem, we can't make it
completely go away and we will need to have an operator that, given the reduced
dimensions, can transform an index in global linearized space into local linearized
space.


The old pathway we took had us attempting top spot specific optimizations that would
hit various fast paths and build operators for exactly that condition.  This worked
OK but there are just a lot of possible interactions we couldn't optimize for.  So
we decided to build an addressing operator by first producing an Abstract Syntax
Tree (AST) implementation of the reduced dimension pathway and then producing an
implementation using purely java bytecode.


This reduces the special case pathway to a set of general conditions that we can
test much more thoroughly as well as producing objects that are tailored
specifically to the addressing scheme presented by the reduced dimensions.


### Step 1 - An Abstract Syntax Tree


Our first step, once producing correct reduced dimensions is to produce an AST that
describes the global->local transformation.  We can build our transformation
out of the reduced dimensions directly along with one more variable,
`max-shape-strides` which are a strides array created out of the max-shape
variable.


We reduce the dimensions.  Below the example represents an image
with dimensions [height width n-channels] that was cropped from
a larger image with concrete dimensions [2048 2048 4].  That means
that width and channels are contiguous in memory while each row
is strided.  Put another way height is non-contiguously strided by
a dimension larger that `(* width n-channels)` and thus we cannot
collapse it.

```clojure

user>;;Image dimensions when you have a 2048x2048 image and you
user>;;want to crop a 256x256 sub-image out of it.
user> (def src-dims (dims/dimensions [256 256 4]
                                     :strides [8192 4 1]))
#'user/src-dims
user> src-dims
{:shape [256 256 4],
 :strides [8192 4 1],
 :offsets [0 0 0],
 :max-shape [256 256 4],
 :dense? false,
 :global->local #<Delay@616813ee: :not-delivered>,
 :local->global #<Delay@6fddf50a: :not-delivered>}

user> ;;Because we are cropping out of a larger image, we have strided rows
user> ;;but data within a row is contiguous.
user> (def reduced-dims (dims-analytics/reduce-dimensionality src-dims))
#'user/reduced-dims
user> reduced-dims
{:shape [256, 1024],
 :strides [8192, 1],
 :offsets nil,
 :max-shape [256, 1024],
 :max-shape-strides [1024, 1]}

```

Once we have a reduced expression of our dimension space, we can build an AST
that expressed exactly the transformation required to transform correctly from
the global index space into the local index space:

```clojure
user> (require '[tech.v2.tensor.dimensions.global-to-local :as gtol])
nil
user> (def test-ast (gtol/global->local-ast reduced-dims))
#'user/test-ast
user> test-ast
{:signature
 {:n-dims 2,
  :direct-vec [true true],
  :offsets? false,
  :broadcast? false,
  :trivial-last-stride? true},
 :ast
 (+
  (*
   (quot idx {:ary-name :max-shape-stride, :dim-idx 0})
   {:ary-name :stride, :dim-idx 0})
  (rem idx {:ary-name :shape, :dim-idx 1}))}
```

The AST above efficiently implements the global->local address space translation
for exactly those reduced dims.  The AST can be completely recreated given only
the signature thus we can cache compiled AST representations by signature.

Keeping in mind that the least rapidly changing dimension, `height`, is dimension
0 and that width and channels have been collapsed into a single contiguous
dimension we can write that AST in a slightly more human readable way:

```clojure
'(+ (* (quot idx max-shape-stride-height) stride-height)
    (rem idx shape-widthchan))
```


### Step 2 - A Class Definition


Now we start interacting with Justin Conklin's excellent
[insn](https://github.com/jgpc42/insn) library.  `insn` is great because it wraps
the bytecode generation facilities of [ow2.asm](https://asm.ow2.io/) in a
functional, declarative abstraction *and stops right there* :-).  This is a
signficant foundational piece to building a great bytecode compiler because it is
easy to visually inspect the bytecode before it is sent to the actual compiler.  It
is also easy to build as it is simply an abstraction built out of some of the
fundamental types of Clojure.  This is really nice because this AST gets automatic
visualization via the REPL thus solving one of the problems with AST's - namely that 
they can be opaque and difficult to debug.


The [bytecode](https://en.wikipedia.org/wiki/Java_bytecode_instruction_listings)
translation of our AST is:

```clojure
user> (def class-def (gtol/gen-ast-class-def test-ast))
#'user/class-def
user> class-def
{:name tech.v2.datatype.GToL2TTOffFBcastFTrivLastST,
 :interfaces [tech.v2.datatype.LongReader],
 :fields
 [{:flags #{:public :final}, :name "maxShapeStride0", :type :long}
  {:flags #{:public :final}, :name "shape1", :type :long}
  {:flags #{:public :final}, :name "stride0", :type :long}
  {:flags #{:public :final}, :name "nElems", :type :long}],
 :methods
 [{:flags #{:public},
   :name :init,
   :desc [[Ljava.lang.Object; [J [J [J [J :void],
   :emit
   [[:aload 0]
    [:invokespecial :super :init [:void]]
    [:aload 0]
    [:aload 5]
    [:ldc 0]
    [:laload]
    [:putfield :this "maxShapeStride0" :long]
    [:aload 0]
    [:aload 1]
    [:ldc 1]
    [:aaload]
    [:checkcast java.lang.Long]
    [:invokevirtual java.lang.Long "longValue"]
    [:putfield :this "shape1" :long]
    [:aload 0]
    [:aload 2]
    [:ldc 0]
    [:laload]
    [:putfield :this "stride0" :long]
    [:aload 0]
    [:aload 4]
    [:ldc 0]
    [:laload]
    [:aload 5]
    [:ldc 0]
    [:laload]
    [:lmul]
    [:putfield :this "nElems" :long]
    [:return]]}
  {:flags #{:public},
   :name "lsize",
   :desc [:long],
   :emit [[:aload 0] [:getfield :this "nElems" :long] [:lreturn]]}
  {:flags #{:public},
   :name "read",
   :desc [:long :long],
   :emit
   [[:lload 1]
    [:aload 0]
    [:getfield :this "maxShapeStride0" :long]
    [:ldiv]
    [:aload 0]
    [:getfield :this "stride0" :long]
    [:lmul]
    [:lload 1]
    [:aload 0]
    [:getfield :this "shape1" :long]
    [:lrem]
    [:ladd]
    [:lreturn]]}]}
```

In order to formulate this, we simply wrote a couple java files and compiled them,
then printed the byte code with
[javap](https://docs.oracle.com/javase/8/docs/technotes/tools/windows/javap.html).
You can find some example java files compiled to readable AST definitions in our
[example-bytecode directory](../example-bytecode).


It is important to note that the class derives from
[LongReader](../java/tech/v2/datatype/LongReader.java).  This is an interface that
defines an [:int64->:int64] randomly addressable translation which is appropriate
for our global->local address space pathway.


### Defining Classes And The Rest


We now compile the bytecode to a class and call that class's constructor:

```clojure
user> (require '[insn.core :as insn])
nil
user> (def class-obj (insn/define class-def))
#'user/class-obj
user> (def idx-obj (.newInstance first-constructor
                                 (gtol/reduced-dims->constructor-args reduced-dims)))
#'user/idx-obj
```


And what did we get back?

```clojure
user> (instance? tech.v2.datatype.LongReader idx-obj)
true
user> (count idx-obj)
262144
user> ;;Due to striding, there is a discontinuity at index 1024
user> (map idx-obj (range 1020 1030))
(1020 1021 1022 1023 8192 8193 8194 8195 8196 8197)
```

This is great!  We now have an implementation of LongReader compiled specifically
to the equation it takes to transform those dimensions (and that have the same
properties) as efficiently as possible into the local address space.  This overall
type translation also works if we do something like reverse the indexes of the first
dimension:

```clojure
user> (def src-dims (dims/dimensions [256 256 [3 2 1 0]]
                                     :strides [8192 4 1]))

#'user/src-dims
user> (def reduced-dims (dims-analytics/reduce-dimensionality src-dims))
#'user/reduced-dims
user> reduced-dims
{:shape [256, 256, [3 2 1 0]],
 :strides [8192, 4, 1],
 :offsets nil,
 :max-shape [256, 256, 4],
 :max-shape-strides [1024, 4, 1]}

user> (def test-ast (gtol/global->local-ast reduced-dims))
#'user/test-ast
user> test-ast
{:signature
 {:n-dims 3,
  :direct-vec [true true false],
  :offsets? false,
  :broadcast? false,
  :trivial-last-stride? true},
 :ast
 (+
  (*
   (quot idx {:ary-name :max-shape-stride, :dim-idx 0})
   {:ary-name :stride, :dim-idx 0})
  (*
   (rem
    (quot idx {:ary-name :max-shape-stride, :dim-idx 1})
    {:ary-name :shape, :dim-idx 1})
   {:ary-name :stride, :dim-idx 1})
  (.read
   {:ary-name :shape, :dim-idx 2}
   (rem idx {:ary-name :shape, :dim-idx 2, :fn-name :lsize})))}
user> (def class-def (gtol/gen-ast-class-def test-ast))
#'user/class-def
user> class-def
{:name tech.v2.datatype.GToL3TTFOffFBcastFTrivLastST,
 :interfaces [tech.v2.datatype.LongReader],
 :fields
 [{:flags #{:public :final}, :name "maxShapeStride0", :type :long}
  {:flags #{:public :final}, :name "maxShapeStride1", :type :long}
  {:flags #{:public :final}, :name "shape1", :type :long}
  {:flags #{:public :final}, :name "shape2", :type tech.v2.datatype.LongReader}
  {:flags #{:public :final}, :name "shape2-lsize", :type :long}
  {:flags #{:public :final}, :name "stride0", :type :long}
  {:flags #{:public :final}, :name "stride1", :type :long}
  {:flags #{:public :final}, :name "nElems", :type :long}],
 :methods
 [{:flags #{:public},
   :name :init,
   :desc [[Ljava.lang.Object; [J [J [J [J :void],
   :emit
   [[:aload 0]
    [:invokespecial :super :init [:void]]
    [:aload 0]
    [:aload 5]
    [:ldc 0]
    [:laload]
    [:putfield :this "maxShapeStride0" :long]
    [:aload 0]
    [:aload 5]
    [:ldc 1]
    [:laload]
    [:putfield :this "maxShapeStride1" :long]
    [:aload 0]
    [:aload 1]
    [:ldc 1]
    [:aaload]
    [:checkcast java.lang.Long]
    [:invokevirtual java.lang.Long "longValue"]
    [:putfield :this "shape1" :long]
    [:aload 0]
    [:aload 1]
    [:ldc 2]
    [:aaload]
    [:checkcast tech.v2.datatype.LongReader]
    [:putfield :this "shape2" tech.v2.datatype.LongReader]
    [:aload 0]
    [:aload 1]
    [:ldc 2]
    [:aaload]
    [:checkcast tech.v2.datatype.LongReader]
    [:invokeinterface tech.v2.datatype.LongReader "lsize"]
    [:putfield :this "shape2-lsize" :long]
    [:aload 0]
    [:aload 2]
    [:ldc 0]
    [:laload]
    [:putfield :this "stride0" :long]
    [:aload 0]
    [:aload 2]
    [:ldc 1]
    [:laload]
    [:putfield :this "stride1" :long]
    [:aload 0]
    [:aload 4]
    [:ldc 0]
    [:laload]
    [:aload 5]
    [:ldc 0]
    [:laload]
    [:lmul]
    [:putfield :this "nElems" :long]
    [:return]]}
  {:flags #{:public},
   :name "lsize",
   :desc [:long],
   :emit [[:aload 0] [:getfield :this "nElems" :long] [:lreturn]]}
  {:flags #{:public},
   :name "read",
   :desc [:long :long],
   :emit
   [[:lload 1]
    [:aload 0]
    [:getfield :this "maxShapeStride0" :long]
    [:ldiv]
    [:aload 0]
    [:getfield :this "stride0" :long]
    [:lmul]
    [:lload 1]
    [:aload 0]
    [:getfield :this "maxShapeStride1" :long]
    [:ldiv]
    [:aload 0]
    [:getfield :this "shape1" :long]
    [:lrem]
    [:aload 0]
    [:getfield :this "stride1" :long]
    [:lmul]
    [:ladd]
    [:aload 0]
    [:getfield :this "shape2" tech.v2.datatype.LongReader]
    [:lload 1]
    [:aload 0]
    [:getfield :this "shape2-lsize" :long]
    [:lrem]
    [:invokeinterface tech.v2.datatype.LongReader "read"]
    [:ladd]
    [:lreturn]]}]}
user> (def class-obj (insn/define class-def))
#'user/class-obj
user> (def first-constructor (first (.getDeclaredConstructors class-obj)))
#'user/first-constructor
user> (def reversed-idx-obj (.newInstance first-constructor
                                          (gtol/reduced-dims->constructor-args reduced-dims)))
#'user/reversed-idx-obj
user> (map reversed-idx-obj (range 1020 1030))
(1023 1022 1021 1020 8195 8194 8193 8192 8199 8198)
```

## Wrapping Up

We covered a lot of ground so if you are still reading at this point, good on you!

The JVM presents a great platform for high performance computing and being able to
generate great code out of abstract syntax trees allows us to customize what we are
doing to precisely the conditions present.  Declaratively producing your bytecode
allows us to easily visually debug what is going on.  Our clojure representation is
strikingly close to the representation used by the javap decompiler which allows us
to easily compare what we are doing to what javac will do and thus bootstrap a new
problem without having to be experts in the JVM bytecode.


We hope this encourages you to explore what is possible with extremely late-bound
and abstract transformations of your problem space!  In this way we can take
advantage of the highest levels of abstraction possible but not pay a large
performance cost for using these abstractions which, put colloquially, just makes
our programming lives better and more dynamic.  For an example of a really well done
system for producing late-bound but extremely fast code allow us direct you to
CMUCL's [compiler stack](https://www.cons.org/cmucl/doc/different-compilers.html)
:-).

Enjoy!
