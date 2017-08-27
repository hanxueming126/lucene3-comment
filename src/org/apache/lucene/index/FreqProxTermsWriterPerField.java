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

import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.document.Fieldable;

// TODO: break into separate freq and prox writers as
// codecs; make separate container (tii/tis/skip/*) that can
// be configured as any number of files 1..N
/**
 * 域索引链3实现：freq,prox
 */
final class FreqProxTermsWriterPerField extends TermsHashConsumerPerField implements Comparable<FreqProxTermsWriterPerField> {

  final FreqProxTermsWriterPerThread perThread;
  final TermsHashPerField termsHashPerField;
  final FieldInfo fieldInfo;
  final DocumentsWriter.DocState docState;
  final FieldInvertState fieldState;
  boolean omitTermFreqAndPositions;
  PayloadAttribute payloadAttribute;

  public FreqProxTermsWriterPerField(TermsHashPerField termsHashPerField, FreqProxTermsWriterPerThread perThread, FieldInfo fieldInfo) {
    this.termsHashPerField = termsHashPerField;
    this.perThread = perThread;
    this.fieldInfo = fieldInfo;
    docState = termsHashPerField.docState;
    fieldState = termsHashPerField.fieldState;
    omitTermFreqAndPositions = fieldInfo.omitTermFreqAndPositions;
  }

  @Override
  int getStreamCount() {
    if (fieldInfo.omitTermFreqAndPositions)
      return 1;
    else
      return 2;
  }

  @Override
  void finish() {}

  boolean hasPayloads;

  @Override
  void skippingLongTerm() throws IOException {}

  public int compareTo(FreqProxTermsWriterPerField other) {
    return fieldInfo.name.compareTo(other.fieldInfo.name);
  }

  void reset() {
    // Record, up front, whether our in-RAM format will be
    // with or without term freqs:
    omitTermFreqAndPositions = fieldInfo.omitTermFreqAndPositions;
    payloadAttribute = null;
  }

  @Override
  boolean start(Fieldable[] fields, int count) {
    for(int i=0;i<count;i++)
      if (fields[i].isIndexed())
        return true;
    return false;
  }     
  
  @Override
  void start(Fieldable f) {
    if (fieldState.attributeSource.hasAttribute(PayloadAttribute.class)) {
      payloadAttribute = fieldState.attributeSource.getAttribute(PayloadAttribute.class);
    } else {
      payloadAttribute = null;
    }
  }

  final void writeProx(FreqProxTermsWriter.PostingList p, int proxCode) {
    final Payload payload;
    if (payloadAttribute == null) {
      payload = null;
    } else {
      payload = payloadAttribute.getPayload();
    }
    
    if (payload != null && payload.length > 0) {
      termsHashPerField.writeVInt(1, (proxCode<<1)|1);
      termsHashPerField.writeVInt(1, payload.length);
      termsHashPerField.writeBytes(1, payload.data, payload.offset, payload.length);
      hasPayloads = true;      
    } else
        //写入prox信息:第一个参数表示向此词的第1个偏移量写入。
      termsHashPerField.writeVInt(1, proxCode<<1);//写prx文件中的positionDelta,payloadLen?,payloadData：或然跟随规则，无payload信息，左移后不用|1
    p.lastPosition = fieldState.position;
  }

  @Override
  final void newTerm(RawPostingList p0) {
    // First time we're seeing this term since the last
    // flush
    assert docState.testPoint("FreqProxTermsWriterPerField.newTerm start");
    FreqProxTermsWriter.PostingList p = (FreqProxTermsWriter.PostingList) p0;
    p.lastDocID = docState.docID;
    if (omitTermFreqAndPositions) {
      p.lastDocCode = docState.docID;
    } else {//写文档号和词频(freq)信息：记录最后一次文档号的目的是为了addTerm中追加doc时，应用差值规则？
      //文档号、词频是在addTerm中添加到bytePool中的
      p.lastDocCode = docState.docID << 1;//文档号：依据或然跟随规则左移一位(默认为跟随，即末位为0，之后在下一篇doc中addTerm的if(1 == p.docFreq)改)
      p.docFreq = 1;//词频：因为是在newTerm中，而不是addTerm中，所以赋值为1
      writeProx(p, fieldState.position);
    }
  }

  @Override
  final void addTerm(RawPostingList p0) {

    assert docState.testPoint("FreqProxTermsWriterPerField.addTerm start");

    FreqProxTermsWriter.PostingList p = (FreqProxTermsWriter.PostingList) p0;

    assert omitTermFreqAndPositions || p.docFreq > 0;

    if (omitTermFreqAndPositions) {
      if (docState.docID != p.lastDocID) {//当term在多个文档中出现时，才写入frq词频信息：p.lastDocCode已在newTerm中赋值=docState.docID无或然跟随规则
        assert docState.docID > p.lastDocID;
        termsHashPerField.writeVInt(0, p.lastDocCode);
        p.lastDocCode = docState.docID - p.lastDocID;
        p.lastDocID = docState.docID;
      }
    } else {
      if (docState.docID != p.lastDocID) {//另一doc的同一term走这，当term在多个文档中出现时，才写入frq词频信息：p.lastDocCode已在newTerm中赋值=docState.docID<<1有或然跟随规则
        assert docState.docID > p.lastDocID;
        // Term not yet seen in the current doc but previously
        // seen in other doc(s) since the last flush

        // Now that we know doc freq for previous doc,
        // write it & lastDocCode
        //在下一篇文档的处理过程出现了该term的时候方才写入，因为当一篇文档没有处理完毕的时候， freq也即词频是无法知道的:
        if (1 == p.docFreq)//写入前一篇doc该term的frq，|1的意思是后面没值（没值即为默认值1，或然跟随规则，docFreq在newTerm中赋初值时，虽然docFrq是1，但是末位默认为0，即跟随）
          termsHashPerField.writeVInt(0, p.lastDocCode|1);
        else {//当term在多个文档中出现时，才写入frq词频信息
          termsHashPerField.writeVInt(0, p.lastDocCode);
          termsHashPerField.writeVInt(0, p.docFreq);
        }
        p.docFreq = 1;//写入上一doc的docFreq后，为本doc的docFreq赋初值
        p.lastDocCode = (docState.docID - p.lastDocID) << 1;//docID差值规则+或然跟随规则(默认为跟随，即末位为0，之后在下一篇doc中addTerm的if(1 == p.docFreq)改)
        p.lastDocID = docState.docID;
        writeProx(p, fieldState.position);
      } else {//同一doc的同一term走这
        p.docFreq++;
        writeProx(p, fieldState.position-p.lastPosition);//差值规则
      }
    }
  }

  public void abort() {}
}

