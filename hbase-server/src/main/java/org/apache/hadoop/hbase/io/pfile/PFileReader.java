package org.apache.hadoop.hbase.io.pfile;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;

import org.apache.hadoop.hbase.io.hfile.HFileReaderV2;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileBlock;
import org.apache.hadoop.hbase.io.hfile.HFile.Writer;
import org.apache.hadoop.hbase.io.hfile.FixedFileTrailer;
import org.apache.hadoop.hbase.io.FSDataInputStreamWrapper;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.util.Bytes;

/*
 * Any component above this layer sees only HFileBlock. Skiplists and 
 * multi-block-reading functionality are made transparent to higher level
 * components.
 *
 * It seems that I do not need to implement PFileBlockReader, as all seek
 * are doen in HFileReaderV2.blockSeek(Cell Key, boolean seekBefore).
 *
 * So, rewrite that should be suffice for get. 
 *
 * TODO: study how the reader works for scan, do I need to rewrite the scan
 * portion?
 */

public class PFileReader extends HFileReaderV2 {
  private static final Log LOG = LogFactory.getLog(PFileReader.class);


  public PFileReader(final Path path, final FixedFileTrailer trailer,
      final FSDataInputStreamWrapper fsdis, final long size,
      final CacheConfig cacheConf, final HFileSystem hfs,
      final Configuration conf) throws IOException {
    super(path, trailer, fsdis, size, cacheConf, hfs, conf);
  }

  @Override
  public int getMajorVersion() {
    return 4;
  }

  @Override
  public HFileScanner getScanner(boolean cacheBlocks, final boolean pread,
                        final boolean isCompaction) {
    if (dataBlockEncoder.useEncodedScanner()) {
      throw new IllegalStateException("Shen Li: PFileScanner does "
          + " not support encoded scanner for now");
    }
    return new PFileScanner(this, cacheBlocks, pread, isCompaction);
  }

  public static class PFileScanner extends HFileReaderV2.ScannerV2 {
    private static final Log LOG = LogFactory.getLog(PFileScanner.class);

    public static final int KEY_LEN_SIZE = Bytes.SIZEOF_INT;
    public static final int MAX_INT = 2147483647;

    //TODO: take care of the following two variables
    private int currSkipListEntryLen = 0;
    private int currPNum = 0;
    private long tmpMemstoreTS = 0;
    private int tmpMemstoreTSLen = 0;

    protected PFileReader reader = null;

    public PFileScanner(PFileReader r, boolean cacheBlocks,
        final boolean pread, final boolean isCompaction) {
      super(r, cacheBlocks, pread, isCompaction);
      this.reader = r;
    }

    private int getKvOffset(int pkvOffset, int pNum) {
      return pkvOffset + PKeyValue.POINTER_NUM_SIZE +
        (pNum + 1) * PKeyValue.POINTER_SIZE;
    }

    @Override
    public Cell getKeyValue() {
      if (!isSeeked())
        return null;
      
      KeyValue ret = new KeyValue(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen, getCellBufSize());
      return ret;
    }

    @Override
    protected int getCellBufSize() {
      return currSkipListEntryLen + KEY_VALUE_LEN_SIZE
             + currKeyLen + currValueLen + currMemstoreTSLen;
    }

    @Override
    public ByteBuffer getKey() {
      assertSeeked();
      return ByteBuffer.wrap(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen + KEY_VALUE_LEN_SIZE, 
          currKeyLen).slice();
    }

    @Override
    public ByteBuffer getValue() {
      assertSeeked();
      return ByteBuffer.wrap(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen + KEY_VALUE_LEN_SIZE
          + currKeyLen, currValueLen).slice();
    }

    @Override
    protected void setNonSeekedState() {
      block = null;
      blockBuffer = null;
      currPNum = 0;
      currSkipListEntryLen = 0;
      currKeyLen = 0;
      currValueLen = 0;
      currMemstoreTS = 0;
      currMemstoreTSLen = 0;
      tmpMemstoreTS = 0;
      tmpMemstoreTSLen = 0;
    }

    @Override
      protected void readKeyValueLen() {
        blockBuffer.mark();
        LOG.info("Shen Li: blockBuffer position = " + blockBuffer.position()
                 + ", limit = " + blockBuffer.limit()
                 + ", arrayOffset = " + blockBuffer.arrayOffset()
                 + ", lastSkipListEntryLen = " + currSkipListEntryLen
                 + ", lastKeyLen = " + currKeyLen
                 + ", lastValueLen = " + currValueLen
                 + ", remaining = " + blockBuffer.remaining());
        currPNum = blockBuffer.get();
        currSkipListEntryLen =
          PKeyValue.POINTER_NUM_SIZE + 
          (currPNum + 1) * PKeyValue.POINTER_SIZE;
        blockBuffer.position(
            blockBuffer.position() + currSkipListEntryLen
            - PKeyValue.POINTER_NUM_SIZE);
        currKeyLen = blockBuffer.getInt();
        currValueLen = blockBuffer.getInt();

        readMvccVersion();

        if (currPNum < 0 || currKeyLen < 0 || currValueLen < 0
            || currKeyLen > blockBuffer.limit() 
            || currValueLen > blockBuffer.limit()
            || currSkipListEntryLen > blockBuffer.limit()) {
          throw new IllegalStateException(
              "Shen Li: Invalid currKeyLen " + currKeyLen
              + " or currValueLen " + currValueLen 
              + " or currPNum " + currPNum
              + " or currSkipListEntryLen " + currSkipListEntryLen
              + ". Block offset: " + block.getOffset()
              + ", block length: " + blockBuffer.limit()
              + ", position: " + blockBuffer.position()
              + " (without header).");
        }
        blockBuffer.reset();
      }

    @Override
      protected int getNextCellStartPosition() {
        return blockBuffer.position() + currSkipListEntryLen
          + KEY_VALUE_LEN_SIZE + currKeyLen + currValueLen + currMemstoreTSLen;
      }

    @Override
      protected ByteBuffer getFirstKeyInBlock(HFileBlock curBlock) {
        ByteBuffer buffer = curBlock.getBufferWithoutHeader();
        buffer.rewind();
        byte pNum = buffer.get();
        buffer.position(buffer.position() + 
            (pNum + 1) * PKeyValue.POINTER_SIZE);
        int klen = buffer.getInt();
        buffer.getInt();
        ByteBuffer keyBuff = buffer.slice();
        keyBuff.limit(klen);
        keyBuff.rewind();
        return keyBuff;
      }

    @Override
      public String getKeyString() {
        return Bytes.toStringBinary(
            blockBuffer.array(),
            blockBuffer.arrayOffset() + blockBuffer.position() +
            currSkipListEntryLen + KEY_VALUE_LEN_SIZE, currKeyLen);
      }

    @Override
    public String getValueString() {
      return Bytes.toString(
          blockBuffer.array(),
          blockBuffer.arrayOffset() + blockBuffer.position() +
          currSkipListEntryLen + KEY_VALUE_LEN_SIZE + currKeyLen,
          currValueLen);
    }

    // TODO: declare and set currSkipListEntryLen
    @Override
    public int compareKey(KVComparator comparator, Cell key) {
      return comparator.compareOnlyKeyPortion(
          key,
          new KeyValue.KeyOnlyKeyValue(
            blockBuffer.array(), 
            blockBuffer.arrayOffset() + blockBuffer.position() +
            currSkipListEntryLen + KEY_VALUE_LEN_SIZE, 
            currKeyLen));
    }


    @Override
    public int compareKey(KVComparator comparator, byte[] key, 
                          int offset, int length) {
      return comparator.compareFlatKey(
          key, offset, length,
          blockBuffer.array(), 
          blockBuffer.arrayOffset() + blockBuffer.position()
          + currSkipListEntryLen + KEY_VALUE_LEN_SIZE,
          currKeyLen);
    }

    private void readMemstoreTS(int offset) {
      if (this.reader.shouldIncludeMemstoreTS()) {
        if (this.reader.decodeMemstoreTS) {
          try {
            tmpMemstoreTS = Bytes.readVLong(
                blockBuffer.array(), offset);
            tmpMemstoreTSLen = 
              WritableUtils.getVIntSize(tmpMemstoreTS);
          } catch (Exception e) {
            throw new RuntimeException(
                "Error reading memstore timestamp", e);
          }
        } else {
          tmpMemstoreTS = 0;
          tmpMemstoreTSLen = 1;
        }
      }
    }

    private void setCurrStates(byte pNum, int skipListEntryLen,
        int klen, int vlen, long memstoreTS, int memstoreTSLen) {
      currPNum = pNum;
      currSkipListEntryLen = skipListEntryLen;
      currKeyLen = klen;
      currValueLen = vlen;
      currMemstoreTS = memstoreTS;
      currMemstoreTSLen = memstoreTSLen;
    }

    /*
     * Within a loaded block, seek looking for the last key that is 
     * SMALLER!!! than the given key.
     *
     */
    @Override
      protected int blockSeek(Cell key, boolean seekBefore) {
        int klen, vlen, skipKLen;
        int kvOffset, skipKvOffset;
        byte pNum, tmpPNum, skipPNum;
        int ptr, skipPrevPtr;
        int lastKeyValueSize = -1;
        int curOffset, skipOffset, ptrOffset, skipPrevOffset;

        LOG.info("Shen Li: call trace");
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
              LOG.info("Shen Li: " + ste);
        }


        // for testing
        KeyValue tmpKey = new KeyValue(key);
        LOG.info("Shen Li: in blockSeek, key = " + tmpKey.getKeyString() 
                 + ", seekBefore = " + seekBefore);

        tmpMemstoreTS = 0;
        tmpMemstoreTSLen = 0;

        KeyValue.KeyOnlyKeyValue keyOnlyKv = new KeyValue.KeyOnlyKeyValue();

        // If the target key is smaller than the first pkv in the
        // current block, return -2 (HConstants.INDEX_KEY_MAGIC)

        curOffset = blockBuffer.position() + blockBuffer.arrayOffset();
        pNum = blockBuffer.get(curOffset);
        kvOffset = this.getKvOffset(curOffset, pNum);
        // key length of that kv
        klen = blockBuffer.getInt(kvOffset);
        keyOnlyKv.setKey(
            blockBuffer.array(), kvOffset + KEY_VALUE_LEN_SIZE, klen);

        int comp = reader.getComparator().compareOnlyKeyPortion(key, 
            keyOnlyKv);

        if (comp < 0) {
          // target key smaller than the first key
          LOG.info("Shen Li: blockSeek called on a larger block");
          readKeyValueLen();
          return HConstants.INDEX_KEY_MAGIC;
        }

        // the target key is within the range of the pointers of the 
        // current entry
        boolean found;

        // helps search in the skiplist
        int maxOffset = this.MAX_INT;

        // initialize variables in case the block contains a single
        // pkv, whose key is smaller than the target key. 
        skipOffset = curOffset;
        ptr = 0;
        skipKLen = klen;
        skipKvOffset = 0;

        /*
         * Invariant: the current key under while has to be smaller than the 
         * target key. The loop over the current skiplist entry will return if
         * found an exact match, otherwise set the current key to the largest key
         * that is smaller than the target key in its skiplist pointers.
         */
        while(true) {

          // offset to the largest pointer
          ptrOffset = curOffset + PKeyValue.POINTER_NUM_SIZE +
            (pNum - 1) * PKeyValue.POINTER_SIZE;

          found = false;
          // check pointers of the current entry
          while (ptrOffset > curOffset) {
            ptr = blockBuffer.getInt(ptrOffset);
            // offset to the beginning of the pkv indicated by the pointer
            skipOffset = curOffset + ptr;
            if (skipOffset >= maxOffset) {
              ptrOffset -= PKeyValue.POINTER_SIZE;
              continue;
            }
            // ptr num of that pkv
            skipPNum = blockBuffer.get(skipOffset);
            // offset to the beginning of kv of that pkv
            skipKvOffset = this.getKvOffset(skipOffset, skipPNum);
            // key length of that kv
            skipKLen = blockBuffer.getInt(skipKvOffset);
            keyOnlyKv.setKey(blockBuffer.array(), 
                skipKvOffset + KEY_VALUE_LEN_SIZE, skipKLen);

            comp = reader.getComparator().compareOnlyKeyPortion(key, 
                keyOnlyKv);
            //and writers.
            if (0 == comp) {
              //Found exact match
              return handleExactMatch(key, blockBuffer.position() + ptr, 
                  skipKvOffset, seekBefore);
            } else if (comp < 0) {
              // larger than the target key, try the next smaller pointer
              ptrOffset -= PKeyValue.POINTER_SIZE;    
              maxOffset = skipOffset;
            } else {
              // found the largest key that is smaller than the target key 
              // known by the current pkv, break
              found = true;
              break;
            }
          }

          //TODO: handle the last block

          if (!found) {
            // all pointers point to larger keys (or no pointer), 
            // and the curren tkey is smaller than the target key.

            //read vlen of the current key
            vlen = blockBuffer.get(kvOffset + KEY_LEN_SIZE);

            //read memstoreTS
            readMemstoreTS(kvOffset + KEY_VALUE_LEN_SIZE
                           + klen + vlen);
            // check next pkv
            ptr = PKeyValue.POINTER_NUM_SIZE +
              (pNum + 1) * PKeyValue.POINTER_SIZE + 
              KEY_VALUE_LEN_SIZE + klen + vlen + tmpMemstoreTSLen;
            LOG.info("Shen Li: position = " + blockBuffer.position() 
                     + ", pointer = " + ptr 
                     + ", limit = " + blockBuffer.limit());
            if (blockBuffer.position() + ptr >= blockBuffer.limit()) {
              // the current pkv is the last pkv in this block
              LOG.info("Shen Li: got last pkv");
              setCurrStates(pNum, 
                  PKeyValue.POINTER_NUM_SIZE + PKeyValue.POINTER_SIZE,
                  klen, vlen, tmpMemstoreTS, tmpMemstoreTSLen);
              return 1;
            }
            skipOffset = curOffset + ptr;
            skipPNum = blockBuffer.get(skipOffset);
            skipKvOffset = getKvOffset(skipOffset, skipPNum);
            skipKLen = blockBuffer.getInt(skipKvOffset);
            keyOnlyKv.setKey(blockBuffer.array(), 
                skipKvOffset + KEY_VALUE_LEN_SIZE, skipKLen);
            comp = reader.getComparator().compareOnlyKeyPortion(key, 
                keyOnlyKv);
            if (0 == comp) {
              // next pkv matches target key
              return handleExactMatch(key, blockBuffer.position() + ptr, 
                  skipKvOffset, seekBefore);
            } else if (comp < 0) {
              // target key is larger than current but smaller than the next.
              // therefore, the current locaiton of blockBuffer is correct
              setCurrStates(pNum, 
                  PKeyValue.POINTER_NUM_SIZE + PKeyValue.POINTER_SIZE,
                  klen, vlen, tmpMemstoreTS, tmpMemstoreTSLen);
              return 1;
            } else {
              // target key is larger than next but smaller than the next of next
              // blockBuffer should be placed at the beginning of the next key
              blockBuffer.position(blockBuffer.position() + ptr);
              readKeyValueLen();
              return 1;
            }
          } else {
            // found a valid range, so update the current pkv info
            blockBuffer.position(blockBuffer.position() + ptr);
            curOffset = skipOffset;
            pNum = blockBuffer.get(curOffset);
            klen = skipKLen;
            kvOffset = skipKvOffset;
          }
        }
      }

    private int handleExactMatch(Cell key, int destPos, int kvOffset, boolean seekBefore) {
      if (seekBefore) {
        int skipPrevOffset = kvOffset - PKeyValue.POINTER_SIZE;
        // note that this pointer is negtive value
        int skipPrevPtr = blockBuffer.getInt(skipPrevOffset);

        if (skipPrevPtr <= 0) {
          KeyValue kv = KeyValueUtil.ensureKeyValue(key);
          LOG.info("Shen Li: seekBefore exact match at the first key");
          throw new IllegalStateException("Shen Li: " +
              "blockSeek with seekBefore at the first key of the block: "
              + "key = " + Bytes.toStringBinary(kv.getKey(), 
                kv.getKeyOffset(), 
                kv.getKeyLength())
              + ", blockOffset = " + block.getOffset() + ", onDiskSize = "
              + block.getOnDiskSizeWithHeader());
        }

        // use the prev ptr to reset the position.
        blockBuffer.position(destPos + skipPrevPtr);
        readKeyValueLen();
        return 1;
      }

      blockBuffer.position(destPos);
      readKeyValueLen();
      return 0;
    }
  }
}
