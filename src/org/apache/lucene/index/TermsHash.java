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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import org.apache.lucene.util.ArrayUtil;

/** 
 * 基本索引链2实现：tii/tis
 * This class implements {@link InvertedDocConsumer}, which
 *  is passed each token produced by the analyzer on each
 *  field.  It stores these tokens in a hash table, and
 *  allocates separate byte streams per token.  Consumers of
 *  this class, eg {@link FreqProxTermsWriter} and {@link
 *  TermVectorsTermsWriter}, write their own byte streams
 *  under each term.
 */
final class TermsHash extends InvertedDocConsumer {
  /**倒排表信息处理*/
  final TermsHashConsumer consumer;
  /**词向量信息处理*/
  final TermsHash nextTermsHash;
  final int bytesPerPosting;
  final int postingsFreeChunk;
  final DocumentsWriter docWriter;

  private RawPostingList[] postingsFreeList = new RawPostingList[1];
  private int postingsFreeCount;
  private int postingsAllocCount;
  boolean trackAllocations;

  public TermsHash(final DocumentsWriter docWriter, boolean trackAllocations, final TermsHashConsumer consumer, final TermsHash nextTermsHash) {
    this.docWriter = docWriter;
    this.consumer = consumer;
    this.nextTermsHash = nextTermsHash;
    this.trackAllocations = trackAllocations;

    // Why + 4*POINTER_NUM_BYTE below?
    //   +1: Posting is referenced by postingsFreeList array
    //   +3: Posting is referenced by hash, which
    //       targets 25-50% fill factor; approximate this
    //       as 3X # pointers
    bytesPerPosting = consumer.bytesPerPosting() + 4*DocumentsWriter.POINTER_NUM_BYTE;
    postingsFreeChunk = (int) (DocumentsWriter.BYTE_BLOCK_SIZE / bytesPerPosting);
  }

  @Override
  InvertedDocConsumerPerThread addThread(DocInverterPerThread docInverterPerThread) {
    return new TermsHashPerThread(docInverterPerThread, this, nextTermsHash, null);
  }

  TermsHashPerThread addThread(DocInverterPerThread docInverterPerThread, TermsHashPerThread primaryPerThread) {
    return new TermsHashPerThread(docInverterPerThread, this, nextTermsHash, primaryPerThread);
  }

  @Override
  void setFieldInfos(FieldInfos fieldInfos) {
    this.fieldInfos = fieldInfos;
    consumer.setFieldInfos(fieldInfos);
  }

  @Override
  synchronized public void abort() {
    consumer.abort();
    if (nextTermsHash != null)
      nextTermsHash.abort();
  }

  void shrinkFreePostings(Map<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>> threadsAndFields, SegmentWriteState state) {

    assert postingsFreeCount == postingsAllocCount: Thread.currentThread().getName() + ": postingsFreeCount=" + postingsFreeCount + " postingsAllocCount=" + postingsAllocCount + " consumer=" + consumer;

    final int newSize = 1;
    if (newSize != postingsFreeList.length) {
      if (postingsFreeCount > newSize) {
        if (trackAllocations) {
          docWriter.bytesAllocated(-(postingsFreeCount-newSize) * bytesPerPosting);
        }

        postingsFreeCount = newSize;
        postingsAllocCount = newSize;
      }

      RawPostingList[] newArray = new RawPostingList[newSize];
      System.arraycopy(postingsFreeList, 0, newArray, 0, postingsFreeCount);
      postingsFreeList = newArray;
    }
  }

  @Override
  synchronized void closeDocStore(SegmentWriteState state) throws IOException {
    consumer.closeDocStore(state);
    if (nextTermsHash != null)
      nextTermsHash.closeDocStore(state);
  }
  /** 
   * 写入倒排表及词向量信息:生成新段的frq、 prx、tii、tis文件
   **/
  @Override
  synchronized void flush(Map<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>> threadsAndFields, final SegmentWriteState state) throws IOException {
    Map<TermsHashConsumerPerThread,Collection<TermsHashConsumerPerField>> childThreadsAndFields = new HashMap<TermsHashConsumerPerThread,Collection<TermsHashConsumerPerField>>();
    Map<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>> nextThreadsAndFields;

    if (nextTermsHash != null)
      nextThreadsAndFields = new HashMap<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>>();
    else
      nextThreadsAndFields = null;

    for (final Map.Entry<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>> entry : threadsAndFields.entrySet()) {

      TermsHashPerThread perThread = (TermsHashPerThread) entry.getKey();

      Collection<InvertedDocConsumerPerField> fields = entry.getValue();

      Iterator<InvertedDocConsumerPerField> fieldsIt = fields.iterator();
      Collection<TermsHashConsumerPerField> childFields = new HashSet<TermsHashConsumerPerField>();
      Collection<InvertedDocConsumerPerField> nextChildFields;

      if (nextTermsHash != null)
        nextChildFields = new HashSet<InvertedDocConsumerPerField>();
      else
        nextChildFields = null;

      while(fieldsIt.hasNext()) {
        TermsHashPerField perField = (TermsHashPerField) fieldsIt.next();
        childFields.add(perField.consumer);
        if (nextTermsHash != null)
          nextChildFields.add(perField.nextPerField);
      }

      childThreadsAndFields.put(perThread.consumer, childFields);
      if (nextTermsHash != null)
        nextThreadsAndFields.put(perThread.nextPerThread, nextChildFields);
    }
    //写入倒排表信息:FreqProxTermsWriter
    consumer.flush(childThreadsAndFields, state);
    //回收RawPostingList:
    shrinkFreePostings(threadsAndFields, state);
    
    if (nextTermsHash != null)
      //写入词向量信息:TermVectorsTermsWriter,从代码中可以看出，是写入tvx, tvd, tvf 三个文件，但是在上述的closeDocStore
      //已经写入了， 并且把tvx 设为null，在这里其实什么也不做，仅仅是清空postingsHash，以便进行下一轮 索引时重用此对象。
      nextTermsHash.flush(nextThreadsAndFields, state);
  }

  @Override
  synchronized public boolean freeRAM() {

    if (!trackAllocations)
      return false;

    boolean any;
    final int numToFree;
    if (postingsFreeCount >= postingsFreeChunk)
      numToFree = postingsFreeChunk;
    else
      numToFree = postingsFreeCount;
    any = numToFree > 0;
    if (any) {
      Arrays.fill(postingsFreeList, postingsFreeCount-numToFree, postingsFreeCount, null);
      postingsFreeCount -= numToFree;
      postingsAllocCount -= numToFree;
      docWriter.bytesAllocated(-numToFree * bytesPerPosting);
      any = true;
    }

    if (nextTermsHash != null)
      any |= nextTermsHash.freeRAM();

    return any;
  }

  synchronized public void recyclePostings(final RawPostingList[] postings, final int numPostings) {

    assert postings.length >= numPostings;

    // Move all Postings from this ThreadState back to our
    // free list.  We pre-allocated this array while we were
    // creating Postings to make sure it's large enough
    assert postingsFreeCount + numPostings <= postingsFreeList.length;
    System.arraycopy(postings, 0, postingsFreeList, postingsFreeCount, numPostings);
    postingsFreeCount += numPostings;
  }

  synchronized public void getPostings(final RawPostingList[] postings) {

    assert docWriter.writer.testPoint("TermsHash.getPostings start");

    assert postingsFreeCount <= postingsFreeList.length;
    assert postingsFreeCount <= postingsAllocCount: "postingsFreeCount=" + postingsFreeCount + " postingsAllocCount=" + postingsAllocCount;

    final int numToCopy;
    if (postingsFreeCount < postings.length)
      numToCopy = postingsFreeCount;
    else
      numToCopy = postings.length;
    final int start = postingsFreeCount-numToCopy;
    assert start >= 0;
    assert start + numToCopy <= postingsFreeList.length;
    assert numToCopy <= postings.length;
    System.arraycopy(postingsFreeList, start,
                     postings, 0, numToCopy);

    // Directly allocate the remainder if any
    if (numToCopy != postings.length) {
      final int extra = postings.length - numToCopy;
      final int newPostingsAllocCount = postingsAllocCount + extra;

      consumer.createPostings(postings, numToCopy, extra);
      assert docWriter.writer.testPoint("TermsHash.getPostings after create");
      postingsAllocCount += extra;

      if (trackAllocations)
        docWriter.bytesAllocated(extra * bytesPerPosting);

      if (newPostingsAllocCount > postingsFreeList.length)
        // Pre-allocate the postingsFreeList so it's large
        // enough to hold all postings we've given out
        postingsFreeList = new RawPostingList[ArrayUtil.getNextSize(newPostingsAllocCount)];
    }

    postingsFreeCount -= numToCopy;

    if (trackAllocations)
      docWriter.bytesUsed(postings.length * bytesPerPosting);
  }
}
