package com.netflix.spinnaker.clouddriver.jobs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ByteArrayInputOutputStream extends ByteArrayOutputStream {
  public ByteArrayInputOutputStream() { super(); }
  public ByteArrayInputOutputStream(int size) { super(size); }

  public InputStream toInputStream() {
    return new ByteArrayInputStream(this.buf, 0, this.count);
  }
}
