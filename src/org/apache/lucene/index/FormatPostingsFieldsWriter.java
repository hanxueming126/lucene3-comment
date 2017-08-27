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

import org.apache.lucene.store.Directory;

/**
 * field反向信息写入处理类：frq、prx、tii、tis文件，用于处理一个域
 */
final class FormatPostingsFieldsWriter extends FormatPostingsFieldsConsumer {

  final Directory dir;
  /**段名*/
  final String segment;
  /**tii, tis 文件的写对象:用于写tii,tis*/
  final TermInfosWriter termsOut;
  /**域元数据信息：本段的.fnm*/
  final FieldInfos fieldInfos;
  /**用于添加词(Term):用FormatPostingsFieldsWriter的termsOut、skipListWriter两个写对象，按照一定的格式写入段*/
  final FormatPostingsTermsWriter termsWriter;
  /**freq, prox中跳跃表的写对象:用于写freq, prox的跳跃表*/
  final DefaultSkipListWriter skipListWriter;
  /**文档总数*/
  final int totalNumDocs;

  /**
   * 反向信息写入准备：创建新段空的frq、prx、tii、tis文件及其各自的写入器，准备写入
   */
public FormatPostingsFieldsWriter(SegmentWriteState state, FieldInfos fieldInfos) throws IOException {
    super();

    dir = state.directory;
    segment = state.segmentName;
    totalNumDocs = state.numDocs;
    this.fieldInfos = fieldInfos;
    //用于写tii,tis
    termsOut = new TermInfosWriter(dir,
                                   segment,
                                   fieldInfos,
                                   state.termIndexInterval);

    // TODO: this is a nasty abstraction violation (that we
    // peek down to find freqOut/proxOut) -- we need a
    // better abstraction here whereby these child consumers
    // can provide skip data or not
    //用于写freq, prox的跳跃表
    skipListWriter = new DefaultSkipListWriter(termsOut.skipInterval,
                                               termsOut.maxSkipLevels,
                                               totalNumDocs,
                                               null,
                                               null);
    //记录写入的文件名
    state.flushedFiles.add(state.segmentFileName(IndexFileNames.TERMS_EXTENSION));
    state.flushedFiles.add(state.segmentFileName(IndexFileNames.TERMS_INDEX_EXTENSION));
    //用以上两个写对象，按照一定的格式写入段
    termsWriter = new FormatPostingsTermsWriter(state, this);
  }

  /** 
   * 用于添加索引域信息， 其返回FormatPostingsTermsConsumer用于添加词信息<br>
   * Add a new field */
  @Override
  FormatPostingsTermsConsumer addField(FieldInfo field) {
    termsWriter.setField(field);
    return termsWriter;
  }

  /** Called when we are done adding everything. */
  @Override
  void finish() throws IOException {
    termsOut.close();
    termsWriter.close();
  }
}
