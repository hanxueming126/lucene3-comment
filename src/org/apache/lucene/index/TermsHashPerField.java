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

import org.apache.lucene.analysis.tokenattributes.TermAttribute;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.util.UnicodeUtil;
/**
 * 域索引链2实现：tii、tis文件处理
 */
final class TermsHashPerField extends InvertedDocConsumerPerField {
  /**类型为FreqProxTermsWriterPerField，用于写freq, prox信息到缓存中*/
  final TermsHashConsumerPerField consumer;
  final TermsHashPerField nextPerField;
  final TermsHashPerThread perThread;
  final DocumentsWriter.DocState docState;
  final FieldInvertState fieldState;
  TermAttribute termAtt;
  
  // Copied from our perThread
  /**用于存储Token的文本信息，如果不足时，从DocumentsWriter中的freeCharBlocks分配*/
  final CharBlockPool charPool;
  /**用于存储分别指向每个Token在bytePool中freq和prox信息的偏移量。如果不足时，从DocumentsWriter的freeIntBlocks分配*/
  final IntBlockPool intPool;
  /**用于存储freq, prox信息，如果不足时，从DocumentsWriter中的 freeByteBlocks分配*/
  final ByteBlockPool bytePool;

  final int streamCount;
  final int numPostingInt;

  final FieldInfo fieldInfo;

  boolean postingsCompacted;
  /**倒排表的数量*/
  int numPostings;
  private int postingsHashSize = 4;
  private int postingsHashHalfSize = postingsHashSize/2;
  private int postingsHashMask = postingsHashSize-1;
  /**
   * 存储倒排表，每一个Term都有一个RawPostingList (PostingList)，其中包含了
   * int textStart，也即文本在charPool中的偏移量，
   * int byteStart，即此Term的freq和prox信息在bytePool中的起始偏移量，
   * int intStart，即此 term的在intPool中的起始偏移量。
   * */
  private RawPostingList[] postingsHash = new RawPostingList[postingsHashSize];
  private RawPostingList p;
  
  public TermsHashPerField(DocInverterPerField docInverterPerField, final TermsHashPerThread perThread, final TermsHashPerThread nextPerThread, final FieldInfo fieldInfo) {
    this.perThread = perThread;
    intPool = perThread.intPool;
    charPool = perThread.charPool;
    bytePool = perThread.bytePool;
    docState = perThread.docState;
    fieldState = docInverterPerField.fieldState;
    this.consumer = perThread.consumer.addField(this, fieldInfo);
    streamCount = consumer.getStreamCount();
    numPostingInt = 2*streamCount;
    this.fieldInfo = fieldInfo;
    if (nextPerThread != null)
      nextPerField = (TermsHashPerField) nextPerThread.addField(docInverterPerField, fieldInfo);
    else
      nextPerField = null;
  }

  void shrinkHash(int targetSize) {
    assert postingsCompacted || numPostings == 0;

    final int newSize = 4;
    if (newSize != postingsHash.length) {
      postingsHash = new RawPostingList[newSize];
      postingsHashSize = newSize;
      postingsHashHalfSize = newSize/2;
      postingsHashMask = newSize-1;
    }
    Arrays.fill(postingsHash, null);
  }

  public void reset() {
    if (!postingsCompacted)
      compactPostings();
    assert numPostings <= postingsHash.length;
    if (numPostings > 0) {
      perThread.termsHash.recyclePostings(postingsHash, numPostings);
      Arrays.fill(postingsHash, 0, numPostings, null);
      numPostings = 0;
    }
    postingsCompacted = false;
    if (nextPerField != null)
      nextPerField.reset();
  }

  @Override
  synchronized public void abort() {
    reset();
    if (nextPerField != null)
      nextPerField.abort();
  }

  public void initReader(ByteSliceReader reader, RawPostingList p, int stream) {
    assert stream < streamCount;
    final int[] ints = intPool.buffers[p.intStart >> DocumentsWriter.INT_BLOCK_SHIFT];
    final int upto = p.intStart & DocumentsWriter.INT_BLOCK_MASK;
    reader.init(bytePool,
                p.byteStart+stream*ByteBlockPool.FIRST_LEVEL_SIZE,
                ints[upto+stream]);
  }

  private synchronized void compactPostings() {
    int upto = 0;
    for(int i=0;i<postingsHashSize;i++) {
      if (postingsHash[i] != null) {
        if (upto < i) {
          postingsHash[upto] = postingsHash[i];
          postingsHash[i] = null;
        }
        upto++;
      }
    }

    assert upto == numPostings;
    postingsCompacted = true;
  }

  /** Collapse the hash table & sort in-place. */
  public RawPostingList[] sortPostings() {
    compactPostings();
    quickSort(postingsHash, 0, numPostings-1);
    return postingsHash;
  }

  void quickSort(RawPostingList[] postings, int lo, int hi) {
    if (lo >= hi)
      return;
    else if (hi == 1+lo) {
      if (comparePostings(postings[lo], postings[hi]) > 0) {
        final RawPostingList tmp = postings[lo];
        postings[lo] = postings[hi];
        postings[hi] = tmp;
      }
      return;
    }

    int mid = (lo + hi) >>> 1;

    if (comparePostings(postings[lo], postings[mid]) > 0) {
      RawPostingList tmp = postings[lo];
      postings[lo] = postings[mid];
      postings[mid] = tmp;
    }

    if (comparePostings(postings[mid], postings[hi]) > 0) {
      RawPostingList tmp = postings[mid];
      postings[mid] = postings[hi];
      postings[hi] = tmp;

      if (comparePostings(postings[lo], postings[mid]) > 0) {
        RawPostingList tmp2 = postings[lo];
        postings[lo] = postings[mid];
        postings[mid] = tmp2;
      }
    }

    int left = lo + 1;
    int right = hi - 1;

    if (left >= right)
      return;

    RawPostingList partition = postings[mid];

    for (; ;) {
      while (comparePostings(postings[right], partition) > 0)
        --right;

      while (left < right && comparePostings(postings[left], partition) <= 0)
        ++left;

      if (left < right) {
        RawPostingList tmp = postings[left];
        postings[left] = postings[right];
        postings[right] = tmp;
        --right;
      } else {
        break;
      }
    }

    quickSort(postings, lo, left);
    quickSort(postings, left + 1, hi);
  }

  /** Compares term text for two Posting instance and
   *  returns -1 if p1 < p2; 1 if p1 > p2; else 0. */
  int comparePostings(RawPostingList p1, RawPostingList p2) {

    if (p1 == p2)
      return 0;

    final char[] text1 = charPool.buffers[p1.textStart >> DocumentsWriter.CHAR_BLOCK_SHIFT];
    int pos1 = p1.textStart & DocumentsWriter.CHAR_BLOCK_MASK;
    final char[] text2 = charPool.buffers[p2.textStart >> DocumentsWriter.CHAR_BLOCK_SHIFT];
    int pos2 = p2.textStart & DocumentsWriter.CHAR_BLOCK_MASK;

    assert text1 != text2 || pos1 != pos2;

    while(true) {
      final char c1 = text1[pos1++];
      final char c2 = text2[pos2++];
      if (c1 != c2) {
        if (0xffff == c2)
          return 1;
        else if (0xffff == c1)
          return -1;
        else
          return c1-c2;
      } else
        // This method should never compare equal postings
        // unless p1==p2
        assert c1 != 0xffff;
    }
  }

  /** 
   * 当前term（tokenText）和charPool中对应的term（text）逐个字符比较，全相等，并且text中的term以0xffff结尾才返回true，否则false
   * Test whether the text for current RawPostingList p equals
   *  current tokenText. */
  private boolean postingEquals(final char[] tokenText, final int tokenTextLen) {

    final char[] text = perThread.charPool.buffers[p.textStart >> DocumentsWriter.CHAR_BLOCK_SHIFT];
    assert text != null;
    int pos = p.textStart & DocumentsWriter.CHAR_BLOCK_MASK;

    int tokenPos = 0;
    for(;tokenPos<tokenTextLen;pos++,tokenPos++)
      if (tokenText[tokenPos] != text[pos])//当前term（tokenText）和charPool中对应的term（text）逐个字符比较
        return false;
    return 0xffff == text[pos];
  }
  
  private boolean doCall;
  private boolean doNextCall;

  @Override
  void start(Fieldable f) {
    termAtt = fieldState.attributeSource.addAttribute(TermAttribute.class);
    consumer.start(f);
    if (nextPerField != null) {
      nextPerField.start(f);
    }
  }
  
  @Override
  boolean start(Fieldable[] fields, int count) throws IOException {
    doCall = consumer.start(fields, count);
    if (nextPerField != null)
      doNextCall = nextPerField.start(fields, count);
    return doCall || doNextCall;
  }

  // Secondary entry point (for 2nd & subsequent TermsHash),
  // because token text has already been "interned" into
  // textStart, so we hash by textStart
  public void add(int textStart) throws IOException {

    int code = textStart;

    int hashPos = code & postingsHashMask;

    assert !postingsCompacted;

    // Locate RawPostingList in hash
    p = postingsHash[hashPos];

    if (p != null && p.textStart != textStart) {
      // Conflict: keep searching different locations in
      // the hash table.
      final int inc = ((code>>8)+code)|1;
      do {
        code += inc;
        hashPos = code & postingsHashMask;
        p = postingsHash[hashPos];
      } while (p != null && p.textStart != textStart);
    }

    if (p == null) {

      // First time we are seeing this token since we last
      // flushed the hash.

      // Refill?
      if (0 == perThread.freePostingsCount)
        perThread.morePostings();

      // Pull next free RawPostingList from free list
      p = perThread.freePostings[--perThread.freePostingsCount];
      assert p != null;

      p.textStart = textStart;
          
      assert postingsHash[hashPos] == null;
      postingsHash[hashPos] = p;
      numPostings++;

      if (numPostings == postingsHashHalfSize)
        rehashPostings(2*postingsHashSize);

      // Init stream slices
      if (numPostingInt + intPool.intUpto > DocumentsWriter.INT_BLOCK_SIZE)
        intPool.nextBuffer();

      if (DocumentsWriter.BYTE_BLOCK_SIZE - bytePool.byteUpto < numPostingInt*ByteBlockPool.FIRST_LEVEL_SIZE)
        bytePool.nextBuffer();

      intUptos = intPool.buffer;
      intUptoStart = intPool.intUpto;
      intPool.intUpto += streamCount;

      p.intStart = intUptoStart + intPool.intOffset;

      for(int i=0;i<streamCount;i++) {
        final int upto = bytePool.newSlice(ByteBlockPool.FIRST_LEVEL_SIZE);
        intUptos[intUptoStart+i] = upto + bytePool.byteOffset;
      }
      p.byteStart = intUptos[intUptoStart];

      consumer.newTerm(p);

    } else {
      intUptos = intPool.buffers[p.intStart >> DocumentsWriter.INT_BLOCK_SHIFT];
      intUptoStart = p.intStart & DocumentsWriter.INT_BLOCK_MASK;
      consumer.addTerm(p);
    }
  }

  // Primary entry point (for first TermsHash)
  @Override
  void add() throws IOException {

    assert !postingsCompacted;

    // We are first in the chain so we must "intern" the
    // term text into textStart address

    // Get the text of this term.
    //得到token的文本及文本长度：
    final char[] tokenText = termAtt.termBuffer();
    final int tokenTextLen = termAtt.termLength();

    // Compute hashcode & replace any invalid UTF16 sequences
    //按照token的文本计算哈希值，以便在postingsHash中找到此token对应的倒排表：这一段的内容就是计算当前单词的Hash值, 
    //从代码中也可以看到, 程序排除了一些无效的utf-16串, 另外hash值计算的函数也还是比较容易看懂, 也就是从单词后往前, 
    //采用类似于31进制的方法计算出一个数.
    int downto = tokenTextLen;
    int code = 0;
    while (downto > 0) {
      //从后往前取出token中的每一个字符:
      char ch = tokenText[--downto];
      //判断ch是不是Unicode编码中的替代区域(surrogate area),这个区域不表示任何字符:
      if (ch >= UnicodeUtil.UNI_SUR_LOW_START && ch <= UnicodeUtil.UNI_SUR_LOW_END) {//正常字符不会走到此
        if (0 == downto) {
          // Unpaired
          ch = tokenText[downto] = UnicodeUtil.UNI_REPLACEMENT_CHAR;
        } else {
          //取出连续两个字符进行hashcode计算(UTF-16编码方式): 
          final char ch2 = tokenText[downto-1];
          if (ch2 >= UnicodeUtil.UNI_SUR_HIGH_START && ch2 <= UnicodeUtil.UNI_SUR_HIGH_END) {
            // OK: high followed by low.  This is a valid
            // surrogate pair.
            code = ((code*31) + ch)*31+ch2;
            downto--;
            continue;
          } else {
            // Unpaired
            ch = tokenText[downto] = UnicodeUtil.UNI_REPLACEMENT_CHAR;
          }            
        }
      } else if (ch >= UnicodeUtil.UNI_SUR_HIGH_START && (ch <= UnicodeUtil.UNI_SUR_HIGH_END ||
                                                          ch == 0xffff)) {//正常字符不会走到此
        // Unpaired or 0xffff
        ch = tokenText[downto] = UnicodeUtil.UNI_REPLACEMENT_CHAR;
      }
      //如果ch是正常字符，则开始计算token的hashcode：  
      code = (code*31) + ch;//什么意思？
    }

    int hashPos = code & postingsHashMask;

    // Locate RawPostingList in hash
    //在倒排表哈希表中查找此Token，如果找到相应的位置，但是不是此Token，说明此位置存在哈希冲突，采取重新计算哈希值：
    p = postingsHash[hashPos];
    //如果lucene发现冲突, 则计算得到一个inc值, 进行二次hash,公式是: inc = ((code >> 8) + code) | 1;
    //冲突：//如果原Hash表中的该位置上有数据并且两个token的字符串内容不等，则产生Hash冲突
    if (p != null && !postingEquals(tokenText, tokenTextLen)) {
      // Conflict: keep searching different locations in the hash table.
      // 冲突解决方法：不断计算新的hashcode，直到避开冲突位置:
      final int inc = ((code>>8)+code)|1;//什么意思？
      do {
        code += inc;
        hashPos = code & postingsHashMask;
        p = postingsHash[hashPos];
      } while (p != null && !postingEquals(tokenText, tokenTextLen));
    }
    //如果此Token之前从未出现过:也就是已经加入索引结构的所有词都没有这个词语。
    //那么首先需要在postingHash中开辟一个新的PostingList准备存放当前token所对应信息:填充PostingList/intPool/bytePool/charPool
    if (p == null) {

      // First time we are seeing this token since we last
      // flushed the hash.
      final int textLen1 = 1+tokenTextLen;//因为charPool的buffer中添加一个char的终止符号：0xffff
     //当charPool不足的时候，在freeCharBlocks中分配新的buffer:
      if (textLen1 + charPool.charUpto > DocumentsWriter.CHAR_BLOCK_SIZE) {
        if (textLen1 > DocumentsWriter.CHAR_BLOCK_SIZE) {
          // Just skip this term, to remain as robust as
          // possible during indexing.  A TokenFilter
          // can be inserted into the analyzer chain if
          // other behavior is wanted (pruning the term
          // to a prefix, throwing an exception, etc).

          if (docState.maxTermPrefix == null)
            docState.maxTermPrefix = new String(tokenText, 0, 30);

          consumer.skippingLongTerm();
          return;
        }
        charPool.nextBuffer();
      }

      // Refill?
      if (0 == perThread.freePostingsCount)
        perThread.morePostings();

      // Pull next free RawPostingList from free list
      //从空闲的倒排表中分配新的倒排表:
      p = perThread.freePostings[--perThread.freePostingsCount];
      assert p != null;
      //将文本复制到charPool中:
      final char[] text = charPool.buffer;
      final int textUpto = charPool.charUpto;
      p.textStart = textUpto + charPool.charOffset;//1、维护term对应的倒排表：charPool中的起始偏移量
      charPool.charUpto += textLen1;//charPool维护1：更改空闲数组第一元素下标
      //在CharBlackPool中创建一个新的区域来存储当前token的字符串信息
      System.arraycopy(tokenText, 0, text, textUpto, tokenTextLen);//charPool维护2：拷贝当前term文本到buffer
      text[textUpto+tokenTextLen] = 0xffff;//charPool维护3：为新添加的term文本添加终止符号
      //将倒排表放入哈希表中:
      assert postingsHash[hashPos] == null;
      postingsHash[hashPos] = p;
      numPostings++;

      if (numPostings == postingsHashHalfSize)//postingsHash数组中倒排表的数量==postingsHash数组长度的一半时，进行扩容操作
        rehashPostings(2*postingsHashSize);

      // Init stream slices
      //当intPool不足的时候，在freeIntBlocks中分配新的buffer：
      if (numPostingInt + intPool.intUpto > DocumentsWriter.INT_BLOCK_SIZE)
        intPool.nextBuffer();
      //当bytePool不足的时候，在freeByteBlocks中分配新的buffer：
      if (DocumentsWriter.BYTE_BLOCK_SIZE - bytePool.byteUpto < numPostingInt*ByteBlockPool.FIRST_LEVEL_SIZE)
        bytePool.nextBuffer();
      //此处streamCount为2，表明在intPool中，每两项表示一个词，一个是指向bytePool中freq信息偏移量的，一个信息偏移量的。
      intUptos = intPool.buffer;
      intUptoStart = intPool.intUpto;
      intPool.intUpto += streamCount; 

      p.intStart = intUptoStart + intPool.intOffset;//2、维护term对应的倒排表：intPool中的起始偏移量
      //在bytePool中分配两个空间，一个放freq信息，一个放prox信息的：
      for(int i=0;i<streamCount;i++) {
        final int upto = bytePool.newSlice(ByteBlockPool.FIRST_LEVEL_SIZE);
        intUptos[intUptoStart+i] = upto + bytePool.byteOffset;
      }
      p.byteStart = intUptos[intUptoStart];//3、维护term对应的倒排表：bytePool中的起始偏移量
      //当Term原来没有出现过的时候，调用newTerm
      //(2-5) 添加新Term的过程，consumer(FreqProxTermsWriterPerField).newTerm
      consumer.newTerm(p);

    } else {//如果此Token之前曾经出现过，则调用addTerm
      intUptos = intPool.buffers[p.intStart >> DocumentsWriter.INT_BLOCK_SHIFT];
      intUptoStart = p.intStart & DocumentsWriter.INT_BLOCK_MASK;
      //(2-6) 添加已有Term的过程
      consumer.addTerm(p);
    }

    if (doNextCall)
      nextPerField.add(p.textStart);
  }

  int[] intUptos;
  int intUptoStart;

  void writeByte(int stream, byte b) {
    int upto = intUptos[intUptoStart+stream];
    byte[] bytes = bytePool.buffers[upto >> DocumentsWriter.BYTE_BLOCK_SHIFT];
    assert bytes != null;
    int offset = upto & DocumentsWriter.BYTE_BLOCK_MASK;
    if (bytes[offset] != 0) {//在upto已经是当前块的结尾的时候,分配新块
      // End of slice; allocate a new one
      offset = bytePool.allocSlice(bytes, offset);
      bytes = bytePool.buffer;
      intUptos[intUptoStart+stream] = offset + bytePool.byteOffset;
    }
    bytes[offset] = b;
    (intUptos[intUptoStart+stream])++;
  }

  public void writeBytes(int stream, byte[] b, int offset, int len) {
    // TODO: optimize
    final int end = offset + len;
    for(int i=offset;i<end;i++)
      writeByte(stream, b[i]);
  }

  void writeVInt(int stream, int i) {
    assert stream < streamCount;
    while ((i & ~0x7F) != 0) {//大于7个字节时，要进行int->byte的处理（即int->vint），当只剩下7位时，跳出循环
      writeByte(stream, (byte)((i & 0x7f) | 0x80));//vint转化:从int的最最低7位开始，最高1位用1标识后面还有
      i >>>= 7;
    }
    writeByte(stream, (byte) i);
  }

  @Override
  void finish() throws IOException {
    consumer.finish();
    if (nextPerField != null)
      nextPerField.finish();
  }

  /** 扩容策略：
   * 1/声明一个新的长度为newSize的PostingList数组
   * 2遍历量原PostingList数组，依据同样的hash策略添加到最新的PostingList中
   * 注：原hash策略：逆序遍历term文本得到集合char[]，将char[]拼接为31进制的int code，之后数组下标=code&(newSize-1)，如果映射的数组
   *     下标有值，则重新计算数组下标=code+一增量【((code>>8)+code)|1，在while中进行直至映射的数组元素为空】
   * 问：二次的加增量映射，查的时候如何找到？primary是干嘛的？
   * Called when postings hash is too small (> 50% occupied) or too large (< 20% occupied). */
  void rehashPostings(final int newSize) {

    final int newMask = newSize-1;

    RawPostingList[] newHash = new RawPostingList[newSize];
    for(int i=0;i<postingsHashSize;i++) {
      RawPostingList p0 = postingsHash[i];
      if (p0 != null) {
        int code;
        if (perThread.primary) {
          final int start = p0.textStart & DocumentsWriter.CHAR_BLOCK_MASK;
          final char[] text = charPool.buffers[p0.textStart >> DocumentsWriter.CHAR_BLOCK_SHIFT];
          int pos = start;
          while(text[pos] != 0xffff)//每个term之间在buffer中以0xffff分隔
            pos++;
          code = 0;
          while (pos > start)
            code = (code*31) + text[--pos];
        } else
          code = p0.textStart;

        int hashPos = code & newMask;
        assert hashPos >= 0;
        if (newHash[hashPos] != null) {
          final int inc = ((code>>8)+code)|1;
          do {
            code += inc;
            hashPos = code & newMask;
          } while (newHash[hashPos] != null);
        }
        newHash[hashPos] = p0;
      }
    }

    postingsHashMask = newMask;
    postingsHash = newHash;
    postingsHashSize = newSize;
    postingsHashHalfSize = newSize >> 1;
  }
}
