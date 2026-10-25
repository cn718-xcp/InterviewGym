package com.interviewgym.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * float[] ↔ byte[]（小端）编解码，向量以 BLOB 落 SQLite（spec: resume-rag.md）。
 */
final class VectorCodec {

    private VectorCodec() {
    }

    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(Float.BYTES * vector.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] toFloats(byte[] bytes) {
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("向量字节长度非法");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
