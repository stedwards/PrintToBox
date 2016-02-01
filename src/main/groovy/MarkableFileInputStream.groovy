/*
 * de.unkrig.commons - A general-purpose Java class library
 *
 * Copyright (c) 2011, Arno Unkrig
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *       following disclaimer.
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *       following disclaimer in the documentation and/or other materials provided with the distribution.
 *    3. The name of the author may not be used to endorse or promote products derived from this software without
 *       specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * A drop-in replacement for {@link java.io.FileInputStream} that supports {@link InputStream#mark(int) mark(int)} and
 * {@link InputStream#reset() reset()}.
 * <p>
 * Typically more efficient than {@code new BufferedInputStream(new FileInputstream(...))}.
 */
class MarkableFileInputStream extends InputStream {

    private final RandomAccessFile randomAccessFile;
    private long                   mark;

    public MarkableFileInputStream(String name) throws FileNotFoundException {
        this(new File(name));
    }

    public MarkableFileInputStream(File file) throws FileNotFoundException {
        this.randomAccessFile = new RandomAccessFile(file, "r");
    }

    @Override public int read() throws IOException {
        return this.randomAccessFile.read();
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        return this.randomAccessFile.read(b, off, len);
    }

    @Override public long skip(long n) throws IOException {
        long result = 0;
        while (n > Integer.MAX_VALUE) {
            int tmp = this.randomAccessFile.skipBytes(Integer.MAX_VALUE);
            n      -= tmp;
            result += tmp;
        }
        result += this.randomAccessFile.skipBytes((int) n);
        return result;
    }

    @Override public int available() throws IOException {
        return (int) Math.min(
            Integer.MAX_VALUE,
            this.randomAccessFile.length() - this.randomAccessFile.getFilePointer()
        );
    }

    @Override public void close() throws IOException { this.randomAccessFile.close(); }

    @Override public synchronized void mark(int readlimit) {
        try {
            this.mark = this.randomAccessFile.getFilePointer();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    @Override public synchronized void reset() throws IOException { this.randomAccessFile.seek(this.mark); }

    @Override public boolean markSupported() { return true; }
}
