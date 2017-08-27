package org.apache.lucene.index;

/**
 * 在 ByteBlockPool 中，文档号和词频(freq)信息是应用或然跟随原则写到一个块中去的，
 * 而位置信息(prox)是写入到另一个块中去的，对于同一个词，这两块的偏移量保存在IntBlockPool 中。
 * 因而在IntBlockPool 中，每一个词都有两个int，第0 个表示docid + freq在ByteBlockPool 中的偏移量，
 * 第1 个表示prox 在ByteBlockPool 中的偏移量。
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

final class IntBlockPool {

  public int[][] buffers = new int[10][];

  int bufferUpto = -1;                        // Which buffer we are upto
  /**空闲数组第一元素下标*/
  public int intUpto = DocumentsWriter.INT_BLOCK_SIZE;             // Where we are in head buffer

  public int[] buffer;                              // Current head buffer
  public int intOffset = -DocumentsWriter.INT_BLOCK_SIZE;          // Current head offset

  final private DocumentsWriter docWriter;
  final boolean trackAllocations;

  public IntBlockPool(DocumentsWriter docWriter, boolean trackAllocations) {
    this.docWriter = docWriter;
    this.trackAllocations = trackAllocations;
  }

  public void reset() {
    if (bufferUpto != -1) {
      if (bufferUpto > 0)
        // Recycle all but the first buffer
        docWriter.recycleIntBlocks(buffers, 1, 1+bufferUpto);

      // Reuse first buffer
      bufferUpto = 0;
      intUpto = 0;
      intOffset = 0;
      buffer = buffers[0];
    }
  }

  public void nextBuffer() {
    if (1+bufferUpto == buffers.length) {
      int[][] newBuffers = new int[(int) (buffers.length*1.5)][];
      System.arraycopy(buffers, 0, newBuffers, 0, buffers.length);
      buffers = newBuffers;
    }
    buffer = buffers[1+bufferUpto] = docWriter.getIntBlock(trackAllocations);
    bufferUpto++;

    intUpto = 0;
    intOffset += DocumentsWriter.INT_BLOCK_SIZE;
  }
}

