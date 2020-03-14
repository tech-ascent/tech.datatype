package tech.v2.datatype;

import it.unimi.dsi.fastutil.longs.LongSet;
import clojure.lang.RT;
import clojure.lang.Keyword;


public interface SimpleLongSet extends IOBase, LongSet
{
  default Keyword getDatatype () { return Keyword.intern(null, "int64"); }
  default int size() { return RT.intCast(lsize()); }
  default boolean contains(Long obj) { return lcontains(RT.longCast(obj)); }
  default boolean contains(long obj) { return lcontains(obj); }
  default boolean add(long obj) { return ladd(obj); }
  default boolean remove(long obj) { return lremove(obj); }
  boolean ladd(long obj);
  boolean lcontains(long obj);
  boolean lremove(long obj);
}
