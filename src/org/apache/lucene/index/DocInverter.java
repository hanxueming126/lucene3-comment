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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;


/**
 * 基本索引链1实现 ：
 * This is a DocFieldConsumer that inverts each field,
 *  separately, from a Document, and accepts a
 *  InvertedTermsConsumer to process those terms. */

final class DocInverter extends DocFieldConsumer {

  final InvertedDocConsumer consumer;
  final InvertedDocEndConsumer endConsumer;

  public DocInverter(InvertedDocConsumer consumer, InvertedDocEndConsumer endConsumer) {
    this.consumer = consumer;
    this.endConsumer = endConsumer;
  }

  @Override
  void setFieldInfos(FieldInfos fieldInfos) {
    super.setFieldInfos(fieldInfos);
    consumer.setFieldInfos(fieldInfos);
    endConsumer.setFieldInfos(fieldInfos);
  }

  /** 
   * 生成新段的frq、nrm、 prx、tii、tis文件
   **/
  @Override
  void flush(Map<DocFieldConsumerPerThread, Collection<DocFieldConsumerPerField>> threadsAndFields, SegmentWriteState state) throws IOException {
    //组装下级索引链：（thread，field）
    Map<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>> childThreadsAndFields = new HashMap<InvertedDocConsumerPerThread,Collection<InvertedDocConsumerPerField>>();
    Map<InvertedDocEndConsumerPerThread,Collection<InvertedDocEndConsumerPerField>> endChildThreadsAndFields = new HashMap<InvertedDocEndConsumerPerThread,Collection<InvertedDocEndConsumerPerField>>();

    for (Map.Entry<DocFieldConsumerPerThread,Collection<DocFieldConsumerPerField>> entry : threadsAndFields.entrySet() ) {


      DocInverterPerThread perThread = (DocInverterPerThread) entry.getKey();

      Collection<InvertedDocConsumerPerField> childFields = new HashSet<InvertedDocConsumerPerField>();
      Collection<InvertedDocEndConsumerPerField> endChildFields = new HashSet<InvertedDocEndConsumerPerField>();
      for (final DocFieldConsumerPerField field: entry.getValue() ) {  
        DocInverterPerField perField = (DocInverterPerField) field;
        childFields.add(perField.consumer);
        endChildFields.add(perField.endConsumer);
      }

      childThreadsAndFields.put(perThread.consumer, childFields);
      endChildThreadsAndFields.put(perThread.endConsumer, endChildFields);
    }
    //写入倒排表及词向量信息:
    consumer.flush(childThreadsAndFields, state);
    //写入标准化因子:
    endConsumer.flush(endChildThreadsAndFields, state);
  }

  @Override
  public void closeDocStore(SegmentWriteState state) throws IOException {
    consumer.closeDocStore(state);
    endConsumer.closeDocStore(state);
  }

  @Override
  void abort() {
    consumer.abort();
    endConsumer.abort();
  }

  @Override
  public boolean freeRAM() {
    return consumer.freeRAM();
  }

  @Override
  public DocFieldConsumerPerThread addThread(DocFieldProcessorPerThread docFieldProcessorPerThread) {
    return new DocInverterPerThread(docFieldProcessorPerThread, this);
  }
}
