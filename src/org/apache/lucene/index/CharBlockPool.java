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

final class CharBlockPool {
  //存放所有的buffer：buffer的集合:buffer空间不足时，则重新分配一个新的buffer，原buffer在buffers中保存着。
  public char[][] buffers = new char[10][];
  int numBuffer;
  /**Which buffer we are upto*/
  int bufferUpto = -1;                     
  /**buffer中第一空闲元素下标Where we are in head buffer*/
  public int charUpto = DocumentsWriter.CHAR_BLOCK_SIZE;     
  /**Current head buffer:buffer空间不足时，则重新分配一个新的buffer，原buffer在buffers中保存着。*/
  public char[] buffer;      
  /**Current head offset*/
  public int charOffset = -DocumentsWriter.CHAR_BLOCK_SIZE;          
  final private DocumentsWriter docWriter;

  public CharBlockPool(DocumentsWriter docWriter) {
    this.docWriter = docWriter;
  }

  public void reset() {
    docWriter.recycleCharBlocks(buffers, 1+bufferUpto);
    bufferUpto = -1;
    charUpto = DocumentsWriter.CHAR_BLOCK_SIZE;
    charOffset = -DocumentsWriter.CHAR_BLOCK_SIZE;
  }
  /**扩容buffers+用docWriter.getCharBlock()重新分配新的buffer*/
  public void nextBuffer() {
    if (1+bufferUpto == buffers.length) {
      char[][] newBuffers = new char[(int) (buffers.length*1.5)][];
      System.arraycopy(buffers, 0, newBuffers, 0, buffers.length);
      buffers = newBuffers;
    }
    buffer = buffers[1+bufferUpto] = docWriter.getCharBlock();//使buffers始终保持现有buffer的引用
    bufferUpto++;

    charUpto = 0;
    charOffset += DocumentsWriter.CHAR_BLOCK_SIZE;
  }
}

