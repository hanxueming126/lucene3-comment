package org.apache.lucene.index;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Class that Posting and PostingVector use to write byte
 * streams into shared fixed-size byte[] arrays.  The idea
 * is to allocate slices of increasing lengths For
 * example, the first slice is 5 bytes, the next slice is
 * 14, etc.  We start by writing our bytes into the first
 * 5 bytes.  When we hit the end of the slice, we allocate
 * the next slice and then write the address of the new
 * slice into the last 4 bytes of the previous slice (the
 * "forwarding address").
 *
 * Each slice is filled with 0's initially, and we mark
 * the end with a non-zero byte.  This way the methods
 * that are writing into the slice don't need to record
 * its length and instead allocate a new slice once they
 * hit a non-zero byte. */

import java.util.Arrays;
import java.util.List;

final class ByteBlockPool {

  abstract static class Allocator {
    abstract void recycleByteBlocks(byte[][] blocks, int start, int end);
    abstract void recycleByteBlocks(List<byte[]> blocks);
    abstract byte[] getByteBlock(boolean trackAllocations);
  }

  public byte[][] buffers = new byte[10][];

  int bufferUpto = -1;                        // Which buffer we are upto
  public int byteUpto = DocumentsWriter.BYTE_BLOCK_SIZE;             // Where we are in head buffer

  public byte[] buffer;                              // Current head buffer
  public int byteOffset = -DocumentsWriter.BYTE_BLOCK_SIZE;          // Current head offset

  private final boolean trackAllocations;
  private final Allocator allocator;

  public ByteBlockPool(Allocator allocator, boolean trackAllocations) {
    this.allocator = allocator;
    this.trackAllocations = trackAllocations;
  }

  public void reset() {
    if (bufferUpto != -1) {
      // We allocated at least one buffer

      for(int i=0;i<bufferUpto;i++)
        // Fully zero fill buffers that we fully used
        Arrays.fill(buffers[i], (byte) 0);

      // Partial zero fill the final buffer
      Arrays.fill(buffers[bufferUpto], 0, byteUpto, (byte) 0);
          
      if (bufferUpto > 0)
        // Recycle all but the first buffer
        allocator.recycleByteBlocks(buffers, 1, 1+bufferUpto);

      // Re-use the first buffer
      bufferUpto = 0;
      byteUpto = 0;
      byteOffset = 0;
      buffer = buffers[0];
    }
  }

  public void nextBuffer() {
    if (1+bufferUpto == buffers.length) {
      byte[][] newBuffers = new byte[(int) (buffers.length*1.5)][];
      System.arraycopy(buffers, 0, newBuffers, 0, buffers.length);
      buffers = newBuffers;
    }
    buffer = buffers[1+bufferUpto] = allocator.getByteBlock(trackAllocations);
    bufferUpto++;

    byteUpto = 0;
    byteOffset += DocumentsWriter.BYTE_BLOCK_SIZE;
  }

  public int newSlice(final int size) {
    if (byteUpto > DocumentsWriter.BYTE_BLOCK_SIZE-size)
      nextBuffer();
    final int upto = byteUpto;
    byteUpto += size;
    buffer[byteUpto-1] = 16;
    return upto;
  }

  // Size of each slice.  These arrays should be at most 16
  // elements (index is encoded with 4 bits).  First array
  // is just a compact way to encode X+1 with a max.  Second
  // array is the length of each slice, ie first slice is 5
  // bytes, next slice is 14 bytes, etc.
  /**表示的是当前层的下一层是第几层，可见第9层的下一层还是第9层，也就是说最高有9层。*/
  final static int[] nextLevelArray = {1, 2, 3, 4, 5, 6, 7, 8, 9, 9};
  /**表示每一层的块大小，第一层是5个byte，第二层是14个byte以此类推。*/
  final static int[] levelSizeArray = {5, 14, 20, 30, 40, 40, 80, 80, 120, 200};
  final static int FIRST_LEVEL_SIZE = levelSizeArray[0];

  /**
   * 此函数仅仅在upto已经是当前块的结尾的时候方才调用来分配新块。
   */
public int allocSlice(final byte[] slice, final int upto) {
    //可根据块的结束符来得到块所在的层次。从而我们可以推断，每个层次的块都有不同的结束符，第1层为16，第2层位17，第3层18，依次类推。
    final int level = slice[upto] & 15;//1层：结束符16&15=10000&01111=0（即nextLevelArray数组下标值,nextLevelArray[0]=1层）
    //从数组中得到下一个层次及下一层块的大小。
    final int newLevel = nextLevelArray[level];
    final int newSize = levelSizeArray[newLevel];

    // Maybe allocate another block
    // 如果当前缓存总量不够大，则从DocumentsWriter的freeByteBlocks中分配。
    if (byteUpto > DocumentsWriter.BYTE_BLOCK_SIZE-newSize)
      nextBuffer();

    final int newUpto = byteUpto;
    final int offset = newUpto + byteOffset;
    byteUpto += newSize;

    // Copy forward the past 3 bytes (which we are about
    // to overwrite with the forwarding address):
//当分配了新的块的时候，需要有一个指针从本块指向下一个块，使得读取此信息的时候， 能够在此块读取结束后，到下一个块继续读取。
//这个指针需要4个byte，在本块中，除了结束符所占用的一个byte之外，之前的三个byte的数据都应该移到新的块中，从而四个byte 连起来形成一个指针。
    buffer[newUpto] = slice[upto-3];//upto指的是intPool中指向bytePool中的偏移量(此处为bytePool中某个slice的结尾元素下标，里面存结束符)
    buffer[newUpto+1] = slice[upto-2];//newUpto指的是bytePool中刚刚分配的新块(slice)的起始偏移量
    buffer[newUpto+2] = slice[upto-1];

    // Write forwarding address at end of last slice:
    // 将偏移量(也即指针)写入到连同结束符在内的四个byte
    slice[upto-3] = (byte) (offset >>> 24);//存储offset的最高位
    slice[upto-2] = (byte) (offset >>> 16);//slice的这4个字节共同构成一个int来存储offset，因为当缓存很大的时候，指针的值也会很大，一个字节可能会存不下
    slice[upto-1] = (byte) (offset >>> 8);
    slice[upto] = (byte) offset;//存储offset的最低位
        
    // Write new level:
    // 在新的块的结尾写入新的结束符，结束符和层次的关系就是(endbyte = 16 | level)
    buffer[byteUpto-1] = (byte) (16|newLevel);

    return newUpto+3;
  }
}

