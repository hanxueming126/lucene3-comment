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

import org.apache.lucene.document.Fieldable;

/**
 * 复用域处理对象:由于一个线程可以连续处理多个文档，而在普通的应用中，
 * 几乎每篇文档的域都是大致相同的，为每篇文档的每个域都创建一个处理对象非常低效，
 * 因而考虑到复用域处理对象DocFieldProcessorPerField，对于每一个域都有一个此对象
 * Holds all per thread, per field state.
 */
final class DocFieldProcessorPerField {

  final DocFieldConsumerPerField consumer;
  /**域元数据存储于此*/
  final FieldInfo fieldInfo;

  DocFieldProcessorPerField next;
  /**标识本field在索引过程中的更新版本：当DocFieldProcessorPerThread中的版本（fieldGen）和该值不一致时：同步该值到DocFieldProcessorPerThread.fields(DocFieldProcessorPerField数组)；默认值-1代表没用到，如果在flush中发现值为-1，将被移除*/
  int lastGen = -1;
  /**该域存储的记录数*/
  int fieldCount;
  /**该域存储的每条具体数据都存储于此*/
  Fieldable[] fields = new Fieldable[1];
  /**
   * 复用域处理对象:由于一个线程可以连续处理多个文档，而在普通的应用中，
   * 几乎每篇文档的域都是大致相同的，为每篇文档的每个域都创建一个处理对象非常低效，
   * 因而考虑到复用域处理对象DocFieldProcessorPerField，对于每一个域都有一个此对象
   * Holds all per thread, per field state.
   */
  public DocFieldProcessorPerField(final DocFieldProcessorPerThread perThread, final FieldInfo fieldInfo) {
    this.consumer = perThread.consumer.addField(fieldInfo);
    this.fieldInfo = fieldInfo;
  }

  public void abort() {
    consumer.abort();
  }
}
