package tech.v2.datatype;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.RT;


public interface FloatWriter extends IOBase, IFn
{
  void write(long idx, float value);
  default Object getDatatype () { return Keyword.intern(null, "float32"); }
  default Object invoke(Object idx, Object value)
  {
    write(RT.longCast(idx), RT.floatCast(value));
    return null;
  }
};
