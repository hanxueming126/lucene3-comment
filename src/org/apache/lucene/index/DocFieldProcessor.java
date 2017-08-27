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
import java.util.Map;


/**
 * 基本索引链0实现：
 * This is a DocConsumer that gathers all fields under the
 * same name, and calls per-field consumers to process field
 * by field.  This class doesn't doesn't do any "real" work
 * of its own: it just forwards the fields to a
 * DocFieldConsumer.
 */

final class DocFieldProcessor extends DocConsumer {

  final DocumentsWriter docWriter;
  final FieldInfos fieldInfos = new FieldInfos();
  final DocFieldConsumer consumer;
  final StoredFieldsWriter fieldsWriter;

  public DocFieldProcessor(DocumentsWriter docWriter, DocFieldConsumer consumer) {
    this.docWriter = docWriter;
    this.consumer = consumer;
    consumer.setFieldInfos(fieldInfos);
    fieldsWriter = new StoredFieldsWriter(docWriter, fieldInfos);
  }

  @Override
  public void closeDocStore(SegmentWriteState state) throws IOException {
    consumer.closeDocStore(state);
    fieldsWriter.closeDocStore(state);//存储域的关闭
  }

  @Override
  public void flush(Collection<DocConsumerPerThread> threads, SegmentWriteState state) throws IOException {

    Map<DocFieldConsumerPerThread, Collection<DocFieldConsumerPerField>> childThreadsAndFields = new HashMap<DocFieldConsumerPerThread, Collection<DocFieldConsumerPerField>>();
    for ( DocConsumerPerThread thread : threads) {
      DocFieldProcessorPerThread perThread = (DocFieldProcessorPerThread) thread;
      childThreadsAndFields.put(perThread.consumer, perThread.fields());
      //回收fieldHash（本轮flush操作中没用到的field），以便用于下一轮的索引，为提高效率，索引链中的对象是被复用的。
      perThread.trimFields(state);
    }
    //写入存储域：从代码中可以看出，是写入fdx,fdt两个文件，
    //但是在上述的closeDocStore已经写入了，并且把state.numDocsInStore置零，fieldsWriter 设为null，在这里其实什么也不做。
    fieldsWriter.flush(state);
    //写入索引域：
    consumer.flush(childThreadsAndFields, state);

    // Important to save after asking consumer to flush so
    // consumer can alter the FieldInfo* if necessary.  EG,
    // FreqProxTermsWriter does this with
    // FieldInfo.storePayload.
    //写入域元数据信息，并记录写入的文件名，以便以后生成cfs 文件：
    final String fileName = state.segmentFileName(IndexFileNames.FIELD_INFOS_EXTENSION);
    fieldInfos.write(state.directory, fileName);
    state.flushedFiles.add(fileName);
  }

  @Override
  public void abort() {
    fieldsWriter.abort();
    consumer.abort();
  }

  @Override
  public boolean freeRAM() {
    return consumer.freeRAM();
  }

  @Override
  public DocConsumerPerThread addThread(DocumentsWriterThreadState threadState) throws IOException {
    return new DocFieldProcessorPerThread(threadState, this);
  }
}
