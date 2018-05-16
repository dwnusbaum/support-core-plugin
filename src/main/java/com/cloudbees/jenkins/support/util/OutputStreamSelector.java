/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.cloudbees.jenkins.support.util;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

@Restricted(NoExternalUse.class)
public class OutputStreamSelector extends OutputStream implements WrapperOutputStream {
    private static final int DEFAULT_PROBE_SIZE = 20;
    private final Supplier<OutputStream> binaryOutputStreamProvider;
    private final Supplier<OutputStream> textOutputStreamProvider;
    private ByteBuffer head = ByteBuffer.allocate(DEFAULT_PROBE_SIZE);
    private OutputStream out;
    private boolean closed;
    private boolean flushScheduled;

    public OutputStreamSelector(@Nonnull Supplier<OutputStream> binaryOutputStreamProvider, @Nonnull Supplier<OutputStream> textOutputStreamProvider) {
        this.binaryOutputStreamProvider = binaryOutputStreamProvider;
        this.textOutputStreamProvider = textOutputStreamProvider;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Stream is closed");
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        write(new byte[]{(byte) b});
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (len < 0) throw new IllegalArgumentException("Length cannot be negative. Got: " + len);
        if (len == 0) return;
        if (out == null) {
            probeContents(b, off, len);
        } else {
            out.write(b, off, len);
        }
    }

    private void probeContents(byte[] b, int off, int len) throws IOException {
        int toCopy = Math.min(head.remaining(), len);
        if (toCopy == 0) throw new IllegalStateException("No more room to buffer header, should have chosen stream by now");
        head.put(b, off, toCopy);
        if (head.hasRemaining()) return;
        chooseStream();
        if (toCopy < len) {
            write(b, off + toCopy, len - toCopy);
        }
    }

    private void chooseStream() throws IOException {
        if (head.position() > 0) {
            head.flip().mark();
            boolean hasControlCharacter = false;
            while (head.hasRemaining()) {
                hasControlCharacter |= isNonWhitespaceControlCharacter(head.get());
            }
            head.reset();
            out = requireNonNull((hasControlCharacter ? binaryOutputStreamProvider : textOutputStreamProvider).get(), "No OutputStream returned by supplier");
            byte[] b = new byte[head.remaining()];
            head.get(b);
            write(b);
        } else {
            out = binaryOutputStreamProvider.get();
        }
        if (flushScheduled) {
            out.flush();
        }
    }

    private static boolean isNonWhitespaceControlCharacter(byte b) {
        char c = (char) (b & 0xff);
        return Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r';
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (out == null) {
            flushScheduled = true;
        } else {
            out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        ensureOpen();
        try {
            if (out == null) {
                chooseStream();
            }
            out.close();
        } finally {
            closed = true;
        }
    }

    @Override
    public OutputStream getUnderlyingStream() {
        return out;
    }
}
