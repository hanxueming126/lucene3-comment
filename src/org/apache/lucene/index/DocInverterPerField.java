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
import java.io.Reader;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Fieldable;

/**
 * 域索引链1实现：对索引域进行处理
 * Holds state for inverting all occurrences of a single
 * field in the document.  This class doesn't do anything
 * itself; instead, it forwards the tokens produced by
 * analysis to its own consumer
 * (InvertedDocConsumerPerField).  It also interacts with an
 * endConsumer (InvertedDocEndConsumerPerField).
 */
final class DocInverterPerField extends DocFieldConsumerPerField {

  final private DocInverterPerThread perThread;
  final private FieldInfo fieldInfo;
  final InvertedDocConsumerPerField consumer;
  final InvertedDocEndConsumerPerField endConsumer;
  final DocumentsWriter.DocState docState;
  final FieldInvertState fieldState;

  public DocInverterPerField(DocInverterPerThread perThread, FieldInfo fieldInfo) {
    this.perThread = perThread;
    this.fieldInfo = fieldInfo;
    docState = perThread.docState;
    fieldState = perThread.fieldState;
    this.consumer = perThread.consumer.addField(this, fieldInfo);
    this.endConsumer = perThread.endConsumer.addField(this, fieldInfo);
  }

  @Override
  void abort() {
    consumer.abort();
    endConsumer.abort();
  }

  @Override
  public void processFields(final Fieldable[] fields,
                            final int count) throws IOException {

    fieldState.reset(docState.doc.getBoost());

    final int maxFieldLength = docState.maxFieldLength;

    final boolean doInvert = consumer.start(fields, count);

    for(int i=0;i<count;i++) {

      final Fieldable field = fields[i];

      // TODO FI: this should be "genericized" to querying
      // consumer if it wants to see this particular field
      // tokenized.
      if (field.isIndexed() && doInvert) {

        final boolean anyToken;
        
        if (fieldState.length > 0)
          fieldState.position += docState.analyzer.getPositionIncrementGap(fieldInfo.name);
//      (1) 对不分词的域的处理
        if (!field.isTokenized()) {		  // un-tokenized field
//        (1-1) 得到域的内容，并构建单个Token形成的SingleTokenAttributeSource。因为不进行分词，因而整个域的内容算做一个Token.
          String stringValue = field.stringValue();
          final int valueLength = stringValue.length();
          perThread.singleToken.reinit(stringValue, 0, valueLength);
          fieldState.attributeSource = perThread.singleToken;
//        (1-2) 得到Token的各种属性信息，为索引做准备。
          consumer.start(field);//做的主要事情就是根据各种属性的类型来构造保存属性的对象

          boolean success = false;
          try {
//          (1-3) 将Token加入倒排表：加入倒排表的过程，无论对于分词的域和不分词的域，过程是一样的
            consumer.add();
            success = true;
          } finally {
            if (!success)
              docState.docWriter.setAborting();
          }
          fieldState.offset += valueLength;
          fieldState.length++;
          fieldState.position++;
          anyToken = valueLength > 0;
        } else {//(2) 对分词的域的处理                                  // tokenized field
          //(2-1) 构建域的TokenStream:
          final TokenStream stream;
          //用户可以在添加域的时候，应用构造函数public Field(String name, TokenStream tokenStream) 直接传进一个TokenStream就不用另外构建一个TokenStream了。
          final TokenStream streamValue = field.tokenStreamValue();

          if (streamValue != null) 
            stream = streamValue;
          else {
            // the field does not have a TokenStream,
            // so we have to obtain one from the analyzer
            final Reader reader;			  // find or make Reader
            final Reader readerValue = field.readerValue();

            if (readerValue != null)
              reader = readerValue;
            else {
              String stringValue = field.stringValue();
              if (stringValue == null)
                throw new IllegalArgumentException("field must have either TokenStream, String or Reader value");
              perThread.stringReader.init(stringValue);
              reader = perThread.stringReader;
            }
          
            // Tokenize field and add to postingTable
            stream = docState.analyzer.reusableTokenStream(fieldInfo.name, reader);
          }

          // reset the TokenStream to the first token
          stream.reset();

          final int startLength = fieldState.length;
          
          try {
            int offsetEnd = fieldState.offset-1;
            //(2-2) 得到第一个Token，并初始化此Token的各项属性信息，并为索引做准备(start)。
            boolean hasMoreTokens = stream.incrementToken();

            fieldState.attributeSource = stream;
            //得到偏 移量属性
            OffsetAttribute offsetAttribute = fieldState.attributeSource.addAttribute(OffsetAttribute.class);
            //得到位置属性
            PositionIncrementAttribute posIncrAttribute = fieldState.attributeSource.addAttribute(PositionIncrementAttribute.class);
            //其中得到了TermAttribute属性，如果存储payload则得到PayloadAttribute属性，
            //如果存储词向量则得到OffsetAttribute属性。
            consumer.start(field);
            //(2-3) 进行循环，不断的取下一个Token，并添加到倒排表
            for(;;) {

              // If we hit an exception in stream.next below
              // (which is fairly common, eg if analyzer
              // chokes on a given document), then it's
              // non-aborting and (above) this one document
              // will be marked as deleted, but still
              // consume a docID
              
              if (!hasMoreTokens) break;
              
              final int posIncr = posIncrAttribute.getPositionIncrement();
              fieldState.position += posIncr;
              if (fieldState.position > 0) {
                fieldState.position--;
              }

              if (posIncr == 0)
                fieldState.numOverlap++;

              boolean success = false;
              try {
                // If we hit an exception in here, we abort
                // all buffered documents since the last
                // flush, on the likelihood that the
                // internal state of the consumer is now
                // corrupt and should not be flushed to a
                // new segment:
                //(2-4) 添加Token到倒排表的过
                consumer.add();
                success = true;
              } finally {
                if (!success)
                  docState.docWriter.setAborting();
              }
              fieldState.position++;
              offsetEnd = fieldState.offset + offsetAttribute.endOffset();
              if (++fieldState.length >= maxFieldLength) {
                if (docState.infoStream != null)
                  docState.infoStream.println("maxFieldLength " +maxFieldLength+ " reached for field " + fieldInfo.name + ", ignoring following tokens");
                break;
              }

              hasMoreTokens = stream.incrementToken();
            }
            // trigger streams to perform end-of-stream operations
            stream.end();
            
            fieldState.offset += offsetAttribute.endOffset();
            anyToken = fieldState.length > startLength;
          } finally {
            stream.close();
          }
        }

        if (anyToken)
          fieldState.offset += docState.analyzer.getOffsetGap(field);
        fieldState.boost *= field.getBoost();
      }

      // LUCENE-2387: don't hang onto the field, so GC can
      // reclaim
      fields[i] = null;
    }

    consumer.finish();
    endConsumer.finish();
  }
}
