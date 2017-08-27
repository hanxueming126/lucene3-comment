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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.lucene.util.UnicodeUtil;

/**
 * 基本索引链3实现 ：负责写freq, prox
 */
final class FreqProxTermsWriter extends TermsHashConsumer {

  @Override
  public TermsHashConsumerPerThread addThread(TermsHashPerThread perThread) {
    return new FreqProxTermsWriterPerThread(perThread);
  }

  @Override
  void createPostings(RawPostingList[] postings, int start, int count) {
    final int end = start + count;
    for(int i=start;i<end;i++)
      postings[i] = new PostingList();
  }

  private static int compareText(final char[] text1, int pos1, final char[] text2, int pos2) {
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
      } else if (0xffff == c1)
        return 0;
    }
  }

  @Override
  void closeDocStore(SegmentWriteState state) {}
  @Override
  void abort() {}


  // TODO: would be nice to factor out more of this, eg the
  // FreqProxFieldMergeState, and code to visit all Fields
  // under the same FieldInfo together, up into TermsHash*.
  // Other writers would presumably share alot of this...

/** 
 * 写入倒排表信息
 */
@Override
  public void flush(Map<TermsHashConsumerPerThread,Collection<TermsHashConsumerPerField>> threadsAndFields, final SegmentWriteState state) throws IOException {

    // Gather all FieldData's that have postings, across all
    // ThreadStates
    List<FreqProxTermsWriterPerField> allFields = new ArrayList<FreqProxTermsWriterPerField>();

    for (Map.Entry<TermsHashConsumerPerThread,Collection<TermsHashConsumerPerField>> entry : threadsAndFields.entrySet()) {

      Collection<TermsHashConsumerPerField> fields = entry.getValue();


      for (final TermsHashConsumerPerField i : fields) {
        final FreqProxTermsWriterPerField perField = (FreqProxTermsWriterPerField) i;
        if (perField.termsHashPerField.numPostings > 0)
          allFields.add(perField);
      }
    }

    // Sort by field name
    //(a) 所有域按名称排序，使得同名域能够一起处理:
    Collections.sort(allFields);
    final int numAllFields = allFields.size();

    // TODO: allow Lucene user to customize this consumer:
    //(b) 生成倒排表的写对象:
    final FormatPostingsFieldsConsumer consumer = new FormatPostingsFieldsWriter(state, fieldInfos);
    /*
    Current writer chain:
      FormatPostingsFieldsConsumer
        -> IMPL: FormatPostingsFieldsWriter
          -> FormatPostingsTermsConsumer
            -> IMPL: FormatPostingsTermsWriter
              -> FormatPostingsDocConsumer
                -> IMPL: FormatPostingsDocWriter
                  -> FormatPostingsPositionsConsumer
                    -> IMPL: FormatPostingsPositionsWriter
    */

    int start = 0;
    //(c) 对于每一个域
    while(start < numAllFields) {
      //(c-1) 找出所有的同名域
      final FieldInfo fieldInfo = allFields.get(start).fieldInfo;
      final String fieldName = fieldInfo.name;

      int end = start+1;
      while(end < numAllFields && allFields.get(end).fieldInfo.name.equals(fieldName))
        end++;
      
      FreqProxTermsWriterPerField[] fields = new FreqProxTermsWriterPerField[end-start];
      for(int i=start;i<end;i++) {
        fields[i-start] = allFields.get(i);

        // Aggregate the storePayload as seen by the same
        // field across multiple threads
        fieldInfo.storePayloads |= fields[i-start].hasPayloads;
      }

      // If this field has postings then add them to the segment
      // (c-2) 将同名域的倒排表添加到文件
      appendPostings(fields, consumer);
      //(c-3) 释放空间
      for(int i=0;i<fields.length;i++) {
        TermsHashPerField perField = fields[i].termsHashPerField;
        int numPostings = perField.numPostings;
        perField.reset();
        perField.shrinkHash(numPostings);
        fields[i].reset();
      }

      start = end;
    }

    for (Map.Entry<TermsHashConsumerPerThread,Collection<TermsHashConsumerPerField>> entry : threadsAndFields.entrySet()) {
      FreqProxTermsWriterPerThread perThread = (FreqProxTermsWriterPerThread) entry.getKey();
      perThread.termsHashPerThread.reset(true);
    }
    //(d) 关闭倒排表的写对象
    consumer.finish();
  }

  private byte[] payloadBuffer;

  /* 
   * 将同名域的倒排表添加到文件
   * Walk through all unique text tokens (Posting
   * instances) found in this field and serialize them
   * into a single RAM segment. */
  void appendPostings(FreqProxTermsWriterPerField[] fields,
                      FormatPostingsFieldsConsumer consumer)
    throws CorruptIndexException, IOException {

    int numFields = fields.length;

    final FreqProxFieldMergeState[] mergeStates = new FreqProxFieldMergeState[numFields];

    for(int i=0;i<numFields;i++) {
      FreqProxFieldMergeState fms = mergeStates[i] = new FreqProxFieldMergeState(fields[i]);

      assert fms.field.fieldInfo == fields[0].fieldInfo;

      // Should always be true
      boolean result = fms.nextTerm();//对所有的域，取第一个词(Term)
      assert result;
    }
    //(1) 添加此域，虽然有多个域，但是由于是同名域，只取第一个域的信息即可。返回的是用于添加此域中的词的对象。
    final FormatPostingsTermsConsumer termsConsumer = consumer.addField(fields[0].fieldInfo);

    FreqProxFieldMergeState[] termStates = new FreqProxFieldMergeState[numFields];

    final boolean currentFieldOmitTermFreqAndPositions = fields[0].fieldInfo.omitTermFreqAndPositions;
    /*(2) 此while循环是遍历每一个尚有未处理的词的域，依次按照词典顺序处理这些域所包含的词。
            当一个域中的所有的词都被处理过后，则numFields 减一，并从mergeStates 数组中移除此域。
            直到所有的域的所有的词都处理完毕，方才退出此循环。*/
    while(numFields > 0) {

      // Get the next term to merge
      /*(2-1) 找出所有域中按字典顺序的下一个词。可能多个同名域中，都包含同一个term，因而要
                遍历所有的numFields，得到所有的域里的下一个词，numToMerge即为有多少个域包含此词。*/
      termStates[0] = mergeStates[0];
      int numToMerge = 1;

      for(int i=1;i<numFields;i++) {
        final char[] text = mergeStates[i].text;
        final int textOffset = mergeStates[i].textOffset;
        final int cmp = compareText(text, textOffset, termStates[0].text, termStates[0].textOffset);

        if (cmp < 0) {
          termStates[0] = mergeStates[i];
          numToMerge = 1;
        } else if (cmp == 0)
          termStates[numToMerge++] = mergeStates[i];
      }
      /*(2-2) 添加此词，返回FormatPostingsDocsConsumer 用于添加文档号(doc ID)及词频信息(freq)*/
      final FormatPostingsDocsConsumer docConsumer = termsConsumer.addTerm(termStates[0].text, termStates[0].textOffset);

      // Now termStates has numToMerge FieldMergeStates
      // which all share the same term.  Now we must
      // interleave the docID streams.
      /*(2-3) 由于共numToMerge个域都包含此词，每个词都有一个链表的文档号表示包含这些词
                  的文档。此循环遍历所有的包含此词的域，依次按照从小到大的循序添加包含此词的文档号及词频信
                  息。当一个域中对此词的所有文档号都处理过了，则numToMerge减一，并从termStates 数组
                  中移除此域。当所有包含此词的域的所有文档号都处理过了，则结束此循环。*/
      while(numToMerge > 0) {
         //(2-3-1) 找出最小的文档号
        FreqProxFieldMergeState minState = termStates[0];
        for(int i=1;i<numToMerge;i++)
          if (termStates[i].docID < minState.docID)
            minState = termStates[i];

        final int termDocFreq = minState.termFreq;
        /*(2-3-2) 添加文档号及词频信息，并形成跳表，返回
        FormatPostingsPositionsConsumer用于添加位置(prox)信息*/
        final FormatPostingsPositionsConsumer posConsumer = docConsumer.addDoc(minState.docID, termDocFreq);

        final ByteSliceReader prox = minState.prox;//用于读取bytepool中的prox信息的。

        // Carefully copy over the prox + payload info,
        // changing the format to match Lucene's segment
        // format.
        if (!currentFieldOmitTermFreqAndPositions) {
          // omitTermFreqAndPositions == false so we do write positions &
          // payload          
          int position = 0;
          //(2-3-3) 此循环对包含此词的文档，添加位置信息
          for(int j=0;j<termDocFreq;j++) {
            final int code = prox.readVInt();
            position += code >> 1;

            final int payloadLength;
            // 如果此位置有payload 信息，则从bytepool 中读出，否则设为零。
            if ((code & 1) != 0) {
              // This position has a payload
              payloadLength = prox.readVInt();

              if (payloadBuffer == null || payloadBuffer.length < payloadLength)
                payloadBuffer = new byte[payloadLength];

              prox.readBytes(payloadBuffer, 0, payloadLength);

            } else
              payloadLength = 0;
            //添加位置(prox)信息
            posConsumer.addPosition(position, payloadBuffer, 0, payloadLength);
          } //End for

          posConsumer.finish();
        }
        /*(2-3-4) 判断退出条件，上次选中的域取得下一个文档号，如果没有，则说明此域包含此词的
                        文档已经处理完毕，则从termStates 中删除此域，并将numToMerge减一。然后此域取得下一
                        个词，当循环到(2)的时候，表明此域已经开始处理下一个词。如果没有下一个词，说明此域中的所
                        有的词都处理完毕，则从mergeStates 中删除此域，并将numFields 减一，当numFields 为
            0 的时候，循环(2)也就结束了。*/
        if (!minState.nextDoc()) {//获得下一个docid
          //如果此域包含此词的文档已经没有下一篇docid，则从数组termStates 中移除，numToMerge 减一。
          // Remove from termStates
          int upto = 0;
          for(int i=0;i<numToMerge;i++)
            if (termStates[i] != minState)
              termStates[upto++] = termStates[i];
          numToMerge--;
          assert upto == numToMerge;

          // Advance this state to the next term
          //此域则取下一个词(term)，在循环(2)处来参与下一个词的合并
          if (!minState.nextTerm()) {
            //如果此域没有下一个词了，则此域从数组mergeStates 中移除，numFields 减一。
            // OK, no more terms, so remove from mergeStates
            // as well
            upto = 0;
            for(int i=0;i<numFields;i++)
              if (mergeStates[i] != minState)
                mergeStates[upto++] = mergeStates[i];
            numFields--;
            assert upto == numFields;
          }
        }
      }
      /*(2-4) 经过上面的过程，docid 和freq 信息虽已经写入段文件，而跳表信息并没有写到文件中，
                  而是写入skip buffer 里面了，此处真正写入文件。并且词典(tii, tis)也应该写入文件。*/
      docConsumer.finish();
    }

    termsConsumer.finish();
  }

  final UnicodeUtil.UTF8Result termsUTF8 = new UnicodeUtil.UTF8Result();

  static final class PostingList extends RawPostingList {
      /**此词在此文档中出现的次数*/
    int docFreq;                                    // # times this term occurs in the current doc
    /**上次处理完的包含此词的文档号*/
    int lastDocID;                                  // Last docID where this term occurred
    /**按照或然跟随原则形成的文档号编码(不包括词频)*/
    int lastDocCode;                                // Code for prior doc
    /**上次处理完的此词的位置*/
    int lastPosition;                               // Last position where this term occurred
  }

  @Override
  int bytesPerPosting() {
    return RawPostingList.BYTES_SIZE + 4 * DocumentsWriter.INT_NUM_BYTE;
  }
}
