package tech.v2.datatype;

import it.unimi.dsi.fastutil.ints.IntIterator;


public interface IntIter extends Datatype, IntIterator
{
  int current();
}
