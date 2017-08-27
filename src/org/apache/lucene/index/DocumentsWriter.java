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
import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMFile;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.ThreadInterruptedException;

/**
 * This class accepts multiple added documents and directly
 * writes a single segment file.  It does this more
 * efficiently than creating a single segment per document
 * (with DocumentWriter) and doing standard merges on those
 * segments.
 *
 * Each added document is passed to the {@link DocConsumer},
 * which in turn processes the document and interacts with
 * other consumers in the indexing chain.  Certain
 * consumers, like {@link StoredFieldsWriter} and {@link
 * TermVectorsTermsWriter}, digest a document and
 * immediately write bytes to the "doc store" files (ie,
 * they do not consume RAM per document, except while they
 * are processing the document).
 *
 * Other consumers, eg {@link FreqProxTermsWriter} and
 * {@link NormsWriter}, buffer bytes in RAM and flush only
 * when a new segment is produced.

 * Once we have used our allowed RAM buffer, or the number
 * of added docs is large enough (in the case we are
 * flushing by doc count instead of RAM usage), we create a
 * real segment and flush it to the Directory.
 *
 * Threads:
 *
 * Multiple threads are allowed into addDocument at once.
 * There is an initial synchronized call to getThreadState
 * which allocates a ThreadState for this thread.  The same
 * thread will get the same ThreadState over time (thread
 * affinity) so that if there are consistent patterns (for
 * example each thread is indexing a different content
 * source) then we make better use of RAM.  Then
 * processDocument is called on that ThreadState without
 * synchronization (most of the "heavy lifting" is in this
 * call).  Finally the synchronized "finishDocument" is
 * called to flush changes to the directory.
 *
 * When flush is called by IndexWriter we forcefully idle
 * all threads and flush only once they are all idle.  This
 * means you can call flush with a given thread even while
 * other threads are actively adding/deleting documents.
 *
 *
 * Exceptions:
 *
 * Because this class directly updates in-memory posting
 * lists, and flushes stored fields and term vectors
 * directly to files in the directory, there are certain
 * limited times when an exception can corrupt this state.
 * For example, a disk full while flushing stored fields
 * leaves this file in a corrupt state.  Or, an OOM
 * exception while appending to the in-memory posting lists
 * can corrupt that posting list.  We call such exceptions
 * "aborting exceptions".  In these cases we must call
 * abort() to discard all docs added since the last flush.
 *
 * All other exceptions ("non-aborting exceptions") can
 * still partially update the index structures.  These
 * updates are consistent, but, they represent only a part
 * of the document seen up until the exception was hit.
 * When this happens, we immediately mark the document as
 * deleted so that the document is always atomically ("all
 * or none") added to the index.
 */

/**
 * @author huangjianc
 *
 */
final class DocumentsWriter {

  IndexWriter writer;
  Directory directory;
  
  /**当前的段名，每当flush的时候，将索引写入以此为名称的段*/
  String segment;                         // Current segment we are working on
  
  /**存储域所要写入的目标段。(在索引文件格式(段)一文中已经详细描述)*/
  private String docStoreSegment;         // Current doc-store segment we are writing
  /**存储域在目标段中的偏移量，为-1时单独存储*/
  private int docStoreOffset;                     // Current starting doc-store offset of current segment
 /**下一篇添加到此索引的文档ID号，对于同一个索引文件夹，此变量唯一，且同步访问。*/
  private int nextDocID;                          // Next docID to be added
  private int numDocsInRAM;                       // # docs buffered in RAM
  /**写到索引库中doc的数量*/
  int numDocsInStore;                     // # docs written to doc stores

  // Max # ThreadState instances; if there are more threads
  // than this they share ThreadStates
  private final static int MAX_THREAD_STATE = 5;
  private DocumentsWriterThreadState[] threadStates = new DocumentsWriterThreadState[0];
  private final HashMap<Thread,DocumentsWriterThreadState> threadBindings = new HashMap<Thread,DocumentsWriterThreadState>();

  private int pauseThreads;               // Non-zero when we need all threads to
                                          // pause (eg to flush)
  boolean flushPending;                   // True when a thread has decided to flush
  /**用来判断缓存是否满了，如果满了，则应该写入磁盘*/
  boolean bufferIsFull;                   // True when it's time to write segment
  private boolean aborting;               // True if an abort is pending

  private DocFieldProcessor docFieldProcessor;
  /**可覆写输出流：打印日志信息*/
  PrintStream infoStream;
  /**一篇文档中，一个域内可索引的最大的词(Term)数：默认值10,000*/
  int maxFieldLength = IndexWriter.DEFAULT_MAX_FIELD_LENGTH;
  /**分词器*/
  Similarity similarity;

  List<String> newFiles;

  static class DocState {
    DocumentsWriter docWriter;
    Analyzer analyzer;
    int maxFieldLength;
    PrintStream infoStream;
    Similarity similarity;
    int docID;
    Document doc;
    String maxTermPrefix;

    // Only called by asserts
    public boolean testPoint(String name) {
      return docWriter.writer.testPoint(name);
    }

    public void clear() {
      // don't hold onto doc nor analyzer, in case it is
      // largish:
      doc = null;
      analyzer = null;
    }
  }

  /** Consumer returns this on each doc.  This holds any
   *  state that must be flushed synchronized "in docID
   *  order".  We gather these and flush them in order. */
  abstract static class DocWriter {
    DocWriter next;
    int docID;
    abstract void finish() throws IOException;
    abstract void abort();
    abstract long sizeInBytes();

    void setNext(DocWriter next) {
      this.next = next;
    }
  }

  /**
   * Create and return a new DocWriterBuffer.
   */
  PerDocBuffer newPerDocBuffer() {
    return new PerDocBuffer();
  }

  /**
   * RAMFile buffer for DocWriters.
   */
  class PerDocBuffer extends RAMFile {
    
    /**
     * Allocate bytes used from shared pool.
     */
    protected byte[] newBuffer(int size) {
      assert size == PER_DOC_BLOCK_SIZE;
      return perDocAllocator.getByteBlock(false);
    }
    
    /**
     * Recycle the bytes used.
     */
    synchronized void recycle() {
      if (buffers.size() > 0) {
        setLength(0);
        
        // Recycle the blocks
        perDocAllocator.recycleByteBlocks(buffers);
        buffers.clear();
        sizeInBytes = 0;
        
        assert numBuffers() == 0;
      }
    }
  }
  
  /**
   * The IndexingChain must define the {@link #getChain(DocumentsWriter)} method
   * which returns the DocConsumer that the DocumentsWriter calls to process the
   * documents. 
   */
  abstract static class IndexingChain {
    abstract DocConsumer getChain(DocumentsWriter documentsWriter);
  }
  
  static final IndexingChain DefaultIndexingChain = new IndexingChain() {

    @Override
    DocConsumer getChain(DocumentsWriter documentsWriter) {
      /*
      This is the current indexing chain:

      DocConsumer / DocConsumerPerThread
        --> code: DocFieldProcessor / DocFieldProcessorPerThread
          --> DocFieldConsumer / DocFieldConsumerPerThread / DocFieldConsumerPerField
            --> code: DocFieldConsumers / DocFieldConsumersPerThread / DocFieldConsumersPerField
              --> code: DocInverter / DocInverterPerThread / DocInverterPerField
                --> InvertedDocConsumer / InvertedDocConsumerPerThread / InvertedDocConsumerPerField
                  --> code: TermsHash / TermsHashPerThread / TermsHashPerField
                    --> TermsHashConsumer / TermsHashConsumerPerThread / TermsHashConsumerPerField
                      --> code: FreqProxTermsWriter / FreqProxTermsWriterPerThread / FreqProxTermsWriterPerField
                      --> code: TermVectorsTermsWriter / TermVectorsTermsWriterPerThread / TermVectorsTermsWriterPerField
                --> InvertedDocEndConsumer / InvertedDocConsumerPerThread / InvertedDocConsumerPerField
                  --> code: NormsWriter / NormsWriterPerThread / NormsWriterPerField
              --> code: StoredFieldsWriter / StoredFieldsWriterPerThread / StoredFieldsWriterPerField
    */

    // Build up indexing chain:
      /**负责写tvd, tvf信息*/
      final TermsHashConsumer termVectorsWriter = new TermVectorsTermsWriter(documentsWriter);
      /**负责写freq, prox*/
      final TermsHashConsumer freqProxWriter = new FreqProxTermsWriter();
      
      final InvertedDocConsumer  termsHash = new TermsHash(documentsWriter, true, freqProxWriter,
      /**负责写nrm信息*/                                           new TermsHash(documentsWriter, false, termVectorsWriter, null));
      final NormsWriter normsWriter = new NormsWriter();
      final DocInverter docInverter = new DocInverter(termsHash, normsWriter);
      /**索引链源头*/
      return new DocFieldProcessor(documentsWriter, docInverter);
    }
  };
  /**这是整个索引过程的核心，是IndexChain整个索引链的源头。*/
  final DocConsumer consumer;

  // Deletes done after the last flush; these are discarded
  // on abort
  /**用writer删除后，会被缓存在deletesInRAM及deletesFlushed中，只有写入到索引文件中，当reader再次打开时才能够看到。*/
  private BufferedDeletes deletesInRAM = new BufferedDeletes(false);

  // Deletes done before the last flush; these are still
  // kept on abort
  /**放置正在写入磁盘的删除文档*/
  private BufferedDeletes deletesFlushed = new BufferedDeletes(true);

  // The max number of delete terms that can be buffered before
  // they must be flushed to disk.
  /**可缓存的最大的删除词(Term)数。当大于这个数的时候，就要写到文件中了*/
  private int maxBufferedDeleteTerms = IndexWriter.DEFAULT_MAX_BUFFERED_DELETE_TERMS;

  // How much RAM we can use before flushing.  This is 0 if
  // we are flushing by doc count instead.
  /**用户设定的内存用量，当使用内存大于此时，开始写入磁盘*/
  private long ramBufferSize = (long) (IndexWriter.DEFAULT_RAM_BUFFER_SIZE_MB*1024*1024);
  /**waitQueue触发doPause()阀值：默认为用户指定的内存使用量的10%*/
  private long waitQueuePauseBytes = (long) (ramBufferSize*0.1);
  private long waitQueueResumeBytes = (long) (ramBufferSize*0.05);

  // If we've allocated 5% over our RAM budget, we then
  // free down to 95%
  /**应该开始回收内存时的内存用量*/
  private long freeTrigger = (long) (IndexWriter.DEFAULT_RAM_BUFFER_SIZE_MB*1024*1024*1.05);
  /**回收内存应该回收到的内存用量*/
  private long freeLevel = (long) (IndexWriter.DEFAULT_RAM_BUFFER_SIZE_MB*1024*1024*0.95);

  // Flush @ this number of docs.  If ramBufferSize is
  // non-zero we will flush by RAM usage instead.
  private int maxBufferedDocs = IndexWriter.DEFAULT_MAX_BUFFERED_DOCS;

  private int flushedDocCount;                      // How many docs already flushed to index

  synchronized void updateFlushedDocCount(int n) {
    flushedDocCount += n;
  }
  synchronized int getFlushedDocCount() {
    return flushedDocCount;
  }
  synchronized void setFlushedDocCount(int n) {
    flushedDocCount = n;
  }

  private boolean closed;

  DocumentsWriter(Directory directory, IndexWriter writer, IndexingChain indexingChain) throws IOException {
    this.directory = directory;
    this.writer = writer;
    this.similarity = writer.getSimilarity();
    flushedDocCount = writer.maxDoc();

    consumer = indexingChain.getChain(this);
    if (consumer instanceof DocFieldProcessor) {
      docFieldProcessor = (DocFieldProcessor) consumer;
    }
  }

  /** Returns true if any of the fields in the current
   *  buffered docs have omitTermFreqAndPositions==false */
  boolean hasProx() {
    return (docFieldProcessor != null) ? docFieldProcessor.fieldInfos.hasProx()
                                       : true;
  }

  /** If non-null, various details of indexing are printed
   *  here. */
  synchronized void setInfoStream(PrintStream infoStream) {
    this.infoStream = infoStream;
    for(int i=0;i<threadStates.length;i++)
      threadStates[i].docState.infoStream = infoStream;
  }

  synchronized void setMaxFieldLength(int maxFieldLength) {
    this.maxFieldLength = maxFieldLength;
    for(int i=0;i<threadStates.length;i++)
      threadStates[i].docState.maxFieldLength = maxFieldLength;
  }

  synchronized void setSimilarity(Similarity similarity) {
    this.similarity = similarity;
    for(int i=0;i<threadStates.length;i++)
      threadStates[i].docState.similarity = similarity;
  }

  /** Set how much RAM we can use before flushing. */
  synchronized void setRAMBufferSizeMB(double mb) {
    if (mb == IndexWriter.DISABLE_AUTO_FLUSH) {
      ramBufferSize = IndexWriter.DISABLE_AUTO_FLUSH;
      waitQueuePauseBytes = 4*1024*1024;
      waitQueueResumeBytes = 2*1024*1024;
    } else {
      ramBufferSize = (long) (mb*1024*1024);
      waitQueuePauseBytes = (long) (ramBufferSize*0.1);
      waitQueueResumeBytes = (long) (ramBufferSize*0.05);
      freeTrigger = (long) (1.05 * ramBufferSize);//当分配的内存到达105%的时候开始释放freeBlocks中的内存
      freeLevel = (long) (0.95 * ramBufferSize);//一直释放到95%
    }
  }

  synchronized double getRAMBufferSizeMB() {
    if (ramBufferSize == IndexWriter.DISABLE_AUTO_FLUSH) {
      return ramBufferSize;
    } else {
      return ramBufferSize/1024./1024.;
    }
  }

  /** Set max buffered docs, which means we will flush by
   *  doc count instead of by RAM usage. */
  void setMaxBufferedDocs(int count) {
    maxBufferedDocs = count;
  }

  int getMaxBufferedDocs() {
    return maxBufferedDocs;
  }

  /** Get current segment name we are writing. */
  String getSegment() {
    return segment;
  }

  /** Returns how many docs are currently buffered in RAM. */
  int getNumDocsInRAM() {
    return numDocsInRAM;
  }

  /** Returns the current doc store segment we are writing
   *  to. */
  synchronized String getDocStoreSegment() {
    return docStoreSegment;
  }

  /** Returns the doc offset into the shared doc store for
   *  the current buffered docs. */
  int getDocStoreOffset() {
    return docStoreOffset;
  }

  /** Closes the current open doc stores an returns the doc
   *  store segment name.  This returns null if there are *
   *  no buffered documents. */
  synchronized String closeDocStore() throws IOException {
    
    assert allThreadsIdle();

    if (infoStream != null)
      message("closeDocStore: " + openFiles.size() + " files to flush to segment " + docStoreSegment + " numDocs=" + numDocsInStore);
    
    boolean success = false;

    try {
      initFlushState(true);
      closedFiles.clear();

      consumer.closeDocStore(flushState);
      assert 0 == openFiles.size();

      String s = docStoreSegment;
      docStoreSegment = null;
      docStoreOffset = 0;
      numDocsInStore = 0;
      success = true;
      return s;
    } finally {
      if (!success) {
        abort();
      }
    }
  }

  private Collection<String> abortedFiles;               // List of files that were written before last abort()

  private SegmentWriteState flushState;

  Collection<String> abortedFiles() {
    return abortedFiles;
  }

  void message(String message) {
    if (infoStream != null)
      writer.message("DW: " + message);
  }

  final List<String> openFiles = new ArrayList<String>();
  final List<String> closedFiles = new ArrayList<String>();

  /* Returns Collection of files in use by this instance,
   * including any flushed segments. */
  @SuppressWarnings("unchecked")
  synchronized List<String> openFiles() {
    return (List<String>) ((ArrayList<String>) openFiles).clone();
  }

  @SuppressWarnings("unchecked")
  synchronized List<String> closedFiles() {
    return (List<String>) ((ArrayList<String>) closedFiles).clone();
  }

  synchronized void addOpenFile(String name) {
    assert !openFiles.contains(name);
    openFiles.add(name);
  }

  synchronized void removeOpenFile(String name) {
    assert openFiles.contains(name);
    openFiles.remove(name);
    closedFiles.add(name);
  }

  synchronized void setAborting() {
    aborting = true;
  }

  /** Called if we hit an exception at a bad time (when
   *  updating the index files) and must discard all
   *  currently buffered docs.  This resets our state,
   *  discarding any docs added since last flush. */
  synchronized void abort() throws IOException {

    try {
      if (infoStream != null) {
        message("docWriter: now abort");
      }

      // Forcefully remove waiting ThreadStates from line
      waitQueue.abort();

      // Wait for all other threads to finish with
      // DocumentsWriter:
      pauseAllThreads();

      try {

        assert 0 == waitQueue.numWaiting;

        waitQueue.waitingBytes = 0;

        try {
          abortedFiles = openFiles();
        } catch (Throwable t) {
          abortedFiles = null;
        }

        deletesInRAM.clear();

        openFiles.clear();

        for(int i=0;i<threadStates.length;i++)
          try {
            threadStates[i].consumer.abort();
          } catch (Throwable t) {
          }

        try {
          consumer.abort();
        } catch (Throwable t) {
        }

        docStoreSegment = null;
        numDocsInStore = 0;
        docStoreOffset = 0;

        // Reset all postings data
        doAfterFlush();

      } finally {
        resumeAllThreads();
      }
    } finally {
      aborting = false;
      notifyAll();
      if (infoStream != null) {
        message("docWriter: done abort");
      }
    }
  }

  /** Reset after a flush */
  private void doAfterFlush() throws IOException {
    // All ThreadStates should be idle when we are called
    assert allThreadsIdle();
    threadBindings.clear();
    waitQueue.reset();
    segment = null;
    numDocsInRAM = 0;
    nextDocID = 0;
    bufferIsFull = false;
    flushPending = false;
    for(int i=0;i<threadStates.length;i++)
      threadStates[i].doAfterFlush();
    numBytesUsed = 0;
  }

  // Returns true if an abort is in progress
  
  /**
   * 让所有的线程都wait
   */
synchronized boolean pauseAllThreads() {
    pauseThreads++;
    while(!allThreadsIdle()) {
      try {
        wait();
      } catch (InterruptedException ie) {
        throw new ThreadInterruptedException(ie);
      }
    }

    return aborting;
  }

  synchronized void resumeAllThreads() {
    pauseThreads--;
    assert pauseThreads >= 0;
    if (0 == pauseThreads)
      notifyAll();
  }

  private synchronized boolean allThreadsIdle() {
    for(int i=0;i<threadStates.length;i++)
      if (!threadStates[i].isIdle)
        return false;
    return true;
  }

  synchronized boolean anyChanges() {
    return numDocsInRAM != 0 ||
      deletesInRAM.numTerms != 0 ||
      deletesInRAM.docIDs.size() != 0 ||
      deletesInRAM.queries.size() != 0;
  }

  synchronized private void initFlushState(boolean onlyDocStore) {
    initSegmentName(onlyDocStore);
    flushState = new SegmentWriteState(this, directory, segment, docStoreSegment, numDocsInRAM, numDocsInStore, writer.getTermIndexInterval());
  }

  /** 
   * Flush all pending docs to a new segment<br>
   * 此过程又包含以下两个阶段；<br>
   * 1、按照基本索引链关闭存储域和词向量信息<br>
   * 2、按照基本索引链的结构将索引结果写入段
   * @param closeDocStore 是否关闭存储域和词向量信息
   **/
  synchronized int flush(boolean closeDocStore) throws IOException {

    assert allThreadsIdle();

    assert numDocsInRAM > 0;

    assert nextDocID == numDocsInRAM;
    assert waitQueue.numWaiting == 0;
    assert waitQueue.waitingBytes == 0;

    initFlushState(false);

    docStoreOffset = numDocsInStore;

    if (infoStream != null)
      message("flush postings as segment " + flushState.segmentName + " numDocs=" + numDocsInRAM);
    
    boolean success = false;

    try {

      if (closeDocStore) {
        assert flushState.docStoreSegmentName != null;
        assert flushState.docStoreSegmentName.equals(flushState.segmentName);
        closeDocStore();//1、按照基本索引链关闭存储域和词向量信息
        flushState.numDocsInStore = 0;
      }

      Collection<DocConsumerPerThread> threads = new HashSet<DocConsumerPerThread>();
      for(int i=0;i<threadStates.length;i++)
        threads.add(threadStates[i].consumer);
      consumer.flush(threads, flushState);//2、按照基本索引链的结构将索引结果写入段

      if (infoStream != null) {
        SegmentInfo si = new SegmentInfo(flushState.segmentName, flushState.numDocs, directory);
        final long newSegmentSize = si.sizeInBytes();
        String message = "  oldRAMSize=" + numBytesUsed +
          " newFlushedSize=" + newSegmentSize +
          " docs/MB=" + nf.format(numDocsInRAM/(newSegmentSize/1024./1024.)) +
          " new/old=" + nf.format(100.0*newSegmentSize/numBytesUsed) + "%";
        message(message);
      }

      flushedDocCount += flushState.numDocs;

      doAfterFlush();

      success = true;

    } finally {
      if (!success) {
        abort();
      }
    }

    assert waitQueue.waitingBytes == 0;

    return flushState.numDocs;
  }

  /** Build compound file for the segment we just flushed */
  void createCompoundFile(String segment) throws IOException {
    
    CompoundFileWriter cfsWriter = new CompoundFileWriter(directory, segment + "." + IndexFileNames.COMPOUND_FILE_EXTENSION);
    //将已记录的文档名全部加入cfs段的写对象。
    for (final String flushedFile : flushState.flushedFiles)
      cfsWriter.addFile(flushedFile);
      
    // Perform the merge
    cfsWriter.close();
  }

  /** Set flushPending if it is not already set and returns
   *  whether it was set. This is used by IndexWriter to
   *  trigger a single flush even when multiple threads are
   *  trying to do so. */
  synchronized boolean setFlushPending() {
    if (flushPending)
      return false;
    else {
      flushPending = true;
      return true;
    }
  }

  synchronized void clearFlushPending() {
    flushPending = false;
  }

/**
 * 此处将deletesInRAM全部加到deletesFlushed中，并把deletesInRAM清空。
 */
synchronized void pushDeletes() {
    deletesFlushed.update(deletesInRAM);
  }

  synchronized void close() {
    closed = true;
    notifyAll();
  }

  synchronized void initSegmentName(boolean onlyDocStore) {
    if (segment == null && (!onlyDocStore || docStoreSegment == null)) {
      segment = writer.newSegmentName();
      assert numDocsInRAM == 0;
    }
    if (docStoreSegment == null) {
      docStoreSegment = segment;
      assert numDocsInStore == 0;
    }
  }

  /** 
   * 得到当前线程对应的文档集处理对象(DocumentsWriterThreadState)：
   * 在Lucene中，文档是按添加的顺序编号的，DocumentsWriter中的nextDocID就是记录下一个添加的文档id。 
   * 当Lucene持多线程的时候，就必须要有一个synchornized方法来付给文档id并且将nextDocID加一，
   * 这些是在DocumentsWriter.getThreadState这个函数里面做的。
   * Returns a free (idle) ThreadState that may be used for
   * indexing this one document.  This call also pauses if a
   * flush is pending.  If delTerm is non-null then we
   * buffer this deleted term after the thread state has
   * been acquired. */
  synchronized DocumentsWriterThreadState getThreadState(Document doc, Term delTerm) throws IOException {

    // First, find a thread state.  If this thread already
    // has affinity to a specific ThreadState, use that one
    // again.
//     1、根据当前线程对象，从HashMap中查找相应的DocumentsWriterThreadState对象，
//     如果没找到，则生成一个新对象，并添加到HashMap中
    DocumentsWriterThreadState state = threadBindings.get(Thread.currentThread());
    if (state == null) {

      // First time this thread has called us since last
      // flush.  Find the least loaded thread state:
      DocumentsWriterThreadState minThreadState = null;
//    找到拥有线程数量最少的对象：
      for(int i=0;i<threadStates.length;i++) {
        DocumentsWriterThreadState ts = threadStates[i];
        if (minThreadState == null || ts.numThreads < minThreadState.numThreads)
          minThreadState = ts;
      }
      if (minThreadState != null && (minThreadState.numThreads == 0 || threadStates.length >= MAX_THREAD_STATE)) {
        state = minThreadState;
        state.numThreads++;
      } else {
        // Just create a new "private" thread state
        DocumentsWriterThreadState[] newArray = new DocumentsWriterThreadState[1+threadStates.length];
        if (threadStates.length > 0)
          System.arraycopy(threadStates, 0, newArray, 0, threadStates.length);
        state = newArray[threadStates.length] = new DocumentsWriterThreadState(this);
        threadStates = newArray;
      }
      threadBindings.put(Thread.currentThread(), state);
    }

    // Next, wait until my thread state is idle (in case
    // it's shared with other threads) and for threads to
    // not be paused nor a flush pending:
//     2、如果此线程对象正在用于处理上一篇文档，则等待，直到此线程的上一篇文档处理完。
    waitReady(state);

    // Allocate segment name if this is the first doc since
    // last flush:
//    如果IndexWriter刚刚commit过，则新添加的文档要加入到新的段中(segment)，则首先要生成新的段名。
    initSegmentName(false);
    /**
     * 标识state对象为非空闲（已使用状态）*/
    state.isIdle = false;

    boolean success = false;
    try {
      state.docState.docID = nextDocID;

      assert writer.testPoint("DocumentsWriter.ThreadState.init start");

      if (delTerm != null) {
        addDeleteTerm(delTerm, state.docState.docID);
        state.doFlushAfter = timeToFlushDeletes();
      }

      assert writer.testPoint("DocumentsWriter.ThreadState.init after delTerm");

      nextDocID++;
      numDocsInRAM++;

      // We must at this point commit to flushing to ensure we
      // always get N docs when we flush by doc count, even if
      // > 1 thread is adding documents:
      if (!flushPending &&
          maxBufferedDocs != IndexWriter.DISABLE_AUTO_FLUSH
          && numDocsInRAM >= maxBufferedDocs) {
        flushPending = true;
        state.doFlushAfter = true;
      }

      success = true;
    } finally {
      if (!success) {
        // Forcefully idle this ThreadState:
        state.isIdle = true;
        notifyAll();
        if (state.doFlushAfter) {
          state.doFlushAfter = false;
          flushPending = false;
        }
      }
    }

    return state;
  }

  /** Returns true if the caller (IndexWriter) should now
   * flush. */
  boolean addDocument(Document doc, Analyzer analyzer)
    throws CorruptIndexException, IOException {
    return updateDocument(doc, analyzer, null);
  }

  boolean updateDocument(Term t, Document doc, Analyzer analyzer)
    throws CorruptIndexException, IOException {
    return updateDocument(doc, analyzer, t);
  }

  boolean updateDocument(Document doc, Analyzer analyzer, Term delTerm)
    throws CorruptIndexException, IOException {

    // This call is synchronized but fast
    /**
     * 得到当前线程对应的文档集处理对象(DocumentsWriterThreadState):
     * 为了支持多线程并发索引，对每一个线程都有一个DocumentsWriterThreadState，
     * 其为每一个线程根据DocConsumer consumer的索引链来创建每个线程的索引链(XXXPerThread)，
     * 来进行对文档的并发处理。*/
    final DocumentsWriterThreadState state = getThreadState(doc, delTerm); 

    final DocState docState = state.docState;
    docState.doc = doc;
    docState.analyzer = analyzer;

    boolean success = false;
    try {
      // This call is not synchronized and does all the
      // work
      final DocWriter perDoc;
      try {
//      用得到的文档集处理对象处理文档
        perDoc = state.consumer.processDocument();
      } finally {
        docState.clear();
      }

      // This call is synchronized but fast
      finishDocument(state, perDoc);

      success = true;
    } finally {
      if (!success) {
        synchronized(this) {

          if (aborting) {
            state.isIdle = true;
            notifyAll();
            abort();
          } else {
            skipDocWriter.docID = docState.docID;
            boolean success2 = false;
            try {
              waitQueue.add(skipDocWriter);
              success2 = true;
            } finally {
              if (!success2) {
                state.isIdle = true;
                notifyAll();
                abort();
                return false;
              }
            }

            state.isIdle = true;
            notifyAll();

            // If this thread state had decided to flush, we
            // must clear it so another thread can flush
            if (state.doFlushAfter) {
              state.doFlushAfter = false;
              flushPending = false;
              notifyAll();
            }

            // Immediately mark this document as deleted
            // since likely it was partially added.  This
            // keeps indexing as "all or none" (atomic) when
            // adding a document:
            addDeleteDocID(state.docState.docID);
          }
        }
      }
    }

    return state.doFlushAfter || timeToFlushDeletes();
  }

  // for testing
  synchronized int getNumBufferedDeleteTerms() {
    return deletesInRAM.numTerms;
  }

  // for testing
  synchronized Map<Term,BufferedDeletes.Num> getBufferedDeleteTerms() {
    return deletesInRAM.terms;
  }

  /** Called whenever a merge has completed and the merged segments had deletions */
  synchronized void remapDeletes(SegmentInfos infos, int[][] docMaps, int[] delCounts, MergePolicy.OneMerge merge, int mergeDocCount) {
    if (docMaps == null)
      // The merged segments had no deletes so docIDs did not change and we have nothing to do
      return;
    MergeDocIDRemapper mapper = new MergeDocIDRemapper(infos, docMaps, delCounts, merge, mergeDocCount);
    deletesInRAM.remap(mapper, infos, docMaps, delCounts, merge, mergeDocCount);
    deletesFlushed.remap(mapper, infos, docMaps, delCounts, merge, mergeDocCount);
    flushedDocCount -= mapper.docShift;
  }

  /**
   * 如果此线程对象正在用于处理上一篇文档，则等待，直到此线程的上一篇文档处理完
   */
synchronized private void waitReady(DocumentsWriterThreadState state) {

    while (!closed && ((state != null && !state.isIdle) || pauseThreads != 0 || flushPending || aborting)) {
      try {
        wait();
      } catch (InterruptedException ie) {
        throw new ThreadInterruptedException(ie);
      }
    }

    if (closed)
      throw new AlreadyClosedException("this IndexWriter is closed");
  }

  synchronized boolean bufferDeleteTerms(Term[] terms) throws IOException {
    waitReady(null);
    for (int i = 0; i < terms.length; i++)
      addDeleteTerm(terms[i], numDocsInRAM);
    return timeToFlushDeletes();
  }

  synchronized boolean bufferDeleteTerm(Term term) throws IOException {
    waitReady(null);
    addDeleteTerm(term, numDocsInRAM);
    return timeToFlushDeletes();
  }

  synchronized boolean bufferDeleteQueries(Query[] queries) throws IOException {
    waitReady(null);
    for (int i = 0; i < queries.length; i++)
      addDeleteQuery(queries[i], numDocsInRAM);
    return timeToFlushDeletes();
  }

  synchronized boolean bufferDeleteQuery(Query query) throws IOException {
    waitReady(null);
    addDeleteQuery(query, numDocsInRAM);
    return timeToFlushDeletes();
  }

  synchronized boolean deletesFull() {
    return (ramBufferSize != IndexWriter.DISABLE_AUTO_FLUSH &&
            (deletesInRAM.bytesUsed + deletesFlushed.bytesUsed + numBytesUsed) >= ramBufferSize) ||
      (maxBufferedDeleteTerms != IndexWriter.DISABLE_AUTO_FLUSH &&
       ((deletesInRAM.size() + deletesFlushed.size()) >= maxBufferedDeleteTerms));
  }

  /**
   * 判断是否将内存中已缓存的“已删除文档”写入磁盘，已释放内存空间
   */
synchronized boolean doApplyDeletes() {
    // Very similar to deletesFull(), except we don't count
    // numBytesAlloc, because we are checking whether
    // deletes (alone) are consuming too many resources now
    // and thus should be applied.  We apply deletes if RAM
    // usage is > 1/2 of our allowed RAM buffer, to prevent
    // too-frequent flushing of a long tail of tiny segments
    // when merges (which always apply deletes) are
    // infrequent.
    return (ramBufferSize != IndexWriter.DISABLE_AUTO_FLUSH &&
            (deletesInRAM.bytesUsed + deletesFlushed.bytesUsed) >= ramBufferSize/2) ||
      (maxBufferedDeleteTerms != IndexWriter.DISABLE_AUTO_FLUSH &&
       ((deletesInRAM.size() + deletesFlushed.size()) >= maxBufferedDeleteTerms));
  }

  synchronized private boolean timeToFlushDeletes() {
    return (bufferIsFull || deletesFull()) && setFlushPending();
  }

  void setMaxBufferedDeleteTerms(int maxBufferedDeleteTerms) {
    this.maxBufferedDeleteTerms = maxBufferedDeleteTerms;
  }

  int getMaxBufferedDeleteTerms() {
    return maxBufferedDeleteTerms;
  }

  synchronized boolean hasDeletes() {
    return deletesFlushed.any();
  }

  synchronized boolean applyDeletes(SegmentInfos infos) throws IOException {

    if (!hasDeletes())
      return false;

    if (infoStream != null)
      message("apply " + deletesFlushed.numTerms + " buffered deleted terms and " +
              deletesFlushed.docIDs.size() + " deleted docIDs and " +
              deletesFlushed.queries.size() + " deleted queries on " +
              + infos.size() + " segments.");

    final int infosEnd = infos.size();

    int docStart = 0;
    boolean any = false;
    for (int i = 0; i < infosEnd; i++) {

      // Make sure we never attempt to apply deletes to
      // segment in external dir
      assert infos.info(i).dir == directory;

      SegmentReader reader = writer.readerPool.get(infos.info(i), false);
      try {
        any |= applyDeletes(reader, docStart);
        docStart += reader.maxDoc();
      } finally {
        writer.readerPool.release(reader);
      }
    }

    deletesFlushed.clear();

    return any;
  }

  // used only by assert
  private Term lastDeleteTerm;

  // used only by assert
  private boolean checkDeleteTerm(Term term) {
    if (term != null) {
      assert lastDeleteTerm == null || term.compareTo(lastDeleteTerm) > 0: "lastTerm=" + lastDeleteTerm + " vs term=" + term;
    }
    lastDeleteTerm = term;
    return true;
  }

  // Apply buffered delete terms, queries and docIDs to the
  // provided reader
  private final synchronized boolean applyDeletes(IndexReader reader, int docIDStart)
    throws CorruptIndexException, IOException {

    final int docEnd = docIDStart + reader.maxDoc();
    boolean any = false;

    assert checkDeleteTerm(null);

    // Delete by term
    //按照词删除，删除所有包含此词的文档。
    TermDocs docs = reader.termDocs();
    try {
      for (Entry<Term, BufferedDeletes.Num> entry: deletesFlushed.terms.entrySet()) {
        Term term = entry.getKey();
        // LUCENE-2086: we should be iterating a TreeMap,
        // here, so terms better be in order:
        assert checkDeleteTerm(term);
        docs.seek(term);
        int limit = entry.getValue().getNum();
        while (docs.next()) {
          int docID = docs.doc();
          if (docIDStart+docID >= limit)
            break;
          reader.deleteDocument(docID);
          any = true;
        }
      }
    } finally {
      docs.close();
    }

    // Delete by docID
    //按照文档号删除。
    for (Integer docIdInt : deletesFlushed.docIDs) {
      int docID = docIdInt.intValue();
      if (docID >= docIDStart && docID < docEnd) {
        reader.deleteDocument(docID-docIDStart);
        any = true;
      }
    }

    // Delete by query
    //按照查询对象删除，删除所有满足此查询的文档。
    IndexSearcher searcher = new IndexSearcher(reader);
    for (Entry<Query, Integer> entry : deletesFlushed.queries.entrySet()) {
      Query query = entry.getKey();
      int limit = entry.getValue().intValue();
      Weight weight = query.weight(searcher);
      Scorer scorer = weight.scorer(reader, true, false);
      if (scorer != null) {
        while(true)  {
          int doc = scorer.nextDoc();
          if (((long) docIDStart) + doc >= limit)
            break;
          reader.deleteDocument(doc);
          any = true;
        }
      }
    }
    searcher.close();
    return any;
  }

  // Buffer a term in bufferedDeleteTerms, which records the
  // current number of documents buffered in ram so that the
  // delete term will be applied to those documents as well
  // as the disk segments.
  synchronized private void addDeleteTerm(Term term, int docCount) {
    BufferedDeletes.Num num = deletesInRAM.terms.get(term);
    final int docIDUpto = flushedDocCount + docCount;
    if (num == null)
      deletesInRAM.terms.put(term, new BufferedDeletes.Num(docIDUpto));
    else
      num.setNum(docIDUpto);
    deletesInRAM.numTerms++;

    deletesInRAM.addBytesUsed(BYTES_PER_DEL_TERM + term.text.length()*CHAR_NUM_BYTE);
  }

  // Buffer a specific docID for deletion.  Currently only
  // used when we hit a exception when adding a document
  synchronized private void addDeleteDocID(int docID) {
    deletesInRAM.docIDs.add(Integer.valueOf(flushedDocCount+docID));
    deletesInRAM.addBytesUsed(BYTES_PER_DEL_DOCID);
  }

  synchronized private void addDeleteQuery(Query query, int docID) {
    deletesInRAM.queries.put(query, Integer.valueOf(flushedDocCount + docID));
    deletesInRAM.addBytesUsed(BYTES_PER_DEL_QUERY);
  }

  synchronized boolean doBalanceRAM() {
    return ramBufferSize != IndexWriter.DISABLE_AUTO_FLUSH && !bufferIsFull && (numBytesUsed+deletesInRAM.bytesUsed+deletesFlushed.bytesUsed >= ramBufferSize || numBytesAlloc >= freeTrigger);
  }

  /**
   * 虽然对文档的处理过程可以并行，但是将文档写入索引文件却必须串行进行，
   * 串行写入的代码在DocumentsWriter.finishDocument中 
   * Does the synchronized work to finish/flush the
   *  inverted document. */
  private void finishDocument(DocumentsWriterThreadState perThread, DocWriter docWriter) throws IOException {

    if (doBalanceRAM())
      // Must call this w/o holding synchronized(this) else
      // we'll hit deadlock:
      balanceRAM();

    synchronized(this) {

      assert docWriter == null || docWriter.docID == perThread.docState.docID;

      if (aborting) {

        // We are currently aborting, and another thread is
        // waiting for me to become idle.  We just forcefully
        // idle this threadState; it will be fully reset by
        // abort()
        if (docWriter != null)
          try {
            docWriter.abort();
          } catch (Throwable t) {
          }

        perThread.isIdle = true;
        notifyAll();
        return;
      }

      final boolean doPause;

      if (docWriter != null)
        doPause = waitQueue.add(docWriter);
      else {
        skipDocWriter.docID = perThread.docState.docID;
        doPause = waitQueue.add(skipDocWriter);
      }

      if (doPause)
        waitForWaitQueue();

      if (bufferIsFull && !flushPending) {
        flushPending = true;
        perThread.doFlushAfter = true;
      }

      perThread.isIdle = true;
      notifyAll();
    }
  }

  synchronized void waitForWaitQueue() {
    do {
      try {
        wait();
      } catch (InterruptedException ie) {
        throw new ThreadInterruptedException(ie);
      }
    } while (!waitQueue.doResume());
  }

  private static class SkipDocWriter extends DocWriter {
    @Override
    void finish() {
    }
    @Override
    void abort() {
    }
    @Override
    long sizeInBytes() {
      return 0;
    }
  }
  final SkipDocWriter skipDocWriter = new SkipDocWriter();

  long getRAMUsed() {
    return numBytesUsed + deletesInRAM.bytesUsed + deletesFlushed.bytesUsed;
  }

  /**分配的内存数量*/long numBytesAlloc;
  /**使用的内存数量*/long numBytesUsed;

  NumberFormat nf = NumberFormat.getInstance();

  // Coarse estimates used to measure RAM usage of buffered deletes
  final static int OBJECT_HEADER_BYTES = 8;
  final static int POINTER_NUM_BYTE = Constants.JRE_IS_64BIT ? 8 : 4;
  final static int INT_NUM_BYTE = 4;
  final static int CHAR_NUM_BYTE = 2;

  /* Rough logic: HashMap has an array[Entry] w/ varying
     load factor (say 2 * POINTER).  Entry is object w/ Term
     key, BufferedDeletes.Num val, int hash, Entry next
     (OBJ_HEADER + 3*POINTER + INT).  Term is object w/
     String field and String text (OBJ_HEADER + 2*POINTER).
     We don't count Term's field since it's interned.
     Term's text is String (OBJ_HEADER + 4*INT + POINTER +
     OBJ_HEADER + string.length*CHAR).  BufferedDeletes.num is
     OBJ_HEADER + INT. */
 
  final static int BYTES_PER_DEL_TERM = 8*POINTER_NUM_BYTE + 5*OBJECT_HEADER_BYTES + 6*INT_NUM_BYTE;

  /* Rough logic: del docIDs are List<Integer>.  Say list
     allocates ~2X size (2*POINTER).  Integer is OBJ_HEADER
     + int */
  final static int BYTES_PER_DEL_DOCID = 2*POINTER_NUM_BYTE + OBJECT_HEADER_BYTES + INT_NUM_BYTE;

  /* Rough logic: HashMap has an array[Entry] w/ varying
     load factor (say 2 * POINTER).  Entry is object w/
     Query key, Integer val, int hash, Entry next
     (OBJ_HEADER + 3*POINTER + INT).  Query we often
     undercount (say 24 bytes).  Integer is OBJ_HEADER + INT. */
  final static int BYTES_PER_DEL_QUERY = 5*POINTER_NUM_BYTE + 2*OBJECT_HEADER_BYTES + 2*INT_NUM_BYTE + 24;

  /* Initial chunks size of the shared byte[] blocks used to
     store postings data */
  final static int BYTE_BLOCK_SHIFT = 15;
  final static int BYTE_BLOCK_SIZE = 1 << BYTE_BLOCK_SHIFT;
  final static int BYTE_BLOCK_MASK = BYTE_BLOCK_SIZE - 1;
  final static int BYTE_BLOCK_NOT_MASK = ~BYTE_BLOCK_MASK;

  private class ByteBlockAllocator extends ByteBlockPool.Allocator {
    final int blockSize;

    ByteBlockAllocator(int blockSize) {
      this.blockSize = blockSize;
    }
    /**将用于缓存文档号(doc id)及词频(freq)，位置(prox)信息的空闲块。*/
    ArrayList<byte[]> freeByteBlocks = new ArrayList<byte[]>();
    
    /* Allocate another byte[] from the shared pool */
    @Override
    byte[] getByteBlock(boolean trackAllocations) {
      synchronized(DocumentsWriter.this) {
        final int size = freeByteBlocks.size();
        final byte[] b;
        if (0 == size) {
          // Always record a block allocated, even if
          // trackAllocations is false.  This is necessary
          // because this block will be shared between
          // things that don't track allocations (term
          // vectors) and things that do (freq/prox
          // postings).
          numBytesAlloc += blockSize;
          b = new byte[blockSize];
        } else
          b = freeByteBlocks.remove(size-1);
        if (trackAllocations)
          numBytesUsed += blockSize;
        assert numBytesUsed <= numBytesAlloc;
        return b;
      }
    }

    /* Return byte[]'s to the pool */

    @Override
    void recycleByteBlocks(byte[][] blocks, int start, int end) {
      synchronized(DocumentsWriter.this) {
        for(int i=start;i<end;i++) {
          freeByteBlocks.add(blocks[i]);
          blocks[i] = null;
        }
      }
    }

    @Override
    void recycleByteBlocks(List<byte[]> blocks) {
      synchronized(DocumentsWriter.this) {
        final int size = blocks.size();
        for(int i=0;i<size;i++)
          freeByteBlocks.add(blocks.get(i));
      }
    }
  }

  /* Initial chunks size of the shared int[] blocks used to
     store postings data */
  final static int INT_BLOCK_SHIFT = 13;
  final static int INT_BLOCK_SIZE = 1 << INT_BLOCK_SHIFT;
  final static int INT_BLOCK_MASK = INT_BLOCK_SIZE - 1;
  /**将存储某词的词频(freq)和位置(prox)分别在byteBlocks中的偏移量*/
  private ArrayList<int[]> freeIntBlocks = new ArrayList<int[]>();

  /* Allocate another int[] from the shared pool */
  synchronized int[] getIntBlock(boolean trackAllocations) {
    final int size = freeIntBlocks.size();
    final int[] b;
    if (0 == size) {
      // Always record a block allocated, even if
      // trackAllocations is false.  This is necessary
      // because this block will be shared between
      // things that don't track allocations (term
      // vectors) and things that do (freq/prox
      // postings).
      numBytesAlloc += INT_BLOCK_SIZE*INT_NUM_BYTE;
      b = new int[INT_BLOCK_SIZE];
    } else
      b = freeIntBlocks.remove(size-1);
    if (trackAllocations)
      numBytesUsed += INT_BLOCK_SIZE*INT_NUM_BYTE;
    assert numBytesUsed <= numBytesAlloc;
    return b;
  }

  synchronized void bytesAllocated(long numBytes) {
    numBytesAlloc += numBytes;
  }

  synchronized void bytesUsed(long numBytes) {
    numBytesUsed += numBytes;
    assert numBytesUsed <= numBytesAlloc;
  }

  /* Return int[]s to the pool */
  synchronized void recycleIntBlocks(int[][] blocks, int start, int end) {
    for(int i=start;i<end;i++) {
      freeIntBlocks.add(blocks[i]);
      blocks[i] = null;
    }
  }

  ByteBlockAllocator byteBlockAllocator = new ByteBlockAllocator(BYTE_BLOCK_SIZE);

  final static int PER_DOC_BLOCK_SIZE = 1024;

  final ByteBlockAllocator perDocAllocator = new ByteBlockAllocator(PER_DOC_BLOCK_SIZE);


  /**决定charPool每个buffer的大小Initial chunk size of the shared char[] blocks used to
     store term text */
  final static int CHAR_BLOCK_SHIFT = 14;
  /**charPool每个buffer的大小*/
  final static int CHAR_BLOCK_SIZE = 1 << CHAR_BLOCK_SHIFT;
  final static int CHAR_BLOCK_MASK = CHAR_BLOCK_SIZE - 1;

  final static int MAX_TERM_LENGTH = CHAR_BLOCK_SIZE-1;
  /**将用于缓存词(Term)信息的空闲块*/
  private ArrayList<char[]> freeCharBlocks = new ArrayList<char[]>();

  /* Allocate another char[] from the shared pool */
  synchronized char[] getCharBlock() {
    final int size = freeCharBlocks.size();
    final char[] c;
    if (0 == size) {
      numBytesAlloc += CHAR_BLOCK_SIZE * CHAR_NUM_BYTE;
      c = new char[CHAR_BLOCK_SIZE];
    } else
      c = freeCharBlocks.remove(size-1);
    // We always track allocations of char blocks, for now,
    // because nothing that skips allocation tracking
    // (currently only term vectors) uses its own char
    // blocks.
    numBytesUsed += CHAR_BLOCK_SIZE * CHAR_NUM_BYTE;
    assert numBytesUsed <= numBytesAlloc;
    return c;
  }

  /* Return char[]s to the pool */
  synchronized void recycleCharBlocks(char[][] blocks, int numBlocks) {
    for(int i=0;i<numBlocks;i++) {
      freeCharBlocks.add(blocks[i]);
      blocks[i] = null;
    }
  }

  String toMB(long v) {
    return nf.format(v/1024./1024.);
  }

  /* We have four pools of RAM: Postings, byte blocks
   * (holds freq/prox posting data), char blocks (holds
   * characters in the term) and per-doc buffers (stored fields/term vectors).  
   * Different docs require varying amount of storage from 
   * these four classes.
   * 
   * For example, docs with many unique single-occurrence
   * short terms will use up the Postings RAM and hardly any
   * of the other two.  Whereas docs with very large terms
   * will use alot of char blocks RAM and relatively less of
   * the other two.  This method just frees allocations from
   * the pools once we are over-budget, which balances the
   * pools to match the current docs. */
  void balanceRAM() {

    // We flush when we've used our target usage
    final long flushTrigger = ramBufferSize;

    final long deletesRAMUsed = deletesInRAM.bytesUsed+deletesFlushed.bytesUsed;

    if (numBytesAlloc+deletesRAMUsed > freeTrigger) {//当分配的内存加删除文档所占用的内存大于105%的时候，开始释放内存

      if (infoStream != null)
        message("  RAM: now balance allocations: usedMB=" + toMB(numBytesUsed) +
                " vs trigger=" + toMB(flushTrigger) +
                " allocMB=" + toMB(numBytesAlloc) +
                " deletesMB=" + toMB(deletesRAMUsed) +
                " vs trigger=" + toMB(freeTrigger) +
                " byteBlockFree=" + toMB(byteBlockAllocator.freeByteBlocks.size()*BYTE_BLOCK_SIZE) +
                " perDocFree=" + toMB(perDocAllocator.freeByteBlocks.size()*PER_DOC_BLOCK_SIZE) +
                " charBlockFree=" + toMB(freeCharBlocks.size()*CHAR_BLOCK_SIZE*CHAR_NUM_BYTE));

      final long startBytesAlloc = numBytesAlloc + deletesRAMUsed;

      int iter = 0;

      // We free equally from each pool in 32 KB
      // chunks until we are below our threshold
      // (freeLevel)

      boolean any = true;

      while(numBytesAlloc+deletesRAMUsed > freeLevel) {//一直进行释放，直到95%
      
        synchronized(this) {
          if (0 == perDocAllocator.freeByteBlocks.size() 
              && 0 == byteBlockAllocator.freeByteBlocks.size() 
              && 0 == freeCharBlocks.size() 
              && 0 == freeIntBlocks.size() 
              && !any) {
            // Nothing else to free -- must flush now.
            bufferIsFull = numBytesUsed+deletesRAMUsed > flushTrigger;
            if (infoStream != null) {
              if (bufferIsFull)
                message("    nothing to free; now set bufferIsFull");
              else
                message("    nothing to free");
            }
            assert numBytesUsed <= numBytesAlloc;
            break;
          }

          if ((0 == iter % 5) && byteBlockAllocator.freeByteBlocks.size() > 0) {//释放free blocks
            byteBlockAllocator.freeByteBlocks.remove(byteBlockAllocator.freeByteBlocks.size()-1);
            numBytesAlloc -= BYTE_BLOCK_SIZE;
          }

          if ((1 == iter % 5) && freeCharBlocks.size() > 0) {
            freeCharBlocks.remove(freeCharBlocks.size()-1);
            numBytesAlloc -= CHAR_BLOCK_SIZE * CHAR_NUM_BYTE;
          }

          if ((2 == iter % 5) && freeIntBlocks.size() > 0) {
            freeIntBlocks.remove(freeIntBlocks.size()-1);
            numBytesAlloc -= INT_BLOCK_SIZE * INT_NUM_BYTE;
          }

          if ((3 == iter % 5) && perDocAllocator.freeByteBlocks.size() > 0) {
            // Remove upwards of 32 blocks (each block is 1K)
            for (int i = 0; i < 32; ++i) {
              perDocAllocator.freeByteBlocks.remove(perDocAllocator.freeByteBlocks.size() - 1);
              numBytesAlloc -= PER_DOC_BLOCK_SIZE;
              if (perDocAllocator.freeByteBlocks.size() == 0) {
                break;
              }
            }
          }
        }

        if ((4 == iter % 5) && any)
          // Ask consumer to free any recycled state
          any = consumer.freeRAM();

        iter++;
      }

      if (infoStream != null)
        message("    after free: freedMB=" + nf.format((startBytesAlloc-numBytesAlloc-deletesRAMUsed)/1024./1024.) + " usedMB=" + nf.format((numBytesUsed+deletesRAMUsed)/1024./1024.) + " allocMB=" + nf.format(numBytesAlloc/1024./1024.));
      
    } else {
      // If we have not crossed the 100% mark, but have
      // crossed the 95% mark of RAM we are actually
      // using, go ahead and flush.  This prevents
      // over-allocating and then freeing, with every
      // flush.
      synchronized(this) {
    	
        if (numBytesUsed+deletesRAMUsed > flushTrigger) {
          if (infoStream != null)
            message("  RAM: now flush @ usedMB=" + nf.format(numBytesUsed/1024./1024.) +
                    " allocMB=" + nf.format(numBytesAlloc/1024./1024.) +
                    " deletesMB=" + nf.format(deletesRAMUsed/1024./1024.) +
                    " triggerMB=" + nf.format(flushTrigger/1024./1024.));
        //当使用的内存加删除文档占有的内存大于用户指定的内存时，可以写入磁盘
          bufferIsFull = true;
        }
      }
    }
  }

  final WaitQueue waitQueue = new WaitQueue();

  /**
   * 虽然给文档付ID没有问题了。但是由Lucene索引文件格式我们知道，
   * 文档是要按照ID的顺序从小到大写到索引文件中，然而不同的文档处理速度不同，
   * 当一个先来的线程1处理1篇需要很长时间的大文档时，另一个后来的线程2可能已了很多小的文档了，
   * 但是这些后来小文档的ID号都大于第一个线程所处理的大文档，因而不能马上写到索引文件中去
   * 只是放到waitQueue中，仅仅当大文档处理完了之后才写入索引文件。
   */
private class WaitQueue {
    DocWriter[] waiting;
    /**下一个可以写入文件的ID*/
    int nextWriteDocID;
    /**等待文档数组waiting中下一个待处理文档的位置*/
    int nextWriteLoc;
    int numWaiting;
    /**当前等待的文档所占用的内存大小:在add()中进行加减（waitingBytes += doc.sizeInBytes()）*/
    long waitingBytes;

    public WaitQueue() {
      waiting = new DocWriter[10];
    }

    synchronized void reset() {
      // NOTE: nextWriteLoc doesn't need to be reset
      assert numWaiting == 0;
      assert waitingBytes == 0;
      nextWriteDocID = 0;
    }

    synchronized boolean doResume() {
      return waitingBytes <= waitQueueResumeBytes;
    }

    /**
     * 当前等待的文档所占用的内存大小>为用户指定的内存使用量的10%，doPause返回true，
     * 于是后来的线程2会进入wait不再处理另外的文档，而是等待线程一处理大文档结束。
     * 当线程一处理大文档结束的时候，调用notifyAll唤醒等待他的线程。
     * (当大文档很大很大，处理的很慢很慢的时候，
     * 后来的线程2可能已经处理了很多的小文档了，文档都是在waitQueue中，
     * 则占有了越来越多的内存，长此以往，有内存不够的危险。
     * 因而在finishDocuments里面，在WaitQueue.add最后调用了doPause()函数)
     */
    synchronized boolean doPause() {
      return waitingBytes > waitQueuePauseBytes;
    }

    synchronized void abort() {
      int count = 0;
      for(int i=0;i<waiting.length;i++) {
        final DocWriter doc = waiting[i];
        if (doc != null) {
          doc.abort();
          waiting[i] = null;
          count++;
        }
      }
      waitingBytes = 0;
      assert count == numWaiting;
      numWaiting = 0;
    }

    private void writeDocument(DocWriter doc) throws IOException {
      assert doc == skipDocWriter || nextWriteDocID == doc.docID;
      boolean success = false;
      try {
        doc.finish();
        nextWriteDocID++;
        numDocsInStore++;
        nextWriteLoc++;
        assert nextWriteLoc <= waiting.length;
        if (nextWriteLoc == waiting.length)
          nextWriteLoc = 0;
        success = true;
      } finally {
        if (!success)
          setAborting();
      }
    }

    /**
     * waitQueue中有一个变量nextWriteDocID表示下一个可以写入文件的ID，
     * 当付给大文档ID=4时，则nextWriteDocID为4，虽然后来的小文档5，6，7，8等都已处理结束，
     * 但只是把5, 6, 7, 8放入waiting队列，并且记录当前等待的文档所占用的内存大小waitingBytes。
     * 当大文档4处理完毕(写入文档4)后，把原来等待的文档5, 6, 7, 8也一起写入。
     */
    synchronized public boolean add(DocWriter doc) throws IOException {

      assert doc.docID >= nextWriteDocID;

      if (doc.docID == nextWriteDocID) {
        writeDocument(doc);
        while(true) {
          doc = waiting[nextWriteLoc];
          if (doc != null) {
            numWaiting--;
            waiting[nextWriteLoc] = null;
            waitingBytes -= doc.sizeInBytes();
            writeDocument(doc);
          } else
            break;
        }
      } else {

        // I finished before documents that were added
        // before me.  This can easily happen when I am a
        // small doc and the docs before me were large, or,
        // just due to luck in the thread scheduling.  Just
        // add myself to the queue and when that large doc
        // finishes, it will flush me:
        int gap = doc.docID - nextWriteDocID;
        if (gap >= waiting.length) {
          // Grow queue
          DocWriter[] newArray = new DocWriter[ArrayUtil.getNextSize(gap)];
          assert nextWriteLoc >= 0;
          System.arraycopy(waiting, nextWriteLoc, newArray, 0, waiting.length-nextWriteLoc);
          System.arraycopy(waiting, 0, newArray, waiting.length-nextWriteLoc, nextWriteLoc);
          nextWriteLoc = 0;
          waiting = newArray;
          gap = doc.docID - nextWriteDocID;
        }

        int loc = nextWriteLoc + gap;
        if (loc >= waiting.length)
          loc -= waiting.length;

        // We should only wrap one time
        assert loc < waiting.length;

        // Nobody should be in my spot!
        assert waiting[loc] == null;
        waiting[loc] = doc;
        numWaiting++;
        waitingBytes += doc.sizeInBytes();
      }
      
      return doPause();
    }
  }
}
