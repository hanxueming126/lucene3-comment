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

/** 
 * 当前线程对应的文档集处理对象(DocumentsWriterThreadState)
 * 为了支持多线程并发索引，对每一个线程都有一个DocumentsWriterThreadState，
 * 其为每一个线程根据DocConsumer consumer的索引链来创建每个线程的索引链(XXXPerThread)，来进行对文档的并发处理。
 * (在Lucene中，对于同一个索引文件夹，只能够有一个IndexWriter打开它，在打开后，在文件夹中，生成文件write.lock，
 * 当其他IndexWriter再试图打开此索引文件夹的时候，则会报org.apache.lucene.store.LockObtainFailedException错误。
 * 这样就出现了这样一个问题，在同一个进程中，对同一个索引文件夹，只能有一个IndexWriter打开它，
 * 因而如果想多线程向此索引文件夹中添加文档，则必须共享一个IndexWriter，而且在以往的实现中，
 * addDocument函数是同步的(synchronized)，也即多线程的索引并不能起到提高性能的效果。
 * 于是为了支持多线程索引，不使IndexWriter成为瓶颈，对于每一个线程都有一个相应的文档集处理对象
 * (DocumentsWriterThreadState)，这样对文档的索引过程可以多线程并行进行，从而增加索引的速度。)
 * Used by DocumentsWriter to maintain per-thread state.
 *  We keep a separate Posting hash and other state for each
 *  thread and then merge postings hashes from all threads
 *  when writing the segment. */
final class DocumentsWriterThreadState {
  /**标识当前对象是否空闲（被使用）：如果isIdle为false，则此线程等待
   * (在一篇文档处理之前，state.isIdle = false会被设定，而在一篇文档处理完毕之后，
   * DocumentsWriter.finishDocument(DocumentsWriterThreadState perThread, DocWriter docWriter)中，
   * 会首先执行perThread.isIdle = true; 然后notifyAll()来唤醒等待此文档完成的线程，从而处理下一篇文档。)
   **/
  boolean isIdle = true;                          // false if this is currently in use by a thread
  /**共享该实例的线程数量*/
  int numThreads = 1;                             // Number of threads that share this instance
  boolean doFlushAfter;                           // true if we should flush after processing current doc
  /**文档及域处理对象*/
  final DocConsumerPerThread consumer;
  final DocumentsWriter.DocState docState;

  final DocumentsWriter docWriter;

  public DocumentsWriterThreadState(DocumentsWriter docWriter) throws IOException {
    this.docWriter = docWriter;
    docState = new DocumentsWriter.DocState();
    docState.maxFieldLength = docWriter.maxFieldLength;
    docState.infoStream = docWriter.infoStream;
    docState.similarity = docWriter.similarity;
    docState.docWriter = docWriter;
    consumer = docWriter.consumer.addThread(this);
  }

  void doAfterFlush() {
    numThreads = 0;
    doFlushAfter = false;
  }
}
