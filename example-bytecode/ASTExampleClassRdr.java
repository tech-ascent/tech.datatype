package tech.v2.tensor.dimensions;

import tech.v2.datatype.LongReader;

//(+ (* (quot idx max-shape-stride-0) stride-0) (rem idx shape-1))
public class ASTExampleClassRdr
{
  public final long maxShapeStride0;
  public final long stride0;
  public final LongReader shape1;
  public final long nElems;
  public ASTExampleClassRdr( Object[] shape, long[] strides, long[] offsets,
			     long[] maxShape, long[] maxShapeStride ) {
    maxShapeStride0 = maxShapeStride[0];
    stride0 = strides[0];
    shape1 = (LongReader) shape[1];
    nElems = maxShape[0] * maxShapeStride[0];
  }
  public long lsize() { return nElems; }
  public long read(long idx) {
    return
      ((idx / maxShapeStride0) * stride0)
      + shape1.read(idx % shape1.lsize());
  }
}
