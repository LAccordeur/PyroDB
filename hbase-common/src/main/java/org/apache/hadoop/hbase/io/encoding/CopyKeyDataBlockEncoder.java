/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.io.encoding;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.util.ByteBufferUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.WritableUtils;

/**
 * Just copy data, do not do any kind of compression. Use for comparison and
 * benchmarking.
 */
@InterfaceAudience.Private
public class CopyKeyDataBlockEncoder extends BufferedDataBlockEncoder {

  @Override
  public int internalEncode(KeyValue kv, HFileBlockDefaultEncodingContext encodingContext,
      DataOutputStream out) throws IOException {
    int klength = kv.getKeyLength();
    int vlength = kv.getValueLength();

    out.writeInt(klength);
    out.writeInt(vlength);
    out.write(kv.getBuffer(), kv.getKeyOffset(), klength);
    out.write(kv.getValueArray(), kv.getValueOffset(), vlength);
    int size = klength + vlength + KeyValue.KEYVALUE_INFRASTRUCTURE_SIZE;
    // Write the additional tag into the stream
    if (encodingContext.getHFileContext().isIncludesTags()) {
      short tagsLength = kv.getTagsLength();
      out.writeShort(tagsLength);
      if (tagsLength > 0) {
        out.write(kv.getTagsArray(), kv.getTagsOffset(), tagsLength);
      }
      size += tagsLength + KeyValue.TAGS_LENGTH_SIZE;
    }
    if (encodingContext.getHFileContext().isIncludesMvcc()) {
      WritableUtils.writeVLong(out, kv.getMvccVersion());
      size += WritableUtils.getVIntSize(kv.getMvccVersion());
    }
    return size;
  }

  @Override
  public ByteBuffer getFirstKeyInBlock(ByteBuffer block) {
    int keyLength = block.getInt(Bytes.SIZEOF_INT);
    return ByteBuffer.wrap(block.array(),
        block.arrayOffset() + 3 * Bytes.SIZEOF_INT, keyLength).slice();
  }


  @Override
  public String toString() {
    return CopyKeyDataBlockEncoder.class.getSimpleName();
  }

  @Override
  public EncodedSeeker createSeeker(KVComparator comparator,
      final HFileBlockDecodingContext decodingCtx) {
    return new BufferedEncodedSeeker<SeekerState>(comparator, decodingCtx) {
      @Override
      protected void decodeNext() {
        current.keyLength = currentBuffer.getInt();
        current.valueLength = currentBuffer.getInt();
        current.ensureSpaceForKey();
        currentBuffer.get(current.keyBuffer, 0, current.keyLength);
        current.valueOffset = currentBuffer.position();
        ByteBufferUtils.skip(currentBuffer, current.valueLength);
        if (includesTags()) {
          current.tagsLength = currentBuffer.getShort();
          ByteBufferUtils.skip(currentBuffer, current.tagsLength);
        }
        if (includesMvcc()) {
          current.memstoreTS = ByteBufferUtils.readVLong(currentBuffer);
        } else {
          current.memstoreTS = 0;
        }
        current.nextKvOffset = currentBuffer.position();
      }

      @Override
      protected void decodeFirst() {
        ByteBufferUtils.skip(currentBuffer, Bytes.SIZEOF_INT);
        current.lastCommonPrefix = 0;
        decodeNext();
      }
    };
  }

  @Override
  protected ByteBuffer internalDecodeKeyValues(DataInputStream source, int allocateHeaderLength,
      int skipLastBytes, HFileBlockDefaultDecodingContext decodingCtx) throws IOException {
    int decompressedSize = source.readInt();
    ByteBuffer buffer = ByteBuffer.allocate(decompressedSize +
        allocateHeaderLength);
    buffer.position(allocateHeaderLength);
    ByteBufferUtils.copyFromStreamToBuffer(buffer, source, decompressedSize);

    return buffer;
  }

}
