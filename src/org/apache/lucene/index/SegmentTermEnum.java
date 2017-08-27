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
import org.apache.lucene.store.IndexInput;

final class SegmentTermEnum extends TermEnum implements Cloneable {
  private IndexInput input;
  FieldInfos fieldInfos;
  long size;
  long position = -1;

  private TermBuffer termBuffer = new TermBuffer();
  private TermBuffer prevBuffer = new TermBuffer();
  private TermBuffer scanBuffer = new TermBuffer(); // used for scanning

  private TermInfo termInfo = new TermInfo();
  /**文件版本信息*/
  private int format;
  private boolean isIndex = false;
  long indexPointer = 0;
  /**为了加快对词的查找速度，也应用类似跳跃表的结构，假设IndexInterval为4，则在词典索引(tii)文件中保存第4个，第8个，第12个词，这样可以加快在词典文件中查找词的速度。 */
  int indexInterval;
  /**倒排表无论是文档号及词频，还是位置信息，都是以跳跃表的结构存在的，SkipInterval是跳跃的步数。*/
  int skipInterval;
  int maxSkipLevels;
  private int formatM1SkipInterval;

  SegmentTermEnum(IndexInput i, FieldInfos fis, boolean isi)
          throws CorruptIndexException, IOException {
    input = i;//tii文件输入流
    fieldInfos = fis;
    isIndex = isi;
    maxSkipLevels = 1; // 跳跃表是多层的，这个值指的是跳跃表的最大层数。use single-level skip lists for formats > -3 
    
    int firstInt = input.readInt();//文件版本信息
    if (firstInt >= 0) {
      // original-format file, without explicit format version number
      format = 0;
      size = firstInt;

      // back-compatible settings
      indexInterval = 128;
      skipInterval = Integer.MAX_VALUE; // switch off skipTo optimization
    } else {
      // we have a format version number
      format = firstInt;//文件版本信息

      // check that it is a format we can understand
      if (format < TermInfosWriter.FORMAT_CURRENT)
        throw new CorruptIndexException("Unknown format version:" + format + " expected " + TermInfosWriter.FORMAT_CURRENT + " or higher");

      size = input.readLong();//•TermCount：词典中包含的总的词数。read the size
      
      if(format == -1){
        if (!isIndex) {
          indexInterval = input.readInt();
          formatM1SkipInterval = input.readInt();
        }
        // switch off skipTo optimization for file format prior to 1.4rc2 in order to avoid a bug in 
        // skipTo implementation of these versions
        skipInterval = Integer.MAX_VALUE;
      } else {
        indexInterval = input.readInt();//为了加快对词的查找速度，也应用类似跳跃表的结构，假设IndexInterval为4，则在词典索引(tii)文件中保存第4个，第8个，第12个词，这样可以加快在词典文件中查找词的速度。 
        skipInterval = input.readInt();//SkipInterval是跳跃的步数。
        if (format <= TermInfosWriter.FORMAT) {
          // this new format introduces multi-level skipping
          maxSkipLevels = input.readInt();
        }
      }
      assert indexInterval > 0: "indexInterval=" + indexInterval + " is negative; must be > 0";
      assert skipInterval > 0: "skipInterval=" + skipInterval + " is negative; must be > 0";
    }
    if (format > TermInfosWriter.FORMAT_VERSION_UTF8_LENGTH_IN_BYTES) {
      termBuffer.setPreUTF8Strings();
      scanBuffer.setPreUTF8Strings();
      prevBuffer.setPreUTF8Strings();
    }
  }

  @Override
  protected Object clone() {
    SegmentTermEnum clone = null;
    try {
      clone = (SegmentTermEnum) super.clone();
    } catch (CloneNotSupportedException e) {}

    clone.input = (IndexInput) input.clone();
    clone.termInfo = new TermInfo(termInfo);

    clone.termBuffer = (TermBuffer)termBuffer.clone();
    clone.prevBuffer = (TermBuffer)prevBuffer.clone();
    clone.scanBuffer = new TermBuffer();

    return clone;
  }

  final void seek(long pointer, long p, Term t, TermInfo ti)
          throws IOException {
    input.seek(pointer);
    position = p;
    termBuffer.set(t);
    prevBuffer.reset();
    termInfo.set(ti);
  }

  /** Increments the enumeration to the next element.  True if one exists.*/
  @Override
  public final boolean next() throws IOException {
    if (position++ >= size - 1) {
      prevBuffer.set(termBuffer);
      termBuffer.reset();
      return false;
    }

    prevBuffer.set(termBuffer);
    termBuffer.read(input, fieldInfos);

    termInfo.docFreq = input.readVInt();	  //有多少篇文档包含此词 read doc freq
    termInfo.freqPointer += input.readVLong();	  // 此词的倒排表在frq中的偏移量(FreqDelta)。read freq pointer
    termInfo.proxPointer += input.readVLong();	  // 此词的倒排表在prx中的偏移量(ProxDelta)。read prox pointer
    
    if(format == -1){
    //  just read skipOffset in order to increment  file pointer; 
    // value is never used since skipTo is switched off
      if (!isIndex) {
        if (termInfo.docFreq > formatM1SkipInterval) {
          termInfo.skipOffset = input.readVInt(); 
        }
      }
    }
    else{
      if (termInfo.docFreq >= skipInterval) 
        termInfo.skipOffset = input.readVInt();
    }
    
    if (isIndex)
      indexPointer += input.readVLong();//tii文件中包含的词典数组中元素的第二部分：是在词典文件中的偏移量(IndexDelta)。read index pointer

    return true;
  }

  /** Optimized scan, without allocating new terms. 
   *  Return number of invocations to next(). */
  final int scanTo(Term term) throws IOException {
    scanBuffer.set(term);
    int count = 0;
    while (scanBuffer.compareTo(termBuffer) > 0 && next()) {
      count++;
    }
    return count;
  }

  /** Returns the current Term in the enumeration.
   Initially invalid, valid after next() called for the first time.*/
  @Override
  public final Term term() {
    return termBuffer.toTerm();
  }

  /** Returns the previous Term enumerated. Initially null.*/
  final Term prev() {
    return prevBuffer.toTerm();
  }

  /** Returns the current TermInfo in the enumeration.
   Initially invalid, valid after next() called for the first time.*/
  final TermInfo termInfo() {
    return new TermInfo(termInfo);
  }

  /** Sets the argument to the current TermInfo in the enumeration.
   Initially invalid, valid after next() called for the first time.*/
  final void termInfo(TermInfo ti) {
    ti.set(termInfo);
  }

  /** Returns the docFreq from the current TermInfo in the enumeration.
   Initially invalid, valid after next() called for the first time.*/
  @Override
  public final int docFreq() {
    return termInfo.docFreq;
  }

  /* Returns the freqPointer from the current TermInfo in the enumeration.
    Initially invalid, valid after next() called for the first time.*/
  final long freqPointer() {
    return termInfo.freqPointer;
  }

  /* Returns the proxPointer from the current TermInfo in the enumeration.
    Initially invalid, valid after next() called for the first time.*/
  final long proxPointer() {
    return termInfo.proxPointer;
  }

  /** Closes the enumeration to further activity, freeing resources. */
  @Override
  public final void close() throws IOException {
    input.close();
  }
}
